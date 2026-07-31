package com.turkcell.cdrgenerator1.parser;

import com.turkcell.cdrgenerator1.model.AsnField;
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
    /** {@code CODE("LEFT")} / {@code CODE("RIGHT")}: sabit genislikli alanin hizalamasi. */
    private static final Pattern CODE_MARKER = Pattern.compile(
            "CODE\\s*\\(\\s*\"[^\"]*\"\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final String SIZE_SUFFIX_TEMPLATE = " (%s)";
    private static final int ALIAS_SIZE_MAX_DEPTH = 15;

    /** Root resolution result: the root type's kind plus its resolved fields. */
    public record ResolvedRoot(AsnTypeKind kind, List<AsnField> fields) {
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

        // Follow alias chains down to the underlying structured type.
        String resolvedName = rootTypeName;
        AsnTypeDefinition current = registry.get(resolvedName);
        Set<String> aliasGuard = new HashSet<>();
        while (current != null && current.getKind() == AsnTypeKind.ALIAS && aliasGuard.add(resolvedName)) {
            String target = normalizeAliasTarget(current.getAliasTarget());
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
            return new ResolvedRoot(AsnTypeKind.CHOICE,
                    alternative == null ? List.of() : List.of(alternative));
        }

        List<AsnField> fields = resolveByTypeName(registry, resolvedName, selections, new HashSet<>(), 0,
                cache, taggingMode);
        return new ResolvedRoot(current.getKind(), fields);
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

        List<AsnField> result = switch (definition.getKind()) {
            case ENUMERATED -> List.of();
            case ALIAS -> resolveAlias(registry, definition, choiceSelections, nextVisiting, depth, cache, taggingMode);
            case SEQUENCE -> parseFieldLines(registry, definition.getRawBody(), choiceSelections, nextVisiting, depth, cache, taggingMode);
            case SET -> sortSetComponents(
                    parseFieldLines(registry, definition.getRawBody(), choiceSelections, nextVisiting, depth, cache, taggingMode));
            case CHOICE -> resolveChoiceAlternative(registry, typeName, definition.getRawBody(), choiceSelections, nextVisiting, depth, cache, taggingMode);
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
        boolean repeated = field.isRepeated() || isAliasRepeated(registry, innerType);
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
        return AsnField.builder()
                .fieldName(field.getFieldName())
                .fieldType(fieldType)
                .optional(field.isOptional())
                .repeated(repeated)
                .choice(choiceElement)
                .set(setElement)
                .tagNumber(effectiveTag.tagNumber())
                .tagClass(effectiveTag.tagClass())
                .explicit(effectiveExplicit(registry, effectiveTag.explicit(), repeated, choiceElement))
                .universalTagOverride(resolveUniversalTagOverride(registry, innerType))
                .children(children.isEmpty() ? null : children)
                .build();
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
     * <p>This is NOT safe to apply everywhere: GGSN/LTE-family CDRs
     * legitimately write EXPLICIT on SEQUENCE/SET/INTEGER/OCTET STRING fields
     * too (e.g. LTE-R10's {@code servedPDPPDNAddress [9] EXPLICIT PDPAddress}),
     * and there is zero EMM verification either way for that family - applying
     * this rule there would be exactly the unverified "strip every EXPLICIT"
     * shortcut this method avoids. So it is gated on
     * {@link #isVerifiedExplicitNeutralizationFamily}: it only fires inside the
     * MMTel/AIMS/IMS/UAG/ATS lineage the reference capture actually covers.
     * Outside that lineage, every written EXPLICIT is returned untouched.</p>
     */
    private boolean effectiveExplicit(Map<String, AsnTypeDefinition> registry, boolean writtenExplicit,
                                      boolean repeated, boolean choiceElement) {
        boolean scalarChoice = choiceElement && !repeated;
        if (scalarChoice) {
            return writtenExplicit;
        }
        if (isVerifiedExplicitNeutralizationFamily(registry)) {
            return false;
        }
        return writtenExplicit;
    }

    /**
     * True when this module's registry carries the shared {@code InvolvedParty}
     * CHOICE lineage - the structural fingerprint of the MMTel/AIMS/IMS/UAG/ATS
     * family whose EXPLICIT-almost-everywhere anomaly was verified against a
     * real EMM-accepted MMTel reference capture (see {@link #effectiveExplicit}).
     *
     * <p>Confirmed absent from every GGSN/LTE/CCN-family module in the current
     * data set (they carry no SIP/IMS party addressing at all), so this check
     * naturally excludes LTE-R10's unrelated, unverified
     * {@code servedPDPPDNAddress}/{@code PDPAddress} case without needing a
     * hardcoded module-name list.</p>
     */
    private boolean isVerifiedExplicitNeutralizationFamily(Map<String, AsnTypeDefinition> registry) {
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
        return entries;
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
            boolean repeated = parsed.isRepeated() || isAliasRepeated(registry, innerType);
            List<AsnField> children = resolveByTypeName(registry, innerType, choiceSelections, visiting, depth + 1,
                    cache, taggingMode);

            // NOT: burada bilerek resolveEffectiveTag KULLANILMIYOR. Bir SEQUENCE/SET
            // govdesindeki alan kendi [n] etiketini tasimiyorsa tag'siz kalir - alias
            // hedefinin kendi tag'i (attachChildren'daki CHOICE alternatifi durumunun
            // aksine) miras alinmaz. Bu, TAP ailesinde 1487 alani (27 yapida,
            // TAP0309'da 427) etkileyen ayri, kasitli olarak ertelenmis bir konu;
            // bkz. AsnFieldTreeResolverTest.listAliasCarryingItsOwnTagIsStillDetectedAsRepeated javadoc'u.
            boolean choiceElement = isChoiceType(registry, innerType);
            boolean setElement = isSetType(registry, innerType);
            fields.add(AsnField.builder()
                    .fieldName(parsed.getFieldName())
                    .fieldType(resolveFieldType(registry, parsed.getFieldType(), innerType, children))
                    .optional(parsed.isOptional())
                    .repeated(repeated)
                    .choice(choiceElement)
                    .set(setElement)
                    .tagNumber(parsed.getTagNumber())
                    .tagClass(parsed.getTagClass())
                    .explicit(effectiveExplicit(registry, parsed.isExplicit(), repeated, choiceElement))
                    .universalTagOverride(resolveUniversalTagOverride(registry, innerType))
                    .children(children.isEmpty() ? null : children)
                    .build());
        }
        return fields;
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
            return new EffectiveTag(field.getTagNumber(), field.getTagClass(), field.isExplicit());
        }

        AsnTypeDefinition aliasDef = registry.get(innerType);
        if (aliasDef == null || aliasDef.getKind() != AsnTypeKind.ALIAS
                || aliasDef.getAliasTarget() == null) {
            return new EffectiveTag(null, field.getTagClass(), field.isExplicit());
        }

        Matcher aliasTag = ALIAS_TAG.matcher(aliasDef.getAliasTarget());
        if (!aliasTag.find()) {
            return new EffectiveTag(null, field.getTagClass(), field.isExplicit());
        }

        BerTagClass tagClass = aliasTag.group(1) != null
                ? BerTagClass.valueOf(aliasTag.group(1))
                : BerTagClass.CONTEXT;
        boolean explicit = aliasTag.group(3) != null
                ? aliasTag.group(3).trim().equalsIgnoreCase(EXPLICIT_KEYWORD)
                : taggingMode == AsnTaggingMode.EXPLICIT;
        return new EffectiveTag(Integer.valueOf(aliasTag.group(2)), tagClass, explicit);
    }

    private record EffectiveTag(Integer tagNumber, BerTagClass tagClass, boolean explicit) {
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

        // SIZE kisiti stripConstraint tarafindan silinmeden once okunur; alan
        // ifadesinde varsa alias zincirinden gelenden onceliklidir.
        String rawTypeExpr = matcher.group(5);
        String inlineSize = readSizeConstraintText(rawTypeExpr);
        String inlineCode = readCodeText(rawTypeExpr);

        String typeExpr = stripConstraint(rawTypeExpr).replace("OPTIONAL", "").trim();

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
                .build();
    }

    private boolean resolveExplicit(String taggingKeyword, AsnTaggingMode taggingMode) {
        if (EXPLICIT_KEYWORD.equalsIgnoreCase(taggingKeyword)) {
            return true;
        }
        if (IMPLICIT_KEYWORD.equalsIgnoreCase(taggingKeyword)) {
            return false;
        }
        // No per-field keyword: fall back to the module default (X.680: EXPLICIT).
        return taggingMode == AsnTaggingMode.EXPLICIT;
    }

    private boolean isRepeatedExpression(String typeExpr) {
        return typeExpr.startsWith("SEQUENCE OF") || typeExpr.startsWith("SET OF");
    }

    private String extractRepeatedInnerType(String typeExpr) {
        return typeExpr.replaceFirst("^(SEQUENCE|SET)\\s+OF\\s+", "").trim();
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
        String size = readSizeConstraintText(declaredType);
        String code = readCodeText(declaredType);
        if (size == null) {
            size = findSizeThroughAliases(registry, innerType);
        }
        return appendSizeConstraint(resolveLeafBaseType(registry, innerType), size, code);
    }
}
