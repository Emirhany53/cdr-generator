package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.service.BerEncoderService;
import com.turkcell.cdrgenerator1.service.FixedWidthTextFormatter;
import com.turkcell.cdrgenerator1.service.TlvWriter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A repeated LEAF - a {@code SEQUENCE OF} some primitive - can carry a value
 * per element, addressed {@code path[0]}, {@code path[1]}, ...
 *
 * <h2>What this protects</h2>
 *
 * <p>MMTel's {@code sDP-Media-Descriptions} is a {@code SEQUENCE OF IA5String}
 * and the EMM-accepted reference capture carries 26 lines in one instance of
 * it. Before indexing, a caller could name only ONE value for the whole
 * collection: {@code buildRepeatedLeaf} resolved a single string through the
 * value-source chain and returned {@code List.of(that)}, so 26 lines of a real
 * record were reproduced as 1. Measured against the reference, 88 of the 104
 * missing leaf values came from this one behaviour.</p>
 *
 * <p>The index form is not new syntax invented here - {@code buildRepeatedGroup}
 * already writes {@code path[i].child} for a repeated SEQUENCE/SET, so this
 * makes one path syntax address both kinds of collection.</p>
 *
 * <h2>What must NOT change</h2>
 *
 * <p>Every unindexed caller keeps its old behaviour exactly: a single value
 * still collapses the collection to one element, and no value at all still
 * falls through to the AI and random sources. The tests below pin both.</p>
 */
class IndexedRepeatedLeafTest {

    private final CdrRecordBuilder builder =
            new CdrRecordBuilder(null, new BcdTimestampFactory(), null, TestValueSources.chain());

    private AsnField repeatedLeaf(String name) {
        return AsnField.builder().fieldName(name).fieldType("IA5String")
                .repeated(true).tagNumber(1).tagClass(BerTagClass.CONTEXT).build();
    }

    private AsnField group(String name, AsnField... children) {
        return AsnField.builder().fieldName(name).fieldType("Group")
                .children(List.of(children)).tagNumber(2).tagClass(BerTagClass.CONTEXT).build();
    }

    @SuppressWarnings("unchecked")
    private List<String> valuesOf(Map<String, Object> record, String key) {
        return (List<String>) record.get(key);
    }

    // ---------------------------------------------------------------- old behaviour

    /**
     * The pre-existing contract: one unindexed value means a one-element
     * collection. Anything that changed this would break every caller that
     * already pins a repeated leaf.
     */
    @Test
    void anUnindexedValueStillCollapsesTheCollectionToOneElement() {
        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(repeatedLeaf("descriptions")),
                Map.of("descriptions", "a=sendrecv"));

        assertThat(valuesOf(record, "descriptions"))
                .as("an unindexed value must behave exactly as before")
                .containsExactly("\"a=sendrecv\"");
    }

    /**
     * With nothing supplied the chain still ends at RandomValueSource, which
     * answers every request - so the collection is generated, not empty.
     */
    @Test
    void noSuppliedValueStillFallsThroughToGeneratedElements() {
        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(repeatedLeaf("descriptions")), Map.of());

        assertThat(valuesOf(record, "descriptions"))
                .as("random fallback must keep producing 1..2 elements")
                .hasSizeBetween(1, 2)
                .allSatisfy(value -> assertThat(value).startsWith("\"").endsWith("\""));
    }

    // ---------------------------------------------------------------- indexed form

    @Test
    void indexedValuesProduceOneElementEachInOrder() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("descriptions[0]", "a=ptime:20");
        user.put("descriptions[1]", "a=maxptime:240");
        user.put("descriptions[2]", "a=sendrecv");

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(repeatedLeaf("descriptions")), user);

        assertThat(valuesOf(record, "descriptions"))
                .containsExactly("\"a=ptime:20\"", "\"a=maxptime:240\"", "\"a=sendrecv\"");
    }

    /**
     * The real shape from the reference capture: 26 SDP description lines in a
     * single {@code sDP-Media-Descriptions}. This is the case the change exists
     * for, so it is asserted at its real size rather than a token two or three.
     */
    @Test
    void twentySixIndexedValuesProduceTwentySixElements() {
        Map<String, String> user = new LinkedHashMap<>();
        for (int index = 0; index < 26; index++) {
            user.put("sDP-Media-Descriptions[" + index + "]", "a=rtpmap:" + index);
        }

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(repeatedLeaf("sDP-Media-Descriptions")), user);

        List<String> values = valuesOf(record, "sDP-Media-Descriptions");
        assertThat(values).hasSize(26);
        assertThat(values.get(0)).isEqualTo("\"a=rtpmap:0\"");
        assertThat(values.get(25)).isEqualTo("\"a=rtpmap:25\"");
    }

    /**
     * Indices run contiguously from zero. A gap ends the collection instead of
     * skipping an element, so the caller's numbering and the emitted order can
     * never disagree about which value is which.
     */
    @Test
    void aGapInTheIndicesEndsTheCollection() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("descriptions[0]", "a=ptime:20");
        user.put("descriptions[2]", "a=sendrecv");

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(repeatedLeaf("descriptions")), user);

        assertThat(valuesOf(record, "descriptions"))
                .as("[2] is unreachable while [1] is absent")
                .containsExactly("\"a=ptime:20\"");
    }

    /**
     * A repeated leaf that is not addressed at index 0 is not indexed at all,
     * and the unchanged chain-then-random path handles it.
     */
    @Test
    void anIndexedKeyThatDoesNotStartAtZeroIsIgnored() {
        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(repeatedLeaf("descriptions")),
                Map.of("descriptions[1]", "a=sendrecv"));

        assertThat(valuesOf(record, "descriptions"))
                .hasSizeBetween(1, 2)
                .noneMatch(value -> value.contains("a=sendrecv"));
    }

    // ---------------------------------------------------------------- path vs bare name

    /**
     * The full path wins over the bare field name, so two same-named leaves in
     * different branches can carry different collections - the case that made
     * {@code called-Party-Address} take the CALLING party's URI when only bare
     * names were available.
     */
    @Test
    void theFullPathIsPreferredOverTheBareFieldName() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("offer.lines[0]", "from the offer");
        user.put("lines[0]", "from the bare name");

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(group("offer", repeatedLeaf("lines"))), user);

        @SuppressWarnings("unchecked")
        Map<String, Object> offer = (Map<String, Object>) record.get("offer");
        assertThat(valuesOf(offer, "lines")).containsExactly("\"from the offer\"");
    }

    /**
     * The bare name still reaches a nested leaf when no path key is given -
     * the same convenience {@code UserProvidedValueSource} already offers for
     * unindexed values.
     */
    @Test
    void theBareFieldNameStillReachesANestedLeaf() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("lines[0]", "first");
        user.put("lines[1]", "second");

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(group("offer", repeatedLeaf("lines"))), user);

        @SuppressWarnings("unchecked")
        Map<String, Object> offer = (Map<String, Object>) record.get("offer");
        assertThat(valuesOf(offer, "lines")).containsExactly("\"first\"", "\"second\"");
    }

    /**
     * A series is read from one key space or the other, never spliced: a bare
     * name cannot supply element [1] of a collection whose [0] came from a path
     * key, because that would make the emitted order depend on which keys the
     * caller happened to mix.
     */
    @Test
    void pathAndBareNameSeriesAreNeverMixed() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("offer.lines[0]", "from the offer");
        user.put("lines[1]", "from the bare name");

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(group("offer", repeatedLeaf("lines"))), user);

        @SuppressWarnings("unchecked")
        Map<String, Object> offer = (Map<String, Object>) record.get("offer");
        assertThat(valuesOf(offer, "lines")).containsExactly("\"from the offer\"");
    }

    // ---------------------------------------------------------------- typing

    /**
     * Indexed values go through the same literal formatting as every other
     * value, so an INTEGER collection stays decimal rather than quoted.
     */
    @Test
    void indexedValuesKeepTypeAwareLiteralFormatting() {
        AsnField counters = AsnField.builder().fieldName("counters").fieldType("INTEGER")
                .repeated(true).tagNumber(1).tagClass(BerTagClass.CONTEXT).build();

        Map<String, String> user = new LinkedHashMap<>();
        user.put("counters[0]", "17");
        user.put("counters[1]", "42");

        Map<String, Object> record = builder.buildRecordFromFields(List.of(counters), user);

        assertThat(valuesOf(record, "counters")).containsExactly("'17'D", "'42'D");
    }

    // ================================================================
    // Repeated GROUP element count
    //
    // Indexed leaves alone are not enough: a value addressed at path[1]
    // has nowhere to land while the enclosing collection is built with a
    // random 1..2 elements. The reference record's
    // list-Of-SDP-Media-Components needs two instances and got one, which
    // silently dropped everything indexed under [1].
    // ================================================================

    private AsnField repeatedGroup(String name, AsnField... children) {
        return AsnField.builder().fieldName(name).fieldType("Group")
                .repeated(true).children(List.of(children))
                .tagNumber(3).tagClass(BerTagClass.CONTEXT).build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> groupsOf(Map<String, Object> record, String key) {
        return (List<Map<String, Object>>) record.get(key);
    }

    private AsnField scalarLeaf(String name) {
        return AsnField.builder().fieldName(name).fieldType("IA5String")
                .tagNumber(1).tagClass(BerTagClass.CONTEXT).build();
    }

    @Test
    void twoDescribedInstancesProduceTwoGroupElements() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("components[0].name", "offer");
        user.put("components[1].name", "answer");

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(repeatedGroup("components", scalarLeaf("name"))), user);

        List<Map<String, Object>> groups = groupsOf(record, "components");
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).get("name")).isEqualTo("\"offer\"");
        assertThat(groups.get(1).get("name")).isEqualTo("\"answer\"");
    }

    @Test
    void sixDescribedInstancesProduceSixGroupElements() {
        Map<String, String> user = new LinkedHashMap<>();
        for (int index = 0; index < 6; index++) {
            user.put("components[" + index + "].name", "c" + index);
        }

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(repeatedGroup("components", scalarLeaf("name"))), user);

        List<Map<String, Object>> groups = groupsOf(record, "components");
        assertThat(groups).hasSize(6);
        assertThat(groups.get(5).get("name")).isEqualTo("\"c5\"");
    }

    /**
     * The case the whole change exists for: a repeated leaf nested inside a
     * repeated group. Both counts have to come from the caller or the inner
     * values are unreachable.
     */
    @Test
    void anIndexedLeafInsideEachIndexedGroupInstanceSurvives() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("components[0].lines[0]", "v=0");
        user.put("components[0].lines[1]", "a=sendrecv");
        user.put("components[1].lines[0]", "v=1");

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(repeatedGroup("components", repeatedLeaf("lines"))), user);

        List<Map<String, Object>> groups = groupsOf(record, "components");
        assertThat(groups).hasSize(2);
        assertThat(valuesOf(groups.get(0), "lines")).containsExactly("\"v=0\"", "\"a=sendrecv\"");
        assertThat(valuesOf(groups.get(1), "lines")).containsExactly("\"v=1\"");
    }

    @Test
    void withoutIndexedInputTheGroupKeepsTheRandomFallbackCount() {
        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(repeatedGroup("components", scalarLeaf("name"))),
                Map.of("name", "same for every element"));

        assertThat(groupsOf(record, "components"))
                .as("random 1..2 must survive untouched when nothing is indexed")
                .hasSizeBetween(1, 2);
    }

    /**
     * A gap does NOT become an instance. Counting to the highest index would
     * emit a fully random element for the missing one - {@code buildFields}
     * fills every child from the chain - putting fabricated data between two
     * reference-exact records.
     */
    @Test
    void aGapLeavesTheLaterInstancesOutRatherThanFabricatingOne() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("components[0].name", "real");
        user.put("components[2].name", "unreachable");

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(repeatedGroup("components", scalarLeaf("name"))), user);

        List<Map<String, Object>> groups = groupsOf(record, "components");
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).get("name")).isEqualTo("\"real\"");
    }

    /**
     * Keys that describe no element zero index nothing at all, so the field
     * falls back to the random count instead of guessing what [3] meant.
     */
    @Test
    void instancesThatDoNotStartAtZeroAreIgnored() {
        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(repeatedGroup("components", scalarLeaf("name"))),
                Map.of("components[3].name", "unreachable"));

        assertThat(groupsOf(record, "components")).hasSizeBetween(1, 2);
    }

    /**
     * A repeated LEAF element key ({@code path[0]}, nothing after the index)
     * must not be mistaken for a group instance.
     */
    @Test
    void aRepeatedLeafKeyDoesNotInflateAGroupCount() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("lines[0]", "first");
        user.put("lines[1]", "second");
        user.put("lines[2]", "third");

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(repeatedLeaf("lines")), user);

        assertThat(valuesOf(record, "lines")).containsExactly("\"first\"", "\"second\"", "\"third\"");
    }

    /**
     * Two collections sharing a field name in different branches are counted
     * apart, because a group count is read from the full path only.
     */
    @Test
    void sameNamedGroupsInDifferentBranchesAreCountedApart() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("early.components[0].name", "one");
        user.put("early.components[1].name", "two");
        user.put("late.components[0].name", "only");

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(group("early", repeatedGroup("components", scalarLeaf("name"))),
                        group("late", repeatedGroup("components", scalarLeaf("name")))),
                user);

        @SuppressWarnings("unchecked")
        Map<String, Object> early = (Map<String, Object>) record.get("early");
        @SuppressWarnings("unchecked")
        Map<String, Object> late = (Map<String, Object>) record.get("late");

        assertThat(groupsOf(early, "components")).hasSize(2);
        assertThat(groupsOf(late, "components")).hasSize(1);
    }

    /**
     * CHOICE collections keep {@code CHOICE_ELEMENT_COUNT}. Two elements there
     * are the SAME alternative written twice - the literal duplicate tag EMM
     * rejected - so indexed input must not lift that cap.
     */
    @Test
    void aChoiceCollectionKeepsItsSingleElementCapDespiteIndexedInput() {
        AsnField choiceCollection = AsnField.builder()
                .fieldName("parties").fieldType("ListOfInvolvedParties")
                .repeated(true).choice(true)
                .children(List.of(scalarLeaf("sIP-URI")))
                .tagNumber(6).tagClass(BerTagClass.CONTEXT).build();

        Map<String, String> user = new LinkedHashMap<>();
        user.put("parties[0].sIP-URI", "sip:a@example.org");
        user.put("parties[1].sIP-URI", "sip:b@example.org");

        Map<String, Object> record = builder.buildRecordFromFields(List.of(choiceCollection), user);

        assertThat(groupsOf(record, "parties"))
                .as("CHOICE_ELEMENT_COUNT must hold regardless of indexed input")
                .hasSize(1);
    }

    // ---------------------------------------------------------------- through the encoder

    /**
     * The whole flow rather than the builder alone: fieldValues in, real BER
     * out, and the collection counted by reading the encoded TLVs back. This is
     * what proves the second instance actually reaches the wire - the builder
     * producing two maps would mean nothing if the encoder wrote one.
     */
    @Test
    void bothGroupInstancesReachTheEncodedBytes() {
        BerEncoderService encoder =
                new BerEncoderService(new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));

        List<AsnField> fields = List.of(repeatedGroup("components", repeatedLeaf("lines")));
        Map<String, String> user = new LinkedHashMap<>();
        user.put("components[0].lines[0]", "AAAA");
        user.put("components[0].lines[1]", "BBBB");
        user.put("components[1].lines[0]", "CCCC");

        Map<String, Object> record = builder.buildRecordFromFields(fields, user);
        byte[] encoded = encoder.encodeRecord(fields, record);
        String asText = new String(encoded, StandardCharsets.ISO_8859_1);

        assertThat(asText)
                .as("every indexed value must survive into the encoded record")
                .contains("AAAA").contains("BBBB").contains("CCCC");

        // encodeRepeated writes ONE outer TLV for the collection and puts each
        // element inside it as its own universal SEQUENCE - so the instance
        // count is read from the elements within [3], not from repeats of [3].
        byte[] recordBody = contentOf(encoded, 0);          // universal SEQUENCE
        byte[] components = contentOf(recordBody, 0);       // [3] collection
        assertThat((recordBody[0] & 0xFF))
                .as("the collection is written once, context-tagged [3] constructed")
                .isEqualTo(0xA3);
        assertThat(countTopLevelTlvs(components))
                .as("two described instances means two element TLVs inside [3]")
                .isEqualTo(2);
    }

    /** Content octets of the TLV that starts at {@code offset}. */
    private byte[] contentOf(byte[] buffer, int offset) {
        int cursor = offset + 1;
        int length = buffer[cursor++] & 0xFF;
        if ((length & 0x80) != 0) {
            int lengthBytes = length & 0x7F;
            length = 0;
            for (int i = 0; i < lengthBytes; i++) {
                length = (length << 8) | (buffer[cursor++] & 0xFF);
            }
        }
        byte[] content = new byte[length];
        System.arraycopy(buffer, cursor, content, 0, length);
        return content;
    }

    /** How many TLVs sit side by side in {@code buffer}. */
    private int countTopLevelTlvs(byte[] buffer) {
        int count = 0;
        int cursor = 0;
        while (cursor < buffer.length) {
            int start = cursor;
            cursor++;                                        // single-byte tags only here
            int length = buffer[cursor++] & 0xFF;
            if ((length & 0x80) != 0) {
                int lengthBytes = length & 0x7F;
                length = 0;
                for (int i = 0; i < lengthBytes; i++) {
                    length = (length << 8) | (buffer[cursor++] & 0xFF);
                }
            }
            cursor += length;
            if (cursor <= start) {
                break;
            }
            count++;
        }
        return count;
    }
}
