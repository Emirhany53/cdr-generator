package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

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
}
