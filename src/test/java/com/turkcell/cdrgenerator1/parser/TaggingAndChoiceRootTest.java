package com.turkcell.cdrgenerator1.parser;

import com.turkcell.cdrgenerator1.model.AsnDeclaredTagging;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the B1 (CHOICE roots), B2 (module tagging mode) and B3 fixes. */
class TaggingAndChoiceRootTest {

    private final AsnTypeRegistryBuilder builder = new AsnTypeRegistryBuilder();
    private final AsnFieldTreeResolver resolver = new AsnFieldTreeResolver();

    // ---- B2: module-level tagging mode ----

    /**
     * A written keyword is reported as written; a header that names no mode is
     * reported as {@code UNSPECIFIED} rather than resolved here.
     *
     * <p>This method used to answer EXPLICIT for the keyword-less case, X.680
     * 31.2.7's default. It no longer decides at all, because the two sites that
     * consume the answer must not decide alike: a FIELD tag is encoded IMPLICIT
     * there (a compatibility rule measured against EMM on
     * {@code IMSCDRS.TokensCSCF}), while a tag written on a TYPE keeps X.680's
     * EXPLICIT, which nothing has measured. Resolving the silence here would
     * force one of those two to be wrong.</p>
     */
    @Test
    void reportsTheHeadersKeywordAndLeavesSilenceUnresolved() {
        assertEquals(AsnTaggingMode.UNSPECIFIED,
                builder.detectTaggingMode("M DEFINITIONS ::= BEGIN END"));
        assertEquals(AsnTaggingMode.IMPLICIT,
                builder.detectTaggingMode("M DEFINITIONS IMPLICIT TAGS ::= BEGIN END"));
        assertEquals(AsnTaggingMode.AUTOMATIC,
                builder.detectTaggingMode("M DEFINITIONS AUTOMATIC TAGS ::= BEGIN END"));
        assertEquals(AsnTaggingMode.EXPLICIT,
                builder.detectTaggingMode("M DEFINITIONS EXPLICIT TAGS ::= BEGIN END"));
    }

    /**
     * The two defaults an UNSPECIFIED header resolves to, side by side - the
     * whole reason {@code UNSPECIFIED} exists rather than being collapsed at
     * parse time.
     *
     * <p>A FIELD tag goes IMPLICIT: measured on {@code IMSCDRS.TokensCSCF},
     * which EMM refused five times in the standard EXPLICIT form and accepted
     * once re-encoded, returning all 34 values intact.</p>
     *
     * <p>A tag written on a TYPE stays EXPLICIT, X.680 31.2.7's default. No
     * measurement covers that construct - IMSCDRS declares no APPLICATION tag at
     * all - so it is left alone. This is what keeps {@code FDRInput}
     * ({@code NrFile ::= [APPLICATION 1] SEQUENCE}) and
     * {@code Audit_Record_Collection_St} encoding as they always have.</p>
     */
    @Test
    void anUnspecifiedHeaderIsImplicitForAFieldTagAndForATypeTag() {
        var fieldTagged = builder.buildRegistry(
                "M DEFINITIONS ::= BEGIN Root ::= SEQUENCE { a [1] INTEGER } END");
        AsnFieldTreeResolver.ResolvedRoot field = resolver.resolveRoot(
                fieldTagged, "Root", Map.of(), AsnTaggingMode.UNSPECIFIED);
        assertFalse(field.fields().get(0).isExplicit(),
                "a FIELD tag under a keyword-less header is IMPLICIT - IMSCDRS measured it");

        // The two used to differ, on the grounds that nothing had measured the
        // type-level case. Round 9 measured it: FDRInput and
        // Audit_Record_Collection_St were both refused at their first reachable
        // field, because EMM found the universal SEQUENCE we wrapped in where it
        // expected the field itself. A keyword-less header is IMPLICIT
        // throughout, not only for fields.
        var typeTagged = builder.buildRegistry(
                "M DEFINITIONS ::= BEGIN Root ::= [APPLICATION 1] SEQUENCE { a [1] IMPLICIT INTEGER } END");
        AsnFieldTreeResolver.ResolvedRoot type = resolver.resolveRoot(
                typeTagged, "Root", Map.of(), AsnTaggingMode.UNSPECIFIED);
        assertFalse(type.rootTagCarrier().isExplicit(),
                "a tag written on a TYPE is IMPLICIT too - EMM refused the wrapper twice");
    }

    /**
     * The declaration has to survive parsing, because every compatibility rule
     * turns on what the schema wrote rather than on what the encoder chose. A
     * test that only checked {@code isExplicit()} would pass with the
     * declaration silently unset.
     */
    @Test
    void theSchemasOwnKeywordSurvivesOnEveryTaggedField() {
        var keywordless = builder.buildRegistry(
                "M DEFINITIONS ::= BEGIN Root ::= SEQUENCE { "
                        + "a [1] INTEGER, b [2] EXPLICIT INTEGER, c [3] IMPLICIT INTEGER } END");
        AsnFieldTreeResolver.ResolvedRoot root = resolver.resolveRoot(
                keywordless, "Root", Map.of(), AsnTaggingMode.UNSPECIFIED);

        assertEquals(AsnDeclaredTagging.NONE, root.fields().get(0).getDeclaredTagging());
        assertEquals(AsnDeclaredTagging.EXPLICIT, root.fields().get(1).getDeclaredTagging());
        assertEquals(AsnDeclaredTagging.IMPLICIT, root.fields().get(2).getDeclaredTagging());

        // A written IMPLICIT and no keyword at all both encode implicitly, so
        // isExplicit() cannot tell them apart - which is the point.
        assertFalse(root.fields().get(0).isExplicit());
        assertFalse(root.fields().get(2).isExplicit());

        for (AsnField field : root.fields()) {
            assertTrue(field.isModuleNamesNoTaggingMode(), field.getFieldName() + " came from a keyword-less module");
            assertFalse(field.isTagDeclaredOnType(), field.getFieldName() + " carries its tag on the field");
        }
    }

    /** A tag written on the TYPE is marked as such, and an IMPLICIT header is not keyword-less. */
    @Test
    void aTypeLevelTagIsMarkedAndAnImplicitHeaderIsNotKeywordless() {
        var typeTagged = builder.buildRegistry(
                "M DEFINITIONS ::= BEGIN NrFile ::= [APPLICATION 1] SEQUENCE { a [1] INTEGER } END");
        AsnFieldTreeResolver.ResolvedRoot root = resolver.resolveRoot(
                typeTagged, "NrFile", Map.of(), AsnTaggingMode.UNSPECIFIED);
        AsnField carrier = root.rootTagCarrier();

        assertTrue(carrier.isTagDeclaredOnType(), "[APPLICATION 1] is written on the type");
        assertEquals(AsnDeclaredTagging.NONE, carrier.getDeclaredTagging());
        assertTrue(carrier.isModuleNamesNoTaggingMode());

        var implicitHeader = builder.buildRegistry(
                "M DEFINITIONS IMPLICIT TAGS ::= BEGIN Root ::= SEQUENCE { a [1] INTEGER } END");
        AsnFieldTreeResolver.ResolvedRoot other = resolver.resolveRoot(
                implicitHeader, "Root", Map.of(), AsnTaggingMode.IMPLICIT);
        assertFalse(other.fields().get(0).isModuleNamesNoTaggingMode(),
                "IMPLICIT TAGS names a mode, so the keyword-less rules must not reach it");
    }

    @Test
    void keywordlessTagIsExplicitUnderDefaultExplicitModule() {
        String asn = "M DEFINITIONS ::= BEGIN Root ::= SEQUENCE { a [1] INTEGER } END";
        var registry = builder.buildRegistry(asn);
        AsnFieldTreeResolver.ResolvedRoot root =
                resolver.resolveRoot(registry, "Root", Map.of(), AsnTaggingMode.EXPLICIT);
        assertTrue(root.fields().get(0).isExplicit(),
                "under a default-EXPLICIT module a keyword-less [n] tag must be EXPLICIT");
    }

    @Test
    void keywordlessTagIsImplicitUnderImplicitModule() {
        String asn = "M DEFINITIONS IMPLICIT TAGS ::= BEGIN Root ::= SEQUENCE { a [1] INTEGER } END";
        var registry = builder.buildRegistry(asn);
        AsnFieldTreeResolver.ResolvedRoot root =
                resolver.resolveRoot(registry, "Root", Map.of(), AsnTaggingMode.IMPLICIT);
        assertFalse(root.fields().get(0).isExplicit());
    }

    @Test
    void automaticModeAssignsSequentialContextTags() {
        String asn = "M DEFINITIONS AUTOMATIC TAGS ::= BEGIN "
                + "Root ::= SEQUENCE { a INTEGER, b IA5String } END";
        var registry = builder.buildRegistry(asn);
        AsnFieldTreeResolver.ResolvedRoot root =
                resolver.resolveRoot(registry, "Root", Map.of(), AsnTaggingMode.AUTOMATIC);
        assertEquals(0, root.fields().get(0).getTagNumber());
        assertEquals(1, root.fields().get(1).getTagNumber());
        assertEquals(BerTagClass.CONTEXT, root.fields().get(0).getTagClass());
    }

    // ---- B1: CHOICE root preserves the chosen alternative and its tag ----

    private static final String CHOICE_MODULE = """
            M DEFINITIONS IMPLICIT TAGS ::= BEGIN
            Sms ::= CHOICE {
                callRecord CallRecord,
                cmdRecord [APPLICATION 0] CmdRecord
            }
            CallRecord ::= SEQUENCE { a [0] INTEGER }
            CmdRecord ::= SEQUENCE { b [0] INTEGER }
            END
            """;

    @Test
    void choiceRootReturnsSingleAlternativeWithKind() {
        var registry = builder.buildRegistry(CHOICE_MODULE);
        AsnFieldTreeResolver.ResolvedRoot root =
                resolver.resolveRoot(registry, "Sms", Map.of(), AsnTaggingMode.IMPLICIT);

        assertEquals(AsnTypeKind.CHOICE, root.kind());
        assertEquals(1, root.fields().size(), "a CHOICE root yields exactly one selected alternative");
        AsnField alternative = root.fields().get(0);
        assertEquals("callRecord", alternative.getFieldName(), "first alternative is the default");
        assertNotNull(alternative.getChildren());
    }

    @Test
    void choiceSelectionPicksAlternativeAndKeepsApplicationTag() {
        var registry = builder.buildRegistry(CHOICE_MODULE);
        AsnFieldTreeResolver.ResolvedRoot root =
                resolver.resolveRoot(registry, "Sms", Map.of("Sms", "cmdRecord"), AsnTaggingMode.IMPLICIT);

        AsnField alternative = root.fields().get(0);
        assertEquals("cmdRecord", alternative.getFieldName());
        assertEquals(0, alternative.getTagNumber());
        assertEquals(BerTagClass.APPLICATION, alternative.getTagClass(),
                "the alternative's own [APPLICATION 0] tag must be preserved, not dropped");
    }

    // ---- listRootChoiceAlternatives: exposes all branch names for a UI picker ----

    @Test
    void listRootChoiceAlternativesReturnsChoiceTypeNameAndAllBranches() {
        var registry = builder.buildRegistry(CHOICE_MODULE);
        AsnFieldTreeResolver.ChoiceAlternatives info = resolver.listRootChoiceAlternatives(registry, "Sms");

        assertNotNull(info);
        assertEquals("Sms", info.choiceTypeName());
        assertEquals(List.of("callRecord", "cmdRecord"), info.alternativeNames());
    }

    @Test
    void listRootChoiceAlternativesReturnsNullForNonChoiceRoot() {
        String asn = "M DEFINITIONS ::= BEGIN Root ::= SEQUENCE { a [1] INTEGER } END";
        var registry = builder.buildRegistry(asn);
        assertEquals(null, resolver.listRootChoiceAlternatives(registry, "Root"));
    }
}
