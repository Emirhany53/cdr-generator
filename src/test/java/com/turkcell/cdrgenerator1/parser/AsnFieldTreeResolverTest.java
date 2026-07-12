package com.turkcell.cdrgenerator1.parser;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsnFieldTreeResolverTest {

    private final AsnTypeRegistryBuilder registryBuilder = new AsnTypeRegistryBuilder();
    private final AsnFieldTreeResolver resolver = new AsnFieldTreeResolver();

    private List<AsnField> resolve(String asn, String root) {
        Map<String, AsnTypeDefinition> registry = registryBuilder.buildRegistry(asn);
        return resolver.resolveRootFields(registry, root);
    }

    @Test
    void tagNumberOptionalAndTypeAreParsed() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE {
                    msisdn [1] IMPLICIT OCTET STRING OPTIONAL,
                    duration [4] IMPLICIT INTEGER
                }
                END
                """, "Root");

        assertEquals(2, fields.size());
        AsnField msisdn = fields.get(0);
        assertEquals("msisdn", msisdn.getFieldName());
        assertEquals(1, msisdn.getTagNumber());
        assertEquals(BerTagClass.CONTEXT, msisdn.getTagClass());
        assertTrue(msisdn.isOptional());
        assertEquals("OCTET STRING", msisdn.getFieldType());

        AsnField duration = fields.get(1);
        assertEquals(4, duration.getTagNumber());
        assertTrue(duration.getFieldType().startsWith("INTEGER"));
        assertTrue(!duration.isOptional());
    }

    @Test
    void applicationTagClassIsParsed() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE {
                    cmd [APPLICATION 0] OCTET STRING OPTIONAL
                }
                END
                """, "Root");

        AsnField cmd = fields.get(0);
        assertEquals(0, cmd.getTagNumber());
        assertEquals(BerTagClass.APPLICATION, cmd.getTagClass());
        assertEquals("OCTET STRING", cmd.getFieldType());
    }

    @Test
    void explicitKeywordIsDetected() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE {
                    a [2] EXPLICIT IA5String
                }
                END
                """, "Root");
        assertTrue(fields.get(0).isExplicit());
    }

    @Test
    void nestedTypeReferencesResolveToChildren() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE { addr [0] Address }
                Address ::= SEQUENCE { ton [0] INTEGER, msisdn [3] IA5String }
                END
                """, "Root");

        AsnField addr = fields.get(0);
        assertNotNull(addr.getChildren());
        assertEquals(2, addr.getChildren().size());
        assertEquals("ton", addr.getChildren().get(0).getFieldName());
    }

    @Test
    void aliasChainsResolveTransitively() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE { a [0] Alias1 }
                Alias1 ::= Alias2
                Alias2 ::= SEQUENCE { x [0] INTEGER }
                END
                """, "Root");
        assertNotNull(fields.get(0).getChildren());
        assertEquals("x", fields.get(0).getChildren().get(0).getFieldName());
    }

    @Test
    void sequenceOfIsMarkedRepeated() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE { items [6] SEQUENCE OF Inner }
                Inner ::= SEQUENCE { a [0] INTEGER }
                END
                """, "Root");
        AsnField items = fields.get(0);
        assertTrue(items.isRepeated());
        assertEquals("Inner", items.getFieldType());
        assertNotNull(items.getChildren());
    }

    @Test
    void choiceResolvesToFirstAlternativeByDefault() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= CHOICE {
                    callRecord CallRecord,
                    cmdRecord [APPLICATION 0] CmdRecord
                }
                CallRecord ::= SEQUENCE { a [0] INTEGER }
                CmdRecord ::= SEQUENCE { b [0] INTEGER }
                END
                """, "Root");
        assertEquals(1, fields.size());
        assertEquals("a", fields.get(0).getFieldName());
    }

    @Test
    void choiceSelectionPicksNamedAlternative() {
        Map<String, AsnTypeDefinition> registry = registryBuilder.buildRegistry("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= CHOICE {
                    callRecord CallRecord,
                    cmdRecord CmdRecord
                }
                CallRecord ::= SEQUENCE { a [0] INTEGER }
                CmdRecord ::= SEQUENCE { b [0] INTEGER }
                END
                """);
        List<AsnField> fields = resolver.resolveRootFields(registry, "Root",
                Map.of("Root", "cmdRecord"));
        assertEquals("b", fields.get(0).getFieldName());
    }

    @Test
    void circularReferencesDoNotRecurseInfinitely() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE { self [0] Root, a [1] INTEGER }
                END
                """, "Root");
        // The recursion guard stops the cycle instead of overflowing the stack.
        assertEquals(2, fields.size());
        assertNull(fields.get(0).getChildren());
    }

    @Test
    void constraintsAreStrippedFromTypeExpression() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE { a [0] IA5String (SIZE(1..20)) }
                END
                """, "Root");
        assertEquals("IA5String", fields.get(0).getFieldType());
    }

    @Test
    void unknownRootReturnsEmptyList() {
        assertTrue(resolve("M DEFINITIONS ::= BEGIN Root ::= SEQUENCE { a [0] INTEGER } END",
                "Missing").isEmpty());
    }
}
