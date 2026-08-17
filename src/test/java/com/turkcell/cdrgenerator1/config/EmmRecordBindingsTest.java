package com.turkcell.cdrgenerator1.config;

import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record type a consuming EMM flow is bound to, and its trip from the
 * shipped file to the bytes.
 *
 * <p>Five modules were refused for encoding the wrong type before this file
 * existed - {@code IMSCDRS}, {@code CHFChargingDataTypes16}, and in round 14
 * {@code TurkcellImsOmm} and both TAP twins. Each was corrected by hand at the
 * call site that happened to remember, which is why the same class of rejection
 * kept arriving.</p>
 */
class EmmRecordBindingsTest {

    private final StructureParserService parser = new StructureParserService(
            null, new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());

    /** The IMSCDRS shape, under the real module name so the binding applies. */
    private static final String IMSCDRS = """
            IMSCDRS DEFINITIONS ::=
            BEGIN
            Cdrs ::= CHOICE {
                tokenMTAS [0] TokensMTAS,
                tokenCSCF [1] TokensCSCF
            }
            TokensMTAS ::= SEQUENCE { originHost [2] IA5String OPTIONAL }
            TokensCSCF ::= SEQUENCE {
                sessionId  [1] IA5String OPTIONAL,
                originHost [2] IA5String OPTIONAL
            }
            END
            """;

    @Test
    void theShippedFileIsReadableAndNotEmpty() {
        EmmRecordBindings shipped = EmmRecordBindings.shipped();

        assertFalse(shipped.isEmpty(), EmmRecordBindings.RESOURCE + " must ship with the schemas");
        assertEquals("TokensCSCF", shipped.recordTypeFor("IMSCDRS"));
        assertEquals("ChargingRecord", shipped.recordTypeFor("CHFChargingDataTypes16"));
        assertEquals("PostCcnCdr", shipped.recordTypeFor("TurkcellImsOmm"));
        assertEquals("CallEventDetail", shipped.recordTypeFor("TAP-0309"));
        assertEquals("CallEventDetail", shipped.recordTypeFor("TAP0309"));
    }

    /**
     * {@code pGWRecord} is an alternative of the {@code CallEventRecord} CHOICE,
     * not a type - LTE-R10 declares {@code pGWRecord [79] PGWRecord}. Recorded
     * as a recordType it would match no type in the registry and be logged and
     * dropped, so the file that made round 4 pass would silently stop being the
     * file we generate.
     */
    @Test
    void anAlternativeIsBoundAsASelectionRatherThanARecordType() {
        EmmRecordBindings shipped = EmmRecordBindings.shipped();

        assertNull(shipped.recordTypeFor("LTE-R10"));
        assertEquals(Map.of("CallEventRecord", "pGWRecord"), shipped.choiceAlternativesFor("LTE-R10"));
    }

    @Test
    void aModuleEmmHasNotAnsweredForIsNotBound() {
        assertNull(EmmRecordBindings.shipped().recordTypeFor("BroadSoft2Tesla"));
        assertTrue(EmmRecordBindings.shipped().choiceAlternativesFor("BroadSoft2Tesla").isEmpty());
    }

    @Test
    void aBoundRecordTypeReplacesTheHeuristicsPick() {
        AsnStructure structure = parser.parseFromContents("IMSCDRS", IMSCDRS);

        assertNotNull(structure);
        assertFalse(structure.isChoiceRoot(),
                "IMSCDRS is bound to TokensCSCF, so the record must not go out through the Cdrs "
                        + "CHOICE wrapper EMM refused five times");
        assertEquals(2, structure.getFields().size());
        assertEquals("sessionId", structure.getFields().get(0).getFieldName());
    }

    /**
     * A binding records what a flow was measured to expect; a caller naming a
     * type is asking a question of EMM deliberately, which is how every binding
     * here was learned in the first place.
     */
    @Test
    void aCallerSuppliedRootTypeStillWinsOverTheBinding() {
        AsnStructure structure = parser.parseFromContents("IMSCDRS", IMSCDRS, null, "TokensMTAS");

        assertNotNull(structure);
        assertEquals(1, structure.getFields().size());
        assertEquals("originHost", structure.getFields().get(0).getFieldName());
    }

    /**
     * The lookup key is the module, so content posted under some other name
     * still gets the binding its header names.
     */
    @Test
    void theHeaderNamesTheModuleWhenTheCallerCallsItSomethingElse() {
        AsnStructure structure = parser.parseFromContents("pasted-by-hand", IMSCDRS);

        assertNotNull(structure);
        assertFalse(structure.isChoiceRoot(), "the ASN.1 header still says IMSCDRS");
        assertEquals("sessionId", structure.getFields().get(0).getFieldName());
    }

    @Test
    void aBoundTypeTheModuleDoesNotDefineFallsBackToTheHeuristic() {
        AsnStructure structure = parser.parseFromContents("TAP0309", """
                TAP0309 DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE { a [1] IA5String OPTIONAL }
                END
                """);

        assertNotNull(structure, "a binding that does not fit the content must not fail the parse");
        assertEquals(1, structure.getFields().size());
    }

    @Test
    void bothBindingKindsAreReadFromTheFormat() {
        EmmRecordBindings bindings = EmmRecordBindings.read(new StringReader("""
                modules:
                  Alpha:
                    recordType: Inner
                    evidence: why we know
                  Beta:
                    choiceAlternatives:
                      Outer: second
                """));

        assertEquals("Inner", bindings.recordTypeFor("Alpha"));
        assertTrue(bindings.choiceAlternativesFor("Alpha").isEmpty());
        assertNull(bindings.recordTypeFor("Beta"));
        assertEquals(Map.of("Outer", "second"), bindings.choiceAlternativesFor("Beta"));
    }

    @Test
    void anEmptyDocumentBindsNothing() {
        assertTrue(EmmRecordBindings.read(new StringReader("# nothing yet\n")).isEmpty());
    }
}
