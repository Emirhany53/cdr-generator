package com.turkcell.cdrgenerator1.infrastructure.ai.gemini;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.model.AsnField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The response schema is what stops the model answering in prose.
 *
 * <p>It is sent as {@code responseSchema} alongside {@code responseMimeType:
 * application/json}, so the provider constrains the answer rather than the
 * prompt asking nicely for one. Everything is a STRING: the values travel as
 * text all the way to {@code FieldValueValidator}, which is where a value is
 * judged against its ASN.1 type. Declaring an INTEGER field as a JSON number
 * here would only move the parsing problem earlier and lose the leading zeros
 * a subscriber number needs.</p>
 */
class GeminiResponseSchemaFactoryTest {

    private final GeminiResponseSchemaFactory factory =
            new GeminiResponseSchemaFactory(new AsnSizeExtractor());

    private AsnField field(String name, String type) {
        return AsnField.builder().fieldName(name).fieldType(type).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> items(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("items");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Map<String, Object> schema) {
        Map<String, Object> items = (Map<String, Object>) schema.get("items");
        return (Map<String, Object>) items.get("properties");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> property(Map<String, Object> schema, String fieldName) {
        return (Map<String, Object>) properties(schema).get(fieldName);
    }

    /** One request asks for many records, so the root has to be a list. */
    @Test
    void theRootIsAnArrayOfObjects() {
        Map<String, Object> schema = factory.create(List.of(field("duration", "INTEGER")));

        assertThat(schema).containsEntry("type", "ARRAY");
        assertThat(schema.get("items")).isInstanceOf(Map.class);
        assertThat(items(schema)).containsEntry("type", "OBJECT");
    }

    @Test
    void everyFieldBecomesAStringProperty() {
        Map<String, Object> schema = factory.create(
                List.of(field("duration", "INTEGER"), field("msisdn", "OCTET STRING")));

        assertThat(properties(schema)).containsOnlyKeys("duration", "msisdn");
        assertThat(property(schema, "duration")).containsEntry("type", "STRING");
        assertThat(property(schema, "msisdn")).containsEntry("type", "STRING");
    }

    /**
     * A partial record would leave the caller unable to tell "the model skipped
     * this" from "this field is genuinely absent", so every field is required.
     */
    @Test
    void everyFieldIsRequired() {
        Map<String, Object> schema = factory.create(
                List.of(field("a", "INTEGER"), field("b", "IA5String")));

        assertThat(items(schema)).containsEntry("required", List.of("a", "b"));
    }

    /**
     * {@code AiRecordSupplier} renames a leaf to its full path before asking, so
     * that {@code AiValueSource} can match the answer back to the right node.
     * Those dots have to survive into the schema keys unchanged.
     */
    @Test
    void dottedPathsAreUsedAsPropertyKeys() {
        Map<String, Object> schema = factory.create(
                List.of(field("mMTelInformation.subscriberRole", "ENUMERATED")));

        assertThat(properties(schema)).containsKey("mMTelInformation.subscriberRole");
    }

    @Test
    void aSizeConstraintBecomesALengthDescription() {
        Map<String, Object> schema = factory.create(List.of(field("userName", "IA5String (SIZE(12))")));

        assertThat(property(schema, "userName"))
                .hasEntrySatisfying("description",
                        description -> assertThat(description.toString()).contains("12"));
    }

    @Test
    void aSizeRangeUsesItsUpperBound() {
        Map<String, Object> schema = factory.create(List.of(field("apn", "IA5String (SIZE(2..40))")));

        assertThat(property(schema, "apn"))
                .hasEntrySatisfying("description",
                        description -> assertThat(description.toString()).contains("40"));
    }

    @Test
    void aFieldWithNoSizeConstraintGetsNoDescription() {
        Map<String, Object> schema = factory.create(List.of(field("duration", "INTEGER")));

        assertThat(property(schema, "duration")).doesNotContainKey("description");
    }

    /**
     * The schema states an OCTET STRING's SIZE in BYTES, unlike the prompt which
     * doubles it into hex characters. The two disagree on purpose: this hint goes
     * to the provider's own validator, and the prompt's goes to the model that
     * writes the hex. Anything that "harmonised" them would reintroduce the
     * byte/character confusion the prompt hint exists to fix.
     */
    @Test
    void anOctetStringDescriptionStatesTheByteCountUnlikeThePrompt() {
        Map<String, Object> schema = factory.create(List.of(field("payload", "OCTET STRING (SIZE(8))")));

        assertThat(property(schema, "payload"))
                .hasEntrySatisfying("description",
                        description -> assertThat(description.toString()).contains("8").doesNotContain("16"));
    }

    @Test
    void anEmptyFieldListStillProducesAValidSchema() {
        Map<String, Object> schema = factory.create(List.of());

        assertThat(schema).containsEntry("type", "ARRAY");
        assertThat(properties(schema)).isEmpty();
        assertThat(items(schema)).containsEntry("required", List.of());
    }
}
