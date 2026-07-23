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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class StructureParserService {

    private static final long SLOW_MODULE_THRESHOLD_MS = 1000L;
    private static final String TYPE_TOKEN_DELIMITER = "[^A-Za-z0-9_-]+";

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
                                      Map<String, String> choiceSelections, AsnTaggingMode taggingMode) {
        Set<String> referenced = collectReferencedTypeNames(registry);

        for (String candidate : registry.keySet()) {
            AsnTypeDefinition definition = registry.get(candidate);
            if (isStructured(definition.getKind()) && !referenced.contains(candidate)
                    && !fieldTreeResolver.resolveRoot(registry, candidate, choiceSelections, taggingMode)
                            .fields().isEmpty()) {
                log.debug("Selected root type '{}' (unreferenced structured type)", candidate);
                return candidate;
            }
        }

        for (String candidate : registry.keySet()) {
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
