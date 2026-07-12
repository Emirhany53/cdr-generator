package com.turkcell.cdrgenerator1.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsnField {
    private String fieldName;
    private String fieldType;
    private boolean optional;
    private boolean repeated;


    private Integer tagNumber;

    /** BER tag class from the [..] annotation; CONTEXT when only a number is given. */
    private BerTagClass tagClass;

    private boolean explicit;

    private List<AsnField> children;
}