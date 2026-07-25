package com.turkcell.cdrgenerator1.infrastructure.ai.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiResponse(List<Candidate> candidates) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(Content content, String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(List<Part> parts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String text) {
    }

    public Optional<String> firstText() {
        if (Objects.isNull(candidates) || candidates.isEmpty()) {
            return Optional.empty();
        }
        Content content = candidates.get(0).content();
        if (Objects.isNull(content) || Objects.isNull(content.parts()) || content.parts().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(content.parts().get(0).text()).filter(text -> !text.isBlank());
    }
}