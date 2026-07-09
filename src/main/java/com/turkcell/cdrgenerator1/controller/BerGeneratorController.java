package com.turkcell.cdrgenerator1.controller;

import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.exception.RecordCountExceededException;
import com.turkcell.cdrgenerator1.exception.StructureNotFoundException;
import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.request.GenerateRequest;
import com.turkcell.cdrgenerator1.service.BerEncoderService;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Objects;

/**
 * Reverse flow endpoint: the caller supplies field values (or lets the
 * generator fill them), and the service returns the record encoded in
 * binary BER as a downloadable .ber file.
 *
 * <p>Reuses the existing GenerateRequest body and CdrRecordBuilder, so the
 * API mirrors POST /api/cdr/generate: any field missing from fieldValues is
 * auto-generated, and recordCount produces multiple concatenated records.</p>
 */
@RestController
@RequestMapping("/api/cdr")
@RequiredArgsConstructor
@Slf4j
public class BerGeneratorController {

    private static final String BER_FILE_EXTENSION = ".ber";
    private static final int MIN_RECORD_COUNT = 1;

    private final StructureParserService structureParserService;
    private final CdrRecordBuilder cdrRecordBuilder;
    private final BerEncoderService berEncoderService;
    private final CdrConfigProperties cdrConfigProperties;

    @PostMapping("/generate-ber")
    public ResponseEntity<Resource> generateBerFile(@RequestBody GenerateRequest request) {
        boolean inlineMode = Objects.nonNull(request.getContent()) && !request.getContent().isBlank();
        log.info("Incoming BER generate request (inline={}) for structure: {}",
                inlineMode, request.getStructureName());

        AsnStructure structure = resolveStructure(request, inlineMode);

        int effectiveRecordCount = Objects.nonNull(request.getRecordCount())
                ? request.getRecordCount()
                : cdrConfigProperties.getDefaultRecordCount();

        if (effectiveRecordCount > cdrConfigProperties.getMaxRecordCount()) {
            throw new RecordCountExceededException(effectiveRecordCount, cdrConfigProperties.getMaxRecordCount());
        }
        if (effectiveRecordCount < MIN_RECORD_COUNT) {
            throw new IllegalArgumentException("recordCount must be at least " + MIN_RECORD_COUNT);
        }

        ByteArrayOutputStream fileBuffer = new ByteArrayOutputStream();
        for (int i = 0; i < effectiveRecordCount; i++) {
            Map<String, Object> record =
                    cdrRecordBuilder.buildRecordFromFields(structure.getFields(), request.getFieldValues());
            fileBuffer.writeBytes(berEncoderService.encodeRecord(structure.getFields(), record));
        }

        byte[] fileBytes = fileBuffer.toByteArray();
        log.info("Generated BER file for '{}': {} record(s), {} bytes",
                structure.getStructureName(), effectiveRecordCount, fileBytes.length);

        String fileName = structure.getStructureName() + BER_FILE_EXTENSION;
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentLength(fileBytes.length)
                .body(new ByteArrayResource(fileBytes));
    }

    /**
     * Resolves the structure to encode against. In inline mode the ASN.1 module
     * from the request body is parsed on the spot; otherwise the structure is
     * looked up by name in the pre-loaded registry.
     */
    private AsnStructure resolveStructure(GenerateRequest request, boolean inlineMode) {
        if (inlineMode) {
            AsnStructure structure =
                    structureParserService.parseFromContents(request.getStructureName(), request.getContent());
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

        AsnStructure structure = structureParserService.getStructureByName(request.getStructureName());
        if (Objects.isNull(structure)) {
            throw new StructureNotFoundException(request.getStructureName());
        }
        return structure;
    }
}