package com.turkcell.cdrgenerator1.model.request;

import lombok.Data;

import java.util.Map;


@Data
public class GenerateRequest {
    private String structureName;

    private String contents;
    private Map<String, String> fieldValues;

    private Map<String, String> choiceSelections;
    private Integer recordCount;


    public void setContent(String content) {
        this.contents = content;
    }
}