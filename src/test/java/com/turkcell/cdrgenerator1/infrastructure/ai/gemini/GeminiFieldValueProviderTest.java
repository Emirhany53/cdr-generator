package com.turkcell.cdrgenerator1.infrastructure.ai.gemini;

import com.turkcell.cdrgenerator1.ai.model.AiGenerationRequest;
import com.turkcell.cdrgenerator1.ai.model.AiGenerationResult;
import com.turkcell.cdrgenerator1.ai.prompt.PromptBuilder;
import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.model.AsnField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The one class allowed to know the word "Gemini", tested without a network.
 *
 * <h2>Why every failure has to be quiet</h2>
 *
 * <p>The AI is an enrichment, never a dependency: {@code CdrRecordBuilder} runs
 * a chain of value sources and the random one is always last, so an empty answer
 * costs realism and nothing else. That only holds if this class refuses to throw.
 * A missing key, a refused key, a timeout, a truncated candidate, a model that
 * answered in prose - each has to come back as {@link AiGenerationResult#empty()}
 * so the chain falls through. These tests are mostly a catalogue of the ways the
 * call can go wrong and the single answer all of them get.</p>
 */
class GeminiFieldValueProviderTest {

    private static final String MODEL = "gemini-test";
    private static final String BASE_URL = "https://example.invalid/v1beta";
    private static final String ENDPOINT = BASE_URL + "/models/" + MODEL + ":generateContent";

    private MockRestServiceServer server;
    private AiConfigProperties properties;
    private GeminiFieldValueProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();

        properties = new AiConfigProperties();
        properties.setMaxRetries(1);
        properties.setRetryBackoffMs(0);
        properties.getGemini().setModel(MODEL);
        properties.getGemini().setApiKey("test-key");
        properties.getGemini().setTemperature(0.9);

        PromptBuilder promptBuilder = request -> "PROMPT for " + request.getStructureName();
        provider = new GeminiFieldValueProvider(builder.build(), properties, promptBuilder,
                new GeminiResponseSchemaFactory(new AsnSizeExtractor()), new ObjectMapper());
    }

    private AiGenerationRequest request() {
        return AiGenerationRequest.builder()
                .structureName("Demo")
                .fieldsToFill(List.of(
                        AsnField.builder().fieldName("msisdn").fieldType("IA5String").build(),
                        AsnField.builder().fieldName("duration").fieldType("INTEGER").build()))
                .recordCount(2)
                .build();
    }

    /** Wraps a model answer in the envelope the API actually returns. */
    private static String envelope(String modelText) {
        return """
                { "candidates": [ { "content": { "parts": [ { "text": %s } ] },
                                    "finishReason": "STOP" } ] }
                """.formatted(new ObjectMapper().writeValueAsString(modelText));
    }

    private void respondWith(String body) {
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void aWellFormedAnswerBecomesRecords() {
        respondWith(envelope("""
                [ { "msisdn": "05301234567", "duration": "42" },
                  { "msisdn": "05559876543", "duration": "17" } ]"""));

        AiGenerationResult result = provider.generate(request());

        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getRecords().get(0))
                .containsEntry("msisdn", "05301234567")
                .containsEntry("duration", "42");
        server.verify();
    }

    /**
     * The key goes in a header, never in the query string - a URL carrying it
     * would end up in access logs and proxy caches.
     */
    @Test
    void theKeyTravelsInAHeaderAndThePromptInTheBody() {
        server.expect(once(), requestTo(ENDPOINT))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("PROMPT for Demo"))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andExpect(jsonPath("$.generationConfig.responseSchema.type").value("ARRAY"))
                .andRespond(withSuccess(envelope("[]"), MediaType.APPLICATION_JSON));

        provider.generate(request());

        server.verify();
    }

    /** Nothing to say is a valid answer, and not an error. */
    @Test
    void anEmptyArrayIsAValidAnswerWithNoRecords() {
        respondWith(envelope("[]"));

        assertThat(provider.generate(request()).getRecords()).isEmpty();
    }

    /**
     * Fewer records than asked for are passed through as they are.
     * {@code AiValueSource} checks the index against the list size, so the
     * uncovered records simply fall to random generation.
     */
    @Test
    void fewerRecordsThanRequestedArePassedThroughUnchanged() {
        respondWith(envelope("""
                [ { "msisdn": "05301234567", "duration": "42" } ]"""));

        assertThat(provider.generate(request()).getRecords()).hasSize(1);
    }

    @Test
    void aFirstAttemptThatFailsIsRetriedAndTheSecondAnswerIsUsed() {
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withServerError());
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(envelope("""
                        [ { "msisdn": "05301234567", "duration": "42" } ]"""),
                        MediaType.APPLICATION_JSON));

        assertThat(provider.generate(request()).getRecords()).hasSize(1);
        server.verify();
    }

    @Test
    void everyAttemptFailingReturnsEmptyRatherThanThrowing() {
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withServerError());
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withServerError());

        assertThat(provider.generate(request()).getRecords()).isEmpty();
        server.verify();
    }

    /** A refused or expired key is just another failed attempt. */
    @Test
    void aRefusedKeyIsJustAnotherFailedAttempt() {
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(provider.generate(request()).getRecords()).isEmpty();
    }

    private static org.springframework.test.web.client.response.DefaultResponseCreator
            withStatus(HttpStatus status) {
        return org.springframework.test.web.client.response.MockRestResponseCreators
                .withStatus(status);
    }

    /** Prose instead of JSON - the commonest way a model answer is unusable. */
    @Test
    void aNonJsonModelAnswerYieldsEmpty() {
        respondWith(envelope("Tabii! Iste ornek CDR kayitlari:"));
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(envelope("hala JSON degil"), MediaType.APPLICATION_JSON));

        assertThat(provider.generate(request()).getRecords()).isEmpty();
    }

    /** A candidate cut short by a token limit carries no parts. */
    @Test
    void aCandidateWithoutPartsYieldsEmpty() {
        String truncated = """
                { "candidates": [ { "content": { }, "finishReason": "MAX_TOKENS" } ] }""";
        respondWith(truncated);
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(truncated, MediaType.APPLICATION_JSON));

        assertThat(provider.generate(request()).getRecords()).isEmpty();
    }

    /**
     * With no key configured the provider must not reach the network at all -
     * {@code server.verify()} would fail on an unexpected call, and a real
     * deployment would otherwise spend the retry budget on a guaranteed 401.
     */
    @Test
    void anUnconfiguredKeyMakesNoCallAndReturnsEmpty() {
        properties.getGemini().setApiKey("");

        assertThat(provider.generate(request()).getRecords()).isEmpty();
        server.verify();
    }

    @Test
    void aNullKeyIsTreatedTheSameWay() {
        properties.getGemini().setApiKey(null);

        assertThat(provider.generate(request()).getRecords()).isEmpty();
        server.verify();
    }
}
