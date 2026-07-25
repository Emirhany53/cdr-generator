package com.turkcell.cdrgenerator1.ai.prompt;

import com.turkcell.cdrgenerator1.ai.model.AiGenerationRequest;

public interface PromptBuilder {

    String build(AiGenerationRequest request);
}