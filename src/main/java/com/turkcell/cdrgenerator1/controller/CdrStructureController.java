package com.turkcell.cdrgenerator1.controller;

import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cdr")
@RequiredArgsConstructor
@Slf4j
public class CdrStructureController {

    private final StructureParserService structureParserService;
    private final CdrRecordBuilder cdrRecordBuilder;

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
}