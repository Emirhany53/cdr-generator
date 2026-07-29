package com.turkcell.cdrgenerator1.parser;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * A CHOICE resolves to its selected alternative kept as ONE field, not
     * flattened into the alternative's inner fields. Flattening would discard
     * the alternative's own tag, which the BER encoder needs.
     */
    /**
     * The exact gap that slipped past all seven BerNestedChoiceEncodingTest
     * cases: those build AsnField directly and set choice(true) by hand, so
     * none of them exercised isChoiceType()'s alias-following at all. Here the
     * repetition is introduced through a NAMED alias
     * (ListOfInvolvedParties ::= SEQUENCE OF InvolvedParty), unlike ccf's
     * inline "SEQUENCE OF NodeAddress" - this is the
     * list-Of-Calling-Party-Address case from MMTelChargingDataTypes.
     */
    @Test
    void choiceFlagSurvivesRepetitionIntroducedByANamedAlias() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE {
                    parties [6] EXPLICIT ListOfInvolvedParties OPTIONAL
                }
                ListOfInvolvedParties ::= SEQUENCE OF InvolvedParty
                InvolvedParty ::= CHOICE {
                    sIP-URI [0] GraphicString,
                    tEL-URI [1] GraphicString
                }
                END
                """, "Root");

        AsnField parties = fields.get(0);
        assertTrue(parties.isRepeated(), "repetition via the named alias must still be detected");
        assertTrue(parties.isChoice(),
                "element type reached through the alias (InvolvedParty) is a CHOICE - "
                        + "encodeRepeated needs this to skip the synthetic per-element SEQUENCE");
    }

    /**
     * SET vs SEQUENCE must survive both a direct reference and a named list
     * alias, mirroring {@link #choiceFlagSurvivesRepetitionIntroducedByANamedAlias}.
     * ManagementExtensions and SubscriptionID in MMTelChargingDataTypes are both
     * SETs reached these two ways.
     */
    /**
     * A list alias may carry its own tag - TAP's
     * {@code CurrencyConversion ::= [APPLICATION 80] SEQUENCE OF
     * ExchangeRateDefinition} is the common shape. The target is stored raw, so
     * the tag has to be stripped before looking for "SEQUENCE OF"; otherwise the
     * field is never marked repeated and the whole list collapses to one element.
     * 102 such aliases exist across the data, almost all in the TAP family.
     *
     * <p><b>Known gap, deliberately not asserted here:</b> the alias's own tag
     * ([APPLICATION 80]) is still dropped for fields declared inside a
     * SEQUENCE/SET body. {@code attachChildren} reads it via ALIAS_TAG but
     * {@code parseFieldLines} does not, so such a field ends up untagged. That
     * affects 1487 fields across 27 structures (TAP0309 alone has 427) and is
     * tracked separately - closing it changes the encoding of all of them, so it
     * should not ride along with this fix.</p>
     */
    @Test
    void listAliasCarryingItsOwnTagIsStillDetectedAsRepeated() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE {
                    currencyConversion CurrencyConversion OPTIONAL,
                    plainList PlainList OPTIONAL
                }
                CurrencyConversion ::= [APPLICATION 80] SEQUENCE OF ExchangeRateDefinition
                PlainList ::= SEQUENCE OF ExchangeRateDefinition
                ExchangeRateDefinition ::= SEQUENCE { rate [0] INTEGER }
                END
                """, "Root");

        assertTrue(fields.get(0).isRepeated(), "a tagged list alias must still count as repeated");
        assertTrue(fields.get(1).isRepeated(), "the untagged form must keep working");
    }

    @Test
    void setFlagIsDetectedDirectlyAndThroughAListAlias() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE {
                    extensions [25] EXPLICIT MySet OPTIONAL,
                    subscriptions [31] EXPLICIT ListOfSets OPTIONAL,
                    plain [26] EXPLICIT MySeq OPTIONAL
                }
                ListOfSets ::= SEQUENCE OF MySet
                MySet ::= SET { a [0] INTEGER }
                MySeq ::= SEQUENCE { b [0] INTEGER }
                END
                """, "Root");

        AsnField extensions = fields.get(0);
        assertTrue(extensions.isSet(), "direct SET reference must be flagged");

        AsnField subscriptions = fields.get(1);
        assertTrue(subscriptions.isRepeated(), "the alias introduces repetition");
        assertTrue(subscriptions.isSet(), "element type reached through the alias is a SET");

        AsnField plain = fields.get(2);
        assertFalse(plain.isSet(), "a real SEQUENCE must not be flagged as a SET");
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
        assertEquals("callRecord", fields.get(0).getFieldName());
        assertNotNull(fields.get(0).getChildren());
        assertEquals("a", fields.get(0).getChildren().get(0).getFieldName());
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
        assertEquals("cmdRecord", fields.get(0).getFieldName());
        assertEquals("b", fields.get(0).getChildren().get(0).getFieldName());
    }

    /**
     * The MMTel/nodeAddress chain: every intermediate CHOICE layer must survive
     * with its own tag, otherwise the encoder cannot rebuild A4 { A0 { 80 ... } }.
     */
    @Test
    void nestedChoiceChainKeepsIntermediateTags() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE {
                    nodeAddress [4] EXPLICIT NodeAddress OPTIONAL
                }
                NodeAddress ::= CHOICE {
                    iPAddress [0] EXPLICIT IPAddress,
                    domainName [1] GraphicString
                }
                IPAddress ::= CHOICE {
                    iPBinaryAddress IPBinaryAddress
                }
                IPBinaryAddress ::= CHOICE {
                    iPBinV4Address [0] OCTET STRING,
                    iPBinV6Address [1] OCTET STRING
                }
                END
                """, "Root");

        AsnField nodeAddress = fields.get(0);
        assertTrue(nodeAddress.isChoice(), "nodeAddress refers to a CHOICE type");
        assertEquals(4, nodeAddress.getTagNumber());

        AsnField ipAddress = nodeAddress.getChildren().get(0);
        assertEquals("iPAddress", ipAddress.getFieldName());
        assertEquals(0, ipAddress.getTagNumber(), "intermediate [0] tag must survive");
        assertTrue(ipAddress.isExplicit(), "intermediate EXPLICIT must survive");

        AsnField binary = ipAddress.getChildren().get(0);
        assertEquals("iPBinaryAddress", binary.getFieldName());

        AsnField leaf = binary.getChildren().get(0);
        assertEquals("iPBinV4Address", leaf.getFieldName());
        assertEquals(0, leaf.getTagNumber());
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
    void sizeConstraintIsPreservedOnLeafTypes() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE { a [0] IA5String (SIZE(1..20)) }
                END
                """, "Root");
        // CODE("LEFT") gibi kisitlar kaldirilir, ancak SIZE(n) yaprak alanin
        // fieldType degerinde tutulur: yapay zeka katmani ve dogrulayici
        // azami uzunlugu buradan okur. SIZE(1..20) formunda ust sinir alinir.
        assertEquals("IA5String (SIZE(20))", fields.get(0).getFieldType());
    }

    @Test
    void unknownRootReturnsEmptyList() {
        assertTrue(resolve("M DEFINITIONS ::= BEGIN Root ::= SEQUENCE { a [0] INTEGER } END",
                "Missing").isEmpty());
    }
}
