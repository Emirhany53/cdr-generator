package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uretim kurallari artik application.yml'de tutuldugu icin testler kurallari
 * programatik olarak kurar. Kural yoksa uretim yalnizca ASN.1 tipine ve
 * SIZE kisitina bakar.
 */
class FieldValueGeneratorTest {

    private static final String MSISDN_PATTERN = "^(\\+?90|0)?5(0|3|4|5|6)[0-9]{8}$";

    private FieldValueGenerator generator;

    @BeforeEach
    void setUp() {
        AiConfigProperties properties = new AiConfigProperties();
        properties.setFieldRules(List.of(
                rule("callingNumber", List.of("msisdn", "anumber"), MSISDN_PATTERN,
                        List.of("05301234567", "905343545097")),
                rule("duration", List.of("duration"), "^[0-9]{1,6}$",
                        List.of("15", "127", "842"))));

        generator = new FieldValueGenerator(
                new TbcdCodec(), properties, new AsnSizeExtractor(), new BcdTimestampFactory());
    }

    private AiConfigProperties.FieldRule rule(String name, List<String> match,
                                              String pattern, List<String> examples) {
        AiConfigProperties.FieldRule fieldRule = new AiConfigProperties.FieldRule();
        fieldRule.setName(name);
        fieldRule.setMatch(match);
        fieldRule.setPattern(pattern);
        fieldRule.setExamples(examples);
        return fieldRule;
    }

    private AsnField field(String name, String type) {
        return AsnField.builder().fieldName(name).fieldType(type)
                .tagNumber(1).tagClass(BerTagClass.CONTEXT).build();
    }

    @Test
    void ruleMatchedFieldsUseRuleExamples() {
        for (String name : new String[] { "msisdn", "aNumber", "servedMsisdn" }) {
            String value = generator.generate(field(name, "IA5STRING"));
            assertTrue(value.matches(MSISDN_PATTERN),
                    name + " kural ornegine uymali, uretilen: " + value);
        }
    }

    @Test
    void integerTypeProducesNumber() {
        String value = generator.generate(field("someField", "INTEGER"));
        assertTrue(value.matches("\\d+"));
    }

    /**
     * The number of decimal digits is not a usable proxy for BER width: 9053435
     * has 7 digits - the most a 3-byte signed integer can show - yet it is above
     * 8388607, so BER needs a fourth byte and SIZE(3) is broken. Seen live in a
     * CBSiso record, where callingPartySType (INTEGER SIZE(3)) came out as
     * {@code 02 04 00 8A 24 FB}.
     */
    @Test
    void integerSeededFromARuleIsBroughtWithinTheRangeItsSizeCanHold() {
        // 'msisdn' matches the callingNumber rule, so the field is seeded with a
        // 11-12 digit phone number - far past what SIZE(3) can hold.
        for (int attempt = 0; attempt < 200; attempt++) {
            String value = generator.generate(field("msisdn", "INTEGER (SIZE(3) CODE(\"DEC\"))"));
            long parsed = Long.parseLong(value);
            assertTrue(parsed >= -8_388_608L && parsed <= 8_388_607L,
                    "SIZE(3) holds -8388608..8388607, generated: " + value);
        }
    }

    @Test
    void unconstrainedIntegerFallbackAlsoRespectsSize() {
        // No rule matches 'someCounter', so this exercises the random fallback,
        // which draws from 0..99999 and overflows anything narrower than 3 bytes.
        for (int attempt = 0; attempt < 200; attempt++) {
            String value = generator.generate(field("someCounter", "INTEGER (SIZE(1))"));
            long parsed = Long.parseLong(value);
            assertTrue(parsed >= -128L && parsed <= 127L,
                    "SIZE(1) holds -128..127, generated: " + value);
        }
    }

    @Test
    void integerAlreadyInsideItsSizeIsLeftUntouched() {
        // The duration rule seeds 15/127/842, all of which fit SIZE(10) already,
        // so the folding must not rewrite them into something else.
        for (int attempt = 0; attempt < 50; attempt++) {
            String value = generator.generate(field("duration", "INTEGER (SIZE(10) CODE(\"DEC\"))"));
            assertTrue(value.matches("\\d{1,3}"),
                    "a value that already fits must pass through unchanged, got: " + value);
        }
    }

    @Test
    void booleanTypeProducesZeroOrOne() {
        String value = generator.generate(field("someField", "BOOLEAN"));
        assertTrue(value.equals("0") || value.equals("1"));
    }

    @Test
    void enumeratedTypeProducesEncodableInteger() {
        String value = generator.generate(field("recordType", "ENUMERATED { threegpp, nin }"));
        assertTrue(value.matches("\\d+"), "ENUMERATED tam sayi olmali: " + value);
    }

    @Test
    void stringTypesProduceAlphanumericText() {
        for (String type : new String[] { "IA5String", "PrintableString", "WeirdCustomType" }) {
            String value = generator.generate(field("someField", type));
            assertEquals(8, value.length());
            assertTrue(value.matches("[A-Z0-9]+"), type + " -> " + value);
        }
    }

    @Test
    void octetStringProducesHexDump() {
        String value = generator.generate(field("someField", "OCTET STRING"));
        assertTrue(value.matches("([0-9A-F]{2})+"), "OCTET STRING hex olmali: " + value);
    }

    @Test
    void sizeConstraintLimitsGeneratedLength() {
        String value = generator.generate(field("someField", "IA5STRING (SIZE(4))"));
        assertEquals(4, value.length(), "SIZE(4) asilmamali: " + value);
    }

    @Test
    void nullTypeIsTreatedAsString() {
        String value = generator.generate(field("someField", null));
        assertEquals(8, value.length());
        assertTrue(value.matches("[A-Z0-9]+"));
    }

    /**
     * Regresyon: kural eslesmesi alan adinin bir parcasina bakiyordu ve
     * 'transactionCurrency' gibi alanlar 'tac' yuzunden yanlis kurala takiliyordu.
     * Artik kelime siniri kullaniliyor.
     */
    @Test
    void ruleDoesNotMatchOnPartialWord() {
        String value = generator.generate(field("transactionDuration", "IA5STRING"));
        assertTrue(value.matches("^[0-9]{1,6}$"),
                "duration kelimesi eslesmeli: " + value);

        String unrelated = generator.generate(field("productionLine", "IA5STRING"));
        assertEquals(8, unrelated.length(), "kural eslesmemeli, jenerik uretim beklenir");
    }

    /**
     * Regresyon: metin ornekli bir kural INTEGER alana carparsa BER kodlamasi
     * BerEncodingException firlatiyordu. Tip uyumsuz kurallar yok sayilmali.
     */
    @Test
    void typeIncompatibleRuleIsIgnored() {
        String value = generator.generate(field("msisdnCount", "INTEGER"));
        assertTrue(value.matches("\\d+"), "INTEGER alan sayi almali: " + value);
    }

    /**
     * Adlandirilmis sayi listesi olan bir tip YALNIZCA listedeki sayilari kabul
     * eder. Uretici bu listeyi hic okumuyor, her INTEGER/ENUMERATED alani
     * {@code nextInt(100_000)} ile dolduruyordu; uretilen bir MMTel kaydinda
     * {@code epf1-Role-of-Node = 85038} cikiyordu, oysa
     * {@code Epf1RoleType ::= INTEGER {native(0) ... cmfbMember(7)}} 0..7'ye izin
     * verir. Tek kayitta 37 adlandirilmis-sayi alaninin 27'si sema disiydi;
     * EMM'in kabul ettigi referansta ayni alanlarin 26912/26912'si gecerli.
     */
    @Test
    void aNamedNumberTypeOnlyProducesDeclaredValues() {
        String type = "INTEGER{native(0),barred(1),asserted(2),unbarred(3),"
                + "origCldPN(4),hGMember(5),mSIMMember(6),cmfbMember(7)}";
        for (int attempt = 0; attempt < 200; attempt++) {
            String value = generator.generate(field("epf1-Role-of-Node", type));
            int number = Integer.parseInt(value);
            assertTrue(number >= 0 && number <= 7,
                    "sema disi deger uretildi: " + number);
        }
    }

    /** ENUMERATED govdeleri de ayni bicimde tasinir, ayni kural gecerlidir. */
    @Test
    void anEnumeratedTypeOnlyProducesDeclaredValues() {
        for (int attempt = 0; attempt < 100; attempt++) {
            assertEquals("6", generator.generate(field("sSType", "ENUMERATED{cDiversion(6)}")),
                    "tek gecerli degeri olan bir ENUMERATED baska deger uretemez");
        }
        for (int attempt = 0; attempt < 100; attempt++) {
            String value = generator.generate(field("direction", "ENUMERATED{forward(0),backward(1)}"));
            assertTrue(List.of("0", "1").contains(value), "sema disi deger: " + value);
        }
    }

    /** Listedeki sayilar bitisik olmak zorunda degil - 1040..1043 gibi bosluklu degerler de gecerli. */
    @Test
    void nonContiguousDeclaredValuesAreProducedAsDeclared() {
        String type = "INTEGER{c2CwoAssertion(0),c2CwAssertion(1),pull-transferredOut(1040),"
                + "push-transferredIn(1041),push-transferredOut(1042),pull-transferredIn(1043)}";
        List<String> allowed = List.of("0", "1", "1040", "1041", "1042", "1043");
        for (int attempt = 0; attempt < 200; attempt++) {
            String value = generator.generate(field("epf1-ServiceMode", type));
            assertTrue(allowed.contains(value), "sema disi deger: " + value);
        }
    }

    /** Listesi olmayan sade bir INTEGER eskisi gibi serbest uretilir. */
    @Test
    void anUnconstrainedIntegerIsStillGeneratedFreely() {
        String value = generator.generate(field("someCounter", "INTEGER"));
        assertTrue(value.matches("\\d+"), "sade INTEGER sayi almali: " + value);
    }

    /**
     * yml kurallari alan adinin bir PARCASINA gore eslesiyor, dolayisiyla
     * tanimadigi bir adlandirilmis-sayi alanina rahatlikla carpabilir:
     * "duration" kurali 15/127/842 ornekleriyle {0,1} kabul eden bir alani
     * doldurmamali.
     */
    @Test
    void aRuleWhoseExamplesAreNotDeclaredValuesIsIgnored() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String value = generator.generate(
                    field("duration", "ENUMERATED{forward(0),backward(1)}"));
            assertTrue(List.of("0", "1").contains(value),
                    "kural ornekleri sema disi oldugu icin yok sayilmaliydi: " + value);
        }
    }
}