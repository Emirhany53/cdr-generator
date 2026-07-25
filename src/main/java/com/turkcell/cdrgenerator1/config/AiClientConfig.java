package com.turkcell.cdrgenerator1.config;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class AiClientConfig {

    public static final String GEMINI_REST_CLIENT = "geminiRestClient";

    private final AiConfigProperties aiConfigProperties;

    /**
     * Gemini yanitindaki JSON metnini ayristirmak icin kullanilir.
     * Ortamda hazir bir ObjectMapper varsa o kullanilir, yoksa bu olusturulur.
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean(GEMINI_REST_CLIENT)
    public RestClient geminiRestClient() {
        final Duration timeout = Duration.ofMillis(aiConfigProperties.getTimeoutMs());

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        return RestClient.builder()
                .baseUrl(aiConfigProperties.getGemini().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}