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
import com.turkcell.cdrgenerator1.model.CdrStructureDto;
import com.turkcell.cdrgenerator1.service.CdrStructureReaderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
        // A fake reader gives the parser one registered structure so
        // verify-ber (which only accepts a structureName, never inline
        // content - the whole point is checking a file against a structure
        // the system already knows) has something to look up in its tests.
        CdrStructureReaderService fakeReader = new CdrStructureReaderService(null, null) {
            @Override
            public List<CdrStructureDto> readAllStructures() {
                return List.of(
                        CdrStructureDto.builder()
                                .name("SimpleRecord")
                                .contents("M DEFINITIONS IMPLICIT TAGS ::= BEGIN Root ::= SEQUENCE "
                                        + "{ msisdn [1] IMPLICIT OCTET STRING OPTIONAL, "
                                        + "duration [4] IMPLICIT INTEGER OPTIONAL } END")
                                .build(),
                        // The IMSCDRS shape: one CHOICE over two records, so the
                        // root heuristic picks the CHOICE and rootType is the only
                        // way to ask for the other one.
                        CdrStructureDto.builder()
                                .name("MultiRoot")
                                .contents("M DEFINITIONS IMPLICIT TAGS ::= BEGIN "
                                        + "Cdrs ::= CHOICE { tokenAlpha [0] Alpha, tokenBeta [1] Beta } "
                                        + "Alpha ::= SEQUENCE { a [1] IMPLICIT OCTET STRING OPTIONAL } "
                                        + "Beta ::= SEQUENCE { b [2] IMPLICIT OCTET STRING OPTIONAL } END")
                                .build());
            }
        };
        StructureParserService parserService =
                new StructureParserService(fakeReader, registryBuilder, resolver);
        parserService.init();

        TbcdCodec tbcdCodec = new TbcdCodec();

        CdrRecordBuilder recordBuilder = new CdrRecordBuilder(parserService, bcdTimestampFactory, config, List.of(
                new UserProvidedValueSource(),
                new AiValueSource(
                        new FieldValueValidator(tbcdCodec, aiProperties, sizeExtractor, bcdTimestampFactory),
                        new EnumValueResolver(), aiProperties),
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

    // --- POST /api/cdr/verify-ber: checking a file this application did not produce ---

    private static byte[] hex(String text) {
        String clean = text.replace(" ", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    void verifiesAnUploadedFileAgainstARegisteredStructure() throws Exception {
        // SEQUENCE { [1] "905321234567" (IA5), [4] 42 } - what this system's own
        // encoder would produce for SimpleRecord, built by hand here because the
        // whole point of this endpoint is checking bytes it did NOT produce.
        MockMultipartFile file = new MockMultipartFile("file", "reference.ber",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                hex("30 11 81 0C 39 30 35 33 32 31 32 33 34 35 36 37 84 01 2A"));

        mockMvc.perform(multipart("/api/cdr/verify-ber/SimpleRecord")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"structureName":"SimpleRecord","recordCount":1,"clean":true,
                         "errorCount":0,"warningCount":0,"findings":[]}
                        """));
    }

    /** The shape EMM actually rejects: two members of one body sharing a tag. */
    @Test
    void reportsADuplicateTagInAnUploadedFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "corrupt.ber",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                hex("30 06 81 01 41 81 01 42"));

        mockMvc.perform(multipart("/api/cdr/verify-ber/SimpleRecord")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"clean\":false")))
                .andExpect(content().string(containsString("duplicate-tag")));
    }

    /**
     * {@code rootType} has to reach the resolver, not merely the signature.
     *
     * <p>The uploaded bytes are a bare {@code SEQUENCE { [2] .. }} - a
     * {@code Beta} record. Left to itself the root heuristic picks the
     * {@code Cdrs} CHOICE and its first alternative, {@code tokenAlpha}, whose
     * body declares {@code [1]} instead. So the same file must verify clean with
     * {@code rootType=Beta} and report findings without it: if the parameter
     * were accepted and dropped, both calls would return the same thing.</p>
     *
     * <p>This is the gap that made an uploaded {@code IMSCDRS} file
     * unverifiable - the endpoint could only ever check it against the
     * auto-selected {@code Cdrs} root.</p>
     */
    @Test
    void verifyBerHonoursRootTypeRatherThanTheAutoSelectedRoot() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "beta.ber",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                hex("30 03 82 01 41"));

        mockMvc.perform(multipart("/api/cdr/verify-ber/MultiRoot")
                        .file(file)
                        .param("rootType", "Beta"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"clean\":true")));

        mockMvc.perform(multipart("/api/cdr/verify-ber/MultiRoot")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"clean\":false")));
    }

    /**
     * Spring folds every query parameter into the untyped choiceSelections map,
     * so rootType has to be stripped before the rest is offered to the resolver
     * as CHOICE selections - otherwise "rootType" is looked up as a CHOICE type
     * name. Passing it alone must still leave the selections empty.
     */
    @Test
    void rootTypeIsNotMistakenForAChoiceSelection() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "beta.ber",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                hex("30 03 82 01 41"));

        mockMvc.perform(multipart("/api/cdr/verify-ber/MultiRoot")
                        .file(file)
                        .param("rootType", "Beta"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"structureName\":\"MultiRoot\"")));
    }

    @Test
    void verifyingAgainstAnUnknownStructureReturnsNotFound() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.ber",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, hex("30 00"));

        mockMvc.perform(multipart("/api/cdr/verify-ber/DoesNotExist")
                        .file(file))
                .andExpect(status().isNotFound());
    }

    @Test
    void verifyingUnreadableBytesReportsAWalkerError() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "truncated.ber",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, hex("81 7F 01"));

        mockMvc.perform(multipart("/api/cdr/verify-ber/SimpleRecord")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not readable as BER")));
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
