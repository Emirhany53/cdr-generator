package com.turkcell.cdrgenerator1.controller;

import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.service.CdrFileWriterService;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/structures")
    public ResponseEntity<List<String>> getAllStructureNames() {
        List<String> structureNames = structureParserService.getAllStructureNames();
        return ResponseEntity.ok(structureNames);
    }

    @GetMapping("/structures/{structureName}")
    public ResponseEntity<AsnStructure> getStructureDetails(@PathVariable String structureName) {
        AsnStructure structure = structureParserService.getStructureByName(structureName);
        if (structure == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(structure);
    }

    @GetMapping("/generate-test/{structureName}")
    public ResponseEntity<Map<String, String>> generateTestRecord(@PathVariable String structureName) {
        log.info("Incoming test request to generate mock data for: {}", structureName);
        try {
            Map<String, String> mockRecord = cdrRecordBuilder.buildRecord(structureName, null);
            return ResponseEntity.ok(mockRecord);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
    @PostMapping("/generate")
    public ResponseEntity<Resource> generateAndDownloadCdr(
            @RequestParam String structureName,
            @RequestParam(defaultValue = "1") int recordCount) {

        try {
            List<Map<String, String>> records = new ArrayList<>();
            for (int i = 0; i < recordCount; i++) {
                records.add(cdrRecordBuilder.buildRecord(structureName, null));
            }

            Path filePath = cdrFileWriterService.writeCdrFile(structureName, records);
            Resource resource = new UrlResource(filePath.toUri());

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + structureName + ".dat\"")
                    .body(resource);

        } catch (Exception e) {
            log.error("Error generating CDR file", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}