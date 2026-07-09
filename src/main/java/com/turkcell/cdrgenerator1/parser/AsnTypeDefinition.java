package com.turkcell.cdrgenerator1.parser;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AsnTypeDefinition {
    private String typeName;
    private AsnTypeKind kind;
    private String rawBody;
    private String aliasTarget;
}