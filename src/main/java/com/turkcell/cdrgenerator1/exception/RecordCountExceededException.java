package com.turkcell.cdrgenerator1.exception;

public class RecordCountExceededException extends RuntimeException {

    private static final String MESSAGE_TEMPLATE =
            "Istenen kayit sayisi (%d) gecersiz. Izin verilen aralik: 1 - %d.";

    public RecordCountExceededException(int requested, int maximum) {
        super(MESSAGE_TEMPLATE.formatted(requested, maximum));
    }
}