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

    @Test
    void emptyContentYieldsNull() {
        assertNull(parser.parseFromContents("X", "   "));
    }

    @Test
    void unknownStructureLookupReturnsNull() {
        assertNull(parser.getStructureByName("does-not-exist"));
    }
}
