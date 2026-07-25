package com.turkcell.cdrgenerator1.exception;

public class StructureNotFoundException extends RuntimeException {

    private static final String MESSAGE_TEMPLATE = "'%s' adinda bir ASN.1 yapisi bulunamadi.";

    public StructureNotFoundException(String structureName) {
        super(MESSAGE_TEMPLATE.formatted(structureName));
    }
}