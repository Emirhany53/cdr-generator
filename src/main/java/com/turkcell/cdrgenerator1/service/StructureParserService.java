package com.turkcell.cdrgenerator1.service;

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
import java.util.ArrayList;
import java.util.Collections;
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

    private final CdrStructureReaderService cdrStructureReaderService;
    private final AsnTypeRegistryBuilder registryBuilder;
    private final AsnFieldTreeResolver fieldTreeResolver;

    private final Map<String, AsnStructure> parsedStructures = new LinkedHashMap<>();
    // Raw ASN.1 contents kept per structure so a request can re-resolve the
    // structure with a specific CHOICE selection (see getStructureByName(name, selections)).
    private final Map<String, String> rawContentsByName = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Starting to parse ASN.1 structures from JSON...");
        long startedAt = System.currentTimeMillis();
        List<CdrStructureDto> rawStructures = cdrStructureReaderService.readAllStructures();

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

    private AsnStructure buildStructure(String suppliedName, String contents,
                                        Map<String, String> choiceSelections) {
        Map<String, AsnTypeDefinition> registry = registryBuilder.buildRegistry(contents);
        if (registry.isEmpty()) {
            return null;
        }

        AsnTaggingMode taggingMode = registryBuilder.detectTaggingMode(contents);
        Map<String, String> selections = choiceSelections == null ? Map.of() : choiceSelections;

        String rootTypeName = selectRootTypeName(registry, selections, taggingMode);
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
                .build();
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

        // Kök adayı birden fazla olabilir (örnek: bir DB lookup şemasında hem
        // "Key" hem "Record" tipi tanımlı). İlk bulunanı değil, en çok alana
        // sahip olanı seçeriz; böylece "sub_merchant_id" gibi tek alanlı bir
        // arama anahtarı, asıl çok alanlı kayıt tipinin (DBRecord) önüne
        // geçmez. "SEQUENCE OF X" şeklindeki referanssız bir alias (örnek:
        // DBDataRecord), X'in kendisini de geçerli bir kök adayı yapar -
        // X normalde "referanslı" sayılsa bile, bu sarmalayıcı dışında başka
        // kullanıcısı yoksa asıl veri kaydı X'tir.
        String bestCandidate = null;
        int bestFieldCount = -1;

        for (String candidate : registry.keySet()) {
            AsnTypeDefinition definition = registry.get(candidate);

            if (isSyntheticMemberType(candidate)) {
                // Lifted out of an enclosing type's body - a member, never a root.
                continue;
            }

            if (isStructured(definition.getKind()) && !referenced.contains(candidate)) {
                int fieldCount = fieldTreeResolver
                        .resolveRoot(registry, candidate, choiceSelections, taggingMode)
                        .fields().size();

                if (fieldCount > bestFieldCount) {
                    bestCandidate = candidate;
                    bestFieldCount = fieldCount;
                }
            }

            if (definition.getKind() == AsnTypeKind.ALIAS && !referenced.contains(candidate)) {
                String innerTypeName = extractSequenceOfInnerType(definition.getAliasTarget());
                AsnTypeDefinition innerDefinition =
                        innerTypeName != null ? registry.get(innerTypeName) : null;

                if (innerDefinition != null && isStructured(innerDefinition.getKind())) {
                    int fieldCount = fieldTreeResolver
                            .resolveRoot(registry, innerTypeName, choiceSelections, taggingMode)
                            .fields().size();

                    if (fieldCount > bestFieldCount) {
                        bestCandidate = innerTypeName;
                        bestFieldCount = fieldCount;
                    }
                }
            }
        }

        if (bestCandidate != null && bestFieldCount > 0) {
            log.debug("Selected root type '{}' ({} fields, unreferenced/wrapper target)",
                    bestCandidate, bestFieldCount);
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

    /**
     * Returns the structure for {@code name}. When a non-empty CHOICE selection
     * is supplied the structure is re-resolved from its stored raw contents so
     * the requested alternative is chosen; otherwise the pre-parsed structure is
     * returned unchanged.
     */
    public AsnStructure getStructureByName(String name, Map<String, String> choiceSelections) {
        if (Objects.isNull(choiceSelections) || choiceSelections.isEmpty()) {
            return parsedStructures.get(name);
        }
        String contents = rawContentsByName.get(name);
        if (Objects.isNull(contents)) {
            return parsedStructures.get(name);
        }
        return buildStructure(name, contents, choiceSelections);
    }

    public List<String> getAllStructureNames() {
        return new ArrayList<>(parsedStructures.keySet());
    }
}
