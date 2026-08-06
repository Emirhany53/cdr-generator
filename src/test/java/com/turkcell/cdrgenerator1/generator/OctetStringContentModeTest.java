package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.generator.source.AiValueSource;
import com.turkcell.cdrgenerator1.generator.source.ValueSourceContext;
import com.turkcell.cdrgenerator1.generator.validation.FieldValueValidator;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code octet-string-content} ayarinin TEK dogruluk kaynagi oldugunu sabitler.
 *
 * <p>Bir OCTET STRING'in icerigini iki ayri yol doldurabilir: AI degeri
 * ({@link AiValueSource}) ve rastgele uretim ({@link FieldValueGenerator}).
 * Ikisi ayni ayari okumazsa ayni alan, degerin nereden geldigine gore farkli
 * kodlanir - kural "binary" derken uretici ASCII metin yazabilir. Bu test iki
 * yolun ayni bicimi urettigini dogrular.</p>
 */
class OctetStringContentModeTest {

    private final AsnSizeExtractor sizeExtractor = new AsnSizeExtractor();
    private final TbcdCodec tbcdCodec = new TbcdCodec();
    private final BcdTimestampFactory bcdTimestampFactory = new BcdTimestampFactory();

    private AiConfigProperties propertiesWith(String ruleName, String match,
                                              String content, List<String> examples) {
        AiConfigProperties properties = new AiConfigProperties();
        AiConfigProperties.FieldRule rule = new AiConfigProperties.FieldRule();
        rule.setName(ruleName);
        rule.setMatch(List.of(match));
        rule.setPattern("^[0-9a-zA-Z:+@._/ -]{1,60}$");
        rule.setExamples(examples);
        rule.setOctetStringContent(content);
        properties.setFieldRules(List.of(rule));
        return properties;
    }

    private AsnField leaf(String name, String type) {
        return AsnField.builder().fieldName(name).fieldType(type)
                .tagNumber(1).tagClass(BerTagClass.CONTEXT).build();
    }

    private Optional<String> throughAi(AiConfigProperties properties, AsnField field, String aiValue) {
        AiValueSource source = new AiValueSource(
                new FieldValueValidator(tbcdCodec, properties, sizeExtractor, bcdTimestampFactory),
                new EnumValueResolver(), properties);
        return source.resolve(
                new ValueSourceContext("S", 0, Map.of(),
                        List.of(Map.of(field.getFieldName(), aiValue))).withCurrentPath(field.getFieldName()),
                field);
    }

    private String throughRandom(AiConfigProperties properties, AsnField field) {
        return new FieldValueGenerator(tbcdCodec, properties, sizeExtractor, bcdTimestampFactory)
                .generate(field);
    }

    @Test
    void binaryContentKeepsTheHexDumpOnBothPaths() {
        AiConfigProperties properties =
                propertiesWith("cellId", "locationarea", "binary", List.of("12345a"));
        AsnField field = leaf("locationAreaId", "OCTET STRING");

        // Before the fix, normalize() sent every OCTET STRING through
        // resolveToNumber, which returns empty for anything non-numeric - so a
        // perfectly good hex dump containing a-f was thrown away.
        assertEquals(Optional.of("12345a"), throughAi(properties, field, "12345a"),
                "a hex value with letters must survive the AI path");
        assertEquals("12345a", throughRandom(properties, field),
                "the random path must emit the example as a hex dump, not as ASCII text");
    }

    @Test
    void textContentEncodesAsAsciiBytesOnBothPaths() {
        AiConfigProperties properties = propertiesWith(
                "userLocationInformation", "userlocationinformation", "text", List.of("81821600d0eaef0d"));
        AsnField field = leaf("userLocationInformation", "OCTET STRING");

        // "81821600d0eaef0d" as ASCII bytes - the shape the EMM-accepted
        // reference capture carries for this field.
        String expected = "38313832313630306430656165663064";
        assertEquals(Optional.of(expected), throughAi(properties, field, "81821600d0eaef0d"),
                "a text-content field must reach the encoder as its ASCII bytes");
        assertEquals(expected, throughRandom(properties, field),
                "both paths must agree on the encoding");
    }

    /**
     * A rule's examples are written per RULE, not per field, so they can be
     * shorter than a particular field's fixed SIZE. The encoder only pads STRING
     * fields, so an OCTET STRING would otherwise go out under-length.
     */
    @Test
    void aFixedSizeFieldIsFilledToItsExactWidth() {
        AiConfigProperties properties =
                propertiesWith("cellId", "locationrout", "binary", List.of("12345a"));
        AsnField fixedFive = leaf("locationRoutNum", "OCTET STRING (SIZE (5))");

        // 3 bytes of example, zero-padded up to the declared 5 bytes.
        assertEquals("12345a0000", throughRandom(properties, fixedFive),
                "a SIZE(5) octet string must carry exactly five bytes");
    }

    @Test
    void withoutTheSettingTheShapeStillDecides() {
        AiConfigProperties properties =
                propertiesWith("hostName", "hostname", null, List.of("host01.turkcell.com.tr"));
        AsnField unconstrained = leaf("hostName", "OCTET STRING");
        // Printable and unconstrained: written as ASCII, as before this change.
        assertTrue(throughRandom(properties, unconstrained).startsWith("686F7374"),
                "an unclassified printable example still becomes ASCII bytes");
    }
}
