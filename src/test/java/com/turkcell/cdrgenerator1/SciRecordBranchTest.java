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
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Madde 7: {@code MMTelServiceRecord}'un ikinci alternatifi olan
 * {@code sCIRecord [999]} dali. Uretim hep birinci alternatifi
 * ({@code mMTelRecord [83]}) kullaniyordu; bu dal ayni {@code MMTelRecord}
 * tipini paylassa da hic uretilip dogrulanmamisti.
 *
 * <p>Ayni zamanda cok baytli yuksek etiket sayisini da sinar: {@code [999]}
 * tel uzerinde {@code BF 87 67} olarak kodlanmalidir (context+constructed,
 * ardindan 999'un taban-128 gosterimi 7*128+103).
 */
class SciRecordBranchTest {

    private static final String MMTEL = "MMTelChargingDataTypesV3";
    private static final Map<String, String> SELECT_SCI = Map.of("MMTelServiceRecord", "sCIRecord");

    private static StructureParserService parser;
    private static CdrRecordBuilder builder;
    private static BerEncoderService encoder;
    private static BerVerifier verifier;

    @BeforeAll
    static void wireRealPipeline() {
        CdrConfigProperties cfg = new CdrConfigProperties();
        cfg.setDataStructurePath("src/main/resources/datastructure.json");
        cfg.setDefaultRecordCount(1);
        cfg.setMaxRecordCount(100);

        CdrStructureReaderService reader = new CdrStructureReaderService(cfg, new ObjectMapper());
        parser = new StructureParserService(reader, new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());
        parser.init();

        AiConfigProperties ai = new AiConfigProperties();
        AsnSizeExtractor size = new AsnSizeExtractor();
        BcdTimestampFactory bcd = new BcdTimestampFactory();
        TbcdCodec tbcd = new TbcdCodec();
        builder = new CdrRecordBuilder(parser, bcd, cfg, java.util.List.of(
                new UserProvidedValueSource(),
                new RandomValueSource(new FieldValueGenerator(tbcd, ai, size, bcd))));

        encoder = new BerEncoderService(new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));

        SelfCheckProperties selfCheck = new SelfCheckProperties();
        selfCheck.setMode(SelfCheckProperties.Mode.WARN);
        verifier = new BerVerifier(new TlvReader(), selfCheck, java.util.List.of(
                new DuplicateTagRule(), new SetOrderingRule(), new TagShapeRule(),
                new NamedNumberRule(), new IntegerRangeRule(size)));
    }

    @Test
    void sciRecordAlternativeResolvesRoundTripsCleanAndCarriesTheHighTag() {
        AsnStructure sci = parser.getStructureByName(MMTEL, SELECT_SCI);

        // We really exercised the OTHER branch, not the mMTelRecord default.
        assertTrue(sci.isChoiceRoot(), "MMTelServiceRecord is a CHOICE root");
        assertEquals("sCIRecord", sci.getFields().get(0).getFieldName(),
                "the selected alternative must be sCIRecord, not mMTelRecord");

        Map<String, Object> record = builder.buildRecordFromFields(sci.getFields(), Map.of());
        byte[] bytes = encoder.encodeRecord(sci, record);

        // [999] on the wire: context(10)+constructed, long-form tag, then 999 in
        // base-128 = 7*128 + 103 -> BF 87 67.
        assertTrue(bytes.length > 3, "sCIRecord must encode to a non-trivial TLV");
        assertEquals((byte) 0xBF, bytes[0], "byte 0: context, constructed, long-form tag");
        assertEquals((byte) 0x87, bytes[1], "byte 1: 999 high group (7 | continuation)");
        assertEquals((byte) 0x67, bytes[2], "byte 2: 999 low group (103)");

        BerVerificationResult result = verifier.verify(sci, bytes);
        assertTrue(result.errors().isEmpty(),
                "sCIRecord branch must self-check clean, same as mMTelRecord: " + result.errors());
    }
}
