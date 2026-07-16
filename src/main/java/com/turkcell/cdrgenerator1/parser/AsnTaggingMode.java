package com.turkcell.cdrgenerator1.parser;

/**
 * The three ASN.1 module-level tagging modes, declared in the module header
 * ({@code ... DEFINITIONS <mode> TAGS ::=}). When the header omits the keyword,
 * the ASN.1 standard default is {@link #EXPLICIT}.
 *
 * <p>The mode decides how a field that carries a {@code [n]} tag but no
 * per-field {@code IMPLICIT}/{@code EXPLICIT} keyword must be encoded, and (for
 * {@link #AUTOMATIC}) whether untagged fields receive sequential context tags.</p>
 */
public enum AsnTaggingMode {
    IMPLICIT,
    EXPLICIT,
    AUTOMATIC
}
