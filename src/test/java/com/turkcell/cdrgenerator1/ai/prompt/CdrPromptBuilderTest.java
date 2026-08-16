package com.turkcell.cdrgenerator1.ai.prompt;

import com.turkcell.cdrgenerator1.ai.model.AiGenerationRequest;
import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.generator.BcdTimestampFactory;
import com.turkcell.cdrgenerator1.generator.TbcdCodec;
import com.turkcell.cdrgenerator1.model.AsnField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt is the only thing standing between an ASN.1 field and a model that
 * has never seen this schema, and every hint in it was added because a generated
 * value came back wrong. These tests pin the hints that cost a debugging round.
 *
 * <p>Two of them are unit confusions that produced values exactly half or twice
 * the required length - the signature of a byte count stated as a character
 * count. A third is a rule/instruction conflict where the model followed the
 * more concrete of two contradictory demands.</p>
 */
class CdrPromptBuilderTest {

    private final AiConfigProperties properties = properties();
    private final CdrPromptBuilder builder = new CdrPromptBuilder(
            properties, new AsnSizeExtractor(), new BcdTimestampFactory(), new TbcdCodec());

    private static AiConfigProperties properties() {
        AiConfigProperties properties = new AiConfigProperties();
        AiConfigProperties.FieldRule msisdn = new AiConfigProperties.FieldRule();
        msisdn.setName("callingNumber");
        msisdn.setMatch(List.of("msisdn", "callingnumber"));
        msisdn.setDescription("Turkiye GSM abone numarasi");
        msisdn.setPattern("^0?5[0-9]{9}$");
        msisdn.setExamples(List.of("05301234567", "05559876543"));

        AiConfigProperties.FieldRule apn = new AiConfigProperties.FieldRule();
        apn.setName("accessPointName");
        apn.setMatch(List.of("accesspointname"));
        apn.setDescription("Erisim noktasi adi");
        apn.setPattern("^[a-z.]+$");
        apn.setExamples(List.of("internet"));
        apn.setOctetStringContent("text");

        properties.setFieldRules(List.of(msisdn, apn));
        return properties;
    }

    private AsnField field(String name, String type) {
        return AsnField.builder().fieldName(name).fieldType(type).build();
    }

    private String promptFor(AsnField... fields) {
        return builder.build(AiGenerationRequest.builder()
                .structureName("Demo")
                .fieldsToFill(List.of(fields))
                .recordCount(3)
                .build());
    }

    @Test
    void thePromptNamesTheStructureAndTheRecordCount() {
        String prompt = promptFor(field("duration", "INTEGER"));

        assertThat(prompt).contains("Demo").contains("Uretilecek kayit sayisi: 3");
    }

    @Test
    void everyFieldIsListedWithItsType() {
        String prompt = promptFor(field("duration", "INTEGER"), field("cause", "ENUMERATED"));

        assertThat(prompt).contains("duration").contains("INTEGER")
                .contains("cause").contains("ENUMERATED");
    }

    @Test
    void aDottedPathIsCarriedThroughUnchanged() {
        // AiRecordSupplier renames a leaf to its full path so the answer can be
        // matched back; anything that shortened it here would break that match.
        String prompt = promptFor(field("addr.msisdn", "IA5String"));

        assertThat(prompt).contains("addr.msisdn");
    }

    /** A character string's SIZE counts characters, so the hint is stated as-is. */
    @Test
    void aCharacterStringLengthHintStaysInCharacters() {
        String prompt = promptFor(field("userName", "IA5String (SIZE(12))"));

        assertThat(prompt).contains("azami uzunluk: 12 karakter");
    }

    /**
     * An OCTET STRING's SIZE counts BYTES while the value travels as a hex dump -
     * two characters per byte. Stating the byte count as the character count is
     * what made {@code servedIMEISV} (SIZE(8)) come back at 4 bytes and
     * {@code otherParty} (SIZE(12)) at 6, every time: exactly half.
     */
    @Test
    void anOctetStringLengthHintIsDoubledIntoHexCharacters() {
        String prompt = promptFor(field("payload", "OCTET STRING (SIZE(8))"));

        assertThat(prompt).contains("azami uzunluk: 16 karakter");
    }

    @Test
    void aPlainOctetStringIsToldToReturnHexOnly() {
        String prompt = promptFor(field("payload", "OCTET STRING (SIZE(4))"));

        assertThat(prompt).contains("SADECE hex").contains("cift sayida karakter");
    }

    /**
     * A field declared as carrying text is converted to ASCII-hex behind the
     * model's back, so telling it to answer in hex would double-encode.
     */
    @Test
    void aTextContentOctetStringIsNotToldToReturnHex() {
        String prompt = promptFor(field("accessPointName", "OCTET STRING (SIZE(32))"));

        assertThat(prompt).doesNotContain("SADECE hex");
    }

    /**
     * {@code Milliseconds ::= INTEGER (0..999)} carries no SIZE clause at all.
     * Without the range, {@code serviceRequestTimeStampFraction} matched a rule
     * on the word "timestamp" and came back as a full 14-digit timestamp.
     */
    @Test
    void anIntegerRangeIsStatedAndOverridesTheOtherHints() {
        String prompt = promptFor(field("fraction", "INTEGER (0..999)"));

        assertThat(prompt).contains("0..999").contains("CELISSE BILE");
    }

    @Test
    void aBcdTimestampFieldIsGivenItsExactShapeAndAnExample() {
        String prompt = promptFor(field("recordOpeningTime", "OCTET STRING (SIZE(9))"));

        assertThat(prompt).contains("BCD zaman damgasidir").contains("18 hex karakter");
    }

    /**
     * A TBCD field's rule describes the value BEFORE nibble-swapping. Told only
     * "must be hex" and given a plain-digit regex, the model produced ASCII-hex -
     * it followed the concrete regex over the abstract packing instruction.
     */
    @Test
    void aTbcdFieldIsToldItsRuleDescribesTheValueBeforePacking() {
        String prompt = promptFor(field("servedMSISDN", "OCTET STRING (SIZE(9))"));

        assertThat(prompt).contains("TBCD").contains("CEVRILMEDEN ONCEKI")
                .contains("duz halin regex'i");
    }

    @Test
    void aMatchingRuleContributesItsDescriptionPatternAndExamples() {
        String prompt = promptFor(field("callingNumber", "IA5String"));

        assertThat(prompt).contains("Turkiye GSM abone numarasi")
                .contains("^0?5[0-9]{9}$")
                .contains("05301234567");
    }

    /**
     * Asserted on the field's own line rather than on the whole prompt: the
     * closing rules speak about regexes in general ("Regex verilen alanlar
     * MUTLAKA o regex ile eslesmelidir"), so a prompt-wide search would match
     * text that has nothing to do with this field.
     */
    @Test
    void aFieldWithNoMatchingRuleGetsNoRuleText() {
        String line = promptFor(field("someUnknownCounter", "INTEGER")).lines()
                .filter(candidate -> candidate.startsWith("- someUnknownCounter"))
                .findFirst()
                .orElseThrow();

        assertThat(line)
                .isEqualTo("- someUnknownCounter (tip: INTEGER)")
                .doesNotContain("aciklama:")
                .doesNotContain("regex");
    }

    @Test
    void fixedValuesAreListedAndMarkedAsNotToBeGenerated() {
        String prompt = builder.build(AiGenerationRequest.builder()
                .structureName("Demo")
                .fieldsToFill(List.of(field("duration", "INTEGER")))
                .fixedValues(Map.of("callingNumber", "05301234567"))
                .recordCount(1)
                .build());

        assertThat(prompt).contains("SABITLEDI")
                .contains("callingNumber = 05301234567");
    }

    @Test
    void noFixedValuesMeansNoFixedValuesSection() {
        assertThat(promptFor(field("duration", "INTEGER"))).doesNotContain("SABITLEDI");
    }

    @Test
    void theClosingRulesDemandDistinctRecordsAndBareJson() {
        String prompt = promptFor(field("duration", "INTEGER"));

        assertThat(prompt).contains("Kayitlar birbirinden farkli olmalidir")
                .contains("yalnizca JSON dizisi");
    }
}
