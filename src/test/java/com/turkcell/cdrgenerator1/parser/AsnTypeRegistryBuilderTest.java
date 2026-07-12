package com.turkcell.cdrgenerator1.parser;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsnTypeRegistryBuilderTest {

    private final AsnTypeRegistryBuilder builder = new AsnTypeRegistryBuilder();

    @Test
    void moduleHeaderIsNotRegisteredAsType() {
        String asn = """
                MyModule DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE { a [0] INTEGER }
                END
                """;
        Map<String, AsnTypeDefinition> registry = builder.buildRegistry(asn);
        assertFalse(registry.containsKey("MyModule"));
        assertEquals("Root", registry.keySet().iterator().next());
    }

    @Test
    void implicitTagsHeaderIsSkipped() {
        String asn = """
                MyModule DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE { a [0] INTEGER }
                END
                """;
        Map<String, AsnTypeDefinition> registry = builder.buildRegistry(asn);
        assertFalse(registry.containsKey("TAGS"));
        assertTrue(registry.containsKey("Root"));
    }

    @Test
    void lineCommentsAreStripped() {
        String asn = """
                M DEFINITIONS ::=
                BEGIN
                -- Fake ::= SEQUENCE { x INTEGER }
                Root ::= SEQUENCE { a [0] INTEGER } -- trailing comment
                END
                """;
        Map<String, AsnTypeDefinition> registry = builder.buildRegistry(asn);
        assertFalse(registry.containsKey("Fake"));
        assertTrue(registry.containsKey("Root"));
    }

    @Test
    void kindsAreClassifiedCorrectly() {
        String asn = """
                M DEFINITIONS ::=
                BEGIN
                Seq ::= SEQUENCE { a [0] INTEGER }
                Cho ::= CHOICE { x IA5String, y INTEGER }
                Enu ::= ENUMERATED { on(0), off(1) }
                Ali ::= IA5String (SIZE(1..20))
                END
                """;
        Map<String, AsnTypeDefinition> registry = builder.buildRegistry(asn);
        assertEquals(AsnTypeKind.SEQUENCE, registry.get("Seq").getKind());
        assertEquals(AsnTypeKind.CHOICE, registry.get("Cho").getKind());
        assertEquals(AsnTypeKind.ENUMERATED, registry.get("Enu").getKind());
        assertEquals(AsnTypeKind.ALIAS, registry.get("Ali").getKind());
    }

    @Test
    void nestedBracesStayInsideOneBody() {
        String asn = """
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE {
                    inner SEQUENCE { a [0] INTEGER },
                    b [1] INTEGER
                }
                END
                """;
        Map<String, AsnTypeDefinition> registry = builder.buildRegistry(asn);
        String body = registry.get("Root").getRawBody();
        assertTrue(body.contains("inner"));
        assertTrue(body.contains("b"));
    }

    @Test
    void blankOrNullContentYieldsEmptyRegistry() {
        assertTrue(builder.buildRegistry(null).isEmpty());
        assertTrue(builder.buildRegistry("   ").isEmpty());
    }

    @Test
    void singleLineInlineModuleParses() {
        String asn = "Inline DEFINITIONS ::= BEGIN Root ::= SEQUENCE { a [0] INTEGER, b [1] IA5String } END";
        Map<String, AsnTypeDefinition> registry = builder.buildRegistry(asn);
        assertTrue(registry.containsKey("Root"));
        assertEquals(AsnTypeKind.SEQUENCE, registry.get("Root").getKind());
        assertNull(registry.get("Root").getAliasTarget());
    }
}
