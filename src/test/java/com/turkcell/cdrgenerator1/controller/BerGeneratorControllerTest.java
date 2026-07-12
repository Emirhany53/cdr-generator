package com.turkcell.cdrgenerator1.controller;

import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.exception.GlobalExceptionHandler;
import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.generator.FieldValueGenerator;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import com.turkcell.cdrgenerator1.service.BerEncoderService;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import com.turkcell.cdrgenerator1.service.TlvWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

    @BeforeEach
    void setUp() {
        CdrConfigProperties config = new CdrConfigProperties();
        config.setDefaultRecordCount(1);
        config.setMaxRecordCount(100);

        AsnTypeRegistryBuilder registryBuilder = new AsnTypeRegistryBuilder();
        AsnFieldTreeResolver resolver = new AsnFieldTreeResolver();
        StructureParserService parserService =
                new StructureParserService(null, registryBuilder, resolver);
        CdrRecordBuilder recordBuilder =
                new CdrRecordBuilder(parserService, new FieldValueGenerator());
        BerEncoderService encoder = new BerEncoderService(new TlvWriter());

        BerGeneratorController controller = new BerGeneratorController(
                parserService, recordBuilder, encoder, config);

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
