package com.turkcell.cdrgenerator1.controller;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.config.SelfCheckProperties;
import com.turkcell.cdrgenerator1.exception.GlobalExceptionHandler;
import com.turkcell.cdrgenerator1.generator.BcdTimestampFactory;
import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.generator.FieldValueGenerator;
import com.turkcell.cdrgenerator1.generator.TbcdCodec;
import com.turkcell.cdrgenerator1.generator.source.RandomValueSource;
import com.turkcell.cdrgenerator1.generator.source.UserProvidedValueSource;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import com.turkcell.cdrgenerator1.service.BerEncoderService;
import com.turkcell.cdrgenerator1.service.CdrFileWriterService;
import com.turkcell.cdrgenerator1.service.CdrStructureReaderService;
import com.turkcell.cdrgenerator1.service.FixedWidthTextFormatter;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import com.turkcell.cdrgenerator1.service.TlvWriter;
import com.turkcell.cdrgenerator1.service.verify.BerVerifier;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Three modules in {@code datastructure.json} declare no record.
 *
 * <p>{@code Array}, {@code LteReturnTypes} and {@code SMSCLookupStructures} are
 * helper modules - they define type aliases for other modules to name, and the
 * parser logs "produced no resolvable fields" for each at startup. Asking either
 * endpoint for a CDR from one of them is a mistake the caller wants to hear
 * about.</p>
 *
 * <p>{@code /generate-ber} has refused them since it was written: an empty field
 * list there produced a file of empty {@code 30 00} records, "a silent success
 * that looks like a generator fault". {@code /generate} carried no such check,
 * so it answered 200 with a body of blank lines - the same defect in the format
 * that gets checked half as often. This pins both endpoints to the same answer,
 * and pins that generatable modules are unaffected.</p>
 */
class UnresolvableStructureTextRefusalTest {

    private static MockMvc mockMvc;

    @BeforeAll
    static void wireTheRealParser() {
        CdrConfigProperties config = new CdrConfigProperties();
        config.setDataStructurePath("src/main/resources/datastructure.json");
        config.setDefaultRecordCount(1);
        config.setMaxRecordCount(100);

        StructureParserService parser = new StructureParserService(
                new CdrStructureReaderService(config, new ObjectMapper()),
                new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());
        parser.init();

        AiConfigProperties aiProperties = new AiConfigProperties();
        AsnSizeExtractor sizes = new AsnSizeExtractor();
        BcdTimestampFactory timestamps = new BcdTimestampFactory();

        CdrRecordBuilder recordBuilder = new CdrRecordBuilder(parser, timestamps, config, List.of(
                new UserProvidedValueSource(),
                new RandomValueSource(new FieldValueGenerator(
                        new TbcdCodec(), aiProperties, sizes, timestamps))));

        // self-check off: this test is about the refusal that happens before any
        // encoding, and strict mode would refuse 17 modules for unrelated reasons.
        SelfCheckProperties selfCheck = new SelfCheckProperties();
        selfCheck.setMode(SelfCheckProperties.Mode.OFF);

        CdrStructureController textController = new CdrStructureController(
                parser, recordBuilder, new CdrFileWriterService(), config,
                TestAiSupport.disabledSupplier(aiProperties));

        BerGeneratorController berController = new BerGeneratorController(
                parser, recordBuilder,
                new BerEncoderService(new TlvWriter(), new FixedWidthTextFormatter(sizes)),
                config, TestAiSupport.disabledSupplier(aiProperties),
                new BerVerifier(new TlvReader(), selfCheck, List.of()), selfCheck);

        mockMvc = MockMvcBuilders.standaloneSetup(textController, berController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String request(String structureName) {
        return """
                { "structureName": "%s", "recordCount": 1 }
                """.formatted(structureName);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Array", "LteReturnTypes", "SMSCLookupStructures"})
    void theTextEndpointRefusesAStructureThatResolvesToNoFields(String module) throws Exception {
        String message = mockMvc.perform(post("/api/cdr/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(module)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(message)
                .as("the refusal has to name the structure and say why")
                .contains(module)
                .contains("no fields");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Array", "LteReturnTypes", "SMSCLookupStructures"})
    void theBerEndpointStillRefusesTheSameStructures(String module) throws Exception {
        mockMvc.perform(post("/api/cdr/generate-ber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(module)))
                .andExpect(status().isBadRequest());
    }

    /**
     * The refusal is about an empty field list, nothing else. A module that
     * resolves to a record still produces one - including {@code IMSCDRS}, whose
     * root is a CHOICE, and {@code FDRInput}, whose root carries a type-level tag.
     */
    @ParameterizedTest
    @ValueSource(strings = {"MMTelChargingDataTypes", "IMSCDRS", "FDRInput", "CGSN40ber", "CHAD"})
    void generatableStructuresStillProduceText(String module) throws Exception {
        String text = mockMvc.perform(post("/api/cdr/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(module)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(text).isNotBlank();
        assertThat(text.lines().count()).isEqualTo(1);
    }
}
