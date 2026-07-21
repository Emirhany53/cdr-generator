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
import java.util.Objects;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cdr")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "CDR Yapıları ve ASCII Üretimi",
        description = "ASN.1 yapılarını listeler, alanlarını gösterir ve "
                + "Token-Separated-ASCII (.dat) CDR dosyaları üretir.")
public class CdrStructureController {

    private static final int MIN_RECORD_COUNT = 1;
    private static final String DAT_FILE_EXTENSION = ".dat";
    /** Characters allowed in a download file name; everything else becomes '_'. */
    private static final String FILE_NAME_UNSAFE_CHARS = "[^A-Za-z0-9._-]";
    private static final String FILE_NAME_REPLACEMENT = "_";

    private final StructureParserService structureParserService;
    private final CdrRecordBuilder cdrRecordBuilder;
    private final CdrFileWriterService cdrFileWriterService;
    private final CdrConfigProperties cdrConfigProperties;

    @Operation(summary = "Tüm yapı adlarını listele",
            description = "datastructure.json içinden ayrıştırılan tüm ASN.1 yapılarının adlarını döner.")
    @GetMapping("/structures")
    public ResponseEntity<List<String>> getAllStructureNames() {
        log.info("Listing all available structure names");
        List<String> structureNames = structureParserService.getAllStructureNames();
        return ResponseEntity.ok(structureNames);
    }

    @Operation(summary = "Yapının alan tanımlarını getir",
            description = "Belirtilen yapının ASN.1 alan tanımlarını döner. "
                    + "Yapı adı bilinmiyorsa 404 döner.")
    @GetMapping("/structures/{structureName}")
    public ResponseEntity<AsnStructure> getStructureDetails(@PathVariable String structureName) {
        log.info("Fetching field definitions for structure: {}", structureName);
        AsnStructure structure = structureParserService.getStructureByName(structureName);
        if (Objects.isNull(structure)) {
            throw new StructureNotFoundException(structureName);
        }
        return ResponseEntity.ok(structure);
    }

    @Operation(summary = "Tek bir örnek kaydı önizle",
            description = "Yapı için tamamen otomatik doldurulmuş tek bir kayıt üretir ve "
                    + "JSON olarak döner (dosya indirmez). Üretilen değerleri hızlıca görmek için kullanışlıdır.")
    @GetMapping("/generate-test/{structureName}")
    public ResponseEntity<Map<String, Object>> generateTestRecord(@PathVariable String structureName) {
        log.info("Incoming test request to generate mock data for: {}", structureName);
        Map<String, Object> mockRecord = cdrRecordBuilder.buildRecord(structureName, null);
        return ResponseEntity.ok(AsnLiteralFormatter.stripRecord(mockRecord));
    }

    @Operation(summary = "ASCII CDR dosyası üret ve indir",
            description = "Token-Separated-ASCII (.dat) dosyası üretir: her satır bir kayıt, "
                    + "alanlar '|' ile ayrılır. Belirtilmeyen alanlar otomatik üretilir. "
                    + "recordCount değerini yapılandırılan üst sınıra kadar dikkate alır.")
    @PostMapping("/generate")
    public ResponseEntity<Resource> generateAndDownloadCdr(@RequestBody GenerateRequest request) throws IOException {

        log.info("Incoming ASCII CDR generate request for structure: {}", request.getStructureName());
        if (Objects.isNull(request.getStructureName()) || request.getStructureName().isBlank()) {
            throw new IllegalArgumentException("structureName is required");
        }

        Integer recordCount = request.getRecordCount();
        int effectiveRecordCount = Objects.nonNull(recordCount)
                ? recordCount
                : cdrConfigProperties.getDefaultRecordCount();

        if (effectiveRecordCount > cdrConfigProperties.getMaxRecordCount()) {
            throw new RecordCountExceededException(effectiveRecordCount, cdrConfigProperties.getMaxRecordCount());
        }
        if (effectiveRecordCount < MIN_RECORD_COUNT) {
            throw new IllegalArgumentException("recordCount must be at least " + MIN_RECORD_COUNT);
        }

        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < effectiveRecordCount; i++) {
            records.add(cdrRecordBuilder.buildRecord(request.getStructureName(),
                    request.getFieldValues(), request.getChoiceSelections()));
        }

        Path filePath = cdrFileWriterService.writeCdrFile(request.getStructureName(), records);
        String safeName = request.getStructureName().replaceAll(FILE_NAME_UNSAFE_CHARS, FILE_NAME_REPLACEMENT);
        Resource resource = new UrlResource(filePath.toUri());

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + DAT_FILE_EXTENSION + "\"")
                .body(resource);
    }
}