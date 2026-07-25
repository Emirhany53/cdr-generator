package com.turkcell.cdrgenerator1.ai;

import com.turkcell.cdrgenerator1.ai.model.AiGenerationRequest;
import com.turkcell.cdrgenerator1.ai.model.AiGenerationResult;

/**
 * Uygulamanin yapay zeka ile TEK temas noktasi (port).
 * Hata durumunda exception firlatmaz, bos sonuc doner.
 */
public interface AiFieldValueProvider {

    AiGenerationResult generate(AiGenerationRequest request);
}