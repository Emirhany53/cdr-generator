package com.turkcell.cdrgenerator1.service.verify;

/**
 * How much weight a finding carries.
 *
 * <p>{@link #ERROR} means a decoder is entitled to reject the record - every
 * rejection EMM has sent so far falls here. {@link #WARNING} means the bytes
 * are suspicious but the verifier cannot be certain, most often because it
 * could not match a node to a schema field and therefore had to judge it
 * without knowing what it was supposed to be.</p>
 */
public enum FindingSeverity {
    ERROR,
    WARNING
}
