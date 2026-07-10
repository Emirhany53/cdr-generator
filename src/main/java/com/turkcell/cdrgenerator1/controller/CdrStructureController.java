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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "CDR Structures & ASCII Generation",
        description = "List ASN.1 structures, inspect their fields, and generate "
                + "Token-Separated-ASCII (.dat) CDR files.")
public class CdrStructureController {

    private final StructureParserService structureParserService;
    private final CdrRecordBuilder cdrRecordBuilder;
    private final CdrFileWriterService cdrFileWriterService;
    private final CdrConfigProperties cdrConfigProperties;

    @Operation(summary = "List all structure names",
            description = "Returns the names of every ASN.1 structure parsed from datastructure.json.")
    @GetMapping("/structures")
    public ResponseEntity<List<String>> getAllStructureNames() {
        log.info("Listing all available structure names");
        List<String> structureNames = structureParserService.getAllStructureNames();
        return ResponseEntity.ok(structureNames);
    }

    @Operation(summary = "Get structure field definitions",
            description = "Returns the ASN.1 field definitions for the given structure. "
                    + "Responds with 404 if the structure name is unknown.")
    @GetMapping("/structures/{structureName}")
    public ResponseEntity<AsnStructure> getStructureDetails(@PathVariable String structureName) {
        log.info("Fetching field definitions for structure: {}", structureName);
        AsnStructure structure = structureParserService.getStructureByName(structureName);
        if (structure == null) {
            throw new StructureNotFoundException(structureName);
        }
        return ResponseEntity.ok(structure);
    }

    @Operation(summary = "Preview a single mock record",
            description = "Generates one fully auto-filled record for the structure and returns "
                    + "it as JSON (no file download). Useful for quickly inspecting generated values.")
    @GetMapping("/generate-test/{structureName}")
    public ResponseEntity<Map<String, Object>> generateTestRecord(@PathVariable String structureName) {
        log.info("Incoming test request to generate mock data for: {}", structureName);
        Map<String, Object> mockRecord = cdrRecordBuilder.buildRecord(structureName, null);
        return ResponseEntity.ok(AsnLiteralFormatter.stripRecord(mockRecord));
    }

    @Operation(summary = "Generate and download an ASCII CDR file",
            description = "Produces a Token-Separated-ASCII (.dat) file, one record per line with "
                    + "fields separated by '|'. Unspecified fields are auto-generated. Honors "
                    + "recordCount up to the configured maximum.")
    @PostMapping("/generate")
    public ResponseEntity<Resource> generateAndDownloadCdr(@RequestBody GenerateRequest request) throws IOException {

        log.info("Incoming ASCII CDR generate request for structure: {}", request.getStructureName());
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