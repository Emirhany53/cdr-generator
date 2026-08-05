package com.turkcell.cdrgenerator1.controller;

import com.turkcell.cdrgenerator1.ai.AiFieldValueProvider;
import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.service.FixedWidthTextFormatter;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.exception.GlobalExceptionHandler;
import com.turkcell.cdrgenerator1.generator.*;
import com.turkcell.cdrgenerator1.generator.source.AiValueSource;
import com.turkcell.cdrgenerator1.generator.source.RandomValueSource;
import com.turkcell.cdrgenerator1.generator.source.UserProvidedValueSource;
import com.turkcell.cdrgenerator1.generator.validation.FieldValueValidator;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import com.turkcell.cdrgenerator1.service.AiRecordSupplier;
import com.turkcell.cdrgenerator1.service.BerEncoderService;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import com.turkcell.cdrgenerator1.service.TlvWriter;
import com.turkcell.cdrgenerator1.config.SelfCheckProperties;
import com.turkcell.cdrgenerator1.service.verify.BerVerifier;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import com.turkcell.cdrgenerator1.service.verify.rule.DuplicateTagRule;
import com.turkcell.cdrgenerator1.service.verify.rule.SetOrderingRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for the BER generation endpoint. The parser service
 * is built with the real registry/resolver so both request paths are exercised:
 * generation from a registered structure and, most importantly, generation from
 * brand-new inline ASN.1 content supplied in the request body - the feature that
 * lets a user add a never-before-seen structure and turn it into a .ber file.
 */
class BerGeneratorControllerTest {

    private MockMvc mockMvc;
    private SelfCheckProperties selfCheckProperties;

    @BeforeEach
    void setUp() {
        CdrConfigProperties config = new CdrConfigProperties();
        config.setDefaultRecordCount(1);
        config.setMaxRecordCount(100);

        AiConfigProperties aiProperties = new AiConfigProperties();
        AsnSizeExtractor sizeExtractor = new AsnSizeExtractor();
        BcdTimestampFactory bcdTimestampFactory = new BcdTimestampFactory();

        AsnTypeRegistryBuilder registryBuilder = new AsnTypeRegistryBuilder();
        AsnFieldTreeResolver resolver = new AsnFieldTreeResolver();
        StructureParserService parserService =
                new StructureParserService(null, registryBuilder, resolver);

        TbcdCodec tbcdCodec = new TbcdCodec();

        CdrRecordBuilder recordBuilder = new CdrRecordBuilder(parserService, List.of(
                new UserProvidedValueSource(),
                new AiValueSource(
                        new FieldValueValidator(tbcdCodec, aiProperties, sizeExtractor, bcdTimestampFactory),
                        new EnumValueResolver()),
                new RandomValueSource(new FieldValueGenerator(tbcdCodec, aiProperties, sizeExtractor, bcdTimestampFactory))));

        BerEncoderService encoder = new BerEncoderService(new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));

        AiRecordSupplier aiRecordSupplier = TestAiSupport.disabledSupplier(aiProperties);

        // Every generated file now goes through the self-check on its way out.
        // WARN keeps these tests about generation rather than about verification:
        // a finding is logged, the response is unchanged. The strict path has its
        // own test below.
        selfCheckProperties = new SelfCheckProperties();
        selfCheckProperties.setMode(SelfCheckProperties.Mode.WARN);
        BerVerifier verifier = new BerVerifier(new TlvReader(), selfCheckProperties,
                List.of(new DuplicateTagRule(), new SetOrderingRule()));

        BerGeneratorController controller = new BerGeneratorController(
                parserService, recordBuilder, encoder, config, aiRecordSupplier,
                verifier, selfCheckProperties);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }


    @Test
    void inlineContentProducesDownloadableBerFile() throws Exception {
        String body = """
                {
                  "structureName": "InlineDemo",
                  "content": "M DEFINITIONS IMPLICIT TAGS ::= BEGIN Root ::= SEQUENCE { msisdn [1] IMPLICIT OCTET STRING OPTIONAL, duration [4] IMPLICIT INTEGER OPTIONAL } END",
                  "fieldValues": { "msisdn": "905321234567", "duration": "42" }
                }
                """;

        mockMvc.perform(post("/api/cdr/generate-ber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("InlineDemo.ber")))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM));
    }

    /**
     * The self-check verdict travels with the file. Without it a caller has no
     * way to know a returned .ber was flagged - the bytes look identical either
     * way, which is exactly how a bad file used to reach EMM unnoticed.
     */
    @Test
    void returnsTheSelfCheckVerdictAsAHeader() throws Exception {
        String body = """
                {
                  "structureName": "InlineDemo",
                  "content": "M DEFINITIONS IMPLICIT TAGS ::= BEGIN Root ::= SEQUENCE { msisdn [1] IMPLICIT OCTET STRING OPTIONAL, duration [4] IMPLICIT INTEGER OPTIONAL } END",
                  "fieldValues": { "msisdn": "905321234567", "duration": "42" }
                }
                """;

        mockMvc.perform(post("/api/cdr/generate-ber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cdr-Self-Check", containsString("0 error(s)")));
    }

    /**
     * A clean structure must stay clean in strict mode too: the point of
     * strict is to stop bad files, not to stop generation.
     */
    @Test
    void strictModeStillReturnsAFileThatPassesTheCheck() throws Exception {
        selfCheckProperties.setMode(SelfCheckProperties.Mode.STRICT);
        String body = """
                {
                  "structureName": "InlineDemo",
                  "content": "M DEFINITIONS IMPLICIT TAGS ::= BEGIN Root ::= SEQUENCE { msisdn [1] IMPLICIT OCTET STRING OPTIONAL, duration [4] IMPLICIT INTEGER OPTIONAL } END",
                  "fieldValues": { "msisdn": "905321234567", "duration": "42" }
                }
                """;

        mockMvc.perform(post("/api/cdr/generate-ber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void unparseableInlineContentReturnsBadRequest() throws Exception {
        String body = """
                {
                  "structureName": "Broken",
                  "content": "this is not valid ASN.1 at all"
                }
                """;

        mockMvc.perform(post("/api/cdr/generate-ber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingStructureNameAndContentReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/cdr/generate-ber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownRegisteredStructureReturnsNotFound() throws Exception {
        String body = """
                { "structureName": "NoSuchStructure" }
                """;

        mockMvc.perform(post("/api/cdr/generate-ber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    /**
     * A module can parse fine and still resolve to no fields - Array,
     * LteReturnTypes and SMSCLookupStructures are helper type modules, not CDR
     * records. Inline mode already rejected that; the registered path did not,
     * so it answered 200 with a file full of empty "30 00" records. A silent
     * success like that reads as a generator fault rather than a bad choice of
     * structure.
     */
    @Test
    void aStructureThatResolvesToNoFieldsReturnsBadRequest() throws Exception {
        String body = """
                {
                  "structureName": "EmptyRoot",
                  "content": "M DEFINITIONS ::= BEGIN Str ::= IA5STRING StringArray ::= SEQUENCE OF Str END"
                }
                """;

        mockMvc.perform(post("/api/cdr/generate-ber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordCountAboveMaxReturnsBadRequest() throws Exception {
        String body = """
                {
                  "structureName": "InlineDemo",
                  "content": "M DEFINITIONS ::= BEGIN Root ::= SEQUENCE { a [1] IMPLICIT INTEGER } END",
                  "recordCount": 5000
                }
                """;

        mockMvc.perform(post("/api/cdr/generate-ber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
