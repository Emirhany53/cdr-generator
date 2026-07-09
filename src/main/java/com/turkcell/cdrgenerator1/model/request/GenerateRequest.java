package com.turkcell.cdrgenerator1.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateRequest {

    private String structureName;
    private Map<String, String> fieldValues;
    private Integer recordCount;

    private String content;
}