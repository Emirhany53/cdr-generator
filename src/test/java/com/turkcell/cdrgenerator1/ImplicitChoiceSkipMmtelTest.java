package com.turkcell.cdrgenerator1;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.generator.BcdTimestampFactory;
import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.generator.FieldValueGenerator;
import com.turkcell.cdrgenerator1.generator.TbcdCodec;
import com.turkcell.cdrgenerator1.generator.source.RandomValueSource;
import com.turkcell.cdrgenerator1.generator.source.UserProvidedValueSource;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import com.turkcell.cdrgenerator1.service.CdrStructureReaderService;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Madde 1: EMM'in implicit-CHOICE hoisting kaynakli "Duplicate Tag" reddini
 * tetikleyen alanlarin uretimden dislanmasi.
 *
 * <p>MMTelRecord'un {CHOICE, IMPLICIT-tag, OPTIONAL} olan dokuz alani bu sinifta
 * isimle sabitlenmistir. Bu test iki seyi birden dogrular: (1) skip acikken bu
 * dokuz alan uretilen kayitta HIC yer almaz; (2) skip kapaliyken en az bir kismi
 * yeniden gorunur - yani dislama config'e bagli, kalici bir kesme degil.
 *
 * <p>Ayni zamanda temeldeki varsayimi da sinar: resolver bu alanlari gercekten
 * choice=true, explicit=false, optional=true olarak cozmeli. Biri EXPLICIT ya da
 * zorunlu cozulseydi skip onu atlamaz ve bu test skip-acik dalinda patlardi.
 */
class ImplicitChoiceSkipMmtelTest {

    private static final String MMTEL = "MMTelChargingDataTypesV3";

    private static final Set<String> IMPLICIT_CHOICE_FIELDS = Set.of(
            "diverting-Party-Address",
            "servingSCSCFAddress",
            "impu",
            "asserted-Party-Address",
            "original-Cldpn-Address",
            "cT-Push-Transferor",
            "cT-Push-Transferee",
            "cT-Push-Transfer-Target");

    private static StructureParserService parser;
    private static BcdTimestampFactory bcd;

    @BeforeAll
    static void loadRealSchema() {
        CdrConfigProperties cfg = new CdrConfigProperties();
        cfg.setDataStructurePath("src/main/resources/datastructure.json");
        cfg.setDefaultRecordCount(1);
        cfg.setMaxRecordCount(100);
        CdrStructureReaderService reader = new CdrStructureReaderService(cfg, new ObjectMapper());
        parser = new StructureParserService(reader, new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());
        parser.init();
        bcd = new BcdTimestampFactory();
    }

    private CdrRecordBuilder builderWithSkip(boolean skip) {
        CdrConfigProperties cfg = new CdrConfigProperties();
        cfg.setSkipImplicitChoiceFields(skip);
        AiConfigProperties ai = new AiConfigProperties();
        AsnSizeExtractor size = new AsnSizeExtractor();
        TbcdCodec tbcd = new TbcdCodec();
        // No AiValueSource: userValues empty, every field falls to RandomValueSource,
        // so the test needs no network and exercises exactly the skip logic.
        return new CdrRecordBuilder(parser, bcd, cfg, List.of(
                new UserProvidedValueSource(),
                new RandomValueSource(new FieldValueGenerator(tbcd, ai, size, bcd))));
    }

    @Test
    void skipOnOmitsEveryImplicitChoiceField() {
        AsnStructure mmtel = parser.getStructureByName(MMTEL);
        Map<String, Object> record = builderWithSkip(true).buildRecordFromFields(mmtel.getFields(), Map.of());

        Set<String> keys = new HashSet<>();
        collectKeys(record, keys);
        for (String field : IMPLICIT_CHOICE_FIELDS) {
            assertTrue(!keys.contains(field),
                    "skip=on must omit implicit-choice OPTIONAL field but kept: " + field);
        }
    }

    @Test
    void skipOffKeepsAtLeastOne() {
        AsnStructure mmtel = parser.getStructureByName(MMTEL);
        Map<String, Object> record = builderWithSkip(false).buildRecordFromFields(mmtel.getFields(), Map.of());

        Set<String> keys = new HashSet<>();
        collectKeys(record, keys);
        long kept = IMPLICIT_CHOICE_FIELDS.stream().filter(keys::contains).count();
        assertTrue(kept > 0,
                "skip=off must still emit implicit-choice fields (regression guard); kept " + kept);
    }

    private void collectKeys(Object node, Set<String> keys) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                keys.add(String.valueOf(key));
                collectKeys(value, keys);
            });
        } else if (node instanceof List<?> list) {
            list.forEach(element -> collectKeys(element, keys));
        }
    }
}
