package com.turkcell.cdrgenerator1.exception;


public class RecordCountExceededException extends RuntimeException {

    public RecordCountExceededException(int requestedCount, int maxAllowedCount) {
        super("Requested record count (" + requestedCount
                + ") exceeds the maximum allowed record count (" + maxAllowedCount + ")");
    }
}