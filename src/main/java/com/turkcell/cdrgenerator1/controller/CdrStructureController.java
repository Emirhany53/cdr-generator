package com.turkcell.cdrgenerator1.controller;

import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.exception.RecordCountExceededException;
import com.turkcell.cdrgenerator1.exception.StructureNotFoundException;
import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.request.GenerateRequest;
import com.turkcell.cdrgenerator1.model.request.ParseInlineRequest;
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
                + "Token-Separated-ASCII (.txt) CDR dosyaları üretir.")
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
            description = "Belirtilen yapının ASN.1 alan tanımlarını döner. Yapı bir CHOICE kökü "
                    + "ise varsayılan alternatif döner; farklı bir alternatif istemek için "
                    + "'choiceSelections' anahtarını query parametresi olarak gönder "
                    + "(ör. ?TokenCDR=refillRecordV2 — anahtar CHOICE tipinin adı, değer alternatifin adı). "
                    + "Yapı adı bilinmiyorsa 404 döner.")
    @GetMapping("/structures/{structureName}")
    public ResponseEntity<AsnStructure> getStructureDetails(
            @PathVariable String structureName,
            @RequestParam(required = false) Map<String, String> choiceSelections) {
        log.info("Fetching field definitions for structure: {}", structureName);
        AsnStructure structure = Objects.nonNull(choiceSelections) && !choiceSelections.isEmpty()
                ? structureParserService.getStructureByName(structureName, choiceSelections)
                : structureParserService.getStructureByName(structureName);
        if (Objects.isNull(structure)) {
            throw new StructureNotFoundException(structureName);
        }
        return ResponseEntity.ok(structure);
    }

    @Operation(summary = "Ham ASN.1 içeriğini parse et (dosya üretmeden)",
            description = "JSON'da kayıtlı olmayan, yüklenen ya da yapıştırılan bir ASN.1 şemasını "
                    + "parse edip alan ağacını (AsnStructure) döner; hiçbir dosya üretmez. "
                    + "Web arayüzünün, henüz kaydedilmemiş bir şema için değer-giriş formunu "
                    + "otomatik oluşturabilmesi amacıyla eklenmiştir.")
    @PostMapping("/structures/parse-inline")
    public ResponseEntity<AsnStructure> parseInlineStructure(@RequestBody ParseInlineRequest request) {
        if (Objects.isNull(request.getContents()) || request.getContents().isBlank()) {
            throw new IllegalArgumentException("contents is required");
        }
        log.info("Parsing inline ASN.1 content (name hint: {})", request.getStructureName());
        AsnStructure structure = structureParserService.parseFromContents(
                request.getStructureName(), request.getContents(), request.getChoiceSelections());
        if (Objects.isNull(structure) || Objects.isNull(structure.getFields()) || structure.getFields().isEmpty()) {
            throw new IllegalArgumentException("Content could not be parsed into any ASN.1 structure");
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
            description = "Token-Separated-ASCII (.txt) dosyası üretir: her satır bir kayıt, "
                    + "alanlar '|' ile ayrılır. Belirtilmeyen alanlar otomatik üretilir. "
                    + "Kayıtlı bir structureName ile ya da istek gövdesindeki 'contents' alanına "
                    + "konan inline ASN.1 metniyle çalışır (JSON'da olmayan yeni bir şema için). "
                    + "recordCount değerini yapılandırılan üst sınıra kadar dikkate alır.")
    @PostMapping("/generate")
    public ResponseEntity<Resource> generateAndDownloadCdr(@RequestBody GenerateRequest request) throws IOException {

        boolean inlineMode = Objects.nonNull(request.getContents()) && !request.getContents().isBlank();
        log.info("Incoming ASCII CDR generate request (inline={}) for structure: {}",
                inlineMode, request.getStructureName());

        AsnStructure structure = resolveStructure(request, inlineMode);

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
            records.add(cdrRecordBuilder.buildRecordFromFields(structure.getFields(), request.getFieldValues()));
        }

        Path filePath = cdrFileWriterService.writeCdrFile(structure.getStructureName(), records);
        String safeName = structure.getStructureName().replaceAll(FILE_NAME_UNSAFE_CHARS, FILE_NAME_REPLACEMENT);
        Resource resource = new UrlResource(filePath.toUri());

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + DAT_FILE_EXTENSION + "\"")
                .body(resource);
    }

    /**
     * Resolves the structure to generate from: inline ASN.1 {@code contents}
     * (parsed fresh, not persisted) when present, otherwise a registered
     * {@code structureName} looked up from datastructure.json. Mirrors
     * BerGeneratorController's resolveStructure so both formats support the
     * same two input modes.
     */
    private AsnStructure resolveStructure(GenerateRequest request, boolean inlineMode) {
        if (inlineMode) {
            AsnStructure structure = structureParserService.parseFromContents(
                    request.getStructureName(), request.getContents(), request.getChoiceSelections());
            if (Objects.isNull(structure) || Objects.isNull(structure.getFields())
                    || structure.getFields().isEmpty()) {
                throw new IllegalArgumentException(
                        "Inline content could not be parsed into any ASN.1 structure");
            }
            return structure;
        }
        if (Objects.isNull(request.getStructureName()) || request.getStructureName().isBlank()) {
            throw new IllegalArgumentException("structureName is required when no content is provided");
        }
        AsnStructure structure = structureParserService.getStructureByName(
                request.getStructureName(), request.getChoiceSelections());
        if (Objects.isNull(structure)) {
            throw new StructureNotFoundException(request.getStructureName());
        }
        return structure;
    }
}