package com.turkcell.cdrgenerator1.model;

/**
 * The tagging keyword the SCHEMA wrote at one site - as opposed to
 * {@link AsnField#isExplicit()}, which is what the encoder decided to do about
 * it.
 *
 * <p>The two are not the same, and the difference is the whole reason this
 * exists. {@code qosRequested [1] EXPLICIT QoSInformation} declares EXPLICIT and
 * goes out IMPLICIT, because EMM refused the wrapper. Collapsing both into one
 * boolean lost the question a verifier has to ask - "was this site allowed to
 * drop its wrapper?" - and left every compatibility rule checkable only against
 * the tree that produced the bytes in the first place.</p>
 *
 * <p>{@link #NONE} is a value, not a default to fall back on: "the schema wrote
 * no keyword here" is exactly the condition three EMM measurements turn on
 * (IMSCDRS for field tags, FDRInput/Audit for type tags, CHF for a tag on a
 * CHOICE). It must stay distinguishable from a written {@code IMPLICIT}, which
 * says the same thing about the bytes but for a different reason.</p>
 */
public enum AsnDeclaredTagging {

    /** The site wrote {@code EXPLICIT}. */
    EXPLICIT,

    /** The site wrote {@code IMPLICIT}. */
    IMPLICIT,

    /** The site wrote no keyword; the module default decides. */
    NONE
}
