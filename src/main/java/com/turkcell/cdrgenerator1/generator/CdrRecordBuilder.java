package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.exception.StructureNotFoundException;
import com.turkcell.cdrgenerator1.generator.source.ValueSource;
import com.turkcell.cdrgenerator1.generator.source.ValueSourceContext;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.RecordFieldKeys;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

@Service
@Slf4j
public class CdrRecordBuilder {

    private static final int MIN_REPEAT_COUNT = 1;
    private static final int MAX_REPEAT_COUNT = 2;
    /**
     * A repeated CHOICE field emits exactly one element.
     *
     * <p>{@code AsnFieldTreeResolver.resolveChoiceAlternative} collapses a CHOICE
     * to a SINGLE alternative, and {@code BerEncoderService.encodeRepeated} reuses
     * that one alternative for every element of the collection. Asking for two
     * elements therefore does not produce two different alternatives - it writes
     * the SAME alternative tag twice, side by side, inside the collection. That is
     * a literal duplicate tag on the wire, and it is what EMM rejected:</p>
     *
     * <pre>
     * Duplicate Tag data found for MMTelChargingDataTypes.MMTelServiceRecord
     *     .mMTelRecord.recordExtensions.enhancedPhoneFeatures1.[0]
     * </pre>
     *
     * <p>Confirmed against both EMM-accepted reference captures: every repeated
     * CHOICE that carries two elements uses two DISTINCT alternatives - tags
     * {@code (0, 1)} in 17512 of 17512 cases for list-Of-Calling-Party-Address and
     * list-Of-Called-Asserted-Identity, never {@code (0, 0)}. Our output carried
     * {@code (0, 0)}. Emitting a single element matches the other shape those same
     * captures show (1442 + 1869 single-element cases for [6], 564 + 659 for
     * [102]), so it is a shape EMM demonstrably accepts.</p>
     *
     * <p>Only CHOICE-valued collections are capped. A repeated SEQUENCE/SET keeps
     * the random 1..2 count: its elements are full bodies, not alternatives, so
     * repetition is legitimate there and the references show it (up to 19
     * elements for extListOfAccessTransferInformation).</p>
     */
    private static final int CHOICE_ELEMENT_COUNT = 1;
    private static final String PATH_SEPARATOR = ".";
    private static final String INDEX_OPEN = "[";
    private static final String INDEX_CLOSE = "]";
    private static final String EMPTY_PATH = "";
    private static final String INTEGER_TOKEN = "INTEGER";
    private static final int SINGLE_RECORD_INDEX = 0;

    private final StructureParserService structureParserService;
    private final List<ValueSource> valueSources;
    private final Random random = new Random();

    public CdrRecordBuilder(StructureParserService structureParserService,
                            List<ValueSource> valueSources) {
        this.structureParserService = structureParserService;
        this.valueSources = valueSources.stream()
                .sorted(Comparator.comparingInt(ValueSource::getOrder))
                .toList();
        log.info("Deger kaynagi zinciri: {}", this.valueSources.stream()
                .map(source -> source.getClass().getSimpleName())
                .toList());
    }

    // ------------------------------------------------------------------
    // Mevcut imzalar - davranis degismedi, AI olmadan calisirlar
    // ------------------------------------------------------------------

    public Map<String, Object> buildRecord(String structureName, Map<String, String> userValues) {
        log.debug("Building record for structure: {}", structureName);

        AsnStructure structure = structureParserService.getStructureByName(structureName);
        if (Objects.isNull(structure)) {
            log.error("Structure not found: {}", structureName);
            throw new StructureNotFoundException(structureName);
        }
        return buildFields(structure.getFields(), emptyAiContext(userValues), EMPTY_PATH);
    }

    public Map<String, Object> buildRecord(String structureName, Map<String, String> userValues,
                                           Map<String, String> choiceSelections) {
        if (Objects.isNull(choiceSelections) || choiceSelections.isEmpty()) {
            return buildRecord(structureName, userValues);
        }
        AsnStructure structure = structureParserService.getStructureByName(structureName, choiceSelections);
        if (Objects.isNull(structure)) {
            log.error("Structure not found: {}", structureName);
            throw new StructureNotFoundException(structureName);
        }
        return buildFields(structure.getFields(), emptyAiContext(userValues), EMPTY_PATH);
    }

    public Map<String, Object> buildRecordFromFields(List<AsnField> fields, Map<String, String> userValues) {
        return buildFields(fields, emptyAiContext(userValues), EMPTY_PATH);
    }

    // ------------------------------------------------------------------
    // Yapay zekanin devrede oldugu imza
    // ------------------------------------------------------------------

    /**
     * Coklu kayit uretiminde her kayit icin farkli recordIndex, ayni
     * aiGeneratedRecords listesi gecirilir. AI listesi bossa davranis
     * buildRecordFromFields ile birebir aynidir.
     */
    public Map<String, Object> buildRecordFromFields(List<AsnField> fields,
                                                     int recordIndex,
                                                     Map<String, String> userValues,
                                                     List<Map<String, String>> aiGeneratedRecords) {
        ValueSourceContext context = new ValueSourceContext(
                null, recordIndex, userValues, aiGeneratedRecords);
        return buildFields(fields, context, EMPTY_PATH);
    }

    private ValueSourceContext emptyAiContext(Map<String, String> userValues) {
        return new ValueSourceContext(null, SINGLE_RECORD_INDEX, userValues, List.of());
    }

    // ------------------------------------------------------------------
    // Agac gezme - eski mantik korundu
    // ------------------------------------------------------------------

    private Map<String, Object> buildFields(List<AsnField> fields, ValueSourceContext context,
                                            String pathPrefix) {
        Map<String, Object> record = new LinkedHashMap<>();
        // Keyed per FIELD, not per name: the same name can appear twice in one
        // body under different tags, and keying by name alone let the second
        // field overwrite the first (see RecordFieldKeys).
        List<String> keys = RecordFieldKeys.forFields(fields);

        for (int index = 0; index < fields.size(); index++) {
            AsnField field = fields.get(index);
            String recordKey = keys.get(index);
            // The PATH stays name-based: it addresses user-supplied and
            // AI-supplied values, which are keyed by the schema's own names.
            String fieldPath = buildPath(pathPrefix, field.getFieldName());

            if (Objects.nonNull(field.getChildren()) && !field.getChildren().isEmpty()) {
                record.put(recordKey, field.isRepeated()
                        ? buildRepeatedGroup(field, context, fieldPath)
                        : buildFields(field.getChildren(), context, fieldPath));
            } else if (field.isRepeated()) {
                record.put(recordKey, buildRepeatedLeaf(field, context, fieldPath));
            } else {
                record.put(recordKey, resolveLeafValue(field, context, fieldPath));
            }
        }
        return record;
    }

    private List<Map<String, Object>> buildRepeatedGroup(AsnField field, ValueSourceContext context,
                                                         String fieldPath) {
        int repeatCount = repeatCountFor(field);
        List<Map<String, Object>> items = new ArrayList<>(repeatCount);
        for (int index = 0; index < repeatCount; index++) {
            String elementPath = fieldPath + INDEX_OPEN + index + INDEX_CLOSE;
            items.add(buildFields(field.getChildren(), context, elementPath));
        }
        return items;
    }

    private List<String> buildRepeatedLeaf(AsnField field, ValueSourceContext context, String fieldPath) {
        Optional<String> resolved = resolveThroughChain(field, context, fieldPath);
        if (resolved.isPresent()) {
            return List.of(formatAsnLiteral(resolved.get(), field.getFieldType()));
        }
        int repeatCount = repeatCountFor(field);
        List<String> values = new ArrayList<>(repeatCount);
        for (int index = 0; index < repeatCount; index++) {
            values.add(formatAsnLiteral(fallbackValue(field, context, fieldPath), field.getFieldType()));
        }
        return values;
    }

    private String resolveLeafValue(AsnField field, ValueSourceContext context, String fieldPath) {
        return formatAsnLiteral(fallbackValue(field, context, fieldPath), field.getFieldType());
    }

    /**
     * Deger kaynagi zinciri: kullanici degeri -> yapay zeka -> rastgele.
     * Zincirin son halkasi her zaman deger dondurdugu icin bos donmez.
     */
    private String fallbackValue(AsnField field, ValueSourceContext context, String fieldPath) {
        return resolveThroughChain(field, context, fieldPath).orElse("");
    }

    private Optional<String> resolveThroughChain(AsnField field, ValueSourceContext context,
                                                 String fieldPath) {
        ValueSourceContext pathAwareContext = context.withCurrentPath(fieldPath);
        return valueSources.stream()
                .map(source -> source.resolve(pathAwareContext, field))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * How many elements a repeated field gets: always one for a CHOICE
     * collection (see {@link #CHOICE_ELEMENT_COUNT}), otherwise a random 1..2.
     */
    private int repeatCountFor(AsnField field) {
        return field.isChoice() ? CHOICE_ELEMENT_COUNT : randomRepeatCount();
    }

    private int randomRepeatCount() {
        return MIN_REPEAT_COUNT + random.nextInt(MAX_REPEAT_COUNT - MIN_REPEAT_COUNT + 1);
    }

    private String buildPath(String prefix, String fieldName) {
        return prefix.isEmpty() ? fieldName : prefix + PATH_SEPARATOR + fieldName;
    }

    private String formatAsnLiteral(String rawValue, String fieldType) {
        if (Objects.isNull(fieldType)) {
            return "\"" + rawValue + "\"";
        }
        if (fieldType.toUpperCase(Locale.ENGLISH).contains(INTEGER_TOKEN)) {
            return "'" + rawValue + "'D";
        }
        return "\"" + rawValue + "\"";
    }
}