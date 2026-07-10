package com.turkcell.cdrgenerator1.controller;

import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.exception.RecordCountExceededException;
import com.turkcell.cdrgenerator1.exception.StructureNotFoundException;
import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.request.GenerateRequest;
import com.turkcell.cdrgenerator1.service.CdrFileWriterService;
import com.turkcell.cdrgenerator1.service.AsnLiteralFormatter;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cdr")
@RequiredArgsConstructor
@Slf4j
public class CdrStructureController {

    private final StructureParserService structureParserService;
    private final CdrRecordBuilder cdrRecordBuilder;
    private final CdrFileWriterService cdrFileWriterService;
    private final CdrConfigProperties cdrConfigProperties;

    @GetMapping("/structures")
    public ResponseEntity<List<String>> getAllStructureNames() {
        List<String> structureNames = structureParserService.getAllStructureNames();
        return ResponseEntity.ok(structureNames);
    }

    @GetMapping("/structures/{structureName}")
    public ResponseEntity<AsnStructure> getStructureDetails(@PathVariable String structureName) {
        AsnStructure structure = structureParserService.getStructureByName(structureName);
        if (structure == null) {
            throw new StructureNotFoundException(structureName);
        }
        return ResponseEntity.ok(structure);
    }

    @GetMapping("/generate-test/{structureName}")
    public ResponseEntity<Map<String, Object>> generateTestRecord(@PathVariable String structureName) {
        log.info("Incoming test request to generate mock data for: {}", structureName);
        Map<String, Object> mockRecord = cdrRecordBuilder.buildRecord(structureName, null);
        return ResponseEntity.ok(AsnLiteralFormatter.stripRecord(mockRecord));
    }

    @PostMapping("/generate")
    public ResponseEntity<Resource> generateAndDownloadCdr(@RequestBody GenerateRequest request) throws IOException {

        if (request.getStructureName() == null || request.getStructureName().isBlank()) {
            throw new IllegalArgumentException("structureName is required");
        }

        Integer recordCount = request.getRecordCount();
        int effectiveRecordCount = (recordCount != null) ? recordCount : cdrConfigProperties.getDefaultRecordCount();

        if (effectiveRecordCount > cdrConfigProperties.getMaxRecordCount()) {
            throw new RecordCountExceededException(effectiveRecordCount, cdrConfigProperties.getMaxRecordCount());
        }
        if (effectiveRecordCount < 1) {
            throw new IllegalArgumentException("recordCount must be at least 1");
        }

        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < effectiveRecordCount; i++) {
            records.add(cdrRecordBuilder.buildRecord(request.getStructureName(), request.getFieldValues()));
        }

        Path filePath = cdrFileWriterService.writeCdrFile(request.getStructureName(), records);
        Resource resource = new UrlResource(filePath.toUri());

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + request.getStructureName() + ".dat\"")
                .body(resource);
    }
}