package com.turkcell.cdrgenerator1.controller;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.exception.GlobalExceptionHandler;
import com.turkcell.cdrgenerator1.generator.BcdTimestampFactory;
import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.generator.FieldValueGenerator;
import com.turkcell.cdrgenerator1.generator.TbcdCodec;
import com.turkcell.cdrgenerator1.generator.source.RandomValueSource;
import com.turkcell.cdrgenerator1.generator.source.UserProvidedValueSource;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.service.CdrFileWriterService;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The column map that makes a {@code .txt} readable, and the temporary file that
 * is no longer left behind.
 *
 * <h2>Why a manifest</h2>
 *
 * <p>The text output has no header row, and its width is not a property of the
 * module alone: a {@code SEQUENCE OF} field contributes a column group per
 * element it happened to produce, so two files generated from the same structure
 * can differ in width. A consumer reading by position cannot tell which one it
 * is holding. {@code /generate/manifest} answers that by returning the text and
 * its column list from a single generation.</p>
 *
 * <h2>Why the temporary file</h2>
 *
 * <p>{@code /generate} used to serve a {@link Files#createTempFile} that nothing
 * ever deleted - every download left a file in the system temp directory for the
 * life of the host, and a measurement on a development machine found 878 of them.
 * The endpoint now renders in memory, so the question is not whether the file is
 * cleaned up but whether one is created at all.</p>
 */
class TextManifestAndTempFileTest {

    private static final String TEMP_FILE_PREFIX = "Demo_";

    private MockMvc mockMvc;
    private StructureParserService parserService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AsnField leaf(String name, String type) {
        return AsnField.builder().fieldName(name).fieldType(type).build();
    }

    @BeforeEach
    void setUp() {
        parserService = Mockito.mock(StructureParserService.class);

        CdrConfigProperties config = new CdrConfigProperties();
        config.setDefaultRecordCount(1);
        config.setMaxRecordCount(100);

        AiConfigProperties aiProperties = new AiConfigProperties();
        AsnSizeExtractor sizeExtractor = new AsnSizeExtractor();
        BcdTimestampFactory timestamps = new BcdTimestampFactory();

        CdrRecordBuilder recordBuilder = new CdrRecordBuilder(parserService, timestamps, config, List.of(
                new UserProvidedValueSource(),
                new RandomValueSource(new FieldValueGenerator(
                        new TbcdCodec(), aiProperties, sizeExtractor, timestamps))));

        CdrStructureController controller = new CdrStructureController(
                parserService, recordBuilder, new CdrFileWriterService(), config,
                TestAiSupport.disabledSupplier(aiProperties));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** A structure with a flat body, so the column set is fully predictable. */
    private void registerFlatStructure() {
        AsnStructure structure = AsnStructure.builder()
                .structureName("Demo")
                .fields(List.of(leaf("msisdn", "IA5String"), leaf("duration", "INTEGER"),
                        leaf("cause", "INTEGER")))
                .build();
        when(parserService.getStructureByName(eq("Demo"), any(), any(), any(), anyBoolean())).thenReturn(structure);
    }

    private String request(int recordCount) {
        return """
                { "structureName": "Demo", "recordCount": %d,
                  "fieldValues": { "msisdn": "905321234567", "duration": "42", "cause": "0" } }
                """.formatted(recordCount);
    }

    private JsonNode manifest(int recordCount) throws Exception {
        String body = mockMvc.perform(post("/api/cdr/generate/manifest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(recordCount)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private byte[] generate(int recordCount) throws Exception {
        return mockMvc.perform(post("/api/cdr/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(recordCount)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void theManifestNamesEveryColumnInTheTextItReturns() throws Exception {
        registerFlatStructure();

        JsonNode manifest = manifest(2);
        List<String> columns = new ArrayList<>();
        manifest.get("columns").forEach(node -> columns.add(node.asString()));

        assertThat(columns).containsExactly("msisdn", "duration", "cause");
        assertThat(manifest.get("columnCount").asInt()).isEqualTo(columns.size());
        assertThat(manifest.get("structureName").asString()).isEqualTo("Demo");
        assertThat(manifest.get("recordCount").asInt()).isEqualTo(2);
    }

    /**
     * The count in the manifest has to agree with the separators in the text -
     * that is the whole point of returning them together.
     */
    @Test
    void theColumnCountMatchesTheSeparatorsInEveryLine() throws Exception {
        registerFlatStructure();

        JsonNode manifest = manifest(3);
        int columnCount = manifest.get("columnCount").asInt();

        assertThat(manifest.get("text").asString().lines())
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line.split("\\|", -1)).hasSize(columnCount));
    }

    /**
     * The i'th column names the i'th value, not merely some permutation of the
     * field names. A manifest whose order did not match would be worse than none.
     */
    @Test
    void theColumnOrderMatchesTheValueOrder() throws Exception {
        registerFlatStructure();

        JsonNode manifest = manifest(1);
        List<String> columns = new ArrayList<>();
        manifest.get("columns").forEach(node -> columns.add(node.asString()));
        String[] values = manifest.get("text").asString().lines().findFirst().orElseThrow()
                .split("\\|", -1);

        assertThat(values[columns.indexOf("msisdn")]).isEqualTo("905321234567");
        assertThat(values[columns.indexOf("duration")]).isEqualTo("42");
        assertThat(values[columns.indexOf("cause")]).isEqualTo("0");
    }

    /** Every record is a line, and every line ends with LF rather than the host's separator. */
    @Test
    void oneLineFeedTerminatedLinePerRecord() throws Exception {
        registerFlatStructure();

        String text = manifest(3).get("text").asString();

        assertThat(text).doesNotContain("\r");
        assertThat(text.chars().filter(c -> c == '\n').count()).isEqualTo(3);
    }

    /**
     * {@code /generate} is untouched by the manifest endpoint existing: same
     * name, same content type, same body.
     */
    @Test
    void theDownloadKeepsItsNameContentTypeAndBody() throws Exception {
        registerFlatStructure();

        var response = mockMvc.perform(post("/api/cdr/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(2)))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getContentType()).startsWith(MediaType.TEXT_PLAIN_VALUE);
        assertThat(response.getHeader("Content-Disposition")).contains("Demo.txt");
        assertThat(response.getContentAsString().strip().lines().toList())
                .containsExactly("905321234567|42|0", "905321234567|42|0");
    }

    /**
     * Both endpoints run one generation path, so the bytes a caller downloads are
     * the bytes the manifest describes. Anything that rendered them separately
     * could drift.
     */
    @Test
    void theDownloadAndTheManifestTextAgreeByteForByte() throws Exception {
        registerFlatStructure();

        assertThat(new String(generate(2), java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo(manifest(2).get("text").asString());
    }

    @Test
    void aSuccessfulDownloadCreatesNoTemporaryFile() throws Exception {
        registerFlatStructure();

        long before = temporaryFileCount();
        generate(5);

        assertThat(temporaryFileCount())
                .as("the endpoint renders in memory; it must leave nothing in the temp directory")
                .isEqualTo(before);
    }

    @Test
    void repeatedDownloadsDoNotAccumulateTemporaryFiles() throws Exception {
        registerFlatStructure();

        long before = temporaryFileCount();
        for (int i = 0; i < 20; i++) {
            generate(2);
            manifest(2);
        }

        assertThat(temporaryFileCount()).isEqualTo(before);
    }

    @Test
    void aRefusedRequestLeavesNothingBehindEither() throws Exception {
        AsnStructure noFields = AsnStructure.builder()
                .structureName("Demo").fields(List.of()).build();
        when(parserService.getStructureByName(eq("Demo"), any(), any(), any(), anyBoolean())).thenReturn(noFields);

        long before = temporaryFileCount();
        mockMvc.perform(post("/api/cdr/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(1)))
                .andExpect(status().isBadRequest());

        assertThat(temporaryFileCount()).isEqualTo(before);
    }

    /**
     * The writer still hands a file to callers that ask for one - the validation
     * sample writer copies it into {@code target/validation}. Those callers own
     * what they get back; only the endpoint stopped using this path.
     */
    @Test
    void theWriterStillServesCallersThatWantAFile() throws Exception {
        Path file = new CdrFileWriterService()
                .writeCdrFile("Demo", List.of(new java.util.LinkedHashMap<>(
                        java.util.Map.of("msisdn", "\"905321234567\""))));
        try {
            assertThat(Files.readString(file)).isEqualTo("905321234567\n");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Temporary files this test's structure name would produce, if any were made. */
    private long temporaryFileCount() throws IOException {
        Path tempDirectory = Paths.get(System.getProperty("java.io.tmpdir"));
        try (DirectoryStream<Path> entries =
                     Files.newDirectoryStream(tempDirectory, TEMP_FILE_PREFIX + "*.txt")) {
            long count = 0;
            for (Path ignored : entries) {
                count++;
            }
            return count;
        }
    }
}
