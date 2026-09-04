package com.turkcell.cdrgenerator1.controller;

import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.exception.RecordCountExceededException;
import com.turkcell.cdrgenerator1.exception.StructureNotFoundException;
import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.request.GenerateRequest;
import com.turkcell.cdrgenerator1.model.request.ParseInlineRequest;
import com.turkcell.cdrgenerator1.model.response.TextGenerationManifest;
import com.turkcell.cdrgenerator1.service.AiRecordSupplier;
import com.turkcell.cdrgenerator1.service.AsnLiteralFormatter;
import com.turkcell.cdrgenerator1.service.CdrFileWriterService;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import com.turkcell.cdrgenerator1.service.TextGenerationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/cdr")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "CDR Yapıları ve ASCII Üretimi",
        description = "ASN.1 yapılarını listeler, alanlarını gösterir ve "
                + "Token-Separated-ASCII (.txt) CDR dosyaları üretir.")
public class CdrStructureController {

    private static final int MIN_RECORD_COUNT = 1;
    /**
     * The Token-Separated text output. It used to be {@code .dat}; that name now
     * belongs to the binary BER copy {@code /generate-ber} produces, so the two
     * are never confused by extension alone.
     */
    private static final String TEXT_FILE_EXTENSION = ".txt";
    /** Characters allowed in a download file name; everything else becomes '_'. */
    private static final String FILE_NAME_UNSAFE_CHARS = "[^A-Za-z0-9._-]";
    private static final String FILE_NAME_REPLACEMENT = "_";
    private static final String ATTACHMENT_TEMPLATE = "attachment; filename=\"%s%s\"";
    /** Query parameter bound on its own, so it must not land in choiceSelections. */
    private static final String ROOT_TYPE_PARAM = "rootType";

    private final StructureParserService structureParserService;
    private final CdrRecordBuilder cdrRecordBuilder;
    private final CdrFileWriterService cdrFileWriterService;
    private final CdrConfigProperties cdrConfigProperties;
    private final AiRecordSupplier aiRecordSupplier;

    @Operation(summary = "Tüm yapı adlarını listele",
            description = "datastructure.json içinden ayrıştırılan tüm ASN.1 yapılarının adlarını döner.")
    @GetMapping("/structures")
    public ResponseEntity<List<String>> getAllStructureNames() {
        log.info("Listing all available structure names");
        return ResponseEntity.ok(structureParserService.getAllStructureNames());
    }

    @Operation(summary = "Yapının alan tanımlarını getir",
            description = "Belirtilen yapının ASN.1 alan tanımlarını döner. Yapı bir CHOICE kökü "
                    + "ise varsayılan alternatif döner; farklı bir alternatif istemek için "
                    + "'choiceSelections' anahtarını query parametresi olarak gönder "
                    + "(ör. ?TokenCDR=refillRecordV2 — anahtar CHOICE tipinin adı, değer alternatifin adı). "
                    + "Modül birden çok üst tip tanımlıyorsa 'rootType' ile hangisinin kayıt "
                    + "sayılacağı seçilebilir (ör. ?rootType=TokensCSCF). "
                    + "Yapı adı bilinmiyorsa 404 döner.")
    @GetMapping("/structures/{structureName}")
    public ResponseEntity<AsnStructure> getStructureDetails(
            @PathVariable String structureName,
            @RequestParam(required = false) String rootType,
            @RequestParam(required = false) Map<String, String> choiceSelections) {
        log.info("Fetching field definitions for structure: {}", structureName);
        // Spring binds EVERY query parameter into the untyped choiceSelections
        // map, rootType included; left in, it would be offered to the resolver
        // as a CHOICE type name.
        Map<String, String> selections = withoutReservedKeys(choiceSelections);
        AsnStructure structure = structureParserService
                .getStructureByName(structureName, selections, rootType);
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
                request.getStructureName(), request.getContents(), request.getChoiceSelections(),
                request.getRootType());
        if (Objects.isNull(structure) || Objects.isNull(structure.getFields())
                || structure.getFields().isEmpty()) {
            throw new IllegalArgumentException("Content could not be parsed into any ASN.1 structure");
        }
        return ResponseEntity.ok(structure);
    }

    /**
     * Drops the query parameters that are bound separately from the catch-all
     * {@code choiceSelections} map, so they are not mistaken for CHOICE type
     * names. Returns null when nothing is left, which keeps the "no selection"
     * fast path in {@code StructureParserService} intact.
     */
    private Map<String, String> withoutReservedKeys(Map<String, String> queryParameters) {
        if (Objects.isNull(queryParameters) || queryParameters.isEmpty()) {
            return null;
        }
        Map<String, String> selections = new LinkedHashMap<>(queryParameters);
        selections.remove(ROOT_TYPE_PARAM);
        return selections.isEmpty() ? null : selections;
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

    @Operation(summary = "ASCII CDR dosyası üret ve indir (.txt)",
            description = "Token-Separated-ASCII (.txt) dosyası üretir: her satır bir kayıt, "
                    + "alanlar '|' ile ayrılır. Belirtilmeyen alanlar yapay zeka ile mantıklı "
                    + "değerlerle doldurulur; yapay zeka devre dışıysa ya da ürettiği değer "
                    + "kurallara uymazsa rastgele üretime düşülür. Kayıtlı bir structureName ile "
                    + "ya da istek gövdesindeki 'contents' alanına konan inline ASN.1 metniyle "
                    + "çalışır. recordCount değerini yapılandırılan üst sınıra kadar dikkate alır.")
    @PostMapping("/generate")
    public ResponseEntity<Resource> generateAndDownloadCdr(@RequestBody GenerateRequest request) {
        AsnStructure structure = resolveStructure(request);
        TextGenerationResult rendered = generate(request, structure);

        String safeName = structure.getStructureName()
                .replaceAll(FILE_NAME_UNSAFE_CHARS, FILE_NAME_REPLACEMENT);
        byte[] bytes = rendered.text().getBytes(StandardCharsets.US_ASCII);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ATTACHMENT_TEMPLATE.formatted(safeName, TEXT_FILE_EXTENSION))
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

    @Operation(summary = "ASCII CDR üret ve kolon haritasıyla birlikte dön",
            description = "/generate ile AYNI üretimi yapar, ama dosya yerine JSON döner: üretilen "
                    + "metin ve o metnin kolon haritası birlikte. .txt dosyasında başlık satırı yoktur "
                    + "ve kolon kümesi üretilen kayıtlara bağlıdır (bir SEQUENCE OF alanı eleman sayısı "
                    + "kadar kolon grubu ekler), bu yüzden aynı yapıdan üretilen iki dosya farklı "
                    + "genişlikte olabilir. Konuma göre okuyan bir tüketici hangisini elinde tuttuğunu "
                    + "ayırt edemez; 'columns' listesi bunu çözer — listenin i. elemanı, metnin her "
                    + "satırındaki i. alanın adıdır. Metin ve harita TEK bir üretimden gelir, "
                    + "dolayısıyla her zaman birbirini anlatır. /generate'in davranışı bundan etkilenmez.")
    @PostMapping("/generate/manifest")
    public ResponseEntity<TextGenerationManifest> generateWithManifest(@RequestBody GenerateRequest request) {
        AsnStructure structure = resolveStructure(request);
        TextGenerationResult rendered = generate(request, structure);

        return ResponseEntity.ok(TextGenerationManifest.of(structure.getStructureName(),
                resolveRecordCount(request.getRecordCount()), rendered));
    }

    /**
     * The one generation both text endpoints run, so the file a caller downloads
     * and the manifest it reads can never describe different records.
     */
    private TextGenerationResult generate(GenerateRequest request, AsnStructure structure) {
        int effectiveRecordCount = resolveRecordCount(request.getRecordCount());

        // Yapay zekadan TUM kayitlar icin degerler tek seferde, toplu olarak alinir.
        // AI kapaliysa veya hata verirse bos liste doner; deger kaynagi zinciri
        // otomatik olarak rastgele uretime duser.
        List<Map<String, String>> aiRecords = aiRecordSupplier.supply(
                structure.getStructureName(),
                structure.getFields(),
                request.getFieldValues(),
                effectiveRecordCount);

        List<Map<String, Object>> records = new ArrayList<>(effectiveRecordCount);
        for (int index = 0; index < effectiveRecordCount; index++) {
            records.add(cdrRecordBuilder.buildRecordFromFields(
                    structure.getFields(), index, request.getFieldValues(), aiRecords,
                    request.isReferenceMode()));
        }

        log.info("Generated ASCII CDR for '{}': {} record(s)",
                structure.getStructureName(), records.size());
        return cdrFileWriterService.render(records);
    }

    private int resolveRecordCount(Integer requestedRecordCount) {
        int effectiveRecordCount = Objects.nonNull(requestedRecordCount)
                ? requestedRecordCount
                : cdrConfigProperties.getDefaultRecordCount();

        if (effectiveRecordCount > cdrConfigProperties.getMaxRecordCount()) {
            throw new RecordCountExceededException(effectiveRecordCount,
                    cdrConfigProperties.getMaxRecordCount());
        }
        if (effectiveRecordCount < MIN_RECORD_COUNT) {
            throw new IllegalArgumentException("recordCount must be at least " + MIN_RECORD_COUNT);
        }
        return effectiveRecordCount;
    }

    /**
     * Resolves the structure to generate from: inline ASN.1 {@code contents}
     * (parsed fresh, not persisted) when present, otherwise a registered
     * {@code structureName} looked up from datastructure.json. Mirrors
     * BerGeneratorController's resolveStructure so both formats support the
     * same two input modes.
     */
    private AsnStructure resolveStructure(GenerateRequest request) {
        boolean inlineMode = Objects.nonNull(request.getContents()) && !request.getContents().isBlank();
        log.info("Incoming ASCII CDR generate request (inline={}) for structure: {}",
                inlineMode, request.getStructureName());

        if (inlineMode) {
            AsnStructure structure = structureParserService.parseFromContents(
                    request.getStructureName(), request.getContents(), request.getChoiceSelections(),
                    request.getRootType(), request.getFieldValues(), request.isReferenceMode());
            if (hasNoFields(structure)) {
                throw new IllegalArgumentException(
                        "Inline content could not be parsed into any ASN.1 structure");
            }
            return structure;
        }
        if (Objects.isNull(request.getStructureName()) || request.getStructureName().isBlank()) {
            throw new IllegalArgumentException("structureName is required when no content is provided");
        }
        AsnStructure structure = structureParserService.getStructureByName(
                request.getStructureName(), request.getChoiceSelections(), request.getRootType(),
                request.getFieldValues(), request.isReferenceMode());
        if (Objects.isNull(structure)) {
            throw new StructureNotFoundException(request.getStructureName());
        }
        // The BER endpoint has refused this since it was written; the text
        // endpoint did not, so Array, LteReturnTypes and SMSCLookupStructures -
        // helper type modules that declare no record - answered 200 with a file
        // of blank lines. A silent empty success reads as a generator fault.
        if (hasNoFields(structure)) {
            throw new IllegalArgumentException(
                    "Structure '" + request.getStructureName()
                            + "' resolves to no fields, so it cannot produce a CDR record");
        }
        return structure;
    }

    private boolean hasNoFields(AsnStructure structure) {
        return Objects.isNull(structure) || Objects.isNull(structure.getFields())
                || structure.getFields().isEmpty();
    }
}