package com.turkcell.cdrgenerator1.parser;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnDeclaredTagging;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class AsnFieldTreeResolver {

    // Groups: 1=field name, 2=tag class keyword (optional), 3=tag number (optional),
    // 4=EXPLICIT/IMPLICIT keyword (optional), 5=type expression.
    // Handles both "[5]" and class-qualified tags like "[APPLICATION 0]".
    private static final Pattern FIELD_LINE = Pattern.compile(
            "^\\s*([A-Za-z_][\\w-]*)\\s*(?:\\[\\s*(?:(UNIVERSAL|APPLICATION|PRIVATE)\\s+)?(\\d+)\\s*\\]\\s*)?(EXPLICIT\\s+|IMPLICIT\\s+)?(.+?)\\s*,?\\s*$"
    );
    private static final Pattern ALIAS_TAG = Pattern.compile(
            "^\\s*\\[\\s*(?:(UNIVERSAL|APPLICATION|PRIVATE)\\s+)?(\\d+)\\s*\\]\\s*(EXPLICIT\\s+|IMPLICIT\\s+)?");
    private static final int MAX_DEPTH = 15;
    private static final String EXPLICIT_KEYWORD = "EXPLICIT";
    private static final String IMPLICIT_KEYWORD = "IMPLICIT";
    private static final Pattern SIZE_CONSTRAINT = Pattern.compile(
            "SIZE\\s*\\(\\s*(\\d+)\\s*(?:\\.\\.\\s*(\\d+)\\s*)?\\)");
    /**
     * A plain INTEGER value-range constraint - {@code (0..999)} in
     * {@code Milliseconds ::= INTEGER (0..999)} - as opposed to SIZE_CONSTRAINT,
     * which bounds byte/character length. Only tried after SIZE_CONSTRAINT has
     * already come back empty (see resolveFieldType), so an OCTET STRING's
     * {@code SIZE(0..999)} is never mistaken for this.
     */
    private static final Pattern INTEGER_RANGE_CONSTRAINT = Pattern.compile(
            "\\(\\s*(-?\\d+)\\s*\\.\\.\\s*(-?\\d+)\\s*\\)");
    /** {@code CODE("LEFT")} / {@code CODE("RIGHT")}: sabit genislikli alanin hizalamasi. */
    private static final Pattern CODE_MARKER = Pattern.compile(
            "CODE\\s*\\(\\s*\"[^\"]*\"\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final String SIZE_SUFFIX_TEMPLATE = " (%s)";
    private static final int ALIAS_SIZE_MAX_DEPTH = 15;
    /**
     * Keywords that can only ever CONTINUE a field declaration, never start one.
     *
     * <p>{@link #splitFieldEntries} ends an entry at a newline as well as at a
     * comma, because some modules omit the trailing comma. That is fine until a
     * declaration wraps across lines, which real schemas do constantly:</p>
     *
     * <pre>
     * communityDataInfo           [30] SEQUENCE OF CommunityDataInfo
     *                                  OPTIONAL,
     * </pre>
     *
     * <p>The lone {@code OPTIONAL} then became its own entry and
     * {@link #parseFieldLine} happily read it as a field named {@code OPTIONA}
     * of type {@code L} - a phantom field that the generator fills and the
     * encoder emits as a real TLV, corrupting the record. 768 such entries exist
     * across 32 of the 808 modules (SCFPDPRecord, Transit, CCAccountData,
     * DiameterCreditControlRecord and friends).</p>
     */
    private static final Pattern FIELD_CONTINUATION_KEYWORD = Pattern.compile(
            "^(OPTIONAL|DEFAULT\\b|OF\\b)", Pattern.CASE_INSENSITIVE);
    /**
     * An ENUMERATED / named-number member such as {@code default (0)}. It looks
     * like a DEFAULT continuation but is a genuine entry of its own, so it must
     * NOT be merged into the previous one.
     */
    private static final Pattern NAMED_NUMBER_MEMBER = Pattern.compile(
            "^[A-Za-z][\\w-]*\\s*\\(\\s*-?\\d+\\s*\\)$");
    /** A declaration ending in one of these is obviously unfinished. */
    private static final Pattern UNFINISHED_TYPE_KEYWORD = Pattern.compile(
            "(SEQUENCE|SET|OF)$", Pattern.CASE_INSENSITIVE);
    /**
     * An entry that is nothing but an identifier. No ASN.1 member consists of a
     * name alone, so this is always the front half of a declaration the schema
     * wrapped after the member name:
     *
     * <pre>
     * timeFromRegisterSeizureToStartOfCharging
     *                             [13] IMPLICIT Time OPTIONAL,
     * </pre>
     *
     * <p>Left unmerged, {@link #parseFieldLine} read the name alone as a field -
     * splitting the last character off as its type, giving
     * {@code timeFromRegisterSeizureToStartOfChargin} of type {@code g} - and
     * dropped the {@code [13]} line, which starts with no name at all. The
     * phantom is untagged, so the encoder wrote it with a universal tag among
     * its context-tagged siblings, and the real field never reached the record.
     * 1278 declarations across 18 modules are written this way: the whole CME20R
     * MSC family, Try and SDPOutputCS40.</p>
     */
    private static final Pattern BARE_NAME_ENTRY = Pattern.compile("^[A-Za-z][\\w-]*$");
    /**
     * What the second half of such a declaration opens with: the member's tag
     * ({@code [13] IMPLICIT Time}) or, in an ENUMERATED body, its number
     * ({@code (7)}). Deliberately narrow - an entry that opens with a NAME could
     * equally be the next member, and merging those would swallow a real field.
     */
    private static final Pattern WRAPPED_DECLARATION_TAIL = Pattern.compile("^[\\[(]");
    private static final String ENTRY_JOIN_SEPARATOR = " ";

    /**
     * Root resolution result: the root type's kind, its resolved fields, and -
     * when the root type tags ITSELF ({@code Row ::= [0] IMPLICIT SEQUENCE},
     * {@code TransferBatch ::= [APPLICATION 1] SEQUENCE}) - a carrier field
     * holding that tag with the resolved fields as its children.
     *
     * <p>The carrier exists so the record's own tag travels as an ordinary
     * {@link AsnField}: the encoder writes it through the same {@code wrapInTlv}
     * that handles every other tagged field, and the verifier walks it through
     * the same {@code walkField}. Null when the root type carries no tag, which
     * leaves every such module encoded exactly as before.</p>
     */
    public record ResolvedRoot(AsnTypeKind kind, List<AsnField> fields, AsnField rootTagCarrier,
                               boolean repeatedRoot, boolean repeatedRootIsSet) {

        public ResolvedRoot(AsnTypeKind kind, List<AsnField> fields) {
            this(kind, fields, null, false, false);
        }

        public ResolvedRoot(AsnTypeKind kind, List<AsnField> fields, AsnField rootTagCarrier) {
            this(kind, fields, rootTagCarrier, false, false);
        }
    }

    // ---------------------------------------------------------------------
    // Legacy field-list API (implicit-default tagging). Kept for callers and
    // tests that only need a SEQUENCE/SET field list without root-kind info.
    // ---------------------------------------------------------------------

    public List<AsnField> resolveRootFields(Map<String, AsnTypeDefinition> registry, String rootTypeName) {
        return resolveRootFields(registry, rootTypeName, Map.of());
    }

    public List<AsnField> resolveRootFields(Map<String, AsnTypeDefinition> registry, String rootTypeName,
                                            Map<String, String> choiceSelections) {
        return resolveRootFields(registry, rootTypeName, choiceSelections, AsnTaggingMode.IMPLICIT);
    }

    public List<AsnField> resolveRootFields(Map<String, AsnTypeDefinition> registry, String rootTypeName,
                                            Map<String, String> choiceSelections, AsnTaggingMode taggingMode) {
        Map<String, List<AsnField>> resolutionCache = new HashMap<>();
        return resolveByTypeName(registry, rootTypeName, choiceSelections, new HashSet<>(), 0,
                resolutionCache, taggingMode);
    }

    // ---------------------------------------------------------------------
    // Root API that preserves the root kind. For a CHOICE root it returns the
    // selected alternative as a single field (tag preserved), so the encoder
    // can emit it directly instead of wrapping it in an artificial SEQUENCE.
    // ---------------------------------------------------------------------

    public ResolvedRoot resolveRoot(Map<String, AsnTypeDefinition> registry, String rootTypeName,
                                    Map<String, String> choiceSelections, AsnTaggingMode taggingMode) {
        Map<String, String> selections = choiceSelections == null ? Map.of() : choiceSelections;

        // Follow alias chains down to the underlying structured type. The
        // first hop that is itself a repeated expression ("X ::= SEQUENCE OF
        // Y") marks the root as a genuine collection - remembered by name
        // rather than silently flattened to Y's own fields. Only reachable
        // when a caller or an EMM binding names the wrapper explicitly: the
        // heuristic (StructureParserService#selectRootTypeName) already
        // resolves straight past a wrapper like this to Y itself, so it never
        // sees this branch.
        String resolvedName = rootTypeName;
        AsnTypeDefinition current = registry.get(resolvedName);
        String repeatedRootAliasName = null;
        Set<String> aliasGuard = new HashSet<>();
        while (current != null && current.getKind() == AsnTypeKind.ALIAS && aliasGuard.add(resolvedName)) {
            String target = normalizeAliasTarget(current.getAliasTarget());
            if (repeatedRootAliasName == null && isRepeatedExpression(target)) {
                repeatedRootAliasName = resolvedName;
            }
            target = isRepeatedExpression(target) ? extractRepeatedInnerType(target) : target;
            resolvedName = target;
            current = registry.get(target);
        }
        if (current == null) {
            return new ResolvedRoot(null, List.of());
        }

        Map<String, List<AsnField>> cache = new HashMap<>();
        if (current.getKind() == AsnTypeKind.CHOICE) {
            AsnField alternative = resolveChoiceRootAlternative(registry, resolvedName, current.getRawBody(),
                    selections, new HashSet<>(), 0, cache, taggingMode);
            List<AsnField> alternatives = alternative == null ? List.of() : List.of(alternative);
            return new ResolvedRoot(AsnTypeKind.CHOICE, alternatives,
                    buildRootTagCarrier(current, resolvedName, alternatives, true, taggingMode));
        }

        List<AsnField> fields = resolveByTypeName(registry, resolvedName, selections, new HashSet<>(), 0,
                cache, taggingMode);

        if (repeatedRootAliasName != null) {
            // The wrapper alias's own tag, if it wrote one, is not read here:
            // AsnTypeRegistryBuilder only fills tagPrefix for a STRUCTURED
            // definition (a brace body right after the tag), never for an
            // ALIAS - and "X ::= [n] SEQUENCE OF Y" has no brace, so it is
            // always parsed as an ALIAS with the tag folded into aliasTarget's
            // raw text instead. No module in this corpus has that shape (every
            // measured wrapper, e.g. ABSSDPXML.SnapshotData, is untagged), so
            // reading it back out is deferred until a real example exists to
            // measure it against, rather than guessed at now.
            return new ResolvedRoot(current.getKind(), fields, null,
                    true, isAliasRepeatedAsSet(registry, repeatedRootAliasName));
        }

        return new ResolvedRoot(current.getKind(), fields,
                buildRootTagCarrier(current, resolvedName, fields, false, taggingMode));
    }

    /**
     * Builds the field that carries a root type's own tag, or null when the type
     * declares none.
     *
     * <p>{@code set} decides which universal tag an EXPLICIT wrapper holds
     * (X.690 8.11), and {@code choice} makes the encoder treat the tag as
     * EXPLICIT whatever the module's default, per X.680 30.6.</p>
     */
    private AsnField buildRootTagCarrier(AsnTypeDefinition definition, String typeName,
                                         List<AsnField> fields, boolean choice,
                                         AsnTaggingMode taggingMode) {
        EffectiveTag tag = readLeadingTag(definition.getTagPrefix(), taggingMode);
        if (tag == null || fields.isEmpty()) {
            return null;
        }

        return AsnField.builder()
                .fieldName(typeName)
                .fieldType(typeName)
                .tagNumber(tag.tagNumber())
                .tagClass(tag.tagClass())
                .explicit(tag.explicit())
                .declaredTagging(tag.declared())
                .tagDeclaredOnType(tag.fromType())
                .moduleNamesNoTaggingMode(taggingMode == AsnTaggingMode.UNSPECIFIED)
                .choice(choice)
                .choiceTagImplicit(choiceTagImplicit(tag, false, choice, taggingMode))
                .set(definition.getKind() == AsnTypeKind.SET)
                .children(fields)
                .build();
    }

    /** Root-level CHOICE metadata: the CHOICE type's own name plus its alternative field names, in declaration order. */
    public record ChoiceAlternatives(String choiceTypeName, List<String> alternativeNames) {
    }

    /**
     * Follows the alias chain from {@code rootTypeName} the same way {@link #resolveRoot} does,
     * and if the resolved type is a CHOICE, returns its type name plus the list of alternative
     * field names (in declaration order) WITHOUT resolving their children. Returns {@code null}
     * when the root does not resolve to a CHOICE. Lets a caller (e.g. a UI) offer a "pick a
     * branch" control before any alternative-specific fields are generated.
     */
    public ChoiceAlternatives listRootChoiceAlternatives(Map<String, AsnTypeDefinition> registry, String rootTypeName) {
        String resolvedName = rootTypeName;
        AsnTypeDefinition current = registry.get(resolvedName);
        Set<String> aliasGuard = new HashSet<>();
        while (current != null && current.getKind() == AsnTypeKind.ALIAS && aliasGuard.add(resolvedName)) {
            String target = normalizeAliasTarget(current.getAliasTarget());
            target = isRepeatedExpression(target) ? extractRepeatedInnerType(target) : target;
            resolvedName = target;
            current = registry.get(target);
        }
        if (current == null || current.getKind() != AsnTypeKind.CHOICE) {
            return null;
        }

        List<String> names = new ArrayList<>();
        for (String line : splitFieldEntries(current.getRawBody())) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            AsnField alternative = parseFieldLine(trimmed, AsnTaggingMode.IMPLICIT);
            if (alternative != null) {
                names.add(alternative.getFieldName());
            }
        }
        return new ChoiceAlternatives(resolvedName, names);
    }

    private List<AsnField> resolveByTypeName(Map<String, AsnTypeDefinition> registry, String typeName,
                                             Map<String, String> choiceSelections, Set<String> visiting, int depth,
                                             Map<String, List<AsnField>> cache, AsnTaggingMode taggingMode) {
        if (typeName == null) {
            return List.of();
        }

        String cacheKey = buildCacheKey(typeName, choiceSelections);
        List<AsnField> cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        AsnTypeDefinition definition = registry.get(typeName);
        if (definition == null) {
            return List.of();
        }
        if (visiting.contains(typeName) || depth > MAX_DEPTH) {
            log.warn("Circular or too-deep ASN.1 reference at '{}', stopping recursion", typeName);
            return List.of();
        }

        Set<String> nextVisiting = new HashSet<>(visiting);
        nextVisiting.add(typeName);

        // An imported definition was written under its own module's header, so
        // its body is read under that header's mode rather than the importing
        // module's. See AsnTypeDefinition.taggingMode; null for every locally
        // declared type, which is the whole registry until an IMPORTS clause is
        // closed against a corpus.
        AsnTaggingMode declaringMode = Objects.nonNull(definition.getTaggingMode())
                ? definition.getTaggingMode()
                : taggingMode;

        List<AsnField> result = switch (definition.getKind()) {
            case ENUMERATED -> List.of();
            case ALIAS -> resolveAlias(registry, definition, choiceSelections, nextVisiting, depth, cache, declaringMode);
            case SEQUENCE -> parseFieldLines(registry, definition.getRawBody(), choiceSelections, nextVisiting, depth, cache, declaringMode);
            case SET -> sortSetComponents(
                    parseFieldLines(registry, definition.getRawBody(), choiceSelections, nextVisiting, depth, cache, declaringMode));
            case CHOICE -> resolveChoiceAlternative(registry, typeName, definition.getRawBody(), choiceSelections, nextVisiting, depth, cache, declaringMode);
        };

        cache.put(cacheKey, result);
        return result;
    }

    /**
     * Orders a SET's components by tag, as X.690 11.6 requires.
     *
     * <p>A SEQUENCE is positional - its components must stay in declaration
     * order - but a SET is unordered, and DER fixes a canonical order: the
     * encodings appear sorted by tag (class first, then number). Plain BER
     * tolerates any order, so sorting is valid under BOTH rule sets; it is
     * never wrong to sort, only sometimes wrong not to.</p>
     *
     * <p>The encoder emits fields in the order of this list, so the source
     * schema's declaration order used to leak straight into the wire format.
     * Two SETs in MMTelChargingDataTypes are declared out of order -
     * {@code MMTelRecord} has {@code routeHeaderReceived [59]} written after
     * {@code mMTelInformation [110]}, and {@code ManagementExtensions} has
     * {@code [520]} after {@code [523]} - so every generated record carried
     * those two components out of sequence. Across 6000 records of two
     * EMM-accepted reference captures, EVERY SET body is sorted ascending with
     * no exception, and EMM rejected our file with a "Duplicate Tag" error: a
     * decoder that assumes ascending order sees a tag lower than the previous
     * one and concludes the component must be a repeat.</p>
     *
     * <p>Fields with no tag of their own are left alone: a SET's components
     * must have distinct tags, and without a resolved number there is nothing
     * to sort by, so the declaration order is kept rather than guessed at.</p>
     */
    private List<AsnField> sortSetComponents(List<AsnField> fields) {
        boolean everyComponentIsTagged = fields.stream()
                .allMatch(field -> Objects.nonNull(field.getTagNumber()));
        if (!everyComponentIsTagged) {
            return fields;
        }
        List<AsnField> sorted = new ArrayList<>(fields);
        sorted.sort(Comparator
                .comparingInt((AsnField field) -> tagClassRank(field.getTagClass()))
                .thenComparingInt(AsnField::getTagNumber));
        return sorted;
    }

    /**
     * X.690 tag-class ordering: universal &lt; application &lt; context-specific
     * &lt; private, which is exactly the order of the class bits.
     */
    private int tagClassRank(BerTagClass tagClass) {
        return Objects.isNull(tagClass) ? BerTagClass.CONTEXT.getClassBits() : tagClass.getClassBits();
    }

    private String buildCacheKey(String typeName, Map<String, String> choiceSelections) {
        return choiceSelections.isEmpty() ? typeName : typeName + "::" + choiceSelections;
    }

    private List<AsnField> resolveAlias(Map<String, AsnTypeDefinition> registry, AsnTypeDefinition definition,
                                        Map<String, String> choiceSelections, Set<String> visiting, int depth,
                                        Map<String, List<AsnField>> cache, AsnTaggingMode taggingMode) {
        String target = normalizeAliasTarget(definition.getAliasTarget());
        String innerType = isRepeatedExpression(target) ? extractRepeatedInnerType(target) : target;
        return resolveByTypeName(registry, innerType, choiceSelections, visiting, depth + 1, cache, taggingMode);
    }

    /**
     * Nested CHOICE resolution: returns the selected alternative as a SINGLE
     * field, keeping its own tag and EXPLICIT flag intact.
     *
     * <p>This deliberately does NOT flatten to the alternative's inner fields.
     * Flattening loses every intermediate tag, which breaks chains such as
     * {@code nodeAddress [4] EXPLICIT NodeAddress -> iPAddress [0] EXPLICIT
     * IPAddress -> iPBinaryAddress -> iPBinV4Address [0]}: the {@code [0]
     * EXPLICIT} layer would vanish and the encoder would emit a universal
     * SEQUENCE in its place, producing BER that no decoder accepts.</p>
     *
     * <p>The caller ({@code attachChildren} / {@code parseFieldLines}) marks the
     * owning field with {@code choice=true}, so the encoder knows this single
     * child IS the value and must not be wrapped.</p>
     */
    private List<AsnField> resolveChoiceAlternative(Map<String, AsnTypeDefinition> registry, String choiceTypeName,
                                                    String rawBody, Map<String, String> choiceSelections,
                                                    Set<String> visiting, int depth,
                                                    Map<String, List<AsnField>> cache, AsnTaggingMode taggingMode) {
        String preferredAlternative = choiceSelections.get(choiceTypeName);
        AsnField firstAlternative = null;

        for (String line : splitFieldEntries(rawBody)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            AsnField alternative = parseFieldLine(trimmed, taggingMode);
            if (alternative == null) continue;

            if (firstAlternative == null) {
                firstAlternative = alternative;
            }
            if (alternative.getFieldName().equals(preferredAlternative)) {
                return List.of(attachChildren(registry, alternative, choiceSelections, visiting, depth, cache, taggingMode));
            }
        }

        if (firstAlternative != null) {
            if (preferredAlternative != null) {
                log.warn("Choice alternative '{}' not found in '{}', falling back to first alternative '{}'",
                        preferredAlternative, choiceTypeName, firstAlternative.getFieldName());
            }
            return List.of(attachChildren(registry, firstAlternative, choiceSelections, visiting, depth, cache, taggingMode));
        }
        return List.of();
    }

    /**
     * Root-level CHOICE resolution: returns the selected alternative as a SINGLE
     * field with its own tag preserved and its children resolved. Unlike the
     * nested variant this does NOT flatten, so the encoder can emit the
     * alternative directly (e.g. {@code commandRecord [APPLICATION 0] ...}).
     */
    private AsnField resolveChoiceRootAlternative(Map<String, AsnTypeDefinition> registry, String choiceTypeName,
                                                  String rawBody, Map<String, String> choiceSelections,
                                                  Set<String> visiting, int depth,
                                                  Map<String, List<AsnField>> cache, AsnTaggingMode taggingMode) {
        String preferredAlternative = choiceSelections.get(choiceTypeName);
        Set<String> nextVisiting = new HashSet<>(visiting);
        nextVisiting.add(choiceTypeName);

        AsnField firstAlternative = null;
        for (String line : splitFieldEntries(rawBody)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            AsnField alternative = parseFieldLine(trimmed, taggingMode);
            if (alternative == null) continue;

            if (firstAlternative == null) {
                firstAlternative = alternative;
            }
            if (alternative.getFieldName().equals(preferredAlternative)) {
                return attachChildren(registry, alternative, choiceSelections, nextVisiting, depth, cache, taggingMode);
            }
        }

        if (firstAlternative != null) {
            if (preferredAlternative != null) {
                log.warn("Choice alternative '{}' not found in '{}', falling back to first alternative '{}'",
                        preferredAlternative, choiceTypeName, firstAlternative.getFieldName());
            }
            return attachChildren(registry, firstAlternative, choiceSelections, nextVisiting, depth, cache, taggingMode);
        }
        return null;
    }

    private AsnField attachChildren(Map<String, AsnTypeDefinition> registry, AsnField field,
                                    Map<String, String> choiceSelections, Set<String> visiting, int depth,
                                    Map<String, List<AsnField>> cache, AsnTaggingMode taggingMode) {
        String innerType = stripConstraint(field.getFieldType());
        // Same split as in parseFieldLines: an inline "SEQUENCE OF Type" must be
        // reduced to its ELEMENT type before the registry is consulted.
        boolean inlineCollection = isRepeatedExpression(innerType);
        if (inlineCollection) {
            innerType = extractRepeatedInnerType(innerType);
        }
        boolean aliasRepeated = isAliasRepeated(registry, innerType);
        boolean repeated = field.isRepeated() || inlineCollection || aliasRepeated;
        // Two collection layers, e.g. list-of-Call-Transfer-Info [428]
        // SEQUENCE OF Call-Transfer-Info-List, Call-Transfer-Info-List ::=
        // SEQUENCE OF Call-Transfer-Info: the field's own inline collection is
        // the FIRST layer, and the ELEMENT type it names (innerType, here
        // "Call-Transfer-Info-List") being itself a named collection alias -
        // aliasRepeated - is the SECOND. See AsnField#isNestedCollectionElement.
        //
        // The field's own inline collection is read from field.isRepeated(),
        // NOT the local 'inlineCollection' above: parseFieldLine already
        // reduces "SEQUENCE OF X" to X and sets isRepeated() on the ORIGINAL
        // (unresolved) field the moment it parses the line, so by the time
        // this method runs, field.getFieldType() never still carries the
        // "SEQUENCE OF"/"SET OF" prefix and 'inlineCollection' here is always
        // false. field.isRepeated() is the one signal that survives that
        // earlier reduction and still says the field's OWN text was a
        // collection.
        boolean nestedCollectionElement = field.isRepeated() && aliasRepeated;
        boolean nestedCollectionElementIsSet = nestedCollectionElement
                && isAliasRepeatedAsSet(registry, innerType);
        List<AsnField> children = resolveByTypeName(registry, innerType, choiceSelections, visiting, depth + 1,
                cache, taggingMode);
        String fieldType = resolveFieldType(registry, field.getFieldType(), innerType, children);

        EffectiveTag effectiveTag = resolveEffectiveTag(registry, field, innerType, taggingMode);
        boolean choiceElement = isChoiceType(registry, innerType);
        boolean setElement = isSetType(registry, innerType);

        // 'choice' means "this field's TYPE is a CHOICE", independent of
        // 'repeated'. A SEQUENCE OF <Choice> still has CHOICE-typed elements;
        // encodeRepeated() needs that signal to avoid wrapping each element in
        // a synthetic SEQUENCE. wrapInTlv() applies its own !isRepeated() guard
        // when deciding how to wrap the field's OUTER collection tag, so this
        // flag no longer needs to pre-filter that case here.
        AsnField resolved = AsnField.builder()
                .fieldName(field.getFieldName())
                .fieldType(fieldType)
                .optional(field.isOptional())
                .repeated(repeated)
                .choice(choiceElement)
                .set(setElement)
                .decoderHoistsImplicitChoice(isMmtelPartyAddressingFamily(registry))
                .choiceTagImplicit(choiceTagImplicit(effectiveTag, repeated, choiceElement, taggingMode))
                .declaredTagging(effectiveTag.declared())
                .tagDeclaredOnType(effectiveTag.fromType())
                .moduleNamesNoTaggingMode(taggingMode == AsnTaggingMode.UNSPECIFIED)
                .tagNumber(effectiveTag.tagNumber())
                .tagClass(effectiveTag.tagClass())
                .explicit(effectiveExplicit(effectiveTag, repeated, choiceElement, taggingMode))
                .universalTagOverride(resolveUniversalTagOverride(registry, innerType))
                .structuralTypeWithNoComponents(
                        field.isOptional() && isStructuralTypeWithNoComponents(registry, innerType))
                .nestedCollectionElement(nestedCollectionElement)
                .nestedCollectionElementIsSet(nestedCollectionElementIsSet)
                .children(children.isEmpty() ? null : children)
                .build();
        if (repeated) {
            resolved.setElementTagCarrier(
                    buildElementTagCarrier(registry, resolved, field.getFieldType(), taggingMode));
        }
        return resolved;
    }

    /**
     * Neutralizes a written {@code EXPLICIT} that the real MMTel-family wire
     * format doesn't carry, without touching the vendored schema text.
     *
     * <p>X.680 doesn't forbid EXPLICIT on a SEQUENCE, a SET, or a
     * SEQUENCE/SET-OF-anything - all of them have their own universal tag
     * that IMPLICIT could otherwise replace, so writing EXPLICIT there is
     * legal ASN.1, not a language violation. The only shape where EXPLICIT is
     * actually MANDATORY is a scalar CHOICE (X.680 8.3): a CHOICE carries no
     * universal tag of its own to be implicitly replaced, so a tag on it is
     * always encoded as EXPLICIT regardless of what's written.</p>
     *
     * <p>Whether a written EXPLICIT elsewhere reflects the real wire format or
     * is a transcription error relative to the true spec is a data-quality
     * question, not an ASN.1 one. For the MMTel-family lineage specifically we
     * have a direct, exhaustive answer: diffing the schema text against an
     * EMM-accepted MMTel reference capture originally stripped EXPLICIT from
     * 122 fields across the 6 MMTel modules, and it turned out to be
     * EVERY field that had it except the ones resolving to a scalar CHOICE -
     * repeated CHOICE ({@code list-Of-Calling-Party-Address}), scalar/repeated
     * SET ({@code recordExtensions}, {@code mMTelInformation},
     * {@code list-of-subscription-ID}), and, discovered only by this
     * comprehensive re-check, plain repeated SEQUENCE too
     * ({@code interOperatorIdentifiers}, {@code list-Of-SDP-Media-Components},
     * {@code list-Of-Early-SDP-Media-Components},
     * {@code list-Of-AccessTransferInformation} - none of these involve a
     * CHOICE or a SET at all, yet the reference capture confirms they carry no
     * extra universal-SEQUENCE layer either). So within this lineage the rule
     * is simply: EXPLICIT survives only for a scalar CHOICE, everything else
     * is neutralized.</p>
     *
     * <p>The 3GPP packet-domain CDR lineage (GGSN/GSN/LTE/CDRF) turned out to
     * behave identically, and there the evidence is an EMM rejection rather
     * than an acceptance: EMM refused both {@code LTE-R10} and
     * {@code GGSNTurkcellCdrR7} samples with "the type
     * ...{@code servingNodeAddress}/{@code sgsnAddress}{@code .[0]} was
     * probably not set and is not optional" - the element of
     * {@code [6] EXPLICIT SEQUENCE OF GSNAddress}. Honoring that EXPLICIT puts a
     * universal SEQUENCE between {@code A6} and the address CHOICE, and EMM,
     * reading {@code A6} as the collection itself, finds a {@code 30} where an
     * address alternative must be. Neutralizing reproduces 3GPP TS 32.298
     * exactly - the published module writes no EXPLICIT at all - for
     * {@code PDPAddress} and the SEQUENCE OFs, while {@code GSNAddress} and
     * {@code Diagnostics} are CHOICEs and keep their wrapper.</p>
     *
     * <p>Two guards keep this off the shapes no capture covers.</p>
     *
     * <p>A scalar CHOICE is exempt because X.680 8.3 already decides it: a
     * CHOICE carries no universal tag of its own to be implicitly replaced, so
     * a tag on it is encoded EXPLICIT whatever is written, and the two readings
     * produce identical bytes.</p>
     *
     * <p>A PRIMITIVE-typed field is NOT exempt, and that took a rejection to
     * settle. The MMTel captures cannot speak to the shape -
     * {@code MMTelChargingDataTypes} declares no EXPLICIT field resolving to a
     * primitive at all - so it was once carved out as unproven extrapolation.
     * EMM then answered directly, on {@code LTE-R10}:</p>
     *
     * <pre>
     * Invalid length 3 of field "LTE-R10.CallEventRecord.sGWRecord.dynamicAddressFlag"
     * Boolean can only have a maximum length of 1 bytes.
     * </pre>
     *
     * <p>{@code dynamicAddressFlag [11] EXPLICIT DynamicAddressFlag} had gone out
     * as {@code AB 03 01 01 FF}; EMM reads {@code [11]} as an IMPLICIT BOOLEAN
     * and wants {@code 8B 01 FF}. So the wrapper is dropped for primitives too.</p>
     *
     * <p>The same round accepted {@code GGSNTurkcellCdrR7} with
     * {@code qosRequested [1] EXPLICIT QoSInformation} still wrapped, as
     * {@code A1 13 04 11 ..} - but that acceptance settles nothing, because a
     * decoder ignoring the wrapper reads those bytes as a 19-octet value whose
     * first two octets are our TLV header, which still satisfies
     * {@code SIZE(4..255)}. BOOLEAN is simply the primitive whose length bound
     * makes the disagreement visible. Treating the two differently would leave
     * every OCTET STRING in the family carrying two bytes of our own framing as
     * data, in a file that passes.</p>
     *
     * <p>This was gated on a family test for as long as only those two lineages
     * had an EMM answer. Round 14 answered for two more, and neither shares an
     * ancestor with them:</p>
     *
     * <pre>
     * Invalid length 38 of field
     *   "CCNCS55_UpdatedCCR_CCN.ChargingDataOutputRecord.sCFPDPRecord.ggsnAddressUsed"
     * Invalid length 28133 of field
     *   "EnrichedVerazCdr.CDR.redirectingInformationSubs"
     * </pre>
     *
     * <p>{@code ggsnAddressUsed [1] EXPLICIT GSNAddress} resolves through
     * {@code GSNAddress ::= IPBinaryAddress} to a SEQUENCE - the CCN/OCC alias
     * shape this method's family test deliberately excluded - and went out as
     * {@code A1 1A 30 18 80 04 .. 81 10 ..}.
     * {@code redirectingInformationSubs [166] EXPLICIT RedirectingInformation}
     * is a plain SEQUENCE in a module carrying no address types at all, and went
     * out as {@code BF 81 26 81 83 30 81 80 ..}. Both carry the same extra
     * universal SEQUENCE between the context tag and the fields, and EMM refused
     * both at exactly that field.</p>
     *
     * <p>The control is in the same round. Every module that PASSED while
     * carrying a written EXPLICIT has it on a CHOICE, where the wrapper is kept
     * either way: {@code GPRS-Charging-Extensions-Tr}
     * ({@code [0] EXPLICIT ExtendedDiagnostics}, {@code [1] EXPLICIT IPAddress})
     * and, from round 12, {@code CHFChargingDataTypes16}'s
     * {@code [2] EXPLICIT IPAddress}. Not one acceptance depends on a wrapper
     * around a non-CHOICE. So the lineage was never what decided it - the target
     * type was - and the family gate is gone. What remains is the same sentence
     * every other rule reduces to: outside a CHOICE, everything is IMPLICIT,
     * written keyword included.</p>
     */
    /**
     * True where X.680 8.3's "a tag on a CHOICE is always EXPLICIT" gives way to
     * the module default - the one case round 13 measured.
     *
     * <p>Four conditions, all required. The type must BE a CHOICE and the field
     * must not be repeated, since for a collection the outer tag wraps the
     * collection rather than an alternative. The tag must carry no written
     * keyword, because EMM honours one ({@code CHFChargingDataTypes16}'s
     * {@code [2] EXPLICIT IPAddress} passed in round 12). And the header must
     * name no mode: an {@code IMPLICIT TAGS} module keeps 8.3, because MMTel's
     * accepted files and its reference capture both depend on it.</p>
     *
     * @see AsnField#isChoiceTagImplicit()
     */
    private boolean choiceTagImplicit(EffectiveTag tag, boolean repeated, boolean choiceElement,
                                      AsnTaggingMode taggingMode) {
        return choiceElement
                && !repeated
                && !tag.explicit()
                && Objects.nonNull(tag.tagNumber())
                && taggingMode == AsnTaggingMode.UNSPECIFIED;
    }

    private boolean effectiveExplicit(EffectiveTag tag, boolean repeated, boolean choiceElement,
                                      AsnTaggingMode taggingMode) {
        if (universalTagCannotWrap(tag)) {
            return false;
        }
        if (!repeated && choiceElement) {
            return tag.explicit();
        }
        // A header that says EXPLICIT TAGS out loud is left alone. Every
        // measurement behind the neutralization comes from a module that either
        // says IMPLICIT TAGS or says nothing at all; no module in this data set
        // declares EXPLICIT TAGS, so widening it there would be extrapolation
        // with nothing to gain and X.680 to contradict.
        if (taggingMode == AsnTaggingMode.EXPLICIT) {
            return tag.explicit();
        }
        return false;
    }

    /**
     * Universal tag numbers X.690 requires to be encoded primitive: BOOLEAN
     * (8.2.1), INTEGER (8.3.1), NULL (8.8.1), OBJECT IDENTIFIER (8.19.1), REAL
     * (8.5.1), ENUMERATED (8.4) and RELATIVE-OID (8.20.1).
     *
     * <p>Kept here rather than shared with
     * {@code service.verify.rule.TagShapeRule}, which enforces the same list on
     * the bytes: this package depends only on {@code model}, and reaching into
     * {@code service} for seven numbers would buy less than the layering costs.
     * The two must stay in step - the rule is what catches it if they drift.</p>
     */
    private static final Set<Integer> UNIVERSAL_TAGS_THAT_MUST_STAY_PRIMITIVE =
            Set.of(1, 2, 5, 6, 9, 10, 13);

    /**
     * True when a tag is UNIVERSAL-class and names a type that can only be
     * encoded primitive, so it cannot be an EXPLICIT wrapper.
     *
     * <p>X.680 does not let a user assign a UNIVERSAL-class tag at all - clause
     * 31.2.1 reserves that class for the types the standard itself defines - so
     * a schema writing {@code [UNIVERSAL n]} is outside the language, and the
     * module's tagging default has no answer for it. What such a tag can only
     * sensibly mean is "encode the value under this universal tag", which is
     * implicit tagging.</p>
     *
     * <p>Reading it as EXPLICIT produces bytes that are invalid on their face.
     * {@code GSN50}'s {@code ManagementExtension} declares</p>
     *
     * <pre>identifier [UNIVERSAL 6] OCTET STRING,</pre>
     *
     * <p>and the module header is a bare {@code GSN50 DEFINITIONS ::=}, so
     * X.680 31.2.7 makes that tag EXPLICIT and the encoder faithfully wrapped
     * the OCTET STRING: {@code 26 0A 04 08 ..}, an OBJECT IDENTIFIER whose
     * contents are an OCTET STRING TLV. X.690 8.19.1 says an object identifier
     * value "shall be primitive", so no conforming decoder can read it. The
     * implicit reading gives {@code 06 08 ..}, which is valid.</p>
     *
     * <p>Deliberately limited to the tags X.690 pins down. BIT STRING, OCTET
     * STRING and the character strings MAY be constructed (8.6.1, 8.7.1,
     * 8.21.3), so for those the EXPLICIT reading produces legal bytes and there
     * is nothing to prove - and the 35 {@code [UNIVERSAL 12|16|25]} declarations
     * in this data set are all in IMPLICIT-tagged modules anyway, so the narrow
     * rule and a blanket one would emit identical bytes today. The narrow one is
     * the one that needs no judgement call.</p>
     */
    private boolean universalTagCannotWrap(EffectiveTag tag) {
        return tag.tagClass() == BerTagClass.UNIVERSAL
                && Objects.nonNull(tag.tagNumber())
                && UNIVERSAL_TAGS_THAT_MUST_STAY_PRIMITIVE.contains(tag.tagNumber());
    }


    /**
     * True when this module's registry carries the shared {@code InvolvedParty}
     * CHOICE lineage - the structural fingerprint of the MMTel/AIMS/IMS/UAG/ATS
     * family, verified against a real EMM-accepted MMTel reference capture.
     *
     * <p>Confirmed absent from every GGSN/LTE/CCN-family module in the current
     * data set (they carry no SIP/IMS party addressing at all), which is what
     * lets it gate the "Duplicate Tag" workaround
     * ({@link AsnField#isDecoderHoistsImplicitChoice()}) on its own: that defect
     * was only ever observed here, and EMM's LTE/GGSN answers said nothing
     * about it.</p>
     */
    private boolean isMmtelPartyAddressingFamily(Map<String, AsnTypeDefinition> registry) {
        AsnTypeDefinition involvedParty = registry.get("InvolvedParty");
        return involvedParty != null && involvedParty.getKind() == AsnTypeKind.CHOICE;
    }


    /**
     * Splits a SEQUENCE/SET/CHOICE body into individual field entries.
     * Fields are separated by newlines and/or commas, but commas inside
     * brackets or parentheses (e.g. INTEGER ( CODE("DEC"))) are NOT separators.
     */
    private List<String> splitFieldEntries(String rawBody) {
        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < rawBody.length(); i++) {
            char c = rawBody.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                current.append(c);
            } else if ((c == '\n' || c == ',') && depth == 0) {
                String entry = current.toString().trim();
                if (!entry.isEmpty()) {
                    entries.add(entry);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            entries.add(last);
        }
        return mergeContinuationEntries(entries);
    }

    /**
     * Re-joins entries that a line break split out of the middle of a field
     * declaration, so a wrapped {@code OPTIONAL} / {@code DEFAULT x} /
     * {@code SEQUENCE OF Type} stays part of the field it belongs to instead of
     * becoming a phantom field (see {@link #FIELD_CONTINUATION_KEYWORD}).
     */
    private List<String> mergeContinuationEntries(List<String> entries) {
        List<String> merged = new ArrayList<>();
        for (String entry : entries) {
            if (!merged.isEmpty() && isContinuationOf(entry, merged.get(merged.size() - 1))) {
                merged.set(merged.size() - 1,
                        merged.get(merged.size() - 1) + ENTRY_JOIN_SEPARATOR + entry);
            } else {
                merged.add(entry);
            }
        }
        return merged;
    }

    private boolean isContinuationOf(String entry, String previous) {
        // A name on its own line, then the rest of its declaration on the next.
        if (BARE_NAME_ENTRY.matcher(previous).matches()
                && WRAPPED_DECLARATION_TAIL.matcher(entry).find()) {
            return true;
        }
        if (NAMED_NUMBER_MEMBER.matcher(entry).matches()) {
            return false;
        }
        return FIELD_CONTINUATION_KEYWORD.matcher(entry).find()
                || UNFINISHED_TYPE_KEYWORD.matcher(previous).find();
    }

    private List<AsnField> parseFieldLines(Map<String, AsnTypeDefinition> registry, String rawBody,
                                           Map<String, String> choiceSelections, Set<String> visiting, int depth,
                                           Map<String, List<AsnField>> cache, AsnTaggingMode taggingMode) {
        // First pass: parse the raw field heads so AUTOMATIC tagging can decide
        // whether to auto-number (it applies only when NO field is manually tagged).
        List<AsnField> parsedLeaves = new ArrayList<>();
        for (String line : splitFieldEntries(rawBody)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            AsnField parsed = parseFieldLine(trimmed, taggingMode);
            if (parsed != null) {
                parsedLeaves.add(parsed);
            }
        }

        boolean autoAssignTags = taggingMode == AsnTaggingMode.AUTOMATIC
                && parsedLeaves.stream().allMatch(f -> f.getTagNumber() == null);

        // Second pass: assign automatic tags if applicable, then resolve children.
        // Second pass: assign automatic tags if applicable, then resolve children.
        List<AsnField> fields = new ArrayList<>();
        for (int index = 0; index < parsedLeaves.size(); index++) {
            AsnField parsed = parsedLeaves.get(index);
            if (autoAssignTags) {
                parsed.setTagNumber(index);
                parsed.setTagClass(BerTagClass.CONTEXT);
                parsed.setExplicit(false);
            }

            // parsed.fieldType SIZE kisiti tasiyor olabilir; registry aramalari
            // kisitsiz ada gore yapilmalidir.
            String innerType = stripConstraint(parsed.getFieldType());
            // An inline "SEQUENCE OF Type" on the field itself must be split so
            // the registry is searched for the ELEMENT type; looking up the whole
            // collection expression finds nothing and silently drops the children.
            boolean inlineCollection = isRepeatedExpression(innerType);
            if (inlineCollection) {
                innerType = extractRepeatedInnerType(innerType);
            }
            boolean aliasRepeated = isAliasRepeated(registry, innerType);
            boolean repeated = parsed.isRepeated() || inlineCollection || aliasRepeated;
            // See AsnField#isNestedCollectionElement and the twin computation
            // (and the note on why 'parsed.isRepeated()', not the local
            // 'inlineCollection', is the field's-own-collection signal here)
            // in attachChildren.
            boolean nestedCollectionElement = parsed.isRepeated() && aliasRepeated;
            boolean nestedCollectionElementIsSet = nestedCollectionElement
                    && isAliasRepeatedAsSet(registry, innerType);
            List<AsnField> children = resolveByTypeName(registry, innerType, choiceSelections, visiting, depth + 1,
                    cache, taggingMode);

            // A field with no [n] of its own is tagged by the TYPE it names -
            // "sender Sender" with "Sender ::= [APPLICATION 196] PlmnId" is
            // written 5F 81 44, not as a bare OCTET STRING. This is the same
            // inheritance attachChildren has always applied to a CHOICE
            // alternative; leaving it out here flattened 1141 field declarations
            // across 12 modules (TAP0309 317, TAP-0309 316, TAP0311 295) plus the
            // 94 reachable sites whose type is a tagged SEQUENCE/SET, and left
            // whole TAP records carrying nothing but universal tags.
            EffectiveTag fieldTag = inheritedFieldTag(registry, parsed, innerType, taggingMode);

            boolean choiceElement = isChoiceType(registry, innerType);
            boolean setElement = isSetType(registry, innerType);
            AsnField resolved = AsnField.builder()
                    .fieldName(parsed.getFieldName())
                    .fieldType(resolveFieldType(registry, parsed.getFieldType(), innerType, children))
                    .optional(parsed.isOptional())
                    .repeated(repeated)
                    .choice(choiceElement)
                    .set(setElement)
                    .decoderHoistsImplicitChoice(isMmtelPartyAddressingFamily(registry))
                    .choiceTagImplicit(choiceTagImplicit(fieldTag, repeated, choiceElement, taggingMode))
                    .declaredTagging(fieldTag.declared())
                    .tagDeclaredOnType(fieldTag.fromType())
                    .moduleNamesNoTaggingMode(taggingMode == AsnTaggingMode.UNSPECIFIED)
                    .tagNumber(fieldTag.tagNumber())
                    .tagClass(fieldTag.tagClass())
                    .explicit(effectiveExplicit(fieldTag, repeated, choiceElement, taggingMode))
                    .universalTagOverride(resolveUniversalTagOverride(registry, innerType))
                    .structuralTypeWithNoComponents(
                            parsed.isOptional() && isStructuralTypeWithNoComponents(registry, innerType))
                    .nestedCollectionElement(nestedCollectionElement)
                    .nestedCollectionElementIsSet(nestedCollectionElementIsSet)
                    .children(children.isEmpty() ? null : children)
                    .build();
            if (repeated) {
                resolved.setElementTagCarrier(
                        buildElementTagCarrier(registry, resolved, parsed.getFieldType(), taggingMode));
            }
            fields.add(resolved);
        }
        return fields;
    }

    /**
     * The tag of an ordinary SEQUENCE/SET member: its own {@code [n]} when it
     * has one, otherwise the tag its type declares.
     *
     * <p>UNIVERSAL-class annotations are deliberately NOT inherited here.
     * {@code GraphicStringImp ::= [UNIVERSAL 25] IMPLICIT IA5String} re-tags the
     * value's own universal tag, which {@link #resolveUniversalTagOverride}
     * already carries into {@code AsnField.universalTagOverride} - the mechanism
     * the MMTel reference capture was verified against. Reading it a second time
     * here would give one annotation two owners.</p>
     */
    private EffectiveTag inheritedFieldTag(Map<String, AsnTypeDefinition> registry, AsnField parsed,
                                           String innerType, AsnTaggingMode taggingMode) {
        if (parsed.getTagNumber() != null) {
            return EffectiveTag.ofField(parsed.getTagNumber(), parsed.getTagClass(), parsed);
        }

        EffectiveTag inherited = resolveEffectiveTag(registry, parsed, innerType, taggingMode);
        boolean usable = inherited.tagNumber() != null && inherited.tagClass() != BerTagClass.UNIVERSAL;
        return usable ? inherited : EffectiveTag.ofField(null, parsed.getTagClass(), parsed);
    }

    /**
     * Resolves the tag that belongs to a CHOICE alternative. A tag written
     * directly on the alternative always wins; otherwise a leading tag on its
     * direct alias target is inherited.
     *
     * <p>Used only by {@link #attachChildren} (CHOICE alternatives).
     * {@link #parseFieldLines} (ordinary SEQUENCE/SET fields) deliberately does
     * NOT use this - see the comment at its call site.</p>
     */
    private EffectiveTag resolveEffectiveTag(Map<String, AsnTypeDefinition> registry, AsnField field,
                                             String innerType, AsnTaggingMode taggingMode) {
        if (field.getTagNumber() != null) {
            return EffectiveTag.ofField(field.getTagNumber(), field.getTagClass(), field);
        }

        AsnTypeDefinition definition = registry.get(innerType);
        if (definition == null) {
            return EffectiveTag.ofField(null, field.getTagClass(), field);
        }

        // The annotation lives in aliasTarget for an ALIAS and in tagPrefix for a
        // structured type - "Sender ::= [APPLICATION 196] PlmnId" and
        // "TransferBatch ::= [APPLICATION 1] SEQUENCE {...}" tag their type the
        // same way, and an alternative naming either of them inherits that tag.
        String annotation = definition.getKind() == AsnTypeKind.ALIAS
                ? definition.getAliasTarget()
                : definition.getTagPrefix();
        EffectiveTag inherited = readLeadingTag(annotation, taggingMode);
        return inherited != null
                ? inherited
                : EffectiveTag.ofField(null, field.getTagClass(), field);
    }

    /**
     * Reads a leading {@code [class n] IMPLICIT|EXPLICIT} annotation. Returns
     * null when there is none. The written keyword wins; without one the
     * module's tagging mode decides (X.680 31.2.7).
     */
    private EffectiveTag readLeadingTag(String annotation, AsnTaggingMode taggingMode) {
        if (annotation == null || annotation.isBlank()) {
            return null;
        }
        Matcher tag = ALIAS_TAG.matcher(annotation);
        if (!tag.find()) {
            return null;
        }

        BerTagClass tagClass = tag.group(1) != null
                ? BerTagClass.valueOf(tag.group(1))
                : BerTagClass.CONTEXT;
        // A tag written on a TYPE, not on a field: "NrFile ::= [APPLICATION 1]
        // SEQUENCE {...}". This used to keep X.680 31.2.7's EXPLICIT when the
        // header named no mode, parting company with resolveExplicit because no
        // measurement covered the type-level case. Round 9 measured it, on the
        // two modules built to ask exactly this, and both refused:
        //
        //   FDRInput.NrFile.name was probably not set and is not optional
        //   Audit_Record_Collection_St.LogEntry.collectionConfiguration
        //       was probably not set and is not optional
        //
        // Both name the first field the reading can reach. FDRInput went out as
        // 61 37 30 35 62 14 ..; EMM opens [APPLICATION 1], expects name's
        // [APPLICATION 2] (0x62) as the first content octet and finds 0x30, the
        // universal SEQUENCE we wrapped in. Audit went out as 75 43 30 41 16 08
        // ..; serviceName is OPTIONAL so it is skipped, and the failure lands on
        // collectionConfiguration, the first mandatory one - the same 0x30 in
        // the way. Two modules, two shapes, the same cause.
        //
        // So a keyword-less header is IMPLICIT for a type tag too, and the split
        // this method used to keep does not exist in the consumer: EMM reads
        // such a module as IMPLICIT throughout. UNSPECIFIED now resolves the
        // same way here as in resolveExplicit.
        //
        // A written keyword still wins, and an EXPLICIT header still means
        // EXPLICIT - though no module in the current data set writes one.
        boolean explicit = tag.group(3) != null
                ? tag.group(3).trim().equalsIgnoreCase(EXPLICIT_KEYWORD)
                : taggingMode == AsnTaggingMode.EXPLICIT;
        AsnDeclaredTagging declared = tag.group(3) == null
                ? AsnDeclaredTagging.NONE
                : (tag.group(3).trim().equalsIgnoreCase(EXPLICIT_KEYWORD)
                        ? AsnDeclaredTagging.EXPLICIT
                        : AsnDeclaredTagging.IMPLICIT);
        return new EffectiveTag(Integer.valueOf(tag.group(2)), tagClass, explicit, declared, true);
    }

    /**
     * A resolved tag plus what the schema actually wrote for it. {@code declared}
     * and {@code fromType} exist so the decision ({@code explicit}) and the
     * declaration stay separable all the way to {@link AsnField}; see
     * {@link AsnField#getDeclaredTagging()}.
     */
    private record EffectiveTag(Integer tagNumber, BerTagClass tagClass, boolean explicit,
                                AsnDeclaredTagging declared, boolean fromType) {

        /** A tag taken from a field that already carries its own declaration. */
        static EffectiveTag ofField(Integer tagNumber, BerTagClass tagClass, AsnField source) {
            return new EffectiveTag(tagNumber, tagClass, source.isExplicit(),
                    declarationOf(source), false);
        }

        private static AsnDeclaredTagging declarationOf(AsnField source) {
            return source.getDeclaredTagging() == null
                    ? AsnDeclaredTagging.NONE
                    : source.getDeclaredTagging();
        }
    }

    private String resolveLeafBaseType(Map<String, AsnTypeDefinition> registry, String typeName) {
        String current = stripAliasTag(typeName);
        Set<String> guard = new HashSet<>();
        while (current != null && guard.add(current)) {
            AsnTypeDefinition definition = registry.get(current);
            if (definition == null) {
                return current;
            }
            if (definition.getKind() == AsnTypeKind.ENUMERATED) {
                return formatWithNamedNumbers(AsnTypeKind.ENUMERATED.name(), definition.getRawBody());
            }
            if (definition.getKind() != AsnTypeKind.ALIAS) {
                return current;
            }
            String aliasTarget = definition.getAliasTarget();
            // Isimli-sabitli INTEGER (ornek: RecordType ::= INTEGER { mMTelRecord(83) })
            // bir alias ama govdesi sozluk tasir. stripConstraint bu sayilari SIZE
            // kisiti sanip silecegi icin, boyle bir govde varsa zincire devam etmeden
            // burada sonuclandirilir.
            if (containsNamedNumberList(aliasTarget)) {
                return formatWithNamedNumbers(extractBaseTypeToken(aliasTarget), aliasTarget);
            }
            String target = stripConstraint(aliasTarget);
            // Alias hedefi "[APPLICATION 2] IA5String" gibi tag önekli olabilir.
            // Tag zaten attachChildren tarafından okundu; burada sadece temel
            // tipin kalması gerekir, yoksa BerUniversalTag tipi tanıyamaz.
            target = stripAliasTag(target);
            current = isRepeatedExpression(target) ? extractRepeatedInnerType(target) : target;
        }
        return current;
    }

    /**
     * Finds the UNIVERSAL-class tag a type re-tags itself with, following the
     * same alias chain as {@link #resolveLeafBaseType} but keeping what that
     * method deliberately throws away.
     *
     * <p>{@link #resolveLeafBaseType} strips every leading tag annotation so
     * the chain bottoms out in a bare primitive name the encoder can classify.
     * For {@code GraphicStringImp ::= [UNIVERSAL 25] IMPLICIT IA5String} that
     * yields "IA5String" - correct for choosing how to encode the VALUE, but it
     * loses the fact that the TAG on the wire has to be 25 (GraphicString), not
     * 22 (IA5String). The MMTel-family reference captures EMM accepts do carry
     * tag 25 in exactly these places, so dropping the override produced BER
     * that a strict decoder rejects.</p>
     *
     * <p>Scope is deliberately narrow. Only UNIVERSAL-class annotations are
     * returned: APPLICATION/CONTEXT tags on an alias target are a different
     * mechanism, already handled by {@link #resolveEffectiveTag} for CHOICE
     * alternatives, and re-reading them here would double-apply them. The walk
     * also stops at the first tag it meets, since an outer re-tag shadows any
     * further one below it. In the current data set only three type definitions
     * across the MMTel/AIMS/IMS/UAG/ATS modules use this form (all
     * {@code [UNIVERSAL 25] IMPLICIT IA5String}); every other type returns
     * {@code null} and is encoded exactly as before.</p>
     */
    private Integer resolveUniversalTagOverride(Map<String, AsnTypeDefinition> registry, String typeName) {
        String current = stripAliasTag(typeName);
        if (current != null && isRepeatedExpression(current)) {
            // A repeated field's universal tag belongs to its ELEMENT type; the
            // collection's own SEQUENCE/SET tag is decided by the encoder.
            current = extractRepeatedInnerType(current);
        }
        Set<String> guard = new HashSet<>();
        while (current != null && guard.add(current)) {
            AsnTypeDefinition definition = registry.get(current);
            if (definition == null || definition.getKind() != AsnTypeKind.ALIAS
                    || definition.getAliasTarget() == null) {
                return null;
            }
            String aliasTarget = definition.getAliasTarget();
            Integer universalTag = readUniversalTag(aliasTarget);
            if (universalTag != null) {
                return universalTag;
            }
            if (containsNamedNumberList(aliasTarget)) {
                // Named-number bodies (ENUMERATED / INTEGER {...}) end the chain;
                // see the matching guard in resolveLeafBaseType.
                return null;
            }
            String target = stripAliasTag(stripConstraint(aliasTarget));
            current = isRepeatedExpression(target) ? extractRepeatedInnerType(target) : target;
        }
        return null;
    }

    /**
     * Reads a leading {@code [UNIVERSAL n]} annotation, returning {@code null}
     * for an untagged expression or for any other tag class.
     */
    private Integer readUniversalTag(String typeExpression) {
        if (typeExpression == null) {
            return null;
        }
        Matcher matcher = ALIAS_TAG.matcher(typeExpression);
        if (!matcher.find() || !BerTagClass.UNIVERSAL.name().equals(matcher.group(1))) {
            return null;
        }
        return Integer.valueOf(matcher.group(2));
    }

    private boolean containsNamedNumberList(String typeExpression) {
        return Objects.nonNull(typeExpression) && typeExpression.contains("{")
                && NAMED_NUMBER_ENTRY.matcher(typeExpression).find();
    }

    private String extractBaseTypeToken(String typeExpression) {
        int braceIndex = typeExpression.indexOf('{');
        String prefix = braceIndex == -1 ? typeExpression : typeExpression.substring(0, braceIndex);
        return stripAliasTag(prefix).trim();
    }

    /**
     * "ad(sayi)" ciftlerini govdeden cikarip "TIP{ad(sayi),ad(sayi)}" seklinde
     * sikistirir. Bos govde ya da eslesme yoksa yalnizca cIplak tip adi doner.
     * Bu bicim daha sonra AI'in dondurdugu ismi sayiya cevirmek icin kullanilacak.
     */
    private String formatWithNamedNumbers(String baseTypeToken, String rawBody) {
        if (Objects.isNull(rawBody)) {
            return baseTypeToken;
        }
        Matcher matcher = NAMED_NUMBER_ENTRY.matcher(rawBody);
        StringBuilder compacted = new StringBuilder();
        while (matcher.find()) {
            if (compacted.length() > 0) {
                compacted.append(',');
            }
            compacted.append(matcher.group(1)).append('(').append(matcher.group(2)).append(')');
        }
        return compacted.isEmpty() ? baseTypeToken : baseTypeToken + "{" + compacted + "}";
    }

    /** Removes a leading tag annotation such as "[APPLICATION 2]" or "[5] IMPLICIT". */
    private String stripAliasTag(String typeExpression) {
        if (typeExpression == null) {
            return null;
        }
        return ALIAS_TAG.matcher(typeExpression).replaceFirst("").trim();
    }

    /** Normalizes an alias target before using it as a registry key or type expression. */
    private String normalizeAliasTarget(String aliasTarget) {
        return stripAliasTag(stripConstraint(aliasTarget));
    }

    /**
     * True when {@code typeName} is an alias whose target is a list, e.g.
     * {@code ListOfInvolvedParties ::= SEQUENCE OF InvolvedParty}.
     *
     * <p>The alias target is stored raw, so it may still carry its own tag -
     * {@code CurrencyConversion ::= [APPLICATION 80] SEQUENCE OF
     * ExchangeRateDefinition}. Without stripping that tag first the target does
     * not start with "SEQUENCE OF", the field is never marked repeated, and a
     * list gets encoded as a single element. The tag itself is not lost: the
     * caller reads it separately via ALIAS_TAG when the field has no tag of its
     * own.</p>
     */
    private boolean isAliasRepeated(Map<String, AsnTypeDefinition> registry, String typeName) {
        AsnTypeDefinition def = registry.get(typeName);
        if (def == null || def.getKind() != AsnTypeKind.ALIAS || def.getAliasTarget() == null) {
            return false;
        }
        return isRepeatedExpression(stripConstraint(stripAliasTag(def.getAliasTarget())));
    }

    /**
     * True when {@code typeName} is a {@link #isAliasRepeated} alias declared
     * {@code SET OF} rather than {@code SEQUENCE OF}.
     *
     * <p>Only consulted for {@link AsnField#isNestedCollectionElement} - the
     * middle layer's OWN kind, distinct from {@link #isSetType}, which follows
     * the SAME alias one step further to the innermost element type
     * ({@code Call-Transfer-Info-List} -&gt; {@code Call-Transfer-Info}). Two
     * different questions the two-layer case needs answered separately: what
     * wraps the middle collection's own elements, and what tag the middle
     * wrapper itself carries.</p>
     */
    private boolean isAliasRepeatedAsSet(Map<String, AsnTypeDefinition> registry, String typeName) {
        AsnTypeDefinition def = registry.get(typeName);
        if (def == null || def.getKind() != AsnTypeKind.ALIAS || def.getAliasTarget() == null) {
            return false;
        }
        String target = stripConstraint(stripAliasTag(def.getAliasTarget()));
        return isRepeatedExpression(target) && SET_OF_PREFIX.matcher(target).lookingAt();
    }

    /**
     * True when {@code typeName} resolves to a CHOICE, following ALIAS chains
     * (e.g. {@code ServedPartyIPAddress ::= IPAddress} where IPAddress is a
     * CHOICE). The encoder needs this to avoid wrapping a CHOICE in a synthetic
     * universal SEQUENCE, which would make the record undecodable.
     *
     * <p>Also follows a named "list" alias down to its element type (e.g.
     * {@code ListOfInvolvedParties ::= SEQUENCE OF InvolvedParty} -&gt; checks
     * InvolvedParty), the same way {@link #resolveRoot} and
     * {@link #resolveChoiceRootAlternative} already do via
     * {@link #extractRepeatedInnerType}. Without this, a repeated field whose
     * repetition is introduced through a named alias - rather than written
     * inline as "SEQUENCE OF X" on the field itself - would report
     * {@code choice=false}, and {@code encodeRepeated} would wrap each element
     * in a synthetic SEQUENCE exactly like the bug this class already fixes for
     * the inline case.</p>
     */
    private boolean isChoiceType(Map<String, AsnTypeDefinition> registry, String typeName) {
        String current = stripConstraint(typeName);
        Set<String> guard = new HashSet<>();
        while (current != null && guard.add(current)) {
            AsnTypeDefinition def = registry.get(current);
            if (def == null) {
                return false;
            }
            if (def.getKind() == AsnTypeKind.CHOICE) {
                return true;
            }
            if (def.getKind() != AsnTypeKind.ALIAS || def.getAliasTarget() == null) {
                return false;
            }
            String target = stripConstraint(stripAliasTag(def.getAliasTarget()));
            current = isRepeatedExpression(target) ? extractRepeatedInnerType(target) : target;
        }
        return false;
    }

    /**
     * The ELEMENT type of a collection, following alias chains the same way
     * {@link #isChoiceType} does: {@code VasInfo ::= [APPLICATION 7] SEQUENCE OF
     * VasDefinition} answers {@code VasDefinition}. Null when the type is not a
     * collection.
     *
     * <p>The field itself keeps the COLLECTION's tag ({@code [APPLICATION 7]}),
     * so the element type's own tag has nowhere to go unless it is looked up
     * separately - which is what {@link #buildElementTagCarrier} does.</p>
     */
    private String resolveElementTypeName(Map<String, AsnTypeDefinition> registry, String typeName) {
        String current = stripConstraint(typeName);
        Set<String> guard = new HashSet<>();
        while (current != null && guard.add(current)) {
            if (isRepeatedExpression(current)) {
                return extractRepeatedInnerType(current);
            }
            AsnTypeDefinition def = registry.get(current);
            if (def == null || def.getKind() != AsnTypeKind.ALIAS || def.getAliasTarget() == null) {
                return null;
            }
            current = stripConstraint(stripAliasTag(def.getAliasTarget()));
        }
        return null;
    }

    /**
     * The tag each ELEMENT of a collection carries, when its type declares one.
     *
     * <p>{@code VasInfo ::= [APPLICATION 7] SEQUENCE OF VasDefinition} with
     * {@code VasDefinition ::= [APPLICATION 238] SEQUENCE {...}} encodes as
     * {@code 67 { 7F 81 6E {...} 7F 81 6E {...} }}: the collection's tag once,
     * the element type's tag on every element. {@code encodeRepeated} wrote a
     * universal SEQUENCE there instead, because the resolved field only ever
     * held the collection's tag. 129 declarations across 37 modules are shaped
     * this way, 91 of them in the TAP family, where it flattened 44 nodes of a
     * single record.</p>
     *
     * <p>Carried as a FIELD rather than as a loose tag so the encoder writes it
     * through the same {@code wrapInTlv} and the verifier walks it through the
     * same {@code walkField} as any other tagged field - the element is that
     * field, minus the repetition. UNIVERSAL-class annotations are excluded for
     * the same reason as everywhere else: {@code [UNIVERSAL 25] IMPLICIT
     * IA5String} belongs to {@code universalTagOverride}, which already writes
     * it correctly on every element, and which the MMTel capture verified.</p>
     */
    private AsnField buildElementTagCarrier(Map<String, AsnTypeDefinition> registry, AsnField field,
                                            String innerType, AsnTaggingMode taggingMode) {
        String elementType = resolveElementTypeName(registry, innerType);
        if (elementType == null) {
            return null;
        }

        AsnTypeDefinition definition = registry.get(elementType);
        if (definition == null) {
            return null;
        }
        String annotation = definition.getKind() == AsnTypeKind.ALIAS
                ? definition.getAliasTarget()
                : definition.getTagPrefix();
        EffectiveTag tag = readLeadingTag(annotation, taggingMode);
        if (tag == null || tag.tagClass() == BerTagClass.UNIVERSAL) {
            return null;
        }

        return AsnField.builder()
                .fieldName(field.getFieldName())
                .fieldType(field.getFieldType())
                .optional(field.isOptional())
                .repeated(false)
                .choice(field.isChoice())
                .set(field.isSet())
                .decoderHoistsImplicitChoice(field.isDecoderHoistsImplicitChoice())
                .tagNumber(tag.tagNumber())
                .tagClass(tag.tagClass())
                .explicit(tag.explicit())
                .declaredTagging(tag.declared())
                .tagDeclaredOnType(tag.fromType())
                .moduleNamesNoTaggingMode(taggingMode == AsnTaggingMode.UNSPECIFIED)
                .universalTagOverride(field.getUniversalTagOverride())
                .children(field.getChildren())
                .build();
    }

    /**
     * True when {@code typeName} resolves to a SET (as opposed to a SEQUENCE),
     * following ALIAS chains and drilling through a list alias to its element
     * type exactly like {@link #isChoiceType}.
     *
     * <p>The encoder needs this because a SET's universal tag is 17 while a
     * SEQUENCE's is 16, and the two are indistinguishable once the type has
     * been resolved to a plain field list.</p>
     */
    private boolean isSetType(Map<String, AsnTypeDefinition> registry, String typeName) {
        String current = stripConstraint(typeName);
        Set<String> guard = new HashSet<>();
        while (current != null && guard.add(current)) {
            AsnTypeDefinition def = registry.get(current);
            if (def == null) {
                return false;
            }
            if (def.getKind() == AsnTypeKind.SET) {
                return true;
            }
            if (def.getKind() != AsnTypeKind.ALIAS || def.getAliasTarget() == null) {
                return false;
            }
            String target = stripConstraint(stripAliasTag(def.getAliasTarget()));
            current = isRepeatedExpression(target) ? extractRepeatedInnerType(target) : target;
        }
        return false;
    }

    /**
     * True when {@code typeName} resolves to a SEQUENCE, SET or CHOICE whose
     * body declares NO components, following ALIAS chains and named collection
     * aliases exactly as {@link #isSetType} does.
     *
     * <p>This asks about the DEFINITION, not about the resolution result.
     * {@link #resolveByTypeName} returns an empty list in four unrelated cases -
     * type absent from the registry, recursion guard tripped, ENUMERATED, and
     * structured-but-empty - and only the last is a container the encoder must
     * still write as one. Reading the outcome instead of the body would fold the
     * other three in with it: an unresolved type name may well be primitive, and
     * a field cut off by the depth guard has real components that simply were
     * not walked.</p>
     *
     * <p>The body is blank rather than absent because
     * {@link AsnTypeRegistryBuilder} strips comments before it records
     * {@code rawBody}, so {@code SET { -- operator specific record extensions }}
     * arrives here as whitespace. See
     * {@link com.turkcell.cdrgenerator1.model.AsnField#isStructuralTypeWithNoComponents()}
     * for the site that made this necessary and what EMM answered there.</p>
     */
    private boolean isStructuralTypeWithNoComponents(Map<String, AsnTypeDefinition> registry, String typeName) {
        String current = stripConstraint(typeName);
        Set<String> guard = new HashSet<>();
        while (current != null && guard.add(current)) {
            AsnTypeDefinition def = registry.get(current);
            if (def == null) {
                return false;
            }
            if (def.getKind() == AsnTypeKind.SEQUENCE
                    || def.getKind() == AsnTypeKind.SET
                    || def.getKind() == AsnTypeKind.CHOICE) {
                return def.getRawBody() == null || def.getRawBody().isBlank();
            }
            if (def.getKind() != AsnTypeKind.ALIAS || def.getAliasTarget() == null) {
                return false;
            }
            String target = stripConstraint(stripAliasTag(def.getAliasTarget()));
            current = isRepeatedExpression(target) ? extractRepeatedInnerType(target) : target;
        }
        return false;
    }

    /** ENUMERATED / isimli-sabitli INTEGER govdesindeki "ad(sayi)" ciftlerini yakalar. */
    private static final Pattern NAMED_NUMBER_ENTRY = Pattern.compile(
            "([A-Za-z][\\w-]*)\\s*\\(\\s*(-?\\d+)\\s*\\)");

    /**
     * Parses a single field/alternative line into an AsnField.
     *
     * <p>The {@code explicit} flag is decided as follows: a per-field
     * {@code EXPLICIT}/{@code IMPLICIT} keyword always wins; otherwise the module
     * default {@code taggingMode} is applied (EXPLICIT default -&gt; explicit,
     * IMPLICIT/AUTOMATIC -&gt; implicit).</p>
     */
    private AsnField parseFieldLine(String line, AsnTaggingMode taggingMode) {
        boolean optional = line.contains("OPTIONAL");
        Matcher matcher = FIELD_LINE.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        String fieldName = matcher.group(1);
        BerTagClass tagClass = matcher.group(2) != null
                ? BerTagClass.valueOf(matcher.group(2))
                : BerTagClass.CONTEXT;
        Integer tagNumber = matcher.group(3) != null ? Integer.valueOf(matcher.group(3)) : null;

        String taggingKeyword = matcher.group(4) != null ? matcher.group(4).trim() : null;
        boolean explicit = resolveExplicit(taggingKeyword, taggingMode);
        // Recorded from the text, not derived from `explicit` - the two differ
        // wherever a compatibility rule applies. See AsnField.getDeclaredTagging.
        AsnDeclaredTagging declaredTagging = declaredTaggingOf(taggingKeyword);

        // SIZE kisiti (ya da bir INTEGER (min..max) araligi) stripConstraint
        // tarafindan silinmeden once okunur; alan ifadesinde varsa alias
        // zincirinden gelenden onceliklidir. Ikisi ayni alanda birlikte
        // gorulmez, bu yuzden SIZE bulunamazsa duz aralik denenir.
        String rawTypeExpr = matcher.group(5);
        String inlineSize = readSizeConstraintText(rawTypeExpr);
        if (inlineSize == null) {
            inlineSize = readIntegerRangeConstraintText(rawTypeExpr);
        }
        String inlineCode = readCodeText(rawTypeExpr);

        // An ENUMERATED (or named-number INTEGER) body written INLINE on the
        // field itself - "cdrIdType [4] ENUMERATED { threegpp(0), nin(1) }" -
        // has nowhere else its "(0)"/"(1)" pairs could survive: stripConstraint
        // treats every "(...)" as a SIZE/range annotation to erase, and this is
        // the ONLY point in the whole pipeline that ever sees the raw text
        // before that happens. resolveLeafBaseType already guards the equivalent
        // case for an ALIAS pointing at a named-number body
        // (containsNamedNumberList(aliasTarget) in the ENUMERATED/ALIAS branch);
        // this mirrors that guard for the body written directly on the field,
        // which had none. Losing the numbers here left the field's own
        // AsnField.fieldType reading "ENUMERATED { threegpp, nin }" - still
        // classified ENUMERATED by BerPrimitiveType, but with no named-number
        // list for anything downstream to consult:
        // FieldValueValidator.isDeclaredNumber found no "(n)" pairs despite the
        // "{", read that as "not actually a named-number type after all" and
        // stopped restricting the value entirely, CdrPromptBuilder never told
        // the AI which numbers were valid, and EnumValueResolver could not map
        // a name back to one. An AI-generated "29147" for a two-valued
        // (0/1) ENUMERATED reached the wire this way.
        String typeExpr = containsNamedNumberList(rawTypeExpr)
                ? formatWithNamedNumbers(extractBaseTypeToken(rawTypeExpr), rawTypeExpr)
                : stripConstraint(rawTypeExpr).replace("OPTIONAL", "").trim();

        boolean repeated = isRepeatedExpression(typeExpr);
        String fieldType = repeated ? extractRepeatedInnerType(typeExpr) : normalize(typeExpr);

        return AsnField.builder()
                .fieldName(fieldName)
                .fieldType(appendSizeConstraint(fieldType, inlineSize, inlineCode))
                .optional(optional)
                .repeated(repeated)
                .tagNumber(tagNumber)
                .tagClass(tagClass)
                .explicit(explicit)
                .declaredTagging(declaredTagging)
                .moduleNamesNoTaggingMode(taggingMode == AsnTaggingMode.UNSPECIFIED)
                .build();
    }

    /** Maps the written keyword straight to its declaration, with none meaning NONE. */
    private AsnDeclaredTagging declaredTaggingOf(String taggingKeyword) {
        if (EXPLICIT_KEYWORD.equalsIgnoreCase(taggingKeyword)) {
            return AsnDeclaredTagging.EXPLICIT;
        }
        if (IMPLICIT_KEYWORD.equalsIgnoreCase(taggingKeyword)) {
            return AsnDeclaredTagging.IMPLICIT;
        }
        return AsnDeclaredTagging.NONE;
    }

    private boolean resolveExplicit(String taggingKeyword, AsnTaggingMode taggingMode) {
        if (EXPLICIT_KEYWORD.equalsIgnoreCase(taggingKeyword)) {
            return true;
        }
        if (IMPLICIT_KEYWORD.equalsIgnoreCase(taggingKeyword)) {
            return false;
        }
        // No per-field keyword, and the header named no mode either. X.680 31.2.7
        // says EXPLICIT; EMM was measured doing the opposite for a FIELD tag, and
        // this is the one place that acts on that measurement. IMSCDRS.TokensCSCF
        // went out five times in the standard form and was refused every time;
        // the same record with its 34 fields IMPLICIT was accepted, and EMM's
        // decode returned all 34 values matching the file exactly. See
        // AsnTypeRegistryBuilder.detectTaggingMode.
        if (taggingMode == AsnTaggingMode.UNSPECIFIED) {
            return false;
        }
        return taggingMode == AsnTaggingMode.EXPLICIT;
    }

    /**
     * Matches the collection prefix of a {@code SEQUENCE OF} / {@code SET OF}
     * expression, tolerating anything the schema puts between the keyword and
     * {@code OF} - most importantly the size constraint, which ASN.1 writes as
     * {@code SEQUENCE (SIZE(1..5)) OF Type}, and stray extra whitespace.
     *
     * <p>The old test was {@code startsWith("SEQUENCE OF")}, which missed both
     * {@code SEQUENCE  OF UsedServiceUnit} (two spaces) and
     * {@code SEQUENCE (SIZE(1..5)) OF AccumulatorValueInfo}. When it missed, the
     * element type was never split off, the registry lookup for the whole
     * expression found nothing, the field ended up with no children AND without
     * its repeated flag, and the encoder wrote a collection of structures out as
     * a single flat text leaf. 70 fields across 19 modules were affected.</p>
     *
     * <p>{@code .*?} is reluctant and {@code OF} is anchored on word boundaries,
     * so an element type that merely contains the letters "of"
     * ({@code SEQUENCE OF ListOfThings}) still splits at the collection's own
     * {@code OF}. {@code SEQUENCE\b} likewise cannot match a type merely named
     * {@code SequenceOfferData}.</p>
     */
    private static final Pattern COLLECTION_PREFIX = Pattern.compile(
            "^(?:SEQUENCE|SET)\\b.*?\\bOF\\b\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Distinguishes {@code SET OF} from {@code SEQUENCE OF} for {@link #isAliasRepeatedAsSet}. */
    private static final Pattern SET_OF_PREFIX = Pattern.compile("^SET\\b", Pattern.CASE_INSENSITIVE);

    private boolean isRepeatedExpression(String typeExpr) {
        return Objects.nonNull(typeExpr)
                && typeExpr.indexOf('{') < 0
                && COLLECTION_PREFIX.matcher(typeExpr).lookingAt();
    }

    private String extractRepeatedInnerType(String typeExpr) {
        return COLLECTION_PREFIX.matcher(typeExpr).replaceFirst("").trim();
    }

    private String normalize(String typeExpr) {
        return typeExpr.replaceAll("\\s+", " ").trim();
    }

    /**
     * Bir tip ifadesindeki SIZE kisitini HAM METIN olarak okur.
     *
     * <p>Sayiya cevirmek bilgi kaybettirir: {@code SIZE(1..20)} ile
     * {@code SIZE(20)} ayni ust sinira sahiptir ama ilki degisken, ikincisi SABIT
     * uzunluklu bir alandir (X.680 49.4). Kodlayici bu ayrimi sabit genislikli
     * alanlari bosluklarla tamamlamak icin kullanir, dolayisiyla kisit metni
     * oldugu gibi tasinmalidir.</p>
     */
    private String readSizeConstraintText(String typeExpression) {
        if (typeExpression == null) {
            return null;
        }
        Matcher matcher = SIZE_CONSTRAINT.matcher(typeExpression);
        return matcher.find() ? matcher.group() : null;
    }

    /**
     * Bir tip ifadesindeki duz INTEGER (min..max) araligini HAM METIN olarak
     * okur - SIZE(n) disinda kalan tek kisit sekli budur. Parantezler
     * OLMADAN dondurulur ("0..999"), cunku appendSizeConstraint zaten kendi
     * parantezini ekliyor (readSizeConstraintText'in "SIZE(...)" doner
     * bicimiyle simetrik degil, cunku o kisitin adi zaten kendi
     * parantezinin icinde degil disinda).
     *
     * <p>Cagirilmadan once SIZE_CONSTRAINT denenmis olmalidir: bu metot hangi
     * kisit turunun soz konusu oldugunu bilmez, sadece ilk "(min..max)"
     * kalibina bakar.</p>
     */
    private String readIntegerRangeConstraintText(String typeExpression) {
        if (typeExpression == null) {
            return null;
        }
        Matcher matcher = INTEGER_RANGE_CONSTRAINT.matcher(typeExpression);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) + ".." + matcher.group(2);
    }

    /** Sabit genislikli bir alanin hizalamasini bildiren {@code CODE("LEFT")} isareti. */
    private String readCodeText(String typeExpression) {
        if (typeExpression == null) {
            return null;
        }
        Matcher matcher = CODE_MARKER.matcher(typeExpression);
        return matcher.find() ? matcher.group() : null;
    }

    /**
     * Alan satirinda SIZE yoksa alias zincirini takip ederek ilk SIZE kisitini bulur.
     * Ornek: mSISDN -> MSISDN -> IA5STRING (SIZE(30)) icin "SIZE(30)" doner.
     */
    private String findSizeThroughAliases(Map<String, AsnTypeDefinition> registry, String typeName) {
        String current = stripAliasTag(typeName);
        Set<String> guard = new HashSet<>();
        int depth = 0;

        while (current != null && guard.add(current) && depth++ < ALIAS_SIZE_MAX_DEPTH) {
            AsnTypeDefinition definition = registry.get(current);
            if (definition == null || definition.getKind() != AsnTypeKind.ALIAS) {
                return null;
            }
            String target = definition.getAliasTarget();
            String size = readSizeConstraintText(target);
            if (size != null) {
                return size;
            }
            String stripped = stripAliasTag(stripConstraint(target));
            current = isRepeatedExpression(stripped) ? extractRepeatedInnerType(stripped) : stripped;
        }
        return null;
    }

    /**
     * Alan satirinda dogrudan (min..max) yoksa alias zincirini takip ederek ilk
     * INTEGER deger araligini bulur. Ornek: noReplyTimerValue [2] Milliseconds
     * OPTIONAL alan satirinin kendisinde kisit yok - Milliseconds ::= INTEGER
     * (0..999) tanimindan gelir. findSizeThroughAliases'in aynisi, sadece
     * SIZE(...) yerine duz bir sayisal araligi ariyor.
     *
     * <p>Bu adim eklenmeden once bu zincirdeki HICBIR INTEGER (min..max)
     * kisiti AsnField.fieldType'a ulasamiyordu: stripConstraint parantezi
     * erkenden siliyor, resolveLeafBaseType da bare "INTEGER" ile donuyordu.
     * Sonuc: alan adinda "fraction" GECEN alanlar yml'deki adi-tabanli kural
     * sayesinde tesadufen dogru deger aliyordu, ama ayni Milliseconds tipini
     * PAYLASAN, adinda "fraction" GECMEYEN noReplyTimerValue bunun disinda
     * kaliyordu: AI'in urettigi 23231 (0..999 disi) hicbir katmanda
     * reddedilmeden dogrudan kodlanan BER'e ulasti - MMTelChargingDataTypes
     * uzerinde gozlenen, dogrulanmis bir ornek.</p>
     */
    private String findIntegerRangeThroughAliases(Map<String, AsnTypeDefinition> registry, String typeName) {
        String current = stripAliasTag(typeName);
        Set<String> guard = new HashSet<>();
        int depth = 0;

        while (current != null && guard.add(current) && depth++ < ALIAS_SIZE_MAX_DEPTH) {
            AsnTypeDefinition definition = registry.get(current);
            if (definition == null || definition.getKind() != AsnTypeKind.ALIAS) {
                return null;
            }
            String target = definition.getAliasTarget();
            String range = readIntegerRangeConstraintText(target);
            if (range != null) {
                return range;
            }
            String stripped = stripAliasTag(stripConstraint(target));
            current = isRepeatedExpression(stripped) ? extractRepeatedInnerType(stripped) : stripped;
        }
        return null;
    }

    /**
     * Cozulmus temel tipe SIZE kisitini (ve varsa hizalama isaretini) geri ekler.
     *
     * Kisitlar arama sirasinda kaldirilir (registry anahtarlari kisitsizdir), ancak
     * yaprak alanin fieldType degerinde tutulmasi gerekir: yapay zeka katmani ve
     * dogrulayici azami uzunlugu, kodlayici ise sabit genislik dolgusunu buradan
     * okur. BerPrimitiveType startsWith ile calistigi icin sondaki kisit tip
     * tanimayi bozmaz.
     */
    private String appendSizeConstraint(String baseType, String sizeText, String codeText) {
        if (sizeText == null || baseType == null || readSizeConstraintText(baseType) != null) {
            return baseType;
        }
        String constraint = codeText == null ? sizeText : sizeText + " " + codeText;
        return baseType + SIZE_SUFFIX_TEMPLATE.formatted(constraint);
    }

    private String stripConstraint(String text) {
        String previous;
        String current = text;
        do {
            previous = current;
            current = current.replaceAll("\\([^()]*\\)", "").trim();
        } while (!current.equals(previous));
        return current;
    }

    private String resolveFieldType(Map<String, AsnTypeDefinition> registry, String declaredType,
                                    String innerType, List<AsnField> children) {
        if (!children.isEmpty()) {
            return innerType;
        }
        // 'innerType' went through stripConstraint a SECOND time on its way
        // here (once already in parseFieldLine, again as the caller's own
        // registry-lookup key) - harmless for that lookup, since an inline
        // named-number body was never going to match a registry entry either
        // way, but fatal for a "(0)"/"(1)" pair a leaf type carries: the second
        // pass erases what the first pass had already preserved.
        // 'declaredType' is the field's own, still-intact text (parseFieldLine
        // now keeps a "{ name(number), ... }" body whole - see its own
        // containsNamedNumberList guard), so it is read straight from there
        // rather than through 'innerType'.
        if (containsNamedNumberList(declaredType)) {
            return formatWithNamedNumbers(extractBaseTypeToken(declaredType), declaredType);
        }
        String size = readSizeConstraintText(declaredType);
        String code = readCodeText(declaredType);
        if (size == null) {
            size = findSizeThroughAliases(registry, innerType);
        }
        // SIZE() ve bir INTEGER deger araligi ayni alanda birlikte gorulmez,
        // bu yuzden SIZE hicbir asamada bulunamadiysa duz (min..max) araligi
        // denenir - once alanin kendi satirinda, sonra alias zincirinde.
        if (size == null) {
            size = readIntegerRangeConstraintText(declaredType);
        }
        if (size == null) {
            size = findIntegerRangeThroughAliases(registry, innerType);
        }
        return appendSizeConstraint(resolveLeafBaseType(registry, innerType), size, code);
    }
}
