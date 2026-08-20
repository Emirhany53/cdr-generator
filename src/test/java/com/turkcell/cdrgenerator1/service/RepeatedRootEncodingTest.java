package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTaggingMode;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers Bulgu 8 (round 19, docs/emm-validation-log.md): an explicitly named
 * {@code X ::= SEQUENCE OF Y} root is encoded as a genuine one-element
 * collection instead of being flattened to Y written bare.
 *
 * <p>ABSSDPXML is the corpus example: {@code SnapshotData ::= SEQUENCE OF
 * SnapshotRecord}, refused by EMM with "Invalid length 54" (the whole file)
 * because the generator wrote SnapshotRecord's own fields directly, with no
 * outer SEQUENCE OF wrapper at all.</p>
 */
class RepeatedRootEncodingTest {

    private final AsnTypeRegistryBuilder registryBuilder = new AsnTypeRegistryBuilder();
    private final AsnFieldTreeResolver resolver = new AsnFieldTreeResolver();
    private final BerEncoderService encoder =
            new BerEncoderService(new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));

    @Test
    void anExplicitlyNamedSequenceOfWrapperIsRepeatedRoot() {
        var registry = registryBuilder.buildRegistry(
                "M DEFINITIONS ::= BEGIN "
                        + "SnapshotData ::= SEQUENCE OF SnapshotRecord "
                        + "SnapshotRecord ::= SEQUENCE { a [0] INTEGER } "
                        + "END");

        AsnFieldTreeResolver.ResolvedRoot root = resolver.resolveRoot(
                registry, "SnapshotData", Map.of(), AsnTaggingMode.IMPLICIT);

        assertTrue(root.repeatedRoot(), "SnapshotData ::= SEQUENCE OF SnapshotRecord must be a repeated root");
        assertFalse(root.repeatedRootIsSet(), "SEQUENCE OF, not SET OF");
        assertEquals(1, root.fields().size());
        assertEquals("a", root.fields().get(0).getFieldName());
    }

    @Test
    void aSetOfWrapperIsRepeatedRootAsSet() {
        var registry = registryBuilder.buildRegistry(
                "M DEFINITIONS ::= BEGIN "
                        + "Wrapper ::= SET OF Element "
                        + "Element ::= SEQUENCE { a [0] INTEGER } "
                        + "END");

        AsnFieldTreeResolver.ResolvedRoot root = resolver.resolveRoot(
                registry, "Wrapper", Map.of(), AsnTaggingMode.IMPLICIT);

        assertTrue(root.repeatedRoot());
        assertTrue(root.repeatedRootIsSet(), "SET OF must select the SET wrapper, not SEQUENCE");
    }

    /**
     * The root-selection heuristic never reaches this branch: it already
     * resolves a "SEQUENCE OF X" wrapper straight to X
     * (StructureParserService#selectRootTypeName), so calling resolveRoot with
     * X's own name - exactly what the heuristic does - must NOT be treated as
     * a repeated root. Regression guard for the 84 modules this heuristic
     * already picks a root for.
     */
    @Test
    void resolvingTheInnerTypeDirectlyIsNotARepeatedRoot() {
        var registry = registryBuilder.buildRegistry(
                "M DEFINITIONS ::= BEGIN "
                        + "SnapshotData ::= SEQUENCE OF SnapshotRecord "
                        + "SnapshotRecord ::= SEQUENCE { a [0] INTEGER } "
                        + "END");

        AsnFieldTreeResolver.ResolvedRoot root = resolver.resolveRoot(
                registry, "SnapshotRecord", Map.of(), AsnTaggingMode.IMPLICIT);

        assertFalse(root.repeatedRoot());
    }

    @Test
    void untaggedWrapperEncodesAsOneElementInsideAUniversalSequenceOf() {
        AsnStructure structure = AsnStructure.builder()
                .structureName("ABSSDPXML")
                .repeatedRoot(true)
                .fields(fieldsOf(registryBuilder, resolver, "a [0] INTEGER"))
                .build();

        byte[] out = encoder.encodeRecord(structure, Map.of("a", "'7'D"));

        // Element: 30 03 80 01 07 (universal SEQUENCE, field a = context [0] 7).
        // Wrapper: one more universal SEQUENCE (SnapshotData carries no tag of
        // its own) around that whole element.
        assertArrayEquals(
                new byte[]{0x30, 0x05, 0x30, 0x03, (byte) 0x80, 0x01, 0x07},
                out);
    }

    /**
     * A wrapper that writes its own tag ({@code Wrapper ::= [APPLICATION 5]
     * SEQUENCE OF Element}) is still detected as a repeated root - but the
     * tag itself is not read back out (rootTagCarrier stays null), because
     * AsnTypeRegistryBuilder only fills tagPrefix for a structured definition
     * with a brace body; an ALIAS like this one never gets one, and no module
     * in the corpus has this shape to measure the extraction against. This
     * locks in that deliberate gap rather than letting it drift silently -
     * see the comment in AsnFieldTreeResolver#resolveRoot.
     */
    @Test
    void aTaggedWrapperIsStillARepeatedRootButItsOwnTagIsNotReadBack() {
        var registry = registryBuilder.buildRegistry(
                "M DEFINITIONS ::= BEGIN "
                        + "Wrapper ::= [APPLICATION 5] SEQUENCE OF Element "
                        + "Element ::= SEQUENCE { a [0] INTEGER } "
                        + "END");

        AsnFieldTreeResolver.ResolvedRoot root = resolver.resolveRoot(
                registry, "Wrapper", Map.of(), AsnTaggingMode.IMPLICIT);

        assertTrue(root.repeatedRoot());
        assertNull(root.rootTagCarrier());
    }

    private static java.util.List<com.turkcell.cdrgenerator1.model.AsnField> fieldsOf(
            AsnTypeRegistryBuilder registryBuilder, AsnFieldTreeResolver resolver, String fieldLine) {
        var registry = registryBuilder.buildRegistry(
                "M DEFINITIONS ::= BEGIN Element ::= SEQUENCE { " + fieldLine + " } END");
        return resolver.resolveRoot(registry, "Element", Map.of(), AsnTaggingMode.IMPLICIT).fields();
    }
}
