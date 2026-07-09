package com.turkcell.cdrgenerator1.exception;

/** Thrown when a requested structure name is not present in the parsed set. */
public class StructureNotFoundException extends RuntimeException {

    public StructureNotFoundException(String structureName) {
        super("Structure not found: " + structureName);
    }
}