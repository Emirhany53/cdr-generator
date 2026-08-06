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

    /**
     * The tag a STRUCTURED type puts on itself, kept verbatim as written:
     * {@code "[APPLICATION 1] "} from {@code TransferBatch ::= [APPLICATION 1]
     * SEQUENCE { ... }}, or {@code "[0] IMPLICIT "} from
     * {@code Row ::= [0] IMPLICIT SEQUENCE { ... }}. Null or blank when the type
     * tags nothing.
     *
     * <p>An ALIAS keeps the same annotation inside {@link #aliasTarget}, where
     * the resolver has always read it. A structured type had nowhere to keep it,
     * so the text between {@code ::=} and {@code SEQUENCE} was dropped at parse
     * time and no later stage could recover it: TAP-0309 records came out as a
     * universal {@code 30} instead of {@code 61}, and every APPLICATION-tagged
     * element under them was flattened the same way.</p>
     */
    private String tagPrefix;
}