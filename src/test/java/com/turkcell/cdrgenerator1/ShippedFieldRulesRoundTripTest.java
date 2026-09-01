package com.turkcell.cdrgenerator1;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.config.SelfCheckProperties;
import com.turkcell.cdrgenerator1.generator.BcdTimestampFactory;
import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.generator.FieldValueGenerator;
import com.turkcell.cdrgenerator1.generator.TbcdCodec;
import com.turkcell.cdrgenerator1.generator.source.RandomValueSource;
import com.turkcell.cdrgenerator1.generator.source.UserProvidedValueSource;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import com.turkcell.cdrgenerator1.service.BerEncoderService;
import com.turkcell.cdrgenerator1.service.CdrStructureReaderService;
import com.turkcell.cdrgenerator1.service.FixedWidthTextFormatter;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import com.turkcell.cdrgenerator1.service.TlvWriter;
import com.turkcell.cdrgenerator1.service.verify.BerFinding;
import com.turkcell.cdrgenerator1.service.verify.BerVerificationResult;
import com.turkcell.cdrgenerator1.service.verify.BerVerifier;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import com.turkcell.cdrgenerator1.service.verify.rule.DuplicateTagRule;
import com.turkcell.cdrgenerator1.service.verify.rule.IntegerRangeRule;
import com.turkcell.cdrgenerator1.service.verify.rule.NamedNumberRule;
import com.turkcell.cdrgenerator1.service.verify.rule.SetOrderingRule;
import com.turkcell.cdrgenerator1.service.verify.rule.TagShapeRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Madde 5'in guvenlik agi. {@link AllModulesRoundTripTest} 808 modulu gezer ama
 * BOS bir {@link AiConfigProperties} ile calisir, yani application.yml'de
 * gonderdigimiz alan kurallarini hic denemez. Bir kural ornegi bir alanin
 * SIZE'ini asabilir, adlandirilmis-sayi kumesinin disina cikabilir ya da ad
 * benzerligiyle hic beklemedigimiz bir modulun alanina carpabilir - kurallar
 * ad PARCASINA gore eslestigi ve 808 modul 83 binden fazla alan bildirdigi
 * icin bu gercek bir risk.
 *
 * <p>Bu test gercek yml'yi okur, kurallari baglar ve ayni gidis-donusu onlarla
 * kosar: bir kural ornegi ureten kayit da kodlanabilmeli ve oz-denetimden
 * gecmeli. Kural eklemek artik sessiz bir regresyon olamaz.</p>
 */
class ShippedFieldRulesRoundTripTest {

    /**
     * Ayni sema kusuru {@link AllModulesRoundTripTest}'te de tolere ediliyor:
     * bu moduller vendored ASN.1 metninde AYNI CONTEXT tag'i iki kez bildiriyor.
     * Kurallarla ilgisi yok, uretilen degerden bagimsiz olarak olusur.
     */
    private static final java.util.Set<String> SCHEMA_LEVEL_DUPLICATE_TAG_MODULES = java.util.Set.of(
            // SET kokleri: keyword'suz bir CHOICE alani, alternatifinin tag'ini
            // yazdiginda kardes bir uyeyle CAKISIYOR. SET'te sira bilgi tasimadigi
            // icin bu gercekten cozulemez ve EMM de reddediyor - ATS_ONDER 25. ve
            // 28. turda tam bu sebeple dustu. Kodlayicinin yazabilecegi baska bir
            // tag yok: CalledPartyAddress'in iki alternatifi [0] ve [1], ikisi de
            // aTSRecord'da zaten dolu.
            "ATS_ONDER", "CDRF-R7", "LTE-R10-TURKCELL-SYNVRS", "MAVENIRTEST",
            "BDCevapsiz", "CDRDatamartTANGOmBalance", "CwinDataStr", "DWHClearedDedicatedISO",
            "FCMSCCNGTP", "FCMSVM", "FciGgsn", "FDRInput", "HTSCevapsiz", "MSCCAP2Test",
            "NotifyIsoCdr", "NRTRDEErrorReport", "OTAGXS", "PSTNSMSMatching", "SDPAdjLikya",
            "SDPAdjLikyaDAC", "SMSCMatching", "SMSCMatching1", "VoiceSMS", "VoiceSmsInput");

    private static StructureParserService parser;
    private static CdrRecordBuilder builder;
    private static BerEncoderService encoder;
    private static BerVerifier verifier;
    private static int ruleCount;

    @BeforeAll
    static void wireWithTheShippedRules() throws Exception {
        CdrConfigProperties cfg = new CdrConfigProperties();
        cfg.setDataStructurePath("src/main/resources/datastructure.json");
        cfg.setDefaultRecordCount(1);
        cfg.setMaxRecordCount(100);

        CdrStructureReaderService reader = new CdrStructureReaderService(cfg, new ObjectMapper());
        parser = new StructureParserService(reader, new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());
        parser.init();

        AiConfigProperties ai = new AiConfigProperties();
        ai.setFieldRules(loadShippedRules());
        ruleCount = ai.getFieldRules().size();

        AsnSizeExtractor size = new AsnSizeExtractor();
        BcdTimestampFactory bcd = new BcdTimestampFactory();
        TbcdCodec tbcd = new TbcdCodec();
        builder = new CdrRecordBuilder(parser, bcd, cfg, List.of(
                new UserProvidedValueSource(),
                new RandomValueSource(new FieldValueGenerator(tbcd, ai, size, bcd))));
        encoder = new BerEncoderService(new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));

        SelfCheckProperties selfCheck = new SelfCheckProperties();
        selfCheck.setMode(SelfCheckProperties.Mode.WARN);
        verifier = new BerVerifier(new TlvReader(), selfCheck, List.of(
                new DuplicateTagRule(), new SetOrderingRule(), new TagShapeRule(),
                new NamedNumberRule(), new IntegerRangeRule(size)));
    }

    /** application.yml'deki app.cdr.ai.field-rules dugumunu okur. */
    @SuppressWarnings("unchecked")
    private static List<AiConfigProperties.FieldRule> loadShippedRules() throws Exception {
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            root = new Yaml().load(in);
        }
        Map<String, Object> app = (Map<String, Object>) root.get("app");
        Map<String, Object> cdr = (Map<String, Object>) app.get("cdr");
        Map<String, Object> ai = (Map<String, Object>) cdr.get("ai");
        List<Map<String, Object>> raw = (List<Map<String, Object>>) ai.get("field-rules");

        List<AiConfigProperties.FieldRule> rules = new ArrayList<>(raw.size());
        for (Map<String, Object> entry : raw) {
            AiConfigProperties.FieldRule rule = new AiConfigProperties.FieldRule();
            rule.setName((String) entry.get("name"));
            rule.setMatch((List<String>) entry.get("match"));
            rule.setDescription((String) entry.get("description"));
            rule.setPattern((String) entry.get("pattern"));
            rule.setExamples((List<String>) entry.get("examples"));
            rule.setOctetStringContent((String) entry.get("octet-string-content"));
            rules.add(rule);
        }
        return rules;
    }

    @Test
    void everyModuleStillRoundTripsCleanWithTheShippedFieldRules() {
        assertTrue(ruleCount > 20, "expected the real yml rules to load; got " + ruleCount);

        Map<String, Exception> crashed = new LinkedHashMap<>();
        Map<String, List<BerFinding>> unexpectedErrors = new TreeMap<>();
        int checked = 0;

        for (Map.Entry<String, AsnStructure> entry : parser.getAllParsedStructures().entrySet()) {
            AsnStructure structure = entry.getValue();
            if (structure.getFields() == null || structure.getFields().isEmpty()) {
                continue;
            }
            try {
                Map<String, Object> record = builder.buildRecordFromFields(structure.getFields(), Map.of());
                BerVerificationResult result = verifier.verify(structure, encoder.encodeRecord(structure, record));
                checked++;
                if (result.hasErrors() && !SCHEMA_LEVEL_DUPLICATE_TAG_MODULES.contains(entry.getKey())) {
                    unexpectedErrors.put(entry.getKey(), result.errors());
                }
            } catch (Exception e) {
                crashed.put(entry.getKey(), e);
            }
        }
        System.out.printf("ShippedFieldRulesRoundTripTest: %d rules applied across %d modules%n",
                ruleCount, checked);

        assertTrue(crashed.isEmpty(), "a shipped field rule broke generation for: " + crashed);
        assertTrue(unexpectedErrors.isEmpty(),
                "a shipped field rule produced a value the self-check rejects: " + unexpectedErrors);
    }

    /**
     * "Bozmuyor" yetmez - kurallarin alanlara GERCEKTEN ulastigini da sabitler.
     * Bir match ifadesi yanlis yazilirsa kural sessizce hic eslesmez ve alan
     * rastgele degere doner; bu test o sessiz kaybi yakalar.
     */
    @Test
    void theMmtelRulesActuallyReachTheirFields() {
        AsnStructure mmtel = parser.getStructureByName("MMTelChargingDataTypesV3");
        Map<String, List<String>> values = new LinkedHashMap<>();
        collectLeafValues(builder.buildRecordFromFields(mmtel.getFields(), Map.of()), values);

        // A text field is stored as its ASN.1 literal, quotes included.
        assertTrue(values.getOrDefault("sIP-URI", List.of()).stream()
                        .anyMatch(v -> unquote(v).startsWith("sip:")),
                "sipUri rule must fill sIP-URI: " + values.get("sIP-URI"));
        assertTrue(values.getOrDefault("sIP-Method", List.of()).stream()
                        .allMatch(v -> List.of("INVITE", "MESSAGE", "SUBSCRIBE").contains(unquote(v))),
                "sipMethod rule must fill sIP-Method: " + values.get("sIP-Method"));

        // userLocationInformation is an OCTET STRING, so the rule example is
        // written as its ASCII bytes - the shape the reference capture shows.
        String location = unquote(values.getOrDefault("userLocationInformation", List.of("")).get(0));
        assertTrue(decodeAsciiHex(location).matches("[0-9a-f]{12,16}"),
                "userLocationInformation must carry the example as ASCII bytes, got: " + location);
    }

    private String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private String decodeAsciiHex(String hex) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            text.append((char) Integer.parseInt(hex.substring(i, i + 2), 16));
        }
        return text.toString();
    }

    @SuppressWarnings("unchecked")
    private void collectLeafValues(Object node, Map<String, List<String>> target) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (value instanceof String text) {
                    target.computeIfAbsent(String.valueOf(key), k -> new ArrayList<>()).add(text);
                } else if (value instanceof List<?> list && !list.isEmpty()
                        && list.get(0) instanceof String) {
                    target.computeIfAbsent(String.valueOf(key), k -> new ArrayList<>())
                            .addAll((List<String>) list);
                } else {
                    collectLeafValues(value, target);
                }
            });
        } else if (node instanceof List<?> list) {
            list.forEach(element -> collectLeafValues(element, target));
        }
    }
}
