package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.CdrStructureDto;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class StructureParserService {

    private final CdrStructureReaderService cdrStructureReaderService;
    private final Map<String, AsnStructure> parsedStructures = new HashMap<>();


    @PostConstruct
    public void init() {
        log.info("Starting to parse ASN.1 structures from JSON...");
        List<CdrStructureDto> rawStructures = cdrStructureReaderService.readAllStructures();

        for (CdrStructureDto dto : rawStructures) {
            String structureName = extractMainStructureName(dto.getContents());

            if (structureName == null || structureName.isEmpty()) {
                structureName = dto.getName();
            }

            List<AsnField> fields = parseAsnFields(dto.getContents(), structureName);

            AsnStructure structure = AsnStructure.builder()
                    .structureName(structureName)
                    .fields(fields)
                    .build();

            parsedStructures.put(structureName, structure);
        }

        log.info("Successfully parsed {} structures.", parsedStructures.size());
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

    private String extractMainStructureName(String contents) {
        if (contents == null || contents.isBlank()) {
            return "";
        }


        Pattern pattern = Pattern.compile("(\\w+)\\s*::=\\s*SEQUENCE\\s*\\{");
        Matcher matcher = pattern.matcher(contents);

        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private List<AsnField> parseAsnFields(String contents, String structureName) {
        List<AsnField> fields = new ArrayList<>();

        if (contents == null || contents.isBlank()) return fields;

        String[] lines = contents.split("\\r?\\n");

        boolean isInsideTargetSequence = false;

        Pattern fieldPattern = Pattern.compile("^\\s*([a-zA-Z0-9_-]+)\\s*(?:\\[\\d+\\])?\\s*(?:IMPLICIT|EXPLICIT)?\\s*([a-zA-Z0-9_-]+)(?:.*?)(OPTIONAL)?\\s*,?\\s*$");

        for (String line : lines) {
            String cleanLine = line.trim();


            if (cleanLine.startsWith("--") || cleanLine.isEmpty()) {
                continue;
            }

            if (cleanLine.contains(structureName) && cleanLine.contains("SEQUENCE")) {
                isInsideTargetSequence = true;
                continue;
            }

            if (isInsideTargetSequence && cleanLine.equals("}")) {
                break;
            }

            if (isInsideTargetSequence) {
                Matcher matcher = fieldPattern.matcher(cleanLine);
                if (matcher.find()) {
                    String fieldName = matcher.group(1);
                    String fieldType = matcher.group(2);
                    boolean isOptional = matcher.group(3) != null;

                    fields.add(AsnField.builder()
                            .fieldName(fieldName)
                            .fieldType(fieldType)
                            .isOptional(isOptional)
                            .build());
                }
            }
        }
        return fields;
    }
}