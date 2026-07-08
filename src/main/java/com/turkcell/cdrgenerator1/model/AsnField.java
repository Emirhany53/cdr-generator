package com.turkcell.cdrgenerator1.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsnField {
    private String fieldName;
    private String fieldType;
    private boolean isOptional;
}