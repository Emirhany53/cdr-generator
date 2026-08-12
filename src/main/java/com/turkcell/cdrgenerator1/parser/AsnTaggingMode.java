package com.turkcell.cdrgenerator1.parser;

/**
 * What a module header says about tagging
 * ({@code ... DEFINITIONS <mode> TAGS ::=}) - including saying nothing.
 *
 * <p>The mode decides how a tag that carries no per-site
 * {@code IMPLICIT}/{@code EXPLICIT} keyword is encoded, and (for
 * {@link #AUTOMATIC}) whether untagged fields receive sequential context
 * tags.</p>
 *
 * <p>{@link #UNSPECIFIED} exists so the header's silence survives as far as the
 * two places that have to act on it, instead of being resolved to a concrete
 * mode at parse time. They do NOT resolve it the same way, and that is the
 * whole point: {@code AsnFieldTreeResolver.resolveExplicit} reads it as
 * IMPLICIT for a field tag - a compatibility rule measured against EMM - while
 * {@code readLeadingTag} keeps X.680 31.2.7's EXPLICIT for a tag written on a
 * type. Collapsing the two would either give up the field-level rule or extend
 * it to type-level tags, which nothing has verified. See
 * {@code AsnTypeRegistryBuilder.detectTaggingMode} for the evidence.</p>
 */
public enum AsnTaggingMode {
    IMPLICIT,
    EXPLICIT,
    AUTOMATIC,
    /** The header named no mode; each site applies its own default. */
    UNSPECIFIED
}
