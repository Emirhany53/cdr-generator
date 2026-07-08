package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.model.CdrStructureDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class CdrStructureReaderService {

    private final CdrConfigProperties cdrConfigProperties;
    private final ObjectMapper objectMapper;

    public List<CdrStructureDto> readAllStructures() {
        String filePath = cdrConfigProperties.getDataStructurePath();

        if (Objects.isNull(filePath) || filePath.isBlank()) {
            log.error("Data structure path is null or empty in application properties!");
            return Collections.emptyList();
        }

        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            log.error("JSON file not found at path: {}", path.toAbsolutePath());
            return Collections.emptyList();
        }

        try {
            return objectMapper.readValue(path.toFile(), new TypeReference<List<CdrStructureDto>>() {});
        } catch (JacksonException e) {
            log.error("Error occurred while reading or parsing the JSON file", e);
            return Collections.emptyList();
        }
    }
}