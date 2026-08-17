package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.config.EmmRecordBindings;
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

    private AsnStructure buildStructure(String suppliedName, String contents,
                                        Map<String, String> choiceSelections) {
        return buildStructure(suppliedName, contents, choiceSelections, null);
    }

    private AsnStructure buildStructure(String suppliedName, String contents,
                                        Map<String, String> choiceSelections, String rootType) {
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
                .build();
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
        boolean narrowed = (Objects.nonNull(choiceSelections) && !choiceSelections.isEmpty())
                || (Objects.nonNull(rootType) && !rootType.isBlank());
        if (!narrowed) {
            return parsedStructures.get(name);
        }
        String contents = rawContentsByName.get(name);
        if (Objects.isNull(contents)) {
            return parsedStructures.get(name);
        }
        return buildStructure(name, contents, choiceSelections, rootType);
    }

    public List<String> getAllStructureNames() {
        return new ArrayList<>(parsedStructures.keySet());
    }
}
