package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.CdrStructureDto;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeDefinition;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class StructureParserService {

    private static final long SLOW_MODULE_THRESHOLD_MS = 1000L;

    private final CdrStructureReaderService cdrStructureReaderService;
    private final AsnTypeRegistryBuilder registryBuilder;
    private final AsnFieldTreeResolver fieldTreeResolver;

    private final Map<String, AsnStructure> parsedStructures = new LinkedHashMap<>();

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

        Map<String, AsnTypeDefinition> registry = registryBuilder.buildRegistry(dto.getContents());
        if (registry.isEmpty()) {
            return;
        }

        ResolvedRoot root = resolveRootStructure(registry);

        String structureName = (dto.getName() != null && !dto.getName().isBlank()) ? dto.getName() : root.typeName();
        if (root.fields().isEmpty()) {
            log.warn("Module '{}' produced no resolvable fields for any of its {} type definitions",
                    structureName, registry.size());
        }

        AsnStructure structure = AsnStructure.builder()
                .structureName(structureName)
                .fields(root.fields())
                .build();

        parsedStructures.put(structureName, structure);

        long moduleElapsedMs = System.currentTimeMillis() - moduleStartedAt;
        if (moduleElapsedMs > SLOW_MODULE_THRESHOLD_MS) {
            log.warn("Module '{}' took {} ms to resolve ({} type definitions) - consider investigating",
                    structureName, moduleElapsedMs, registry.size());
        } else {
            log.debug("Module '{}' resolved in {} ms", structureName, moduleElapsedMs);
        }
    }


    public AsnStructure parseFromContents(String structureName, String contents) {
        Map<String, AsnTypeDefinition> registry = registryBuilder.buildRegistry(contents);
        if (registry.isEmpty()) {
            return null;
        }

        ResolvedRoot root = resolveRootStructure(registry);

        String name = (structureName != null && !structureName.isBlank()) ? structureName : root.typeName();
        return AsnStructure.builder()
                .structureName(name)
                .fields(root.fields())
                .build();
    }

    /**
     * Picks the module's root structure: the first defined type whose field
     * tree resolves to at least one field. Modules often open with primitive
     * aliases (e.g. {@code Number ::= OCTET STRING}) before the actual record
     * type, so blindly taking the first definition would yield an empty root.
     */
    private ResolvedRoot resolveRootStructure(Map<String, AsnTypeDefinition> registry) {
        String firstTypeName = registry.keySet().iterator().next();
        for (String candidate : registry.keySet()) {
            List<AsnField> fields = fieldTreeResolver.resolveRootFields(registry, candidate);
            if (!fields.isEmpty()) {
                return new ResolvedRoot(candidate, fields);
            }
        }
        return new ResolvedRoot(firstTypeName, List.of());
    }

    private record ResolvedRoot(String typeName, List<AsnField> fields) {
    }

    public Map<String, AsnStructure> getAllParsedStructures() {
        return Collections.unmodifiableMap(parsedStructures);
    }

    public AsnStructure getStructureByName(String name) {
        return parsedStructures.get(name);
    }

    public List<String> getAllStructureNames() {
        return new ArrayList<>(parsedStructures.keySet());
    }
}