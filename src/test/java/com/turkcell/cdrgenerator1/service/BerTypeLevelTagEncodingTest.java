package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.SelfCheckProperties;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import com.turkcell.cdrgenerator1.service.verify.BerVerificationResult;
import com.turkcell.cdrgenerator1.service.verify.BerVerifier;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import com.turkcell.cdrgenerator1.service.verify.rule.DuplicateTagRule;
import com.turkcell.cdrgenerator1.service.verify.rule.SetOrderingRule;
import com.turkcell.cdrgenerator1.service.verify.rule.TagShapeRule;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type may tag ITSELF - {@code TransferBatch ::= [APPLICATION 1] SEQUENCE
 * { ... }} - and that tag replaces (IMPLICIT) or wraps (EXPLICIT) the universal
 * SEQUENCE the body would otherwise carry.
 *
 * <p>The registry used to keep only the kind and the body, so the annotation
 * between {@code ::=} and {@code SEQUENCE} was gone before any later stage could
 * read it. TAP-0309 records came out as a universal {@code 30} instead of
 * {@code 61}, and 41 modules were flattened the same way.</p>
 */
class BerTypeLevelTagEncodingTest {

    private final StructureParserService parser = new StructureParserService(
            null, new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());
    private final BerEncoderService encoder = new BerEncoderService(
            new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));

    private String encodeHex(AsnStructure structure, Map<String, Object> record) {
        return HexFormat.of().formatHex(encoder.encodeRecord(structure, record));
    }

    @Test
    void anImplicitlyTaggedRootTypeReplacesTheUniversalSequenceTag() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS ::= BEGIN
                Row ::= [0] IMPLICIT SEQUENCE {
                    a [1] IMPLICIT INTEGER
                }
                END
                """);

        assertNotNull(structure.getRootTagCarrier());
        // a0 = CONTEXT 0 constructed, holding 81 01 07 - no universal SEQUENCE
        // between them, because IMPLICIT tagging REPLACES that tag.
        assertEquals("a003810107", encodeHex(structure, Map.of("a", "7")));
    }

    /**
     * The keyword is what decides, and it used to be lost: the prefix was stored
     * trimmed, the reader's pattern ends the keyword on whitespace, so
     * {@code "[0] IMPLICIT"} matched as a bare {@code [0]} and fell back to the
     * module's default. Every {@code [0] IMPLICIT SEQUENCE} record in a module
     * without {@code IMPLICIT TAGS} then gained an EXPLICIT double wrapper -
     * {@code a0 .. 30 ..} - which is exactly what this asserts against.
     */
    @Test
    void theImplicitKeywordSurvivesInAModuleThatDefaultsToExplicit() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS ::= BEGIN
                NokiaCdr ::= [0] IMPLICIT SEQUENCE {
                    a [1] IMPLICIT INTEGER
                }
                END
                """);

        String hex = encodeHex(structure, Map.of("a", "7"));
        assertEquals("a003810107", hex, "an IMPLICIT type tag must not wrap the body in a SEQUENCE");
    }

    @Test
    void aTaggedRootTypeUnderAKeywordlessHeaderReplacesTheUniversalSequence() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS ::= BEGIN
                NrFile ::= [APPLICATION 1] SEQUENCE {
                    a [1] IMPLICIT INTEGER
                }
                END
                """);

        // This is FDRInput's shape, and it used to encode 61 05 30 03 .. on
        // X.680 31.2.7's default-EXPLICIT reading. EMM refused that file with
        // "FDRInput.NrFile.name was probably not set": it opens [APPLICATION 1]
        // and expects the first field, not the universal SEQUENCE we wrapped in.
        // So the tag replaces 30 rather than wrapping it.
        assertEquals("6103810107", encodeHex(structure, Map.of("a", "7")));
    }

    @Test
    void aTaggedRootSetUnderAKeywordlessHeaderReplacesTheUniversalSetTag() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS ::= BEGIN
                Wrapper ::= [APPLICATION 2] SET {
                    a [1] IMPLICIT INTEGER
                }
                END
                """);

        // Audit_Record_Collection_St's shape, refused the same way and named
        // its first mandatory field. 62 = APPLICATION 2 constructed, and the
        // universal SET tag (X.690 8.11) is replaced, not wrapped.
        assertEquals("6203810107", encodeHex(structure, Map.of("a", "7")));
    }

    @Test
    void aChoiceAlternativeInheritsTheTagItsOwnTypeDeclares() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS ::= BEGIN
                CallDataRecord ::= CHOICE {
                    gsmRecord GsmRecord
                }
                GsmRecord ::= [0] IMPLICIT SEQUENCE {
                    a [1] IMPLICIT INTEGER
                }
                END
                """);

        assertTrue(structure.isChoiceRoot());
        assertNull(structure.getRootTagCarrier(), "the CHOICE itself declares no tag");
        // The alternative is untagged in the CHOICE body; its tag comes from the
        // type it names. Before this it fell back to a universal SEQUENCE.
        assertEquals("a003810107", encodeHex(structure, Map.of("gsmRecord", Map.of("a", "7"))));
    }

    @Test
    void aTaggedRootChoiceWrapsTheSelectedAlternative() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS IMPLICIT TAGS ::= BEGIN
                CallEvent ::= [APPLICATION 1] CHOICE {
                    moc Moc
                }
                Moc ::= [APPLICATION 3] SEQUENCE {
                    a [1] IMPLICIT INTEGER
                }
                END
                """);

        // X.680 30.6: a tag on a CHOICE is always EXPLICIT, whatever the module
        // default, because the tag is what identifies the alternative. Moc's own
        // [APPLICATION 3] is IMPLICIT here, so it replaces the SEQUENCE tag:
        // 61 { 63 { 81 01 07 } }
        assertEquals("61056303810107", encodeHex(structure, Map.of("moc", Map.of("a", "7"))));
    }

    @Test
    void anUntaggedRootTypeIsStillAPlainUniversalSequence() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS ::= BEGIN
                Record ::= SEQUENCE {
                    a [1] IMPLICIT INTEGER
                }
                END
                """);

        assertNull(structure.getRootTagCarrier());
        assertEquals("3003810107", encodeHex(structure, Map.of("a", "7")));
    }

    /** Encoder and self-check must read the record's own tag the same way. */
    @Test
    void theSelfCheckWalksARootTaggedRecordWithoutComplaint() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS ::= BEGIN
                Row ::= [0] IMPLICIT SEQUENCE {
                    a [1] IMPLICIT INTEGER,
                    b [2] IMPLICIT INTEGER
                }
                END
                """);

        byte[] encoded = encoder.encodeRecord(structure, Map.of("a", "7", "b", "8"));

        SelfCheckProperties properties = new SelfCheckProperties();
        properties.setMode(SelfCheckProperties.Mode.WARN);
        BerVerifier verifier = new BerVerifier(new TlvReader(), properties,
                List.of(new DuplicateTagRule(), new SetOrderingRule(), new TagShapeRule()));

        BerVerificationResult result = verifier.verify(structure, encoded);
        assertTrue(result.findings().isEmpty(),
                "self-check must recognise the type's own tag, not report it: " + result.findings());
    }

    /** The tag annotation itself must reach the registry, not be dropped at parse time. */
    @Test
    void theRegistryKeepsTheTagAnnotationOfAStructuredType() {
        var registry = new AsnTypeRegistryBuilder().buildRegistry("""
                Mod DEFINITIONS ::= BEGIN
                TransferBatch ::= [APPLICATION 1] SEQUENCE { a [1] IMPLICIT INTEGER }
                Plain ::= SEQUENCE { a [1] IMPLICIT INTEGER }
                END
                """);

        assertTrue(registry.get("TransferBatch").getTagPrefix().contains("[APPLICATION 1]"));
        assertTrue(registry.get("Plain").getTagPrefix().isBlank());
    }

    /** Every field of the resolved tree keeps its own tag; only the root gains one. */
    @Test
    void theRecordFieldsAreUnchangedByTheRootTag() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS ::= BEGIN
                Row ::= [0] IMPLICIT SEQUENCE {
                    a [1] IMPLICIT INTEGER,
                    b [2] IMPLICIT INTEGER
                }
                END
                """);

        assertEquals(2, structure.getFields().size(),
                "the carrier must not replace the field list a UI reads");
        AsnField carrier = structure.getRootTagCarrier();
        assertEquals(structure.getFields(), carrier.getChildren());
        assertEquals(0, carrier.getTagNumber());
    }
}
