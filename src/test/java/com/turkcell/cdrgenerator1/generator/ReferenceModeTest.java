package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reference mode: an OPTIONAL field the caller never described is not generated.
 *
 * <h2>Why</h2>
 *
 * <p>Ordinary generation fills every leaf the resolved tree offers -
 * {@code isOptional()} is read nowhere except the implicit-CHOICE skip, and
 * {@code RandomValueSource} answers every request, so nothing ever comes back
 * empty. Reproducing Yasin's MMTel reference that way produced 144 leaf values
 * the real record does not contain, entire invented subtrees among them
 * ({@code recordExtensions.iN-AMA-Extension}, {@code cTPullInformation},
 * {@code enhancedPhoneFeatures1}). For a record meant to reproduce a reference,
 * a field the reference does not carry should stay silent.</p>
 *
 * <h2>What must not change</h2>
 *
 * <p>The flag defaults to false and every existing caller gets that. With it
 * off the walk behaves exactly as before - the tests below pin the whole
 * record, not a sample of it - and the indexed repeated behaviour from KN-1
 * keeps working in both modes.</p>
 */
class ReferenceModeTest {

    private final CdrRecordBuilder builder =
            new CdrRecordBuilder(null, new BcdTimestampFactory(), null, TestValueSources.chain());

    private AsnField optionalLeaf(String name) {
        return AsnField.builder().fieldName(name).fieldType("IA5String")
                .optional(true).tagNumber(1).tagClass(BerTagClass.CONTEXT).build();
    }

    private AsnField mandatoryLeaf(String name) {
        return AsnField.builder().fieldName(name).fieldType("IA5String")
                .optional(false).tagNumber(2).tagClass(BerTagClass.CONTEXT).build();
    }

    private AsnField optionalGroup(String name, AsnField... children) {
        return AsnField.builder().fieldName(name).fieldType("Group")
                .optional(true).children(List.of(children))
                .tagNumber(3).tagClass(BerTagClass.CONTEXT).build();
    }

    private AsnField optionalRepeatedLeaf(String name) {
        return AsnField.builder().fieldName(name).fieldType("IA5String")
                .optional(true).repeated(true).tagNumber(4).tagClass(BerTagClass.CONTEXT).build();
    }

    private AsnField optionalRepeatedGroup(String name, AsnField... children) {
        return AsnField.builder().fieldName(name).fieldType("Group")
                .optional(true).repeated(true).children(List.of(children))
                .tagNumber(5).tagClass(BerTagClass.CONTEXT).build();
    }

    private Map<String, Object> build(List<AsnField> fields, Map<String, String> user, boolean referenceMode) {
        return builder.buildRecordFromFields(fields, 0, user, List.of(), referenceMode);
    }

    // ---------------------------------------------------------------- 1. mode off

    @Test
    void withReferenceModeOffEveryOptionalFieldIsStillGenerated() {
        List<AsnField> fields = List.of(
                mandatoryLeaf("required"), optionalLeaf("described"), optionalLeaf("undescribed"));

        Map<String, Object> record = build(fields, Map.of("described", "value"), false);

        assertThat(record).containsKeys("required", "described", "undescribed");
        assertThat(record.get("undescribed"))
                .as("the old behaviour fills every optional field")
                .isNotNull();
    }

    /**
     * The four-argument overload every existing caller uses must land in the
     * same place as passing false explicitly.
     */
    @Test
    void theExistingOverloadKeepsReferenceModeOff() {
        List<AsnField> fields = List.of(optionalLeaf("undescribed"));

        Map<String, Object> record = builder.buildRecordFromFields(fields, 0, Map.of(), List.of());

        assertThat(record).containsKey("undescribed");
        assertThat(record.get("undescribed")).isNotNull();
    }

    // ---------------------------------------------------------------- 2. undescribed optionals

    @Test
    void anUndescribedOptionalIsOmittedInReferenceMode() {
        List<AsnField> fields = List.of(optionalLeaf("described"), optionalLeaf("undescribed"));

        Map<String, Object> record = build(fields, Map.of("described", "value"), true);

        assertThat(record).containsKey("described");
        assertThat(record)
                .as("nothing described it, so it is not in the record at all")
                .doesNotContainKey("undescribed");
    }

    /**
     * A whole subtree disappears when nothing inside it was described - the
     * shape that accounted for most of the noise against the MMTel reference.
     */
    @Test
    void anEntireUndescribedOptionalSubtreeIsOmitted() {
        List<AsnField> fields = List.of(
                optionalGroup("extensions", optionalLeaf("a"), optionalLeaf("b")),
                optionalGroup("other", optionalLeaf("c")));

        Map<String, Object> record = build(fields, Map.of("extensions.a", "kept"), true);

        assertThat(record).containsKey("extensions");
        assertThat(record).doesNotContainKey("other");

        @SuppressWarnings("unchecked")
        Map<String, Object> extensions = (Map<String, Object>) record.get("extensions");
        assertThat(extensions).containsKey("a").doesNotContainKey("b");
    }

    /**
     * A container survives on the strength of its children: a flattened
     * reference names leaves, never the group that holds them.
     */
    @Test
    void aContainerSurvivesBecauseOneOfItsLeavesWasDescribed() {
        List<AsnField> fields = List.of(optionalGroup("extensions", optionalLeaf("nodeId")));

        Map<String, Object> record = build(fields, Map.of("extensions.nodeId", "vIMS"), true);

        @SuppressWarnings("unchecked")
        Map<String, Object> extensions = (Map<String, Object>) record.get("extensions");
        assertThat(extensions.get("nodeId")).isEqualTo("\"vIMS\"");
    }

    // ---------------------------------------------------------------- 3. mandatory fields

    @Test
    void aMandatoryFieldIsGeneratedEvenWhenNobodyDescribedIt() {
        List<AsnField> fields = List.of(mandatoryLeaf("required"), optionalLeaf("optional"));

        Map<String, Object> record = build(fields, Map.of(), true);

        assertThat(record)
                .as("dropping a required component makes the record invalid, not smaller")
                .containsKey("required");
        assertThat(record.get("required")).isNotNull();
        assertThat(record).doesNotContainKey("optional");
    }

    // ---------------------------------------------------------------- 4. described optionals

    @Test
    void anOptionalDescribedByBareNameSurvives() {
        List<AsnField> fields = List.of(optionalGroup("wrapper", optionalLeaf("inner")));

        Map<String, Object> record = build(fields, Map.of("inner", "by bare name"), true);

        @SuppressWarnings("unchecked")
        Map<String, Object> wrapper = (Map<String, Object>) record.get("wrapper");
        assertThat(wrapper.get("inner"))
                .as("UserProvidedValueSource accepts a bare name, so reference mode must too")
                .isEqualTo("\"by bare name\"");
    }

    // ---------------------------------------------------------------- 5. indexed repeated leaf

    @Test
    void indexedRepeatedLeavesStillWorkInReferenceMode() {
        List<AsnField> fields = List.of(optionalRepeatedLeaf("lines"), optionalRepeatedLeaf("dropped"));

        Map<String, String> user = new LinkedHashMap<>();
        user.put("lines[0]", "v=0");
        user.put("lines[1]", "a=sendrecv");
        user.put("lines[2]", "b=AS:80");

        Map<String, Object> record = build(fields, user, true);

        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) record.get("lines");
        assertThat(lines).containsExactly("\"v=0\"", "\"a=sendrecv\"", "\"b=AS:80\"");
        assertThat(record)
                .as("an undescribed repeated leaf goes the way of any other optional")
                .doesNotContainKey("dropped");
    }

    // ---------------------------------------------------------------- 6. indexed repeated group

    @Test
    void indexedRepeatedGroupCountsStillWorkInReferenceMode() {
        List<AsnField> fields = List.of(
                optionalRepeatedGroup("components", optionalRepeatedLeaf("lines"), optionalLeaf("unused")));

        Map<String, String> user = new LinkedHashMap<>();
        user.put("components[0].lines[0]", "first");
        user.put("components[1].lines[0]", "second");
        user.put("components[1].lines[1]", "third");

        Map<String, Object> record = build(fields, user, true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> components = (List<Map<String, Object>>) record.get("components");
        assertThat(components).as("KN-1's group count is untouched by reference mode").hasSize(2);

        @SuppressWarnings("unchecked")
        List<String> firstLines = (List<String>) components.get(0).get("lines");
        @SuppressWarnings("unchecked")
        List<String> secondLines = (List<String>) components.get(1).get("lines");
        assertThat(firstLines).containsExactly("\"first\"");
        assertThat(secondLines).containsExactly("\"second\"", "\"third\"");

        assertThat(components.get(0))
                .as("an optional sibling nobody described is dropped inside each instance too")
                .doesNotContainKey("unused");
    }

    /**
     * Reference mode must not resurrect a field the implicit-CHOICE skip
     * removes, nor drop one that skip already handled - the two rules are
     * independent and both run.
     */
    @Test
    void referenceModeDoesNotDisturbFieldsWithNoUserValuesAtAll() {
        List<AsnField> fields = List.of(mandatoryLeaf("required"));

        Map<String, Object> withNullMap = build(fields, null, true);

        assertThat(withNullMap)
                .as("a null value map means nothing was described; required still stands")
                .containsKey("required");
    }
}
