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
        assertFalse(parties.isExplicit(),
                "a written EXPLICIT on a SEQUENCE-OF-CHOICE field must be neutralized: the real "
                        + "MMTel wire format (confirmed against an EMM-accepted reference capture) has no "
                        + "extra universal SEQUENCE between [6] and the CHOICE elements. Honoring the "
                        + "source EXPLICIT literally here is exactly the bug that made EMM reject "
                        + "list-Of-Calling-Party-Address; see AsnFieldTreeResolver.effectiveExplicit().");
    }

    /**
     * Without the InvolvedParty fingerprint (no module in this fixture defines
     * it), {@code AsnFieldTreeResolver.effectiveExplicit} must leave every
     * written EXPLICIT untouched - scalar CHOICE, plain SEQUENCE, plain
     * repeated element alike. This is the GGSN/LTE-family baseline: those
     * modules are outside the verified lineage, so nothing here should ever
     * change without EMM evidence for that family specifically (see
     * {@link #explicitIsPreservedOutsideTheVerifiedInvolvedPartyFamily} for
     * the SET/plain-SEQUENCE-focused sibling of this test).
     */
    @Test
    void explicitIsPreservedForEveryShapeOutsideAnyVerifiedFamily() {
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
        assertTrue(fields.get(1).isExplicit(),
                "outside any verified family, a plain SEQUENCE field must keep its written EXPLICIT");
        assertTrue(fields.get(2).isExplicit(),
                "outside any verified family, a SEQUENCE OF <non-CHOICE> must keep its written EXPLICIT too");
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
     * The exact same shapes, but WITHOUT the InvolvedParty fingerprint -
     * simulating LTE-R10's {@code servedPDPPDNAddress [9] EXPLICIT PDPAddress}
     * (PDPAddress is a SET in that module) and any GGSN/LTE-family field that
     * writes EXPLICIT on a plain SEQUENCE or SET. There is no EMM verification
     * for that CDR family, so every written EXPLICIT must be left exactly as
     * written; this is what keeps the fix from becoming a blanket "ignore
     * EXPLICIT" rule applied to the whole data set.
     */
    @Test
    void explicitIsPreservedOutsideTheVerifiedInvolvedPartyFamily() {
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

        assertTrue(fields.get(0).isExplicit(),
                "without the InvolvedParty fingerprint (e.g. LTE-R10) a written EXPLICIT on a SET "
                        + "must be left untouched - there is no EMM evidence for that CDR family");
        assertTrue(fields.get(1).isExplicit(),
                "same for a plain repeated SEQUENCE outside the verified family");
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
}
