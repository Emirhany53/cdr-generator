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
import java.util.HashMap;
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

    private final Map<String, AsnStructure> parsedStructures = new HashMap<>();

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

        String rootTypeName = registry.keySet().iterator().next(); // modüldeki ilk tanımlı tip = kök yapı
        List<AsnField> fields = fieldTreeResolver.resolveRootFields(registry, rootTypeName);

        String structureName = (dto.getName() != null && !dto.getName().isBlank()) ? dto.getName() : rootTypeName;

        AsnStructure structure = AsnStructure.builder()
                .structureName(structureName)
                .fields(fields)
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

    public Map<String, AsnStructure> getAllParsedStructures() {
        return parsedStructures;
    }

    public AsnStructure getStructureByName(String name) {
        return parsedStructures.get(name);
    }

    public List<String> getAllStructureNames() {
        return new ArrayList<>(parsedStructures.keySet());
    }
}