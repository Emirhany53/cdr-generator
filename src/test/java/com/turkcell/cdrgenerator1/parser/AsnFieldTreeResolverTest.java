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

    /**
     * The keyword is read from the text and kept in {@code declaredTagging};
     * what the encoder does with it is a separate decision, and outside a
     * CHOICE that decision is IMPLICIT. See
     * {@link #writtenExplicitSurvivesOnlyOnAChoice}.
     */
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
        assertEquals(AsnDeclaredTagging.EXPLICIT, fields.get(0).getDeclaredTagging(),
                "the written keyword must survive parsing even where it does not survive encoding");
        assertFalse(fields.get(0).isExplicit(),
                "a written EXPLICIT on a primitive adds no wrapper - EMM asked for 8B 01 FF, "
                        + "not AB 03 01 01 FF, on LTE-R10.dynamicAddressFlag");
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
        assertFalse(parties.isExplicit(),
                "a written EXPLICIT on a SEQUENCE-OF-CHOICE field must be neutralized: the real "
                        + "MMTel wire format (confirmed against an EMM-accepted reference capture) has no "
                        + "extra universal SEQUENCE between [6] and the CHOICE elements. Honoring the "
                        + "source EXPLICIT literally here is exactly the bug that made EMM reject "
                        + "list-Of-Calling-Party-Address; see AsnFieldTreeResolver.effectiveExplicit().");
    }

    /**
     * The target type decides, not the module's lineage. This fixture carries
     * no verified fingerprint at all - no {@code InvolvedParty}, no
     * {@code GSNAddress}/{@code IPAddress} pair - and the written EXPLICIT
     * still survives only where the type is a CHOICE.
     *
     * <p>Round 14 is what moved this test from asserting the opposite. Two
     * modules outside both fingerprinted lineages were refused at exactly the
     * field where a written EXPLICIT wrapped a SEQUENCE:
     * {@code CCNCS55_UpdatedCCR_CCN.ChargingDataOutputRecord.sCFPDPRecord.ggsnAddressUsed}
     * and {@code EnrichedVerazCdr.CDR.redirectingInformationSubs}. In the same
     * round every module that PASSED while carrying a written EXPLICIT has it
     * on a CHOICE.</p>
     */
    @Test
    void writtenExplicitSurvivesOnlyOnAChoice() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE {
                    scalarChoice [4] EXPLICIT NodeAddress OPTIONAL,
                    plainSequence [7] EXPLICIT MySeq OPTIONAL,
                    plainList [12] EXPLICIT ListOfInts OPTIONAL
                }
                NodeAddress ::= CHOICE {
                    iPAddress [0] GraphicString,
                    domainName [1] GraphicString
                }
                MySeq ::= SEQUENCE { a [0] INTEGER }
                ListOfInts ::= SEQUENCE OF INTEGER
                END
                """, "Root");

        assertTrue(fields.get(0).isExplicit(), "scalar CHOICE must keep its EXPLICIT tag");
        assertFalse(fields.get(1).isExplicit(),
                "a written EXPLICIT on a plain SEQUENCE adds no wrapper - the shape EMM refused in "
                        + "EnrichedVerazCdr.CDR.redirectingInformationSubs");
        assertFalse(fields.get(2).isExplicit(),
                "nor on a SEQUENCE OF <non-CHOICE>: the collection tag replaces the universal one");
    }

    /**
     * The shape EMM rejected in both {@code LTE-R10} and
     * {@code GGSNTurkcellCdrR7}: "the type ...{@code servingNodeAddress.[0]} was
     * probably not set and is not optional" for
     * {@code [6] EXPLICIT SEQUENCE OF GSNAddress}. Honoring that EXPLICIT puts a
     * universal SEQUENCE between {@code A6} and the address CHOICE; EMM reads
     * {@code A6} as the collection itself and finds a {@code 30} where an
     * address alternative must be, so element {@code [0]} never gets set.
     *
     * <p>The fingerprint is the 3GPP TS 32.298 address triple - a named
     * {@code GSNAddress} over an {@code IPAddress} CHOICE over an
     * {@code IPBinaryAddress} CHOICE. Neutralizing across the family reproduces
     * the published standard encoding, which writes no EXPLICIT anywhere:
     * {@code sGWAddress} (scalar CHOICE) keeps its wrapper because X.680 8.3
     * requires one, while {@code servedPDPPDNAddress} (scalar SET),
     * {@code dynamicAddressFlag} (BOOLEAN) and the SEQUENCE OFs all shed the
     * layer the standard does not have.</p>
     */
    @Test
    void explicitIsNeutralizedInTheEmmRejectedPacketDomainCdrFamily() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SET {
                    sGWAddress [4] EXPLICIT GSNAddress,
                    servingNodeAddress [6] EXPLICIT SEQUENCE OF GSNAddress OPTIONAL,
                    servedPDPPDNAddress [9] EXPLICIT PDPAddress OPTIONAL,
                    dynamicAddressFlag [11] EXPLICIT DynamicAddressFlag OPTIONAL
                }
                GSNAddress ::= IPAddress
                IPAddress ::= CHOICE {
                    iPBinaryAddress IPBinaryAddress,
                    iPTextRepresentedAddress IPTextRepresentedAddress
                }
                IPBinaryAddress ::= CHOICE {
                    iPBinV4Address [0] OCTET STRING,
                    iPBinV6Address [1] OCTET STRING
                }
                IPTextRepresentedAddress ::= CHOICE {
                    iPTextV4Address [2] IA5String,
                    iPTextV6Address [3] IA5String
                }
                PDPAddress ::= SET { iPAddress [0] EXPLICIT IPAddress OPTIONAL }
                DynamicAddressFlag ::= BOOLEAN
                END
                """, "Root");

        assertTrue(fields.get(0).isExplicit(),
                "a scalar CHOICE keeps EXPLICIT even here - a CHOICE has no universal tag to replace "
                        + "(X.680 8.3), which is why EMM accepted sGWAddress [4] and rejected [6]");
        assertFalse(fields.get(1).isExplicit(),
                "SEQUENCE OF GSNAddress must lose its written EXPLICIT: this is the exact field EMM "
                        + "reported as servingNodeAddress.[0] not set, in LTE-R10 and GGSNTurkcellCdrR7 alike");
        assertFalse(fields.get(2).isExplicit(),
                "scalar SET (servedPDPPDNAddress-shape) must be neutralized too - 3GPP TS 32.298 has "
                        + "no universal SET layer under [9]");
        assertFalse(fields.get(3).isExplicit(),
                "a scalar BOOLEAN is neutralized too - EMM refused AB 03 01 01 FF for "
                        + "dynamicAddressFlag with \"Boolean can only have a maximum length of 1 bytes\"");
    }

    /**
     * A primitive-typed field is neutralized like every other shape, and it took
     * a rejection to settle that. The MMTel captures are silent here -
     * {@code MMTelChargingDataTypes} declares no EXPLICIT field resolving to a
     * primitive - so the shape was once carved out as unproven. EMM answered it
     * on {@code LTE-R10}: "Invalid length 3 of field ...dynamicAddressFlag /
     * Boolean can only have a maximum length of 1 bytes", against the
     * {@code AB 03 01 01 FF} the carve-out produced. EMM reads {@code [11]} as
     * an IMPLICIT BOOLEAN and wants {@code 8B 01 FF}.
     *
     * <p>The same round accepted GGSN with {@code qosRequested} still wrapped,
     * but that settles nothing: a decoder ignoring the wrapper reads
     * {@code A1 13 04 11 ..} as a 19-octet value whose first two octets are our
     * own TLV header, and {@code SIZE(4..255)} still holds. BOOLEAN is just the
     * primitive whose length bound makes the disagreement visible.</p>
     */
    @Test
    void aScalarPrimitiveIsNeutralizedLikeEveryOtherShape() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SET {
                    dynamicAddressFlag [11] EXPLICIT DynamicAddressFlag OPTIONAL,
                    qosRequested [1] EXPLICIT QoSInformation OPTIONAL,
                    subscriberRole [2] EXPLICIT SubscriberRole OPTIONAL,
                    servedPDPPDNAddress [9] EXPLICIT PDPAddress OPTIONAL,
                    listOfTrafficVolumes [12] EXPLICIT SEQUENCE OF ChangeOfCharCondition OPTIONAL
                }
                DynamicAddressFlag ::= BOOLEAN
                QoSInformation ::= OCTET STRING (SIZE (4..255))
                SubscriberRole ::= ENUMERATED { originating(0), terminating(1) }
                PDPAddress ::= SET { iPAddress [0] EXPLICIT INTEGER OPTIONAL }
                ChangeOfCharCondition ::= SEQUENCE { changeTime [6] OCTET STRING OPTIONAL }
                InvolvedParty ::= CHOICE { sIP-URI [0] GraphicString, tEL-URI [1] GraphicString }
                END
                """, "Root");

        assertFalse(byName(fields, "dynamicAddressFlag").isExplicit(),
                "EMM refused AB 03 01 01 FF here: \"Boolean can only have a maximum length of 1 bytes\"");
        assertFalse(byName(fields, "qosRequested").isExplicit(),
                "an OCTET STRING follows BOOLEAN: a decoder ignoring the wrapper would read our own "
                        + "TLV header as the first two octets of the value");
        assertFalse(byName(fields, "subscriberRole").isExplicit(),
                "ENUMERATED is a primitive like the others");
        assertFalse(byName(fields, "servedPDPPDNAddress").isExplicit(),
                "a scalar SET still loses it: 27 906 observations of that shape, zero with a wrapper");
        assertFalse(byName(fields, "listOfTrafficVolumes").isExplicit(),
                "a collection still loses it: 46 282 observations of SEQUENCE OF <SEQUENCE>, zero with a wrapper");
    }

    /** Looks a field up by name - the resolver is free to reorder SET members. */
    private AsnField byName(List<AsnField> fields, String name) {
        return fields.stream().filter(f -> name.equals(f.getFieldName())).findFirst()
                .orElseThrow(() -> new AssertionError("no field named " + name));
    }

    /**
     * {@code GSN50}'s {@code ManagementExtension} declares
     * {@code identifier [UNIVERSAL 6] OCTET STRING} in a module whose header is
     * a bare {@code DEFINITIONS ::=}, so X.680 31.2.7 made the tag EXPLICIT and
     * the encoder wrapped the OCTET STRING in it: {@code 26 0A 04 08 ..} - an
     * OBJECT IDENTIFIER whose contents are an OCTET STRING TLV. X.690 8.19.1
     * requires an object identifier value to be primitive, so those bytes are
     * unreadable; {@code 06 08 ..} is what the declaration can only have meant.
     *
     * <p>X.680 31.2.1 reserves the UNIVERSAL class for the types the standard
     * defines, so {@code [UNIVERSAL n]} is outside the language and the module
     * default has no say over it. The guard is limited to the tags X.690 pins to
     * primitive - a character string may legally be constructed, so nothing is
     * proven there and nothing is changed.</p>
     */
    @Test
    void aUniversalTagOnAnAlwaysPrimitiveTypeCannotBecomeAnExplicitWrapper() {
        // The module default has to be EXPLICIT for this to mean anything - that
        // is the only setting under which a [UNIVERSAL n] tag would wrap. The
        // plain resolve() helper hardcodes IMPLICIT, where every field comes out
        // implicit and the guard would never be reached.
        Map<String, AsnTypeDefinition> registry = registryBuilder.buildRegistry("""
                GSN50 DEFINITIONS ::=
                BEGIN
                ManagementExtension ::= SEQUENCE {
                    identifier [UNIVERSAL 6] OCTET STRING,
                    label [UNIVERSAL 25] IA5String,
                    significance [1] BOOLEAN
                }
                END
                """);
        List<AsnField> fields = resolver.resolveRootFields(
                registry, "ManagementExtension", Map.of(), AsnTaggingMode.EXPLICIT);

        assertEquals(List.of("identifier", "label", "significance"),
                fields.stream().map(AsnField::getFieldName).toList());
        assertFalse(byName(fields, "identifier").isExplicit(),
                "[UNIVERSAL 6] must re-tag the value, not wrap it: wrapping emits 26 { 04 .. }, "
                        + "a constructed OBJECT IDENTIFIER, which X.690 8.19.1 forbids");
        assertTrue(byName(fields, "label").isExplicit(),
                "[UNIVERSAL 25] is a character string - X.690 8.21.3 lets it be constructed, so "
                        + "the module's EXPLICIT default is left alone");
        assertTrue(byName(fields, "significance").isExplicit(),
                "an ordinary CONTEXT tag is untouched by this guard");
    }

    /**
     * berTreeDump against freshly generated files caught violations the
     * CHOICE-only fix above didn't cover - {@code recordExtensions [25]
     * EXPLICIT ManagementExtensions} (scalar SET), {@code mMTelInformation
     * [110] EXPLICIT MMTelInformation} (scalar SET), {@code
     * list-of-subscription-ID [31] EXPLICIT SEQUENCE OF SubscriptionID}
     * (repeated SET). Re-diffing the ORIGINAL schema text against the
     * EMM-verified fix commit (44b5bfb) then showed the anomaly is broader
     * still: {@code interOperatorIdentifiers}, {@code
     * list-Of-SDP-Media-Components} and two more fields were ALSO stripped
     * there even though their target is a plain repeated SEQUENCE - no CHOICE,
     * no SET involved at all. Of the ~20 EXPLICIT-marked fields in
     * MMTelChargingDataTypes, every single one that survived the reference-file
     * diff resolves to a scalar CHOICE; every other shape (repeated CHOICE,
     * scalar/repeated SET, scalar/repeated SEQUENCE) was confirmed wrong. This
     * fixture reproduces all three neutralized shapes with the {@code
     * InvolvedParty} fingerprint present (as in every real MMTel/AIMS/IMS/
     * UAG/ATS module), so {@code AsnFieldTreeResolver.effectiveExplicit}
     * fires for all of them.
     */
    @Test
    void explicitIsNeutralizedForEveryNonScalarChoiceShapeWithinTheVerifiedInvolvedPartyFamily() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SET {
                    recordExtensions [25] EXPLICIT ManagementExtensions OPTIONAL,
                    subscriptions [31] EXPLICIT ListOfSubscriptionID OPTIONAL,
                    parties [6] EXPLICIT ListOfInvolvedParties OPTIONAL,
                    interOperatorIdentifiers [14] EXPLICIT ListOfIOI OPTIONAL
                }
                ManagementExtensions ::= SET { a [0] INTEGER OPTIONAL }
                ListOfSubscriptionID ::= SEQUENCE OF SubscriptionID
                SubscriptionID ::= SET { b [0] INTEGER OPTIONAL }
                ListOfInvolvedParties ::= SEQUENCE OF InvolvedParty
                InvolvedParty ::= CHOICE {
                    sIP-URI [0] GraphicString,
                    tEL-URI [1] GraphicString
                }
                ListOfIOI ::= SEQUENCE OF IOIPair
                IOIPair ::= SEQUENCE { originatingIOI [0] GraphicString OPTIONAL }
                END
                """, "Root");

        assertFalse(fields.get(0).isExplicit(),
                "scalar SET (recordExtensions-shape) must be neutralized within the verified family");
        assertFalse(fields.get(1).isExplicit(),
                "SEQUENCE OF <SET> (list-of-subscription-ID-shape) must be neutralized within the verified family");
        assertFalse(fields.get(3).isExplicit(),
                "SEQUENCE OF <plain SEQUENCE> (interOperatorIdentifiers-shape) must ALSO be neutralized - "
                        + "the reference-file diff confirmed this shape is wrong too, not just CHOICE/SET");
    }

    /**
     * The exact same shapes without the InvolvedParty fingerprint - LTE-R10's
     * {@code servedPDPPDNAddress [9] EXPLICIT PDPAddress} (a SET there) and any
     * field writing EXPLICIT on a plain SEQUENCE or SET. They neutralize for the
     * same reason the fingerprinted ones do: the type is not a CHOICE.
     *
     * <p>This test asserted the opposite while the neutralization was gated on a
     * lineage. The gate is gone (see {@link #writtenExplicitSurvivesOnlyOnAChoice}),
     * so what remains to guard is that the rule reaches a SET and a repeated
     * SEQUENCE, not only the shapes the MMTel capture happened to contain.</p>
     */
    @Test
    void writtenExplicitOnASetOrRepeatedSequenceAddsNoWrapper() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE {
                    servedPDPPDNAddress [9] EXPLICIT PDPAddress OPTIONAL,
                    plainList [12] EXPLICIT ListOfInts OPTIONAL
                }
                PDPAddress ::= SET { a [0] INTEGER OPTIONAL }
                ListOfInts ::= SEQUENCE OF INTEGER
                END
                """, "Root");

        assertFalse(fields.get(0).isExplicit(),
                "a written EXPLICIT on a SET adds no wrapper, fingerprint or not");
        assertFalse(fields.get(1).isExplicit(),
                "same for a plain repeated SEQUENCE");
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
     * <p>The alias's own tag travels with it now: a field naming such a type
     * inherits {@code [APPLICATION 80]} instead of falling back to a universal
     * tag. That gap is closed in {@code inheritedFieldTag}; see
     * {@code BerFieldTagInheritanceTest} for the encoding it produces.</p>
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
        assertEquals(80, fields.get(0).getTagNumber(),
                "the alias's own tag belongs to the field that names it");
        assertEquals(BerTagClass.APPLICATION, fields.get(0).getTagClass());
        assertNull(fields.get(1).getTagNumber(), "an untagged alias leaves the field untagged");
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

    /**
     * SIZE kisiti yaprak alanin fieldType degerinde HAM METIN olarak tutulur.
     *
     * <p>Onceden kisit bir Integer'a okunur (aralikta UST SINIR alinir) ve sabit
     * bir {@code SIZE(ustSinir)} olarak geri yazilirdi. Bu, X.680 49.4'un ayrimini
     * siliyordu: {@code SIZE(20)} uzunlugu TAM olarak sabitler,
     * {@code SIZE(1..20)} sabitlemez. Kodlayici yalnizca sabit genislikli alanlari
     * bosluklarla tamamladigi icin, sema genelindeki 807 aralikli karakter-string
     * alani da azami uzunluga kadar doldurulmus olurdu.</p>
     */
    @Test
    void aRangedSizeConstraintKeepsItsRange() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE { a [0] IA5String (SIZE(1..20)) }
                END
                """, "Root");
        assertEquals("IA5String (SIZE(1..20))", fields.get(0).getFieldType());
    }

    /**
     * Sabit SIZE(n) ve yanindaki hizalama isareti oldugu gibi tasinir: kodlayici
     * dolgunun hangi tarafa gidecegine {@code CODE} degerine bakarak karar verir.
     */
    @Test
    void aFixedSizeConstraintKeepsItsCodeMarker() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE { a [0] IA5String (SIZE(15) CODE("LEFT")) }
                END
                """, "Root");
        assertEquals("IA5String (SIZE(15) CODE(\"LEFT\"))", fields.get(0).getFieldType());
    }

    @Test
    void unknownRootReturnsEmptyList() {
        assertTrue(resolve("M DEFINITIONS ::= BEGIN Root ::= SEQUENCE { a [0] INTEGER } END",
                "Missing").isEmpty());
    }

    /**
     * {@code GraphicStringImp ::= [UNIVERSAL 25] IMPLICIT IA5String} re-tags a
     * primitive into the UNIVERSAL class: the VALUE is encoded as an IA5String
     * but the TAG on the wire must be 25 (GraphicString), not 22.
     *
     * <p>{@code resolveLeafBaseType} strips every leading tag while chasing the
     * alias chain down to a bare primitive name, so before
     * {@code AsnFieldTreeResolver.resolveUniversalTagOverride} existed the
     * override was silently lost and the encoder emitted tag 22. Comparing our
     * output against two EMM-accepted MMTel reference captures showed them
     * carrying 25 in exactly these places
     * ({@code list-Of-SDP-Media-Components}, {@code
     * list-Of-Early-SDP-Media-Components}, {@code listOfReasonHeader} and the
     * nested {@code sDP-Media-Descriptions}/{@code sDP-Session-Description}),
     * confirming the loss was a real encoding fault rather than a cosmetic one.
     *
     * <p>The fixture covers all three ways the chain reaches the override: a
     * direct alias, a {@code SEQUENCE OF} over it, and a two-hop alias
     * ({@code ListOfReasonHeader -> ReasonHeaderInformation -> GraphicStringImp})
     * which is the exact shape of MMTel's {@code listOfReasonHeader [55]}.</p>
     */
    @Test
    void universalTagOverrideSurvivesTheAliasChain() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE {
                    directAlias [0] GraphicStringImp OPTIONAL,
                    inlineList [4] SEQUENCE OF GraphicStringImp OPTIONAL,
                    twoHopList [55] ListOfReasonHeader OPTIONAL
                }
                GraphicStringImp ::= [UNIVERSAL 25] IMPLICIT IA5String
                ListOfReasonHeader ::= SEQUENCE OF ReasonHeaderInformation
                ReasonHeaderInformation ::= GraphicStringImp
                END
                """, "Root");

        assertEquals(Integer.valueOf(25), fields.get(0).getUniversalTagOverride(),
                "a direct [UNIVERSAL 25] alias must keep its tag override");
        assertEquals(Integer.valueOf(25), fields.get(1).getUniversalTagOverride(),
                "SEQUENCE OF <re-tagged primitive>: the override belongs to the ELEMENT type");
        assertEquals(Integer.valueOf(25), fields.get(2).getUniversalTagOverride(),
                "the override must survive a multi-hop alias chain (MMTel's listOfReasonHeader shape)");

        // The value encoding still follows the underlying primitive - only the
        // tag changes, so fieldType must stay IA5String.
        assertEquals("IA5String", fields.get(0).getFieldType());
    }

    /**
     * The override is deliberately narrow: it must fire ONLY for a UNIVERSAL-class
     * re-tag. Plain primitives keep {@code null} so the encoder falls back to the
     * tag implied by their type, and APPLICATION/CONTEXT tags on an alias target
     * are a different mechanism - {@code AsnFieldTreeResolver.resolveEffectiveTag}
     * already applies those for CHOICE alternatives, so reading them here too
     * would double-apply them.
     */
    @Test
    void nonUniversalTagsDoNotProduceAnOverride() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE {
                    plain [0] IA5String OPTIONAL,
                    plainAlias [1] Msisdn OPTIONAL,
                    appTagged [2] AppString OPTIONAL,
                    enumerated [3] Cause OPTIONAL
                }
                Msisdn ::= IA5String
                AppString ::= [APPLICATION 2] IA5String
                Cause ::= INTEGER { normal(0), abnormal(1) }
                END
                """, "Root");

        assertNull(fields.get(0).getUniversalTagOverride(), "a bare primitive must not get an override");
        assertNull(fields.get(1).getUniversalTagOverride(), "an untagged alias must not get an override");
        assertNull(fields.get(2).getUniversalTagOverride(),
                "an APPLICATION tag is not a universal re-tag and must be left to resolveEffectiveTag");
        assertNull(fields.get(3).getUniversalTagOverride(),
                "a named-number body ends the chain without yielding an override");
    }

    /**
     * X.690 11.6: a SET's components are encoded in tag order. The encoder emits
     * fields in the order of the resolved list, so a SET declared out of order
     * used to leak that order straight onto the wire.
     *
     * <p>Two SETs in MMTelChargingDataTypes are declared out of order -
     * {@code MMTelRecord} writes {@code routeHeaderReceived [59]} after
     * {@code mMTelInformation [110]}, and {@code ManagementExtensions} writes
     * {@code [520]} after {@code [523]}. Across 6000 records of two EMM-accepted
     * reference captures every SET body is sorted ascending without exception,
     * and EMM rejected our file with "Duplicate Tag data found": a decoder that
     * assumes ascending order sees a tag lower than the previous one and takes
     * the component for a repeat. 120 SETs across 28 of the 808 modules are
     * declared out of order, so this was never MMTel-specific.</p>
     *
     * <p>Sorting is valid under plain BER as well as DER, so it is applied
     * everywhere rather than gated on a verified family.</p>
     */
    @Test
    void setComponentsAreOrderedByTagRegardlessOfDeclarationOrder() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SET {
                    late [110] INTEGER OPTIONAL,
                    early [59] INTEGER OPTIONAL,
                    first [2] INTEGER OPTIONAL
                }
                END
                """, "Root");

        assertEquals(List.of(2, 59, 110),
                fields.stream().map(AsnField::getTagNumber).toList(),
                "a SET's components must be encoded in ascending tag order (X.690 11.6), "
                        + "not in the order the schema happens to declare them");
    }

    /**
     * The counterpart guard: a SEQUENCE is positional, so its declared order is
     * meaningful and must survive untouched. Sorting it would reorder the wire
     * format of every SEQUENCE-based record in the data set.
     */
    @Test
    void sequenceComponentsKeepTheirDeclarationOrder() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE {
                    late [110] INTEGER OPTIONAL,
                    early [59] INTEGER OPTIONAL,
                    first [2] INTEGER OPTIONAL
                }
                END
                """, "Root");

        assertEquals(List.of(110, 59, 2),
                fields.stream().map(AsnField::getTagNumber).toList(),
                "a SEQUENCE is positional - reordering it would corrupt the wire format");
    }

    /**
     * A SET whose components are not all tagged offers nothing reliable to sort
     * by, so the declaration order is kept rather than guessed at.
     */
    @Test
    void aSetWithUntaggedComponentsIsLeftInDeclarationOrder() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SET {
                    tagged [110] INTEGER OPTIONAL,
                    untagged INTEGER OPTIONAL
                }
                END
                """, "Root");

        assertEquals(List.of("tagged", "untagged"),
                fields.stream().map(AsnField::getFieldName).toList());
    }

    /**
     * Real schemas wrap long declarations, putting OPTIONAL on the next line.
     * Entries end at a newline as well as a comma, so the lone OPTIONAL used to
     * become its own entry and parse into a phantom field named OPTIONA of type
     * L - which the generator filled and the encoder emitted as a real TLV,
     * corrupting the record. 768 such entries exist across 32 of 808 modules.
     */
    @Test
    void aWrappedOptionalKeywordDoesNotBecomeItsOwnField() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE {
                    familyAndFriendsIndicator   [17] INTEGER
                                                     OPTIONAL,
                    numberOfInterrogations      [18] INTEGER
                }
                END
                """, "Root");

        assertEquals(List.of("familyAndFriendsIndicator", "numberOfInterrogations"),
                fields.stream().map(AsnField::getFieldName).toList());
        assertTrue(fields.get(0).isOptional(),
                "the wrapped OPTIONAL must attach to the field it belongs to");
    }

    /** The same wrapping happens mid-type, splitting "SEQUENCE OF" from its element. */
    @Test
    void aWrappedSequenceOfKeepsItsElementType() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE {
                    communityDataInfo [30] SEQUENCE OF CommunityDataInfo
                                           OPTIONAL
                }
                CommunityDataInfo ::= SEQUENCE { a [0] INTEGER }
                END
                """, "Root");

        assertEquals(1, fields.size(), "no phantom entry may be produced");
        AsnField field = fields.get(0);
        assertEquals("communityDataInfo", field.getFieldName());
        assertTrue(field.isRepeated(), "SEQUENCE OF must survive the line break");
        assertTrue(field.isOptional());
    }

    /**
     * ASN.1 puts a collection's size constraint between the keyword and OF:
     * {@code SEQUENCE (SIZE(1..5)) OF Type}. The old check was
     * {@code startsWith("SEQUENCE OF")}, so this form - and the equally common
     * {@code SEQUENCE  OF Type} with stray whitespace - never registered as a
     * collection. The element type was then never split off, the registry lookup
     * for the whole expression found nothing, and the field ended up with no
     * children AND no repeated flag: a list of structures went out as one flat
     * text leaf. 70 fields across 19 modules were affected.
     */
    @Test
    void aSizeConstrainedCollectionStillResolvesItsElementType() {
        for (String declaration : new String[] {
                "values [26] SEQUENCE (SIZE(1..5)) OF Item OPTIONAL",
                "values [26] SEQUENCE  OF Item OPTIONAL",
                "values [26] SET (SIZE(1..5)) OF Item OPTIONAL" }) {
            List<AsnField> fields = resolve("""
                    M DEFINITIONS IMPLICIT TAGS ::=
                    BEGIN
                    Root ::= SEQUENCE { %s }
                    Item ::= SEQUENCE { a [0] INTEGER, b [1] IA5String }
                    END
                    """.formatted(declaration), "Root");

            assertEquals(1, fields.size(), declaration);
            AsnField collection = fields.get(0);
            assertTrue(collection.isRepeated(), "must be a collection: " + declaration);
            assertNotNull(collection.getChildren(), "element children lost: " + declaration);
            assertEquals(List.of("a", "b"),
                    collection.getChildren().stream().map(AsnField::getFieldName).toList());
        }
    }

    /** The collection split must not fire on a type merely named like the keywords. */
    @Test
    void aTypeNamedLikeTheCollectionKeywordsIsNotSplit() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE {
                    a [0] SequenceOfferData,
                    b [1] SEQUENCE OF ListOfThings
                }
                SequenceOfferData ::= SEQUENCE { x [0] INTEGER }
                ListOfThings ::= SEQUENCE { y [0] INTEGER }
                END
                """, "Root");

        assertFalse(fields.get(0).isRepeated(), "SequenceOfferData is a plain type, not a collection");
        assertEquals(List.of("x"),
                fields.get(0).getChildren().stream().map(AsnField::getFieldName).toList());
        // Splitting must happen at the collection's own OF, not inside "ListOfThings".
        assertTrue(fields.get(1).isRepeated());
        assertEquals(List.of("y"),
                fields.get(1).getChildren().stream().map(AsnField::getFieldName).toList());
    }

    /**
     * Guards the fix from over-reaching: an ENUMERATED member that happens to be
     * called "default" looks like a wrapped DEFAULT keyword but is a real entry.
     */
    @Test
    void anEnumeratedMemberNamedDefaultIsNotSwallowed() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Root ::= SEQUENCE {
                    method [0] PartialRecordMethod
                }
                PartialRecordMethod ::= ENUMERATED {
                    default (0),
                    aFieldChange (1)
                }
                END
                """, "Root");

        assertEquals(1, fields.size());
        String type = fields.get(0).getFieldType();
        assertTrue(type.contains("default(0)") && type.contains("aFieldChange(1)"),
                "both declared members must survive, was: " + type);
    }

    /**
     * A declaration may wrap right after the member name, which the CME20R MSC
     * schemas do 1278 times:
     *
     * <pre>
     * timeFromRegisterSeizureToStartOfCharging
     *                             [13] IMPLICIT Time OPTIONAL,
     * </pre>
     *
     * <p>The name alone was read as a field - with its last character split off
     * as the type, giving {@code timeFromRegisterSeizureToStartOfChargin} of
     * type {@code g} - while the {@code [13]} line, starting with no name, was
     * dropped. The phantom is untagged, so it went out with a universal tag
     * among its context-tagged siblings and the real field never reached the
     * record at all.</p>
     */
    @Test
    void aDeclarationWrappedAfterItsNameStaysOneField() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SET {
                    interruptionTime            [12] IMPLICIT OCTET STRING OPTIONAL,
                    timeFromRegisterSeizureToStartOfCharging
                                                [13] IMPLICIT OCTET STRING OPTIONAL,
                    chargedParty                [14] IMPLICIT INTEGER OPTIONAL
                }
                END
                """, "Root");

        assertEquals(3, fields.size(), "the wrapped declaration must not add a phantom field");
        assertEquals("timeFromRegisterSeizureToStartOfCharging", fields.get(1).getFieldName());
        assertEquals(13, fields.get(1).getTagNumber());
        assertTrue(fields.get(1).getFieldType().contains("OCTET STRING"));
    }

    /** The same wrap in an ENUMERATED body, where the tail is the member's number. */
    @Test
    void aNamedNumberWrappedAfterItsNameStaysOneMember() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE {
                    service [0] IMPLICIT ServiceKind OPTIONAL
                }
                ServiceKind ::= ENUMERATED {
                    plain (6),
                    originatingExtendedCAMELServiceWithINC
                        (7)
                }
                END
                """, "Root");

        assertTrue(fields.get(0).getFieldType().contains("originatingExtendedCAMELServiceWithINC(7)"),
                "a wrapped named number belongs to the name above it: " + fields.get(0).getFieldType());
    }

    /**
     * The merge is deliberately narrow. A bare name followed by another
     * DECLARATION is left alone - swallowing it would lose a real field, and no
     * evidence says which of the two the schema meant.
     */
    @Test
    void twoDeclarationsAreNeverMergedIntoOne() {
        List<AsnField> fields = resolve("""
                M DEFINITIONS ::=
                BEGIN
                Root ::= SEQUENCE {
                    first  [0] IMPLICIT INTEGER OPTIONAL,
                    second [1] IMPLICIT INTEGER OPTIONAL
                }
                END
                """, "Root");

        assertEquals(2, fields.size());
        assertEquals("first", fields.get(0).getFieldName());
        assertEquals("second", fields.get(1).getFieldName());
    }
}
