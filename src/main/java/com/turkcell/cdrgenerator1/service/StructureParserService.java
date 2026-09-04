package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.config.EmmRecordBindings;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.CdrStructureDto;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTaggingMode;
import com.turkcell.cdrgenerator1.parser.AsnTypeDefinition;
import com.turkcell.cdrgenerator1.parser.AsnTypeKind;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.regex.Pattern;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;

@Service
@RequiredArgsConstructor
@Slf4j
public class StructureParserService {

    /** Field-path separator; an ASN.1 type reference can never contain it. */
    private static final String PATH_SEPARATOR = ".";
    /** Indexed-repeated-field bracket, matching CdrRecordBuilder's own addressing. */
    private static final String INDEX_OPEN = "[";
    private static final String INDEX_CLOSE = "]";

    private static final long SLOW_MODULE_THRESHOLD_MS = 1000L;
    /**
     * Splits a type body into candidate type-reference tokens.
     *
     * <p>{@code $} counts as a NAME character, not a delimiter. An inline
     * {@code fieldName CHOICE { ... }} is lifted into the registry under a
     * synthetic name like {@code ISOCdr$cdr} and the parent's body is rewritten
     * to reference it. While {@code $} was treated as a separator that reference
     * tokenised into {@code ISOCdr} + {@code cdr}, so the synthetic name itself
     * was never seen and the type looked unreferenced - which made it eligible to
     * win root selection over its own parent. The parent {@code ISOCdr ::=
     * SEQUENCE} then never got encoded, and every record lost its outer SEQUENCE
     * wrapper: FCMSTAPIN records started at {@code AA ...} ({@code [10]} = the
     * chosen alternative) instead of {@code 30 .. AA ..}. 58 of the 808 modules
     * picked a synthetic type as their root this way.</p>
     */
    private static final String TYPE_TOKEN_DELIMITER = "[^A-Za-z0-9_$-]+";

    /** "SEQUENCE OF TypeName" biçimindeki alias hedeflerini yakalar. */
    private static final Pattern SEQUENCE_OF_ALIAS = Pattern.compile(
            "^\\s*SEQUENCE\\s+OF\\s+([A-Za-z][\\w-]*)\\s*$");

    /** The module name that opens an ASN.1 module header. */
    private static final Pattern MODULE_HEADER_NAME = Pattern.compile(
            "([A-Za-z][\\w-]*)\\s*(?:\\{[^}]*\\})?\\s*DEFINITIONS\\b");

    private final CdrStructureReaderService cdrStructureReaderService;
    private final AsnTypeRegistryBuilder registryBuilder;
    private final AsnFieldTreeResolver fieldTreeResolver;

    private final Map<String, AsnStructure> parsedStructures = new LinkedHashMap<>();
    // Raw ASN.1 contents kept per structure so a request can re-resolve the
    // structure with a specific CHOICE selection (see getStructureByName(name, selections)).
    private final Map<String, String> rawContentsByName = new LinkedHashMap<>();
    /**
     * Every module's text keyed by module name, filled before the first parse so
     * an IMPORTS clause resolves whatever order the modules arrive in. Separate
     * from {@link #rawContentsByName}, which is keyed by the STRUCTURE name a
     * module produced and only grows as modules parse successfully - an import
     * has to find its source even when that source is a pure type library
     * nobody generates records from.
     */
    private final Map<String, String> moduleContentsByName = new LinkedHashMap<>();
    /**
     * Which type EMM decodes as the record, per module. Shipped data rather than
     * an injected dependency: it is a file that travels with the schemas, every
     * caller needs the same copy, and reading it here is what makes the API, the
     * UI behind it and the validation samples all encode the type EMM asked for
     * without each having to remember it.
     */
    private final EmmRecordBindings recordBindings = EmmRecordBindings.shipped();

    @PostConstruct
    public void init() {
        log.info("Starting to parse ASN.1 structures from JSON...");
        long startedAt = System.currentTimeMillis();
        List<CdrStructureDto> rawStructures = cdrStructureReaderService.readAllStructures();

        for (CdrStructureDto dto : rawStructures) {
            if (Objects.nonNull(dto.getName()) && Objects.nonNull(dto.getContents())) {
                moduleContentsByName.putIfAbsent(dto.getName(), dto.getContents());
            }
        }

        for (CdrStructureDto dto : rawStructures) {
            try {
                parseSingleModule(dto);
            } catch (Exception e) {
                log.error("Failed to parse module '{}': {}", dto.getName(), e.getMessage());
            }
        }

        long elapsedMs = System.currentTimeMillis() - startedAt;
        log.info("Successfully parsed {} structures in {} ms.", parsedStructures.size(), elapsedMs);
    }

    private void parseSingleModule(CdrStructureDto dto) {
        long moduleStartedAt = System.currentTimeMillis();

        AsnStructure structure = buildStructure(dto.getName(), dto.getContents(), Map.of());
        if (Objects.isNull(structure)) {
            // buildStructure returns null only when the module declares no types at
            // all. Three of the 808 modules are empty stubs - ModuleNamedsds,
            // ModuleNameff and GlobalTKDataCDR are just "DEFINITIONS ::= BEGIN END" -
            // and were being dropped in total silence, which is why the startup
            // banner reports 805 structures and nothing explains the missing three.
            log.warn("Module '{}' declares no ASN.1 type and was skipped", dto.getName());
            return;
        }

        if (structure.getFields().isEmpty()) {
            log.warn("Module '{}' produced no resolvable fields", structure.getStructureName());
        }

        parsedStructures.put(structure.getStructureName(), structure);
        rawContentsByName.put(structure.getStructureName(), dto.getContents());

        long moduleElapsedMs = System.currentTimeMillis() - moduleStartedAt;
        if (moduleElapsedMs > SLOW_MODULE_THRESHOLD_MS) {
            log.warn("Module '{}' took {} ms to resolve - consider investigating",
                    structure.getStructureName(), moduleElapsedMs);
        } else {
            log.debug("Module '{}' resolved in {} ms (choiceRoot={})",
                    structure.getStructureName(), moduleElapsedMs, structure.isChoiceRoot());
        }
    }

    /** Parses inline ASN.1 content without any CHOICE selection. */
    public AsnStructure parseFromContents(String structureName, String contents) {
        return parseFromContents(structureName, contents, Map.of());
    }

    /** Parses inline ASN.1 content, honouring the caller's CHOICE selection. */
    public AsnStructure parseFromContents(String structureName, String contents,
                                          Map<String, String> choiceSelections) {
        return buildStructure(structureName, contents, choiceSelections);
    }

    /**
     * Parses inline ASN.1 content, honouring the caller's CHOICE selection and
     * root type (see {@link #resolveRootTypeName}).
     */
    public AsnStructure parseFromContents(String structureName, String contents,
                                          Map<String, String> choiceSelections, String rootType) {
        return buildStructure(structureName, contents, choiceSelections, rootType);
    }

    /**
     * As above, but also able to expand a repeated CHOICE field into every
     * alternative the caller's INDEXED {@code fieldValues} keys name - P2. See
     * {@link #applyIndexedChoiceExpansion} for what this does and why it is
     * safe; {@code referenceMode=false} or empty {@code fieldValues} reproduces
     * the four-argument overload exactly.
     */
    public AsnStructure parseFromContents(String structureName, String contents,
                                          Map<String, String> choiceSelections, String rootType,
                                          Map<String, String> fieldValues, boolean referenceMode) {
        return buildStructure(structureName, contents, choiceSelections, rootType, fieldValues, referenceMode);
    }

    private AsnStructure buildStructure(String suppliedName, String contents,
                                        Map<String, String> choiceSelections) {
        return buildStructure(suppliedName, contents, choiceSelections, null);
    }

    private AsnStructure buildStructure(String suppliedName, String contents,
                                        Map<String, String> choiceSelections, String rootType) {
        return buildStructure(suppliedName, contents, choiceSelections, rootType, null, false);
    }

    private AsnStructure buildStructure(String suppliedName, String contents,
                                        Map<String, String> choiceSelections, String rootType,
                                        Map<String, String> fieldValues, boolean referenceMode) {
        Map<String, AsnTypeDefinition> registry = registryBuilder.buildRegistry(contents);
        if (registry.isEmpty()) {
            return null;
        }
        closeImports(registry, contents);

        AsnTaggingMode taggingMode = registryBuilder.detectTaggingMode(contents);
        String moduleName = moduleNameOf(suppliedName, contents);
        Map<String, String> selections = applyBoundAlternatives(moduleName, choiceSelections);

        String rootTypeName =
                resolveRootTypeName(registry, selections, taggingMode, rootType, moduleName);
        AsnFieldTreeResolver.ResolvedRoot root =
                fieldTreeResolver.resolveRoot(registry, rootTypeName, selections, taggingMode);
        applyPathScopedChoiceSelections(registry, root.fields(), selections, taggingMode);
        if (referenceMode) {
            applyIndexedChoiceExpansion(registry, root.fields(), selections, fieldValues, taggingMode);
        }
        AsnFieldTreeResolver.ChoiceAlternatives choiceInfo =
                fieldTreeResolver.listRootChoiceAlternatives(registry, rootTypeName);

        String name = (suppliedName != null && !suppliedName.isBlank()) ? suppliedName : rootTypeName;
        return AsnStructure.builder()
                .structureName(name)
                .fields(root.fields())
                .choiceRoot(root.kind() == AsnTypeKind.CHOICE)
                .setRoot(root.kind() == AsnTypeKind.SET)
                .choiceTypeName(choiceInfo != null ? choiceInfo.choiceTypeName() : null)
                .choiceAlternatives(choiceInfo != null ? choiceInfo.alternativeNames() : null)
                .rootTagCarrier(root.rootTagCarrier())
                .repeatedRoot(root.repeatedRoot())
                .repeatedRootIsSet(root.repeatedRootIsSet())
                .build();
    }

    /**
     * Re-picks a CHOICE alternative for ONE call site, after the tree is built.
     *
     * <h4>The problem</h4>
     *
     * <p>{@code AsnFieldTreeResolver.resolveChoiceAlternative} reads the wanted
     * alternative as {@code choiceSelections.get(choiceTypeName)} - by TYPE, so
     * every site sharing a type gets the same answer. MMTel's reference record
     * needs {@code called-Party-Address} to be {@code sIP-URI} and
     * {@code requested-Party-Address} to be {@code tEL-URI} in the same record,
     * and both are {@code InvolvedParty}. One global answer cannot satisfy
     * both.</p>
     *
     * <h4>Why the fix lives here and not in the resolver</h4>
     *
     * <p>The resolver has no notion of a field path at all, and its cache is
     * keyed {@code typeName::choiceSelections}. Threading a path through it
     * would touch every one of the 28 places {@code choiceSelections} is passed
     * AND make the cache per-site instead of per-type - for a startup that
     * resolves 802 modules, that is a cost paid by every module to serve the
     * handful that need it, and a wide change to the code whose output EMM has
     * already accepted.</p>
     *
     * <p>So the resolver keeps its global, type-keyed behaviour untouched and
     * this runs afterwards: walk the finished tree, and where a path override
     * names a CHOICE field, resolve that field's own type again with the wanted
     * alternative and swap in the result. The module's own tagging mode is
     * passed through, so the replacement alternative is built exactly as the
     * resolver would have built it had the selection been global.</p>
     *
     * <h4>Which keys are paths</h4>
     *
     * <p>No key is classified up front. A key acts as a path override exactly
     * where it equals the path of a CHOICE field in this tree, and does nothing
     * anywhere else - so a type name, which matches no field path, keeps working
     * as the resolver already handled it, and a caller passing only type names
     * sees no change at all. Classifying by "contains a dot" was tried and is
     * wrong: a CHOICE at the root of a record has a path with no dot in it, and
     * such a site could never be addressed.</p>
     *
     * <p>Where a string is both a type name and a field path, the path reading
     * wins at that one site. That is the more specific of the two intents, and
     * the type-keyed pick still stands everywhere else.</p>
     */
    private void applyPathScopedChoiceSelections(Map<String, AsnTypeDefinition> registry,
                                                 List<AsnField> fields,
                                                 Map<String, String> selections,
                                                 AsnTaggingMode taggingMode) {
        if (Objects.isNull(fields) || fields.isEmpty()
                || Objects.isNull(selections) || selections.isEmpty()) {
            return;
        }
        rewriteChoiceAlternatives(registry, fields, "", selections, taggingMode);
    }

    private void rewriteChoiceAlternatives(Map<String, AsnTypeDefinition> registry,
                                           List<AsnField> fields, String prefix,
                                           Map<String, String> selections,
                                           AsnTaggingMode taggingMode) {
        for (AsnField field : fields) {
            String path = prefix.isEmpty()
                    ? field.getFieldName()
                    : prefix + PATH_SEPARATOR + field.getFieldName();
            String wanted = selections.get(path);
            if (Objects.nonNull(wanted) && field.isChoice() && Objects.nonNull(field.getFieldType())
                    && !wanted.equals(currentAlternativeName(field))) {
                Map<String, String> forThisSite = new LinkedHashMap<>(selections);
                forThisSite.put(field.getFieldType(), wanted);
                List<AsnField> alternative = fieldTreeResolver.resolveRootFields(
                        registry, field.getFieldType(), forThisSite, taggingMode);
                // resolveChoiceAlternative falls back to the FIRST alternative when
                // the wanted name is not declared, so an unknown override would
                // silently rewrite the site to something nobody asked for. Only a
                // resolution that actually produced the requested branch is applied.
                if (!alternative.isEmpty() && wanted.equals(alternative.get(0).getFieldName())) {
                    field.setChildren(alternative);
                    log.debug("Path-scoped CHOICE at '{}': {} now uses alternative '{}'",
                            path, field.getFieldType(), wanted);
                } else {
                    log.warn("Path-scoped CHOICE '{}' -> '{}' does not name an alternative of {}; "
                            + "keeping the global pick", path, wanted, field.getFieldType());
                }
            }
            if (Objects.nonNull(field.getChildren()) && !field.getChildren().isEmpty()) {
                rewriteChoiceAlternatives(registry, field.getChildren(), path, selections, taggingMode);
            }
        }
    }

    /** The alternative a resolved CHOICE field currently carries, or null. */
    private String currentAlternativeName(AsnField field) {
        List<AsnField> children = field.getChildren();
        return Objects.isNull(children) || children.isEmpty() ? null : children.get(0).getFieldName();
    }

    /**
     * P2: expands a REPEATED CHOICE field's single resolved alternative into
     * the UNION of every alternative the caller's INDEXED {@code fieldValues}
     * keys name.
     *
     * <h4>The problem</h4>
     *
     * <p>{@code list-Of-Calling-Party-Address} - {@code InvolvedParty} CHOICE,
     * repeated - resolves to exactly ONE alternative; the resolver always
     * collapses a CHOICE to a single branch, same as {@link
     * #rewriteChoiceAlternatives} above swaps that one branch for another. The
     * EMM-accepted MMTel reference needs TWO instances of this collection, one
     * carrying {@code sIP-URI} and the other {@code tEL-URI}. Reproducing that
     * means {@code field.getChildren()} has to hold BOTH, because {@code
     * BerEncoderService.encodeRepeated}'s {@code elementIsChoice} branch reads
     * the SAME {@code field.getChildren()} for every element it writes - there
     * is no per-element children list to vary, and {@code BerEncoderService}
     * is untouched. {@code CdrRecordBuilder} then derives the element count
     * from the caller's indices and, per element, keeps only the ONE
     * alternative that element actually names - so with the union present,
     * each instance's encoded content is exactly the one alternative it was
     * given.</p>
     *
     * <h4>Which keys trigger this</h4>
     *
     * <p>A key shaped {@code <fieldPath>[<index>].<name>} names one instance's
     * alternative. Expansion happens only when TWO OR MORE DISTINCT names are
     * found for the SAME field's path - a single name (today's normal shape,
     * or a caller who only ever describes one alternative, or the same
     * alternative repeated at several indices) leaves the field exactly as the
     * resolver already produced it. That last case is deliberate: it refuses
     * to reproduce {@link CdrRecordBuilder#CHOICE_ELEMENT_COUNT}'s documented
     * defect (two elements, the SAME alternative, is the literal duplicate tag
     * EMM rejected) rather than trying to guess the caller meant something
     * else.</p>
     *
     * <h4>Why this can only run on a freshly-resolved tree</h4>
     *
     * <p>{@code field.setChildren(...)} mutates the {@link AsnField} in place.
     * This method is called from {@link #buildStructure} only when {@code
     * referenceMode} is true, and {@link #getStructureByName(String, Map,
     * String, Map, boolean)} widens its "narrowed" check so a reference-mode
     * call carrying non-empty {@code fieldValues} never returns the {@code
     * parsedStructures} entry built once at startup and shared by every other
     * caller - every {@link AsnField} reached here belongs to this one call's
     * own, newly-resolved tree.</p>
     */
    /**
     * The CHOICE type name {@code resolveChoiceAlternative} actually keys an
     * alternative selection on - which is NOT always {@code field.getFieldType()}.
     *
     * <p>{@code list-Of-Calling-Party-Address}'s own declared type is {@code
     * ListOfInvolvedParties}, an alias for {@code SEQUENCE OF InvolvedParty} -
     * {@code resolveRootFields(registry, "ListOfInvolvedParties", ...)}
     * correctly unwraps that alias internally and ends up resolving {@code
     * InvolvedParty}, but the SELECTION MAP it consults at that point is keyed
     * "InvolvedParty", not "ListOfInvolvedParties". Passing {@code
     * Map.of(field.getFieldType(), altName)} as {@link
     * #expandIndexedChoiceCollections} first did therefore built a selection
     * nobody ever looked up, and every alternative silently fell back to the
     * first one - caught by generating against the real MMTel schema and
     * finding {@code tEL-URI} missing where {@code sIP-URI} was expected
     * twice.</p>
     *
     * <p>Reuses {@link #extractSequenceOfInnerType}, the SAME "SEQUENCE OF X"
     * pattern {@link #resolveRootTypeName} already uses for an unrelated
     * purpose - not new parsing. Falls back to {@code field.getFieldType()}
     * unchanged when it is not behind such an alias, which is the correct
     * answer for a CHOICE declared directly (matching {@code
     * rewriteChoiceAlternatives}'s scalar-CHOICE case above).</p>
     */
    private String choiceTypeNameFor(Map<String, AsnTypeDefinition> registry, AsnField field) {
        AsnTypeDefinition definition = registry.get(field.getFieldType());
        if (Objects.nonNull(definition) && definition.getKind() == AsnTypeKind.ALIAS) {
            String innerType = extractSequenceOfInnerType(definition.getAliasTarget());
            if (Objects.nonNull(innerType)) {
                return innerType;
            }
        }
        return field.getFieldType();
    }

    private void applyIndexedChoiceExpansion(Map<String, AsnTypeDefinition> registry,
                                             List<AsnField> fields,
                                             Map<String, String> selections,
                                             Map<String, String> fieldValues,
                                             AsnTaggingMode taggingMode) {
        if (Objects.isNull(fields) || fields.isEmpty()
                || Objects.isNull(fieldValues) || fieldValues.isEmpty()) {
            return;
        }
        expandIndexedChoiceCollections(registry, fields, "", selections, fieldValues, taggingMode);
    }

    private void expandIndexedChoiceCollections(Map<String, AsnTypeDefinition> registry,
                                                List<AsnField> fields, String prefix,
                                                Map<String, String> selections,
                                                Map<String, String> fieldValues,
                                                AsnTaggingMode taggingMode) {
        for (AsnField field : fields) {
            String path = prefix.isEmpty()
                    ? field.getFieldName()
                    : prefix + PATH_SEPARATOR + field.getFieldName();
            if (field.isRepeated() && field.isChoice() && Objects.nonNull(field.getFieldType())) {
                List<String> wantedAlternatives = indexedAlternativeNames(fieldValues, path);
                if (wantedAlternatives.size() > 1) {
                    String choiceTypeName = choiceTypeNameFor(registry, field);
                    List<AsnField> union = new ArrayList<>();
                    for (String altName : wantedAlternatives) {
                        // Preserves the request's broader choiceSelections (KN-3's
                        // own discipline in rewriteChoiceAlternatives above) so an
                        // alternative that itself contains a nested CHOICE still
                        // resolves using the caller's other picks, not just this
                        // one override.
                        Map<String, String> forThisAlternative =
                                Objects.isNull(selections) ? new LinkedHashMap<>() : new LinkedHashMap<>(selections);
                        forThisAlternative.put(choiceTypeName, altName);
                        List<AsnField> resolved = fieldTreeResolver.resolveRootFields(
                                registry, field.getFieldType(), forThisAlternative, taggingMode);
                        // Same discipline as rewriteChoiceAlternatives: an
                        // unresolvable name is dropped rather than silently
                        // substituting the resolver's fallback-to-first-alternative.
                        if (!resolved.isEmpty() && altName.equals(resolved.get(0).getFieldName())) {
                            union.add(resolved.get(0));
                        } else {
                            log.warn("Indexed CHOICE expansion at '{}': '{}' does not name an "
                                    + "alternative of {}; instances naming it will be skipped at "
                                    + "generation time", path, altName, field.getFieldType());
                        }
                    }
                    if (union.size() > 1) {
                        field.setChildren(union);
                        log.debug("Indexed CHOICE expansion at '{}': {} now carries {} alternatives {}",
                                path, field.getFieldType(), union.size(), wantedAlternatives);
                    }
                }
            }
            if (Objects.nonNull(field.getChildren()) && !field.getChildren().isEmpty()) {
                expandIndexedChoiceCollections(registry, field.getChildren(), path, selections, fieldValues, taggingMode);
            }
        }
    }

    /**
     * Distinct alternative names found at {@code <fieldPath>[i].<name>} for
     * CONTIGUOUS indices starting at zero - the same "a gap ends the series"
     * rule {@code CdrRecordBuilder}'s indexed-repeated helpers already use, so
     * a caller's numbering and this expansion never disagree about which
     * instances exist. Order is first-seen by increasing index.
     */
    private List<String> indexedAlternativeNames(Map<String, String> fieldValues, String fieldPath) {
        List<String> names = new ArrayList<>();
        for (int index = 0; ; index++) {
            String indexPrefix = fieldPath + INDEX_OPEN + index + INDEX_CLOSE + PATH_SEPARATOR;
            String altName = fieldValues.keySet().stream()
                    .filter(key -> key.startsWith(indexPrefix))
                    .map(key -> key.substring(indexPrefix.length()))
                    .map(rest -> {
                        int dot = rest.indexOf(PATH_SEPARATOR.charAt(0));
                        return dot < 0 ? rest : rest.substring(0, dot);
                    })
                    .findFirst()
                    .orElse(null);
            if (Objects.isNull(altName)) {
                return names;
            }
            if (!names.contains(altName)) {
                names.add(altName);
            }
        }
    }

    /**
     * The root type to resolve: the caller's when they named one, otherwise
     * {@link #selectRootTypeName}'s pick.
     *
     * <p>The heuristic answers "which type is this module's record?", which is
     * the right question when a module describes one record. It is the wrong
     * question when the module describes several and the consuming system is
     * bound to a particular one: {@code IMSCDRS} defines
     * {@code Cdrs ::= CHOICE {tokenMTAS, tokenCSCF, mergedIMS}}, the heuristic
     * correctly picks {@code Cdrs} and generates its first alternative, and EMM
     * rejected the file because its CSCFColl flow decodes {@code TokensCSCF}
     * directly - "the type IMSCDRS.TokensCSCF was probably not set and is not
     * optional", reported against the top level with no field path under it,
     * because our {@code A0} wrapper is not the bare SEQUENCE it was waiting
     * for. Which type a flow is bound to is EMM configuration; nothing in the
     * schema text records it, so it has to be something the caller can say.</p>
     *
     * <p>An unknown name is ignored rather than rejected, so a stale or
     * misspelled override degrades to the previous behaviour instead of
     * failing the request.</p>
     */
    private String resolveRootTypeName(Map<String, AsnTypeDefinition> registry,
                                       Map<String, String> choiceSelections,
                                       AsnTaggingMode taggingMode,
                                       String requestedRootType,
                                       String moduleName) {
        if (Objects.nonNull(requestedRootType) && !requestedRootType.isBlank()) {
            if (registry.containsKey(requestedRootType)) {
                log.debug("Using caller-supplied root type '{}'", requestedRootType);
                return requestedRootType;
            }
            log.warn("Requested root type '{}' is not defined in this module; falling back to the "
                    + "auto-selected root. Known types: {}", requestedRootType, registry.keySet());
        }
        String boundRecordType = recordBindings.recordTypeFor(moduleName);
        if (Objects.nonNull(boundRecordType)) {
            if (registry.containsKey(boundRecordType)) {
                log.debug("Module '{}' is bound to record type '{}' by EMM's answer", moduleName, boundRecordType);
                return boundRecordType;
            }
            log.warn("Module '{}' is bound to record type '{}', which it does not define; "
                    + "falling back to the auto-selected root", moduleName, boundRecordType);
        }
        return selectRootTypeName(registry, choiceSelections, taggingMode);
    }

    /**
     * The caller's CHOICE selections over the ones EMM's answer bound to this
     * module. The caller wins: a binding records what a flow was measured to
     * expect, and someone deliberately asking for another alternative - to send
     * EMM the comparison that settles a question - must still get it.
     */
    private Map<String, String> applyBoundAlternatives(String moduleName,
                                                       Map<String, String> choiceSelections) {
        Map<String, String> requested = Objects.isNull(choiceSelections) ? Map.of() : choiceSelections;
        Map<String, String> bound = recordBindings.choiceAlternativesFor(moduleName);
        if (bound.isEmpty()) {
            return requested;
        }
        Map<String, String> merged = new LinkedHashMap<>(bound);
        merged.putAll(requested);
        return merged;
    }

    /**
     * The module name to look a binding up under: what the caller registered the
     * structure as, falling back to the name in the ASN.1 header. Both are the
     * module name for anything read from the shipped data set; they diverge only
     * for content posted inline under a name of the caller's choosing, where the
     * header is the more trustworthy of the two.
     */
    private String moduleNameOf(String suppliedName, String contents) {
        if (Objects.nonNull(suppliedName) && !suppliedName.isBlank()
                && Objects.nonNull(recordBindings.recordTypeFor(suppliedName))) {
            return suppliedName;
        }
        String headerName = suppliedModuleName(contents);
        if (Objects.nonNull(recordBindings.recordTypeFor(headerName))
                || !recordBindings.choiceAlternativesFor(headerName).isEmpty()) {
            return headerName;
        }
        return Objects.nonNull(suppliedName) && !suppliedName.isBlank() ? suppliedName : headerName;
    }

    /**
     * Chooses the module's root type. A CDR module's real record is the top
     * structured type that no other type references, so we prefer the first
     * structured (SEQUENCE/SET/CHOICE) type that is not referenced elsewhere and
     * resolves to at least one field. This is far more robust than blindly taking
     * the first non-empty definition (helper sub-records defined before the root
     * would otherwise win). Falls back to the first resolvable type, then the
     * first defined type.
     */
    private String selectRootTypeName(Map<String, AsnTypeDefinition> registry,
                                      Map<String, String> choiceSelections,
                                      AsnTaggingMode taggingMode) {
        Set<String> referenced = collectReferencedTypeNames(registry);
        Map<String, Set<String>> references = buildReferenceEdges(registry);

        // Kök adayı birden fazla olabilir (örnek: bir DB lookup şemasında hem
        // "Key" hem "Record" tipi tanımlı). Adayları ULAŞILABİLİRLİĞE göre
        // sıralarız: bir tipten yola çıkıp geçişli olarak kaç tipe erişildiği.
        // Modülün gerçek kaydı, şemadaki yardımcı tiplerin neredeyse tamamını
        // kendi ağacında toplar; "Key" gibi bir arama anahtarı ya da
        // ChangeOfServiceCondition gibi bir konteyner ise küçük bir ada kalır.
        //
        // Önceki ölçüt (en çok ALANA sahip aday) kayıt bir CHOICE olduğunda
        // yanlış sonuç veriyordu: resolveRoot bir CHOICE kökünü tek alan olarak
        // döndürdüğü için LTE-R10'da CallEventRecord (1 alan) referanssız bir
        // yardımcı tipe - ChangeOfServiceCondition (22 alan) - yeniliyordu ve
        // üretilen dosya sGWRecord [78] sarmalayıcısı olmadan, CDR yerine bir
        // servis-veri konteyneri olarak çıkıyordu. Alan sayısı artık sadece
        // eşitlik bozucu: eski davranış beraberliklerde aynen korunur.
        // "SEQUENCE OF X" şeklindeki referanssız bir alias (örnek:
        // DBDataRecord), X'in kendisini de geçerli bir kök adayı yapar -
        // X normalde "referanslı" sayılsa bile, bu sarmalayıcı dışında başka
        // kullanıcısı yoksa asıl veri kaydı X'tir.
        String bestCandidate = null;
        int bestReach = -1;
        int bestFieldCount = -1;

        for (String candidate : registry.keySet()) {
            AsnTypeDefinition definition = registry.get(candidate);

            if (isSyntheticMemberType(candidate)) {
                // Lifted out of an enclosing type's body - a member, never a root.
                continue;
            }

            String scored = null;
            if (isStructured(definition.getKind()) && !referenced.contains(candidate)) {
                scored = candidate;
            } else if (definition.getKind() == AsnTypeKind.ALIAS && !referenced.contains(candidate)) {
                String innerTypeName = extractSequenceOfInnerType(definition.getAliasTarget());
                AsnTypeDefinition innerDefinition =
                        innerTypeName != null ? registry.get(innerTypeName) : null;
                if (innerDefinition != null && isStructured(innerDefinition.getKind())) {
                    scored = innerTypeName;
                }
            }
            if (scored == null) {
                continue;
            }

            int fieldCount = fieldTreeResolver
                    .resolveRoot(registry, scored, choiceSelections, taggingMode)
                    .fields().size();
            int reach = countReachableTypes(references, scored);

            if (reach > bestReach || (reach == bestReach && fieldCount > bestFieldCount)) {
                bestCandidate = scored;
                bestReach = reach;
                bestFieldCount = fieldCount;
            }
        }

        if (bestCandidate != null && bestFieldCount > 0) {
            log.debug("Selected root type '{}' ({} fields, reaches {} types, unreferenced/wrapper target)",
                    bestCandidate, bestFieldCount, bestReach);
            return bestCandidate;
        }

        for (String candidate : registry.keySet()) {
            if (isSyntheticMemberType(candidate)) {
                continue;
            }
            if (!fieldTreeResolver.resolveRoot(registry, candidate, choiceSelections, taggingMode)
                    .fields().isEmpty()) {
                log.debug("Selected root type '{}' (first resolvable fallback)", candidate);
                return candidate;
            }
        }

        String firstType = registry.keySet().iterator().next();
        log.debug("Selected root type '{}' (first defined, none resolvable)", firstType);
        return firstType;
    }

    private String extractSequenceOfInnerType(String aliasTarget) {
        if (aliasTarget == null) {
            return null;
        }

        Matcher matcher = SEQUENCE_OF_ALIAS.matcher(aliasTarget);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * Pulls in every type this module IMPORTS, so a reference across a module
     * boundary resolves like any other.
     *
     * <p>Until this ran, a registry held one module's text and nothing else. An
     * imported name matched no definition, the field got no children, and the
     * encoder wrote a leaf where a structured type belongs. EMM answered on
     * exactly that: {@code GSN50}'s
     * {@code recordExtensions.[0].information [2] GprsCdrExtensions} went out as
     * {@code 82 08 4F 58 4E 51 ..} and came back {@code Invalid length 8}. The
     * type is real and so is the module that exports it -
     * {@code GPRS-Charging-Extensions} declares
     * {@code GprsCdrExtensions ::= SET { .. }} and names it in an EXPORTS
     * clause.</p>
     *
     * <p>The lookup is by exact module name, never by resemblance. Measured over
     * the whole corpus, that is enough: 17 modules declare an IMPORTS clause,
     * they name 7 distinct source modules, and every one of those exists under
     * exactly that name with the imported symbol defined in it. Three of the
     * seven differ only by a suffix - {@code GPRS-Charging-Extensions},
     * {@code -Tr} and {@code -KKTC} - and three different importers name three
     * different ones, so matching on similarity would silently pick the wrong
     * variant where an exact match picks the right one.</p>
     *
     * <p>A symbol the module also declares itself is left alone, and a source
     * module that is missing or does not define the symbol is logged and
     * skipped: an unresolvable import degrades to the previous behaviour rather
     * than failing the parse.</p>
     */
    private void closeImports(Map<String, AsnTypeDefinition> registry, String contents) {
        Map<String, String> imports = registryBuilder.readImports(contents);
        if (imports.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> imported : imports.entrySet()) {
            String symbol = imported.getKey();
            String sourceModule = imported.getValue();
            if (registry.containsKey(symbol)) {
                continue;
            }
            String sourceContents = moduleContentsByName.get(sourceModule);
            if (Objects.isNull(sourceContents)) {
                log.warn("Module '{}' imports '{}' from '{}', which this data set does not contain; "
                        + "the reference stays unresolved", suppliedModuleName(contents), symbol, sourceModule);
                continue;
            }
            mergeImportedType(registry, symbol, sourceModule, sourceContents);
        }
    }

    /**
     * Copies one imported type and everything it reaches into the importing
     * registry, tagging each copy with the source module's tagging mode.
     *
     * <p>The closure is taken over the source module's own registry, so a type
     * the export depends on comes along even when it is not itself exported -
     * {@code GprsCdrExtensions} is the only name in
     * {@code GPRS-Charging-Extensions}'s EXPORTS clause, and it reaches 40 of
     * the module's 43 types. A name already present in the importing module is
     * never overwritten: a local declaration is what that module means by it.</p>
     */
    private void mergeImportedType(Map<String, AsnTypeDefinition> registry, String symbol,
                                   String sourceModule, String sourceContents) {
        Map<String, AsnTypeDefinition> sourceRegistry = registryBuilder.buildRegistry(sourceContents);
        if (!sourceRegistry.containsKey(symbol)) {
            log.warn("Module '{}' does not define the imported symbol '{}'; the reference stays unresolved",
                    sourceModule, symbol);
            return;
        }
        // The source module may itself import; closing it first means a chain
        // resolves in one pass rather than stopping at the first boundary.
        closeImports(sourceRegistry, sourceContents);

        AsnTaggingMode sourceMode = registryBuilder.detectTaggingMode(sourceContents);
        Map<String, Set<String>> edges = buildReferenceEdges(sourceRegistry);

        Set<String> toCopy = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(symbol);
        toCopy.add(symbol);
        while (!queue.isEmpty()) {
            for (String target : edges.getOrDefault(queue.poll(), Set.of())) {
                if (toCopy.add(target)) {
                    queue.add(target);
                }
            }
        }

        int copied = 0;
        for (String name : toCopy) {
            AsnTypeDefinition definition = sourceRegistry.get(name);
            if (Objects.isNull(definition) || registry.containsKey(name)) {
                continue;
            }
            registry.put(name, AsnTypeDefinition.builder()
                    .typeName(definition.getTypeName())
                    .kind(definition.getKind())
                    .rawBody(definition.getRawBody())
                    .aliasTarget(definition.getAliasTarget())
                    .tagPrefix(definition.getTagPrefix())
                    // Null on the source definition means "the module it was
                    // declared in", which from here is the source module.
                    .taggingMode(Objects.requireNonNullElse(definition.getTaggingMode(), sourceMode))
                    .build());
            copied++;
        }
        log.debug("Imported '{}' from '{}' ({} tagging), pulling in {} type(s)",
                symbol, sourceModule, sourceMode, copied);
    }

    /** The module name from the header, for a log line that names the right module. */
    private String suppliedModuleName(String contents) {
        Matcher header = MODULE_HEADER_NAME.matcher(contents);
        return header.find() ? header.group(1) : "<inline>";
    }

    /**
     * Tip -> gövdesinde adı geçen diğer tipler. {@link #collectReferencedTypeNames}
     * ile aynı sözcük ayrıştırmasını kullanır, tek farkı kenarları kaynağına göre
     * ayrı tutmasıdır; böylece bir adayın geçişli olarak kaç tipe eriştiği
     * hesaplanabilir. Kendine referans atlanır.
     */
    private Map<String, Set<String>> buildReferenceEdges(Map<String, AsnTypeDefinition> registry) {
        Set<String> typeNames = registry.keySet();
        Map<String, Set<String>> edges = new LinkedHashMap<>();

        for (Map.Entry<String, AsnTypeDefinition> entry : registry.entrySet()) {
            AsnTypeDefinition definition = entry.getValue();
            String text = Objects.nonNull(definition.getRawBody())
                    ? definition.getRawBody()
                    : definition.getAliasTarget();
            Set<String> targets = new HashSet<>();
            if (Objects.nonNull(text)) {
                for (String token : text.split(TYPE_TOKEN_DELIMITER)) {
                    if (typeNames.contains(token) && !token.equals(entry.getKey())) {
                        targets.add(token);
                    }
                }
            }
            edges.put(entry.getKey(), targets);
        }
        return edges;
    }

    /** {@code start} tipinden geçişli olarak erişilen tip sayısı (start hariç). */
    private int countReachableTypes(Map<String, Set<String>> references, String start) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String target : references.getOrDefault(current, Set.of())) {
                if (!target.equals(start) && seen.add(target)) {
                    queue.add(target);
                }
            }
        }
        return seen.size();
    }

    private Set<String> collectReferencedTypeNames(Map<String, AsnTypeDefinition> registry) {
        Set<String> typeNames = registry.keySet();
        Set<String> referenced = new HashSet<>();
        for (Map.Entry<String, AsnTypeDefinition> entry : registry.entrySet()) {
            AsnTypeDefinition definition = entry.getValue();
            String text = Objects.nonNull(definition.getRawBody())
                    ? definition.getRawBody()
                    : definition.getAliasTarget();
            if (Objects.isNull(text)) {
                continue;
            }
            for (String token : text.split(TYPE_TOKEN_DELIMITER)) {
                // A self-reference does not make a type a non-root candidate.
                if (typeNames.contains(token) && !token.equals(entry.getKey())) {
                    referenced.add(token);
                }
            }
        }
        return referenced;
    }

    /**
     * True for a type the registry minted for an inline {@code fieldName CHOICE
     * { ... }} (name shaped {@code Parent$field}).
     *
     * <p>Such a type exists only as a member of the type it was lifted out of, so
     * it can never be the module's root record. Excluding it is a guard that
     * holds even if the reference scan misses it for some other reason.</p>
     */
    private boolean isSyntheticMemberType(String typeName) {
        return Objects.nonNull(typeName)
                && typeName.contains(AsnTypeRegistryBuilder.SYNTHETIC_NAME_SEPARATOR);
    }

    private boolean isStructured(AsnTypeKind kind) {
        return kind == AsnTypeKind.SEQUENCE || kind == AsnTypeKind.SET || kind == AsnTypeKind.CHOICE;
    }

    public Map<String, AsnStructure> getAllParsedStructures() {
        return Collections.unmodifiableMap(parsedStructures);
    }

    public AsnStructure getStructureByName(String name) {
        return parsedStructures.get(name);
    }

    /** The module's raw ASN.1 text, as read from the data file. Null when unknown. */
    public String getRawContents(String name) {
        return rawContentsByName.get(name);
    }

    /**
     * Returns the structure for {@code name}. When a non-empty CHOICE selection
     * is supplied the structure is re-resolved from its stored raw contents so
     * the requested alternative is chosen; otherwise the pre-parsed structure is
     * returned unchanged.
     */
    public AsnStructure getStructureByName(String name, Map<String, String> choiceSelections) {
        return getStructureByName(name, choiceSelections, null);
    }

    /**
     * Returns the structure for {@code name}, re-resolved from its stored raw
     * contents when the caller narrows it with a CHOICE selection or a root
     * type (see {@link #resolveRootTypeName}). With neither, the pre-parsed
     * structure is returned unchanged.
     */
    public AsnStructure getStructureByName(String name, Map<String, String> choiceSelections,
                                           String rootType) {
        return getStructureByName(name, choiceSelections, rootType, null, false);
    }

    /**
     * As above, but also able to expand a repeated CHOICE field for reference
     * mode - P2, see {@link #applyIndexedChoiceExpansion}.
     *
     * <p>{@code narrowed} is widened to force the fresh-resolve path whenever
     * {@code referenceMode} is on with non-empty {@code fieldValues}, even if
     * {@code choiceSelections} and {@code rootType} are both empty. Without
     * this, a reference-mode caller who only sets {@code fieldValues} (no
     * other narrowing) would receive {@code parsedStructures.get(name)} - the
     * SAME {@link AsnStructure} object every other caller of the plain,
     * no-selection lookup shares - and {@link #applyIndexedChoiceExpansion}'s
     * {@code field.setChildren(...)} would mutate that shared object,
     * corrupting it for every concurrent and future request. Forcing the
     * fresh path guarantees the tree reached here is always this one call's
     * own.</p>
     */
    public AsnStructure getStructureByName(String name, Map<String, String> choiceSelections,
                                           String rootType, Map<String, String> fieldValues,
                                           boolean referenceMode) {
        boolean narrowed = (Objects.nonNull(choiceSelections) && !choiceSelections.isEmpty())
                || (Objects.nonNull(rootType) && !rootType.isBlank())
                || (referenceMode && Objects.nonNull(fieldValues) && !fieldValues.isEmpty());
        if (!narrowed) {
            return parsedStructures.get(name);
        }
        String contents = rawContentsByName.get(name);
        if (Objects.isNull(contents)) {
            return parsedStructures.get(name);
        }
        return buildStructure(name, contents, choiceSelections, rootType, fieldValues, referenceMode);
    }

    public List<String> getAllStructureNames() {
        return new ArrayList<>(parsedStructures.keySet());
    }
}
