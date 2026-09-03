package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
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
    private final BcdTimestampFactory bcdTimestampFactory;
    private final CdrConfigProperties cdrConfigProperties;
    private final List<ValueSource> valueSources;
    private final Random random = new Random();

    public CdrRecordBuilder(StructureParserService structureParserService,
                            BcdTimestampFactory bcdTimestampFactory,
                            CdrConfigProperties cdrConfigProperties,
                            List<ValueSource> valueSources) {
        this.structureParserService = structureParserService;
        this.bcdTimestampFactory = bcdTimestampFactory;
        this.cdrConfigProperties = cdrConfigProperties;
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
        return buildFields(structure.getFields(), emptyAiContext(userValues), EMPTY_PATH,
                structure.isChoiceRoot());
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
        return buildFields(structure.getFields(), emptyAiContext(userValues), EMPTY_PATH,
                structure.isChoiceRoot());
    }

    public Map<String, Object> buildRecordFromFields(List<AsnField> fields, Map<String, String> userValues) {
        return buildFields(fields, emptyAiContext(userValues), EMPTY_PATH, false);
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
                null, recordIndex, userValues, aiGeneratedRecords, bcdTimestampFactory.newRecordAnchor());
        return buildFields(fields, context, EMPTY_PATH, false);
    }

    private ValueSourceContext emptyAiContext(Map<String, String> userValues) {
        return new ValueSourceContext(null, SINGLE_RECORD_INDEX, userValues, List.of(),
                bcdTimestampFactory.newRecordAnchor());
    }

    // ------------------------------------------------------------------
    // Agac gezme - eski mantik korundu
    // ------------------------------------------------------------------

    private Map<String, Object> buildFields(List<AsnField> fields, ValueSourceContext context,
                                            String pathPrefix, boolean parentIsChoice) {
        Map<String, Object> record = new LinkedHashMap<>();
        // Keyed per FIELD, not per name: the same name can appear twice in one
        // body under different tags, and keying by name alone let the second
        // field overwrite the first (see RecordFieldKeys).
        List<String> keys = RecordFieldKeys.forFields(fields);

        for (int index = 0; index < fields.size(); index++) {
            AsnField field = fields.get(index);
            // IMPLICIT-tag'li OPTIONAL CHOICE alani: hic doldurulmaz, boylece
            // encoder onu unset OPTIONAL sayip atlar ve EMM'in hoisting kaynakli
            // "Duplicate Tag" reddi tetiklenmez (bkz. skipImplicitChoiceFields).
            // parentIsChoice korumasi sart: bu ayni sekle sahip bir alan bir
            // CHOICE'un SECILI ALTERNATIFI oldugunda onu atlamak, ust katmani
            // (ornek: uELocalIPAddress [0] EXPLICIT IPAddress) bos birakip bozar.
            // Alternatif atlanmaz; yalnizca SEQUENCE/SET uyeleri atlanir.
            if (!parentIsChoice && shouldSkipImplicitChoice(field)) {
                continue;
            }
            String recordKey = keys.get(index);
            // The PATH stays name-based: it addresses user-supplied and
            // AI-supplied values, which are keyed by the schema's own names.
            String fieldPath = buildPath(pathPrefix, field.getFieldName());

            if (Objects.nonNull(field.getChildren()) && !field.getChildren().isEmpty()) {
                record.put(recordKey, field.isNestedCollectionElement()
                        ? buildNestedCollectionGroup(field, context, fieldPath)
                        : field.isRepeated()
                                ? buildRepeatedGroup(field, context, fieldPath)
                                : buildFields(field.getChildren(), context, fieldPath, field.isChoice()));
            } else if (field.isStructuralTypeWithNoComponents()) {
                // The type IS a container; it just declares nothing (X.690 8.11:
                // a SET or SEQUENCE with no components has zero content octets).
                // An empty BODY is therefore the value here, not a leaf - which
                // is what the encoder reads to write 31 00 / 30 00 instead of a
                // generated string under OCTET STRING's tag.
                //
                // Exactly one element for a collection: every element of a
                // SET OF <empty SET> is the same 31 00, so a second one carries
                // no information and only puts a duplicate TLV on the wire.
                record.put(recordKey, field.isRepeated() ? List.of(Map.of()) : Map.of());
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
            // A SEQUENCE OF <Choice> element is itself the chosen alternative;
            // field.isChoice() carries that through so its content is not skipped.
            items.add(buildFields(field.getChildren(), context, elementPath, field.isChoice()));
        }
        return items;
    }

    /**
     * A {@link AsnField#isNestedCollectionElement()} field: the outer
     * collection generates exactly ONE instance of its middle-layer type
     * (e.g. one {@code Call-Transfer-Info-List}), holding
     * {@link #repeatCountFor} elements of the innermost type (e.g.
     * {@code Call-Transfer-Info}) - the same element count an ordinary
     * single-layer collection would generate, just wrapped one layer deeper.
     *
     * <p>The outer list always has size 1: nothing in the corpus measures how
     * many middle-layer instances a real record carries, so generating more
     * than the one instance needed to prove the missing wrapper would be
     * inventing an answer nobody asked for.</p>
     */
    private List<List<Map<String, Object>>> buildNestedCollectionGroup(AsnField field, ValueSourceContext context,
                                                                       String fieldPath) {
        int innerCount = repeatCountFor(field);
        List<Map<String, Object>> innerElements = new ArrayList<>(innerCount);
        for (int index = 0; index < innerCount; index++) {
            String elementPath = fieldPath + INDEX_OPEN + index + INDEX_CLOSE;
            innerElements.add(buildFields(field.getChildren(), context, elementPath, field.isChoice()));
        }
        return List.of(innerElements);
    }

    private List<String> buildRepeatedLeaf(AsnField field, ValueSourceContext context, String fieldPath) {
        List<String> indexed = indexedUserValues(context, fieldPath, field.getFieldName());
        if (!indexed.isEmpty()) {
            return indexed.stream()
                    .map(value -> formatAsnLiteral(value, field.getFieldType()))
                    .toList();
        }
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

    /**
     * Values a caller supplied for the INDIVIDUAL elements of a repeated leaf,
     * keyed {@code path[0]}, {@code path[1]}, ... - the same index form
     * {@link #buildRepeatedGroup} already writes for a repeated SEQUENCE/SET,
     * so one path syntax addresses both kinds of collection.
     *
     * <p>Why this exists: a repeated LEAF (a {@code SEQUENCE OF IA5String} such
     * as MMTel's {@code sDP-Media-Descriptions}, which carries 26 lines in the
     * reference capture) could previously hold only ONE caller value. The chain
     * resolves a single string for the field and the whole collection collapses
     * to that one element, so 26 lines of a real record arrived as 1. Indexing
     * the key is what lets a caller name each element apart.</p>
     *
     * <p>Read straight from the user map instead of through the value-source
     * chain, and deliberately so: {@code RandomValueSource} answers EVERY
     * request, so probing the chain for {@code path[1]} would always succeed and
     * the loop below would never terminate. Only explicitly supplied values take
     * part here; when none are indexed this returns empty and the caller falls
     * through to the unchanged chain-then-random path.</p>
     *
     * <p>The full path is tried first and the bare field name second - the same
     * order {@code UserProvidedValueSource} uses - but a series is taken from one
     * key space or the other, never mixed, so a partially indexed bare name
     * cannot splice itself into a path-keyed collection.</p>
     */
    private List<String> indexedUserValues(ValueSourceContext context, String fieldPath, String fieldName) {
        Map<String, String> userValues = context.getUserProvidedValues();
        if (Objects.isNull(userValues) || userValues.isEmpty()) {
            return List.of();
        }
        List<String> byPath = indexedSeries(userValues, fieldPath);
        return byPath.isEmpty() ? indexedSeries(userValues, fieldName) : byPath;
    }

    /**
     * Reads {@code key[0]}, {@code key[1]}, ... until the first index that is
     * absent or blank. Indices must run contiguously from zero: a gap ends the
     * collection rather than silently skipping an element, so the caller's
     * numbering and the emitted element order always agree.
     */
    private List<String> indexedSeries(Map<String, String> userValues, String key) {
        List<String> values = new ArrayList<>();
        for (int index = 0; ; index++) {
            String value = userValues.get(key + INDEX_OPEN + index + INDEX_CLOSE);
            if (Objects.isNull(value) || value.isBlank()) {
                return values;
            }
            values.add(value);
        }
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
     * Tipi CHOICE, tag'i IMPLICIT ve OPTIONAL olan bir alan mi? Bu ucu bir arada
     * olan alanlar (MMTel'de diverting-Party-Address, servingSCSCFAddress, impu,
     * cT-Push-* vb. 9 alan) EMM'in implicit-CHOICE hoisting hatasini tetikliyor.
     * OPTIONAL sarti onemli: zorunlu bir CHOICE atlanirsa kayit gecersiz olur.
     * field.isExplicit()==false, IMPLICIT tagli demektir (resolver EXPLICIT
     * yazilmis skaler CHOICE'lar disinda explicit bayragini birakmaz).
     *
     * <p>Dorduncu sart {@link AsnField#isDecoderHoistsImplicitChoice()}: kural
     * yalnizca hatanin GOZLENDIGI ailede - InvolvedParty imzasini tasiyan
     * MMTel/AIMS/IMS/UAG/ATS modullerinde - calisir. Sadece yapisal oldugu
     * surece her modulde calisiyordu ve MMTel disinda 9 modulden 49 alan
     * siliyordu; TAP-0309'da bu, dosyanin cagri kayitlarini tasiyan tek bolumu
     * olan {@code callEventDetails} demekti - orada hic gozlenmemis bir hata
     * icin bos bir TAP dosyasi.</p>
     */
    private boolean shouldSkipImplicitChoice(AsnField field) {
        return cdrConfigProperties != null
                && cdrConfigProperties.isSkipImplicitChoiceFields()
                && field.isDecoderHoistsImplicitChoice()
                && field.isChoice()
                && !field.isExplicit()
                && field.isOptional();
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