package com.turkcell.cdrgenerator1.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CdrFileWriterService {

    public Path writeCdrFile(String structureName, List<Map<String, String>> records) throws IOException {
        log.info("Generating CDR file for structure: {} with {} records", structureName, records.size());

        List<String> fileLines = records.stream()
                .map(record -> String.join("|", record.values()))
                .collect(Collectors.toList());

        Path tempFile = Files.createTempFile(structureName + "_", ".dat");

        Files.write(tempFile, fileLines);

        log.info("CDR file successfully written to: {}", tempFile.toAbsolutePath());

        return tempFile;
    }
}