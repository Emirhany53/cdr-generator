package com.turkcell.cdrgenerator1.controller;

import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.exception.GlobalExceptionHandler;
import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.generator.FieldValueGenerator;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.service.CdrFileWriterService;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the structure listing and Token-Separated-ASCII generation
 * endpoints. The parser service is mocked so each endpoint's HTTP contract
 * (200/404, headers, body shape) can be verified in isolation.
 */
class CdrStructureControllerTest {

    private MockMvc mockMvc;
    private StructureParserService parserService;

    private AsnField leaf(String name, String type, int tag) {
        return AsnField.builder().fieldName(name).fieldType(type)
                .tagNumber(tag).tagClass(BerTagClass.CONTEXT).build();
    }

    @BeforeEach
    void setUp() {
        parserService = Mockito.mock(StructureParserService.class);

        CdrConfigProperties config = new CdrConfigProperties();
        config.setDefaultRecordCount(1);
        config.setMaxRecordCount(100);

        CdrRecordBuilder recordBuilder =
                new CdrRecordBuilder(parserService, new FieldValueGenerator());
        CdrFileWriterService writer = new CdrFileWriterService();

        CdrStructureController controller = new CdrStructureController(
                parserService, recordBuilder, writer, config);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listStructuresReturnsAllNames() throws Exception {
        when(parserService.getAllStructureNames())
                .thenReturn(List.of("SMSCBerCdr", "LTE-R10"));

        mockMvc.perform(get("/api/cdr/structures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("SMSCBerCdr"))
                .andExpect(jsonPath("$[1]").value("LTE-R10"));
    }

    @Test
    void structureDetailsReturnFieldsForKnownName() throws Exception {
        AsnStructure structure = AsnStructure.builder()
                .structureName("SMSCBerCdr")
                .fields(List.of(leaf("msisdn", "OCTET STRING", 1)))
                .build();
        when(parserService.getStructureByName("SMSCBerCdr")).thenReturn(structure);

        mockMvc.perform(get("/api/cdr/structures/SMSCBerCdr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structureName").value("SMSCBerCdr"))
                .andExpect(jsonPath("$.fields[0].fieldName").value("msisdn"));
    }

    @Test
    void unknownStructureNameReturnsNotFound() throws Exception {
        when(parserService.getStructureByName(anyString())).thenReturn(null);

        mockMvc.perform(get("/api/cdr/structures/DOES_NOT_EXIST"))
                .andExpect(status().isNotFound());
    }

    @Test
    void generateAsciiFileReturnsDownloadableAttachment() throws Exception {
        AsnStructure structure = AsnStructure.builder()
                .structureName("SMSCBerCdr")
                .fields(List.of(leaf("msisdn", "OCTET STRING", 1), leaf("duration", "INTEGER", 4)))
                .build();
        when(parserService.getStructureByName("SMSCBerCdr")).thenReturn(structure);

        String body = """
                { "structureName": "SMSCBerCdr", "recordCount": 3 }
                """;

        mockMvc.perform(post("/api/cdr/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("SMSCBerCdr.dat")));
    }

    @Test
    void generateWithoutStructureNameReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/cdr/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generateAboveMaxRecordCountReturnsBadRequest() throws Exception {
        AsnStructure structure = AsnStructure.builder()
                .structureName("SMSCBerCdr")
                .fields(List.of(leaf("msisdn", "OCTET STRING", 1)))
                .build();
        when(parserService.getStructureByName("SMSCBerCdr")).thenReturn(structure);

        String body = """
                { "structureName": "SMSCBerCdr", "recordCount": 5000 }
                """;

        mockMvc.perform(post("/api/cdr/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void previewTestRecordReturnsStrippedJson() throws Exception {
        AsnStructure structure = AsnStructure.builder()
                .structureName("SMSCBerCdr")
                .fields(List.of(leaf("duration", "INTEGER", 4)))
                .build();
        when(parserService.getStructureByName("SMSCBerCdr")).thenReturn(structure);

        mockMvc.perform(get("/api/cdr/generate-test/SMSCBerCdr"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // Stripped: the value is a bare number, not a '..'D literal.
                .andExpect(jsonPath("$.duration").isString());
    }
}
