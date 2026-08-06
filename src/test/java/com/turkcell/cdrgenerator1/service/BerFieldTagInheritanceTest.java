package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A SEQUENCE/SET member with no {@code [n]} of its own is tagged by the TYPE it
 * names. {@code parseFieldLines} deliberately skipped that inheritance while
 * {@code attachChildren} applied it to CHOICE alternatives, so 1141 field
 * declarations across 12 modules - 928 of them in the TAP family - were written
 * with a universal tag instead of the APPLICATION tag their schema gives them.
 * A TAP-0309 record came out as 106 universal TLVs and not one APPLICATION tag.
 */
class BerFieldTagInheritanceTest {

    private final StructureParserService parser = new StructureParserService(
            null, new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());
    private final BerEncoderService encoder = new BerEncoderService(
            new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));

    private String encodeHex(AsnStructure structure, Map<String, Object> record) {
        return HexFormat.of().formatHex(encoder.encodeRecord(structure, record));
    }

    @Test
    void aFieldInheritsTheTagOfTheAliasTypeItNames() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS IMPLICIT TAGS ::= BEGIN
                Root ::= SEQUENCE {
                    sender Sender
                }
                Sender ::= [APPLICATION 196] PlmnId
                PlmnId ::= IA5String
                END
                """);

        // APPLICATION 196 needs the high-tag form: 5f 81 44. Before this the
        // field fell back to the universal IA5String tag (16).
        assertEquals("30055f81440142", encodeHex(structure, Map.of("sender", "B")));
    }

    @Test
    void aFieldInheritsTheTagOfTheStructuredTypeItNames() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS IMPLICIT TAGS ::= BEGIN
                Root ::= SEQUENCE {
                    batchControlInfo BatchControlInfo
                }
                BatchControlInfo ::= [APPLICATION 4] SEQUENCE {
                    a [1] IMPLICIT INTEGER
                }
                END
                """);

        // 64 = APPLICATION 4 constructed, replacing the universal SEQUENCE.
        assertEquals("30056403810107",
                encodeHex(structure, Map.of("batchControlInfo", Map.of("a", "7"))));
    }

    @Test
    void aTagWrittenOnTheFieldStillWinsOverTheTypesOwn() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS IMPLICIT TAGS ::= BEGIN
                Root ::= SEQUENCE {
                    sender [3] IMPLICIT Sender
                }
                Sender ::= [APPLICATION 196] IA5String
                END
                """);

        // X.680: the innermost tag wins, and here that is the field's own [3].
        assertEquals("3003830142", encodeHex(structure, Map.of("sender", "B")));
    }

    /**
     * {@code GraphicStringImp ::= [UNIVERSAL 25] IMPLICIT IA5String} re-tags the
     * VALUE's universal tag. That mechanism is
     * {@code AsnField.universalTagOverride}, verified against the MMTel
     * reference capture; inheriting it a second time as a field tag would give
     * one annotation two owners.
     */
    @Test
    void aUniversalClassAnnotationStaysWithTheUniversalTagOverride() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS IMPLICIT TAGS ::= BEGIN
                Root ::= SEQUENCE {
                    note GraphicStringImp
                }
                GraphicStringImp ::= [UNIVERSAL 25] IMPLICIT IA5String
                END
                """);

        // 19 = universal GraphicString (25), written once, by the override.
        assertEquals("3003190142", encodeHex(structure, Map.of("note", "B")));
    }

    @Test
    void anUntaggedTypeLeavesTheFieldOnItsUniversalTag() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS IMPLICIT TAGS ::= BEGIN
                Root ::= SEQUENCE {
                    plain Plain
                }
                Plain ::= IA5String
                END
                """);

        assertEquals("3003160142", encodeHex(structure, Map.of("plain", "B")));
    }

    /** The TAP shape end to end: a tagged root holding tagged members. */
    @Test
    void aTaggedRootAndTaggedMembersNestCorrectly() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS IMPLICIT TAGS ::= BEGIN
                TransferBatch ::= [APPLICATION 1] SEQUENCE {
                    batchControlInfo BatchControlInfo
                }
                BatchControlInfo ::= [APPLICATION 4] SEQUENCE {
                    fileSequenceNumber FileSequenceNumber
                }
                FileSequenceNumber ::= [APPLICATION 109] IA5String
                END
                """);

        // 61 { 64 { 5f 6d 01 42 } } - exactly the head of a real TAP3 file.
        assertEquals("610664045f6d0142",
                encodeHex(structure, Map.of("batchControlInfo", Map.of("fileSequenceNumber", "B"))));
    }
}
