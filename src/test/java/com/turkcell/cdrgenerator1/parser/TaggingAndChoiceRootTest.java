package com.turkcell.cdrgenerator1.parser;

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

    @Test
    void detectsTaggingModeFromHeaderAndDefaultsToExplicit() {
        assertEquals(AsnTaggingMode.EXPLICIT,
                builder.detectTaggingMode("M DEFINITIONS ::= BEGIN END"));
        assertEquals(AsnTaggingMode.IMPLICIT,
                builder.detectTaggingMode("M DEFINITIONS IMPLICIT TAGS ::= BEGIN END"));
        assertEquals(AsnTaggingMode.AUTOMATIC,
                builder.detectTaggingMode("M DEFINITIONS AUTOMATIC TAGS ::= BEGIN END"));
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
}
