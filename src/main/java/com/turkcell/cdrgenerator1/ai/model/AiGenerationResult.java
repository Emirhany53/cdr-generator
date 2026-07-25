package com.turkcell.cdrgenerator1.ai.model;

import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
public class AiGenerationResult {

    /** Her eleman bir kayittir: alan yolu -> deger. */
    List<Map<String, String>> records;

    public static AiGenerationResult empty() {
        return new AiGenerationResult(List.of());
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }
}