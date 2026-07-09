package com.turkcell.cdrgenerator1.exception;


public class StructureNotFoundException extends RuntimeException {

    public StructureNotFoundException(String structureName) {
        super("Structure not found: " + structureName);
    }
}