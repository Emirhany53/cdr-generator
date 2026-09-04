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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

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
        return buildRecordFromFields(fields, recordIndex, userValues, aiGeneratedRecords, false);
    }

    /**
     * As above, but able to run in reference mode: when {@code referenceMode} is
     * true an OPTIONAL field the caller never described is left out of the
     * record entirely. False reproduces the four-argument behaviour exactly.
     */
    public Map<String, Object> buildRecordFromFields(List<AsnField> fields,
                                                     int recordIndex,
                                                     Map<String, String> userValues,
                                                     List<Map<String, String>> aiGeneratedRecords,
                                                     boolean referenceMode) {
        ValueSourceContext context = new ValueSourceContext(
                null, recordIndex, userValues, aiGeneratedRecords,
                bcdTimestampFactory.newRecordAnchor(), null, referenceMode);
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
            String recordKey = keys.get(index);
            // The PATH stays name-based: it addresses user-supplied and
            // AI-supplied values, which are keyed by the schema's own names.
            String fieldPath = buildPath(pathPrefix, field.getFieldName());

            if (!parentIsChoice && shouldSkipImplicitChoice(field, context, fieldPath)) {
                continue;
            }

            // P2-C: inside an expanded repeated-CHOICE instance (parentIsChoice,
            // more than one sibling alternative present - see buildRepeatedGroup's
            // expandedChoiceCollection gate), an alternative the caller did not
            // describe FOR THIS SPECIFIC INSTANCE is never encoded, regardless of
            // its own optional flag. Without this, every sibling alternative
            // would fall through to RandomValueSource for whichever instance
            // didn't name it, writing that alternative's tag with a fabricated
            // value right next to the one the caller actually asked for - the
            // literal duplicate/conflicting-CHOICE shape this whole feature
            // exists to avoid producing. Deliberately NOT gated on
            // context.isReferenceMode(): fields.size()>1 can only be true here
            // because applyIndexedChoiceExpansion already ran (which IS gated on
            // reference mode), so this check is a structural safety net, not a
            // second copy of that gate - and "never emit two alternatives in one
            // instance" is correct in every mode, not only reference mode.
            if (parentIsChoice && fields.size() > 1 && !describedByCaller(context, field, fieldPath)) {
                continue;
            }

            // Reference-driven generation: an OPTIONAL field the caller never
            // described is left out entirely, the same way an unfilled OPTIONAL
            // already leaves the record - the key is absent, so the encoder's
            // null branch omits it. Mandatory fields are untouched: dropping one
            // would make the record invalid, not merely quieter.
            if (shouldOmitUndescribedOptional(field, context, fieldPath)) {
                continue;
            }

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
        // A CHOICE collection keeps its capped count untouched by DEFAULT - see
        // CHOICE_ELEMENT_COUNT. The exception is reference mode: there the
        // caller's own indexed keys are the authority on how many instances
        // exist, exactly as they already are for a non-CHOICE collection.
        //
        // This used to additionally require that applyIndexedChoiceExpansion
        // had widened field.getChildren() past one alternative, which made the
        // count depend on whether the caller's alternatives happened to DIFFER:
        // "[0].sIP-URI + [1].sIP-URI" described two instances but produced one,
        // silently dropping the second value, and "[0].tEL-URI + [1].tEL-URI"
        // produced one instance of the WRONG alternative filled at random. How
        // many instances there are and which alternative each carries are two
        // separate questions; only the second one belongs to expansion.
        //
        // referenceMode=false is untouched and short-circuits first, so every
        // EMM-passed module keeps CHOICE_ELEMENT_COUNT's single element and the
        // same-alternative repeat is reachable only by a caller who indexes it
        // deliberately. X.690 8.10 makes that legal: a SEQUENCE OF's elements
        // are delimited by position, not by tag.
        int described = (!field.isChoice() || context.isReferenceMode())
                ? indexedGroupCount(context, fieldPath) : 0;
        int repeatCount = described > 0 ? described : repeatCountFor(field);
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
     * Reference mode's single rule: an OPTIONAL field nobody described is not
     * generated at all.
     *
     * <p>Ordinary generation fills every leaf in the resolved tree, because
     * {@code isOptional()} is read nowhere except the implicit-CHOICE skip and
     * {@code RandomValueSource} answers every request. Against Yasin's MMTel
     * reference that produced 144 leaf values the real record does not have -
     * whole subtrees such as {@code recordExtensions.iN-AMA-Extension} invented
     * from nothing. For a record meant to reproduce a reference, silence is the
     * correct output for a field the reference does not carry.</p>
     *
     * <p>Off by default and short-circuited first, so a normal generation run
     * does not even scan the key set: with {@code referenceMode} false this
     * method returns immediately and the walk behaves exactly as before.</p>
     *
     * <p>Mandatory fields are never dropped. A record missing a required
     * component is invalid rather than merely smaller, and no reference can
     * make that the right answer.</p>
     */
    private boolean shouldOmitUndescribedOptional(AsnField field, ValueSourceContext context,
                                                   String fieldPath) {
        return context.isReferenceMode()
                && field.isOptional()
                && !describedByCaller(context, field, fieldPath);
    }

    /**
     * Did the caller say anything about this field or anything beneath it?
     *
     * <p>A path-keyed reference describes a container implicitly: nothing names
     * {@code recordExtensions}, only its leaves are named, so the container has
     * to survive on the strength of {@code recordExtensions.node-id}. The prefix
     * tests cover that - {@code path.} for a child, {@code path[} for a
     * collection element - alongside the exact match for a leaf.</p>
     *
     * <p>The bare field name is accepted too, mirroring
     * {@code UserProvidedValueSource}'s own fallback. That is why the subtree is
     * walked rather than only prefix-matched: a bare {@code inner} names a
     * descendant without naming any ancestor, so a container whose child was
     * addressed that way is invisible from the container's own path. Dropping it
     * would silently discard a value the caller explicitly set - the one outcome
     * reference mode must never produce. The cost of the walk is paid only in
     * reference mode; {@link #shouldOmitUndescribedOptional} short-circuits on
     * the flag before reaching here.</p>
     *
     * <p>A bare name that matches a field in an unrelated branch keeps that
     * branch alive as well. That ambiguity is inherent to bare names and already
     * governs which field receives the VALUE; reference mode inherits it rather
     * than inventing a second, conflicting rule.</p>
     */
    private boolean describedByCaller(ValueSourceContext context, AsnField field, String fieldPath) {
        Map<String, String> userValues = context.getUserProvidedValues();
        if (Objects.isNull(userValues) || userValues.isEmpty()) {
            return false;
        }
        return describesFieldOrDescendant(userValues, field, fieldPath);
    }

    private boolean describesFieldOrDescendant(Map<String, String> userValues, AsnField field,
                                               String fieldPath) {
        for (String key : userValues.keySet()) {
            if (describes(key, fieldPath) || describes(key, field.getFieldName())) {
                return true;
            }
        }
        List<AsnField> children = field.getChildren();
        if (Objects.isNull(children)) {
            return false;
        }
        for (AsnField child : children) {
            if (describesFieldOrDescendant(userValues, child,
                    buildPath(fieldPath, child.getFieldName()))) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code key} names {@code target} itself, a child of it, or one of its elements. */
    private boolean describes(String key, String target) {
        if (!key.startsWith(target)) {
            return false;
        }
        if (key.length() == target.length()) {
            return true;
        }
        char next = key.charAt(target.length());
        return next == PATH_SEPARATOR.charAt(0) || next == INDEX_OPEN.charAt(0);
    }

    /**
     * How many elements of a repeated GROUP the caller actually described,
     * counted from the {@code path[i].child} keys they supplied.
     *
     * <p>Without this the element count came from {@link #repeatCountFor}'s
     * random 1..2 regardless of the input, so indexed values addressed at
     * {@code path[1]} and beyond had nowhere to land: the instance holding them
     * was never built. Indexed leaves alone therefore only reached the wire in
     * whichever instance the dice happened to create - the reference record's
     * {@code list-Of-SDP-Media-Components} needs two, and got one.</p>
     *
     * <h4>Contiguous, not max-index-plus-one</h4>
     *
     * <p>Counting to the highest index would invent instances for the gaps, and
     * those would not be empty: {@code buildFields} fills every child of an
     * instance from the chain, which ends at {@code RandomValueSource}. Given
     * {@code path[0]} and {@code path[2]}, a max-index rule would emit a
     * fully random {@code path[1]} in the middle of otherwise reference-exact
     * data - fabricated content sitting between real records. Counting only the
     * contiguous run from zero refuses to guess: the first gap ends the
     * collection, matching the rule {@link #indexedSeries} already applies to
     * leaves. A caller whose keys start at {@code path[3]} describes no element
     * zero, so nothing is indexed and the random fallback runs unchanged.</p>
     *
     * <h4>Full path only</h4>
     *
     * <p>Unlike a leaf value this does not fall back to the bare field name. An
     * instance count taken from a bare name would silently reshape a same-named
     * collection in a different branch - changing how many records that branch
     * emits, not merely what one of them says.</p>
     *
     * <p>Only keys that name something INSIDE an instance count: the index must
     * be followed by {@code .}, so {@code path[0]} on its own (a repeated leaf
     * element) never inflates a group's element count.</p>
     */
    private int indexedGroupCount(ValueSourceContext context, String fieldPath) {
        Map<String, String> userValues = context.getUserProvidedValues();
        if (Objects.isNull(userValues) || userValues.isEmpty()) {
            return 0;
        }
        String prefix = fieldPath + INDEX_OPEN;
        Set<Integer> described = new HashSet<>();
        for (String key : userValues.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            int close = key.indexOf(INDEX_CLOSE, prefix.length());
            if (close < 0 || close + 1 >= key.length()
                    || key.charAt(close + 1) != PATH_SEPARATOR.charAt(0)) {
                continue;
            }
            try {
                described.add(Integer.parseInt(key.substring(prefix.length(), close)));
            } catch (NumberFormatException notAnIndex) {
                // A key such as "foo[bar].baz" addresses nothing this builder emits.
            }
        }
        int count = 0;
        while (described.contains(count)) {
            count++;
        }
        return count;
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
    private boolean shouldSkipImplicitChoice(AsnField field, ValueSourceContext context, String fieldPath) {
        boolean structurallyAffected = cdrConfigProperties != null
                && cdrConfigProperties.isSkipImplicitChoiceFields()
                && field.isDecoderHoistsImplicitChoice()
                && field.isChoice()
                && !field.isExplicit()
                && field.isOptional();
        if (!structurallyAffected) {
            return false;
        }
        return !bypassedByCallerInReferenceMode(context, field, fieldPath);
    }

    /**
     * P1: a caller can reclaim ONE call site of the workaround above, but only
     * in reference mode and only by naming that exact site.
     *
     * <p>The global rule ({@link #shouldSkipImplicitChoice}'s four structural
     * conditions) exists because the resolver cannot tell, from shape alone,
     * which {CHOICE, IMPLICIT, OPTIONAL} field is the one EMM's decoder
     * mis-reads and which is an ordinary field that happens to have the same
     * shape - {@code skip-implicit-choice-fields} is deliberately blunt because
     * the alternative, at the time it was added, was worse: every module
     * carrying that shape lost the field, workaround or not (see
     * {@link ImplicitChoiceSkipScopeTest} for the family gate that already
     * narrows this once). Widening the rule globally would be exactly that
     * same blunt trade in the other direction - it stays {@code true} in
     * {@code application.yml} and this method never reads it as anything
     * else.</p>
     *
     * <p>Reference mode changes what evidence is available: a caller who names
     * {@code list-Of-Calling-Party-Address[0].sIP-URI} has already done the
     * thing skip-implicit-choice-fields' blunt rule cannot do for itself -
     * pointed at one exact field and said "this one is really there, for this
     * one record". That is narrower than the global rule can be and doesn't
     * need the global rule to change: {@link #describedByCaller} already
     * answers "did the caller name this field or anything under it", built for
     * KN-4's same reference-mode reasoning, so reusing it here rather than
     * writing a second description rule keeps both aligned rather than letting
     * them drift.</p>
     *
     * <p>Gated on {@code referenceMode} first and short-circuited: ordinary
     * generation ({@code referenceMode=false}) never reaches
     * {@link #describedByCaller}, so a caller who happens to supply a value at
     * this path outside reference mode gets exactly the old behaviour - the
     * field is still dropped, evidence or not.</p>
     */
    private boolean bypassedByCallerInReferenceMode(ValueSourceContext context, AsnField field, String fieldPath) {
        return context.isReferenceMode() && describedByCaller(context, field, fieldPath);
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