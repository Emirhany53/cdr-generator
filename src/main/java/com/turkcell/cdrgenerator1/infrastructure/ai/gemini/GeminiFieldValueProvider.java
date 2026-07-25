package com.turkcell.cdrgenerator1.infrastructure.ai.gemini;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.turkcell.cdrgenerator1.ai.AiFieldValueProvider;
import com.turkcell.cdrgenerator1.ai.model.AiGenerationRequest;
import com.turkcell.cdrgenerator1.ai.model.AiGenerationResult;
import com.turkcell.cdrgenerator1.ai.prompt.PromptBuilder;
import com.turkcell.cdrgenerator1.config.AiClientConfig;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** "Gemini" kelimesinin gectigi tek paket burasidir. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.cdr.ai", name = "provider", havingValue = "gemini")
public class GeminiFieldValueProvider implements AiFieldValueProvider {

    private static final String GENERATE_CONTENT_PATH = "/models/{model}:generateContent";
    private static final String API_KEY_HEADER = "x-goog-api-key";
    private static final String JSON_MIME_TYPE = "application/json";
    private static final int FIRST_ATTEMPT = 1;

    private final RestClient restClient;
    private final AiConfigProperties aiConfigProperties;
    private final PromptBuilder promptBuilder;
    private final GeminiResponseSchemaFactory responseSchemaFactory;
    private final ObjectMapper objectMapper;

    public GeminiFieldValueProvider(@Qualifier(AiClientConfig.GEMINI_REST_CLIENT) RestClient restClient,
                                    AiConfigProperties aiConfigProperties,
                                    PromptBuilder promptBuilder,
                                    GeminiResponseSchemaFactory responseSchemaFactory,
                                    ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.aiConfigProperties = aiConfigProperties;
        this.promptBuilder = promptBuilder;
        this.responseSchemaFactory = responseSchemaFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiGenerationResult generate(AiGenerationRequest request) {
        if (!aiConfigProperties.isGeminiConfigured()) {
            log.warn("Gemini API anahtari tanimli degil, yapay zeka uretimi atlaniyor.");
            return AiGenerationResult.empty();
        }
        final int maxAttempts = FIRST_ATTEMPT + aiConfigProperties.getMaxRetries();

        for (int attempt = FIRST_ATTEMPT; attempt <= maxAttempts; attempt++) {
            Optional<AiGenerationResult> result = attemptGenerate(request, attempt);
            if (result.isPresent()) {
                return result.get();
            }
            sleepBeforeRetry(attempt, maxAttempts);
        }
        log.error("Gemini uretimi {} denemede basarisiz oldu, rastgele uretime dusuluyor.", maxAttempts);
        return AiGenerationResult.empty();
    }

    private Optional<AiGenerationResult> attemptGenerate(AiGenerationRequest request, int attempt) {
        try {
            GeminiRequest body = GeminiRequest.of(
                    promptBuilder.build(request),
                    aiConfigProperties.getGemini().getTemperature(),
                    JSON_MIME_TYPE,
                    responseSchemaFactory.create(request.getFieldsToFill()));

            GeminiResponse response = restClient.post()
                    .uri(GENERATE_CONTENT_PATH, aiConfigProperties.getGemini().getModel())
                    .header(API_KEY_HEADER, aiConfigProperties.getGemini().getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(GeminiResponse.class);

            return parse(response);

        } catch (Exception exception) {
            log.warn("Gemini cagrisi basarisiz. Deneme: {}, Sebep: {}", attempt, exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<AiGenerationResult> parse(GeminiResponse response) {
        if (Objects.isNull(response)) {
            return Optional.empty();
        }
        Optional<String> rawJson = response.firstText();
        if (rawJson.isEmpty()) {
            log.warn("Gemini yaniti bos icerik dondurdu.");
            return Optional.empty();
        }
        try {
            List<Map<String, String>> records =
                    objectMapper.readValue(rawJson.get(), new TypeReference<>() {
                    });
            log.info("Gemini {} kayit uretti.", records.size());
            return Optional.of(new AiGenerationResult(records));
        } catch (Exception exception) {
            log.warn("Gemini yaniti JSON olarak ayristirilamadi: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private void sleepBeforeRetry(int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) {
            return;
        }
        try {
            Thread.sleep(aiConfigProperties.getRetryBackoffMs() * attempt);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}