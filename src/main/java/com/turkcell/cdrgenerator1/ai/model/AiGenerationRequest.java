package com.turkcell.cdrgenerator1.ai.model;

import com.turkcell.cdrgenerator1.model.AsnField;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class AiGenerationRequest {

    String structureName;

    /** Yalnizca kullanicinin doldurmadigi alanlar. */
    List<AsnField> fieldsToFill;

    /** Kullanicinin sabitledigi degerler. AI bunlarla tutarli uretim yapar. */
    Map<String, String> fixedValues;

    int recordCount;
}