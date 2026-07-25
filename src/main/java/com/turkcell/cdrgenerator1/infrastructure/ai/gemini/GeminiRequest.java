package com.turkcell.cdrgenerator1.infrastructure.ai.gemini;

import java.util.List;
import java.util.Map;

public record GeminiRequest(List<Content> contents, GenerationConfig generationConfig) {

    public record Content(List<Part> parts) {
    }

    public record Part(String text) {
    }

    public record GenerationConfig(double temperature,
                                   String responseMimeType,
                                   Map<String, Object> responseSchema) {
    }

    public static GeminiRequest of(String prompt,
                                   double temperature,
                                   String responseMimeType,
                                   Map<String, Object> responseSchema) {
        return new GeminiRequest(
                List.of(new Content(List.of(new Part(prompt)))),
                new GenerationConfig(temperature, responseMimeType, responseSchema));
    }
}