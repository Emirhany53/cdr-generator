package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureParserServiceTest {

    // parseFromContents does not read the JSON file, so the reader dependency
    // can stay null; only the real registry builder and resolver are needed.
    private final StructureParserService parser = new StructureParserService(
            null, new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());

    @Test
    void inlineContentIsParsedIntoResolvableFields() {
        AsnStructure structure = parser.parseFromContents("MyStructure", """
                CustomModule DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                MyRecord ::= SEQUENCE {
                    msisdn   [1] IMPLICIT OCTET STRING OPTIONAL,
                    duration [4] IMPLICIT INTEGER OPTIONAL
                }
                END
                """);

        assertNotNull(structure);
        assertEquals("MyStructure", structure.getStructureName());
        assertEquals(2, structure.getFields().size());
        assertEquals("msisdn", structure.getFields().get(0).getFieldName());
        assertEquals("duration", structure.getFields().get(1).getFieldName());
    }

    @Test
    void suppliedNameFallsBackToRootTypeWhenBlank() {
        AsnStructure structure = parser.parseFromContents("", """
                M DEFINITIONS ::= BEGIN
                Root ::= SEQUENCE { a [1] IMPLICIT INTEGER }
                END
                """);

        assertNotNull(structure);
        assertEquals("Root", structure.getStructureName(),
                "a blank name should fall back to the root type name");
    }

    @Test
    void rootSelectionSkipsLeadingPrimitiveAliases() {
        // Root candidate order: Number (alias, no fields) then Record (SEQUENCE).
        // The service must skip the alias and pick the first type that resolves
        // to at least one field.
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS ::= BEGIN
                Number ::= OCTET STRING (SIZE(1..20))
                Record ::= SEQUENCE {
                    caller [1] IMPLICIT Number,
                    callee [2] IMPLICIT Number
                }
                END
                """);

        assertNotNull(structure);
        assertFalse(structure.getFields().isEmpty(),
                "root selection must skip the leading primitive alias");
        assertEquals(2, structure.getFields().size());
    }

    /**
     * An inline {@code field CHOICE { ... }} is lifted into the registry under a
     * synthetic name ({@code ISOCdr$cdr}) and the parent body is rewritten to
     * reference it. The reference scan split tokens on {@code $}, so the
     * synthetic name was never recognised, the type looked unreferenced, and -
     * on a field-count tie - it beat its own parent to become the root.
     *
     * <p>A CHOICE root is encoded as the bare selected alternative, so the
     * parent {@code SEQUENCE} vanished from the wire: FCMSTAPIN records began at
     * {@code AA} ({@code [10]}, the {@code mo} alternative) instead of
     * {@code 30 .. AA ..}. 58 of the 808 modules picked a synthetic root this
     * way.</p>
     */
    @Test
    void anInlineChoiceDoesNotStealRootFromItsEnclosingSequence() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS ::= BEGIN
                ISOCdr ::= SEQUENCE {
                    cdr CHOICE {
                        mo [10] IMPLICIT MOCDR,
                        mt [20] IMPLICIT MTCDR
                    }
                }
                MOCDR ::= SEQUENCE { entity [1] IA5String OPTIONAL }
                MTCDR ::= SEQUENCE { entity [1] IA5String OPTIONAL }
                END
                """);

        assertNotNull(structure);
        assertFalse(structure.isChoiceRoot(),
                "the root is ISOCdr (a SEQUENCE); its inline CHOICE member must not win root selection");
        assertEquals(1, structure.getFields().size());
        assertEquals("cdr", structure.getFields().get(0).getFieldName());
    }

    /** The same guard holds when the enclosing type is a SET rather than a SEQUENCE. */
    @Test
    void aSyntheticChoiceTypeIsNeverARootCandidate() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS ::= BEGIN
                Wrapper ::= SET {
                    payload CHOICE { only [0] IMPLICIT Inner }
                }
                Inner ::= SEQUENCE { a [1] IA5String OPTIONAL }
                END
                """);

        assertNotNull(structure);
        assertFalse(structure.isChoiceRoot());
        assertTrue(structure.isSetRoot(), "Wrapper is a SET, so the record wraps in universal SET (0x31)");
        assertEquals("payload", structure.getFields().get(0).getFieldName());
    }

    /**
     * The module's record is a CHOICE of record types, and {@code resolveRoot}
     * reports a CHOICE root as a SINGLE field (the selected alternative). Ranking
     * candidates by field count therefore let any unreferenced helper SEQUENCE
     * outvote the record itself: in LTE-R10 the helper container
     * {@code ChangeOfServiceCondition} (22 fields, never referenced because the
     * records use the plural {@code ChangeOfServiceConditions}) beat
     * {@code CallEventRecord} (1 field), and generated files carried a bare
     * service-data container instead of an {@code sGWRecord [78]} CDR.
     *
     * <p>Candidates are now ranked by how many types they transitively reach -
     * the real record pulls in the module's whole type graph, a helper reaches
     * only its own corner.</p>
     */
    @Test
    void aRecordChoiceOutranksAnUnreferencedHelperWithMoreFields() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS IMPLICIT TAGS ::= BEGIN
                CallEventRecord ::= CHOICE {
                    sGWRecord [78] SGWRecord,
                    pGWRecord [79] PGWRecord
                }
                SGWRecord ::= SET {
                    recordType   [0] INTEGER,
                    servedIMSI   [3] IMSI OPTIONAL,
                    servedMSISDN [22] MSISDN OPTIONAL
                }
                PGWRecord ::= SET {
                    recordType [0] INTEGER,
                    servedIMSI [3] IMSI OPTIONAL
                }
                IMSI ::= OCTET STRING (SIZE(3..8))
                MSISDN ::= OCTET STRING (SIZE(1..20))
                ChangeOfServiceCondition ::= SEQUENCE {
                    ratingGroup  [1] INTEGER OPTIONAL,
                    resultCode   [3] INTEGER OPTIONAL,
                    timeOfReport [14] OCTET STRING OPTIONAL,
                    rATType      [15] INTEGER OPTIONAL
                }
                END
                """);

        assertNotNull(structure);
        assertTrue(structure.isChoiceRoot(),
                "the record CHOICE must win root selection over the unreferenced helper SEQUENCE");
        assertEquals("CallEventRecord", structure.getChoiceTypeName());
        assertEquals("sGWRecord", structure.getFields().get(0).getFieldName());
    }

    /**
     * The counterpart guard: a lookup schema defines a small {@code Key} type and
     * the real {@code Record}, and the record must still win. Reachability keeps
     * this working - {@code DBRecord} pulls in the module's types, {@code DBKey}
     * stands alone.
     */
    @Test
    void aLookupKeyDoesNotOutrankTheRecordItReachesLessOf() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS ::= BEGIN
                DBKey ::= SEQUENCE { msisdn [1] IMPLICIT IA5String }
                DBDataRecord ::= SEQUENCE OF DBRecord
                DBRecord ::= SEQUENCE {
                    msisdn  [1] IMPLICIT IA5String,
                    tariff  [2] IMPLICIT Tariff,
                    balance [3] IMPLICIT INTEGER
                }
                Tariff ::= IA5String (SIZE(1..10))
                END
                """);

        assertNotNull(structure);
        assertEquals(3, structure.getFields().size(),
                "DBRecord (reached through the SEQUENCE OF wrapper) is the record, not DBKey");
        assertEquals("balance", structure.getFields().get(2).getFieldName());
    }

    /**
     * X.680 49.4: {@code SIZE(n)} fixes the length exactly while
     * {@code SIZE(a..b)} does not. The resolver used to read the constraint into
     * an Integer (taking a range's UPPER bound) and re-emit it as a fixed
     * {@code SIZE(upper)}, which erased that distinction - and the encoder pads
     * only fixed-width fields, so 807 ranged character-string fields across the
     * schema would have been padded out to their maximum.
     */
    @Test
    void aRangedSizeConstraintStaysRangedOnTheResolvedField() {
        AsnStructure structure = parser.parseFromContents("Mod", """
                Mod DEFINITIONS ::= BEGIN
                Record ::= SEQUENCE {
                    variable [1] IA5String (SIZE(1..20)) OPTIONAL,
                    fixed    [2] IA5String (SIZE(15) CODE("LEFT")) OPTIONAL
                }
                END
                """);

        assertNotNull(structure);
        assertTrue(structure.getFields().get(0).getFieldType().contains("SIZE(1..20)"),
                "a range must survive resolution, not collapse to its upper bound");
        assertTrue(structure.getFields().get(1).getFieldType().contains("SIZE(15)"));
        assertTrue(structure.getFields().get(1).getFieldType().contains("CODE(\"LEFT\")"),
                "the justification marker decides which side is padded");
    }

    @Test
    void emptyContentYieldsNull() {
        assertNull(parser.parseFromContents("X", "   "));
    }

    @Test
    void unknownStructureLookupReturnsNull() {
        assertNull(parser.getStructureByName("does-not-exist"));
    }
}
