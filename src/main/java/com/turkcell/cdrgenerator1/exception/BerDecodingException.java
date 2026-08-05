package com.turkcell.cdrgenerator1.exception;

/**
 * Raised when a byte sequence cannot be read as BER at all - a truncated
 * length, a tag that runs past the end of the buffer, a child TLV that
 * overflows its parent.
 *
 * <p>Deliberately distinct from a verification FINDING. A finding says "these
 * bytes are readable BER but do not match the schema"; this exception says
 * "these bytes are not BER". Only the first is something a rule reports.</p>
 */
public class BerDecodingException extends RuntimeException {

    public BerDecodingException(String message) {
        super(message);
    }
}
