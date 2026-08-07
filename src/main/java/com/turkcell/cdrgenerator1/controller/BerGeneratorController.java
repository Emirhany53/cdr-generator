package com.turkcell.cdrgenerator1.controller;
import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.config.SelfCheckProperties;
import com.turkcell.cdrgenerator1.exception.BerSelfCheckFailedException;
import com.turkcell.cdrgenerator1.exception.RecordCountExceededException;
import com.turkcell.cdrgenerator1.exception.StructureNotFoundException;
import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.request.GenerateBerRequest;
import com.turkcell.cdrgenerator1.model.response.BerVerificationResponse;
import com.turkcell.cdrgenerator1.service.AiRecordSupplier;
import com.turkcell.cdrgenerator1.service.BerEncoderService;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import com.turkcell.cdrgenerator1.service.verify.BerVerificationResult;
import com.turkcell.cdrgenerator1.service.verify.BerVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/cdr")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "BER Üretimi",
        description = "Kayıtlı bir yapı adından ya da doğrudan gönderilen (inline) ASN.1 "
                + "metninden binary BER kodlu (.ber) CDR dosyaları üretir.")
public class BerGeneratorController {

    private static final String BER_FILE_EXTENSION = ".ber";
    private static final String FILE_NAME_UNSAFE_CHARS = "[^A-Za-z0-9._-]";
    private static final String FILE_NAME_REPLACEMENT = "_";
    private static final int MIN_RECORD_COUNT = 1;
    /** Response header carrying the self-check verdict for a returned file. */
    private static final String SELF_CHECK_HEADER = "X-Cdr-Self-Check";

    private final StructureParserService structureParserService;
    private final CdrRecordBuilder cdrRecordBuilder;
    private final BerEncoderService berEncoderService;
    private final CdrConfigProperties cdrConfigProperties;
    private final AiRecordSupplier aiRecordSupplier;
    private final BerVerifier berVerifier;
    private final SelfCheckProperties selfCheckProperties;

    @Operation(summary = "BER CDR dosyası üret ve indir",
            description = "Bir veya daha fazla kaydı binary BER olarak kodlar ve indirilebilir "
                    + "bir .ber dosyası döner. Kayıtlı bir structureName ile ya da istek gövdesindeki "
                    + "'contents' alanına konan inline ASN.1 metniyle çalışır. CHOICE yapılarda "
                    + "'choiceSelections' ile hangi alternatifin üretileceği seçilebilir. "
                    + "Modül birden çok üst tip tanımlıyorsa 'rootType' ile hangisinin kayıt "
                    + "sayılacağı seçilebilir (ör. IMSCDRS için \"rootType\": \"TokensCSCF\").")
    @PostMapping("/generate-ber")
    public ResponseEntity<Resource> generateBerFile(@RequestBody GenerateBerRequest request) {
        boolean inlineMode = Objects.nonNull(request.getContents()) && !request.getContents().isBlank();
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

        // Yapay zekadan TUM kayitlar icin degerler tek seferde, toplu olarak alinir.
        // AI kapaliysa veya hata verirse bos liste doner, asagidaki build cagrisi
        // otomatik olarak rastgele uretime duser.
        List<Map<String, String>> aiRecords = aiRecordSupplier.supply(
                structure.getStructureName(),
                structure.getFields(),
                request.getFieldValues(),
                effectiveRecordCount);

        ByteArrayOutputStream fileBuffer = new ByteArrayOutputStream();
        for (int i = 0; i < effectiveRecordCount; i++) {
            Map<String, Object> record = cdrRecordBuilder.buildRecordFromFields(
                    structure.getFields(), i, request.getFieldValues(), aiRecords);
            fileBuffer.writeBytes(berEncoderService.encodeRecord(structure, record));
        }

        byte[] fileBytes = fileBuffer.toByteArray();
        log.info("Generated BER file for '{}': {} record(s), {} bytes",
                structure.getStructureName(), effectiveRecordCount, fileBytes.length);

        BerVerificationResult selfCheck = runSelfCheck(structure, fileBytes);

        String safeName = structure.getStructureName()
                .replaceAll(FILE_NAME_UNSAFE_CHARS, FILE_NAME_REPLACEMENT);
        String fileName = safeName + BER_FILE_EXTENSION;

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(SELF_CHECK_HEADER, selfCheck.summary())
                .contentLength(fileBytes.length)
                .body(new ByteArrayResource(fileBytes));
    }

    @Operation(summary = "BER CDR dosyası üret ve indir (HAM METİN)",
            description = "/generate-ber ile aynıdır, ancak ASN.1 metnini JSON kaçış karakteri "
                    + "gerektirmeden doğrudan gövdede alır. Swagger'da dosyadan kopyala-yapıştır için idealdir.")
    @PostMapping(value = "/generate-ber/raw", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Resource> generateBerFileRaw(
            @RequestBody String rawAsn1Contents,
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "1") Integer recordCount,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String structureName) {
        log.info("Incoming RAW BER generate request");

        GenerateBerRequest request = new GenerateBerRequest();
        request.setContents(rawAsn1Contents);
        request.setRecordCount(recordCount);
        request.setStructureName(structureName);

        return generateBerFile(request);
    }

    @Operation(summary = "Bir .ber dosyasını verilen yapıya göre doğrula",
            description = "Bu servisin ÜRETMEDİĞİ bir .ber dosyasını (bir referans yakalama, ya da "
                    + "EMM'in geri gönderdiği bir dosya) verilen structureName'in alan ağacına göre "
                    + "doğrular. tools/ altındaki Python scriptlerini elle çalıştırmanın yerini alır: "
                    + "aynı beş kural (duplicate-tag, set-ordering, tag-shape, named-number, "
                    + "integer-range) burada da çalışır. Üretim akışından bağımsızdır; self-check.mode "
                    + "ayarından etkilenmez, her zaman tüm bulguları döner.")
    @PostMapping(value = "/verify-ber/{structureName}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BerVerificationResponse> verifyBerFile(
            @PathVariable String structureName,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Map<String, String> choiceSelections) {
        log.info("Incoming BER verification request for structure '{}', file '{}' ({} bytes)",
                structureName, file.getOriginalFilename(), file.getSize());

        AsnStructure structure = structureParserService.getStructureByName(structureName, choiceSelections);
        if (Objects.isNull(structure)) {
            throw new StructureNotFoundException(structureName);
        }

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded file '"
                    + file.getOriginalFilename() + "'", e);
        }

        BerVerificationResult result = berVerifier.verify(structure, fileBytes);
        log.info("Verification of '{}' against '{}': {}",
                file.getOriginalFilename(), structureName, result.summary());

        return ResponseEntity.ok(BerVerificationResponse.from(result));
    }

    /**
     * Reads the bytes we just wrote back against the field tree that produced
     * them, and decides what to do about what it finds.
     *
     * <p>Every check on a generated file used to live in {@code tools/} as a
     * script somebody had to remember to run, which meant a defect was usually
     * discovered by EMM a day later rather than here. In {@code warn} mode -
     * the default - nothing about the response changes except an added header
     * and a log line, so switching this on cannot start refusing files that
     * were being returned yesterday. {@code strict} is the setting to reach for
     * once the findings on real structures have been looked at.</p>
     */
    private BerVerificationResult runSelfCheck(AsnStructure structure, byte[] fileBytes) {
        BerVerificationResult result = berVerifier.verify(structure, fileBytes);

        if (result.hasErrors()) {
            log.error("Self-check found {} error(s) in generated '{}': {}",
                    result.errors().size(), structure.getStructureName(), result.errors());
        } else if (!result.warnings().isEmpty()) {
            log.warn("Self-check: {} ({} warning(s))",
                    result.summary(), result.warnings().size());
        } else if (selfCheckProperties.isEnabled()) {
            log.info("Self-check clean: {}", result.summary());
        }

        if (selfCheckProperties.getMode() == SelfCheckProperties.Mode.STRICT && result.hasErrors()) {
            throw new BerSelfCheckFailedException(structure.getStructureName(), result.errors());
        }
        return result;
    }

    private AsnStructure resolveStructure(GenerateBerRequest request, boolean inlineMode) {
        if (inlineMode) {
            AsnStructure structure =
                    structureParserService.parseFromContents(request.getStructureName(),
                            request.getContents(), request.getChoiceSelections(),
                            request.getRootType());
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
                request.getStructureName(), request.getChoiceSelections(), request.getRootType());
        if (Objects.isNull(structure)) {
            throw new StructureNotFoundException(request.getStructureName());
        }
        // Inline mode already refuses a structure with no fields; a registered one
        // deserves the same answer. Without this, picking a module whose root
        // resolves to nothing (Array, LteReturnTypes and SMSCLookupStructures are
        // helper type modules, not CDR records) returns a file full of empty
        // "30 00" records - a silent success that looks like a generator fault.
        if (Objects.isNull(structure.getFields()) || structure.getFields().isEmpty()) {
            throw new IllegalArgumentException(
                    "Structure '" + request.getStructureName()
                            + "' resolves to no fields, so it cannot produce a CDR record");
        }
        return structure;
    }
}