package com.turkcell.cdrgenerator1.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AsnField {
    private String fieldName;
    private String fieldType;
    private boolean optional;
    private boolean repeated;

    /**
     * True when this field's type resolves to an ASN.1 CHOICE.
     *
     * <p>A CHOICE is "exactly one of these alternatives", so its encoding IS the
     * encoding of the selected alternative - it must never be wrapped in an
     * extra universal SEQUENCE. {@link #children} therefore holds a single
     * element: the selected alternative, carrying its own tag.</p>
     *
     * <p>The encoder cannot infer this from {@code children} alone, because a
     * SEQUENCE with one field looks identical. Without this flag a tagged
     * CHOICE is emitted as {@code A4 { 30 { ... } }} instead of
     * {@code A4 { A0 { ... } }}, and a decoder rejects the record: the
     * universal SEQUENCE matches none of the CHOICE's alternatives.</p>
     */
    private boolean choice;

    /**
     * CHOICE tipinin adi (ornek: {@code called-Party-Address} icin
     * {@code "InvolvedParty"}). {@code AsnStructure#choiceTypeName} yalnizca KOKU
     * kapsiyordu; bu onun alan bazindaki karsiligi. Arayuz bununla, tip adini
     * onceden bilmeden, path-scoped {@code choiceSelections} ile tek bir call
     * site'i hedefleyebiliyor. {@link #choice} false ise null.
     */
    private String choiceTypeName;

    /**
     * CHOICE alternatiflerinin adlari, tanim sirasiyla (ornek:
     * {@code ["sIP-URI", "tEL-URI"]}). Arayuzdeki alternatif secici bunu
     * kullanir. {@link #choice} false ise null.
     */
    private List<String> choiceAlternatives;

    /**
     * True when this field's type (or, for a repeated field, its ELEMENT type)
     * resolves to an ASN.1 SET rather than a SEQUENCE.
     *
     * <p>A SET's universal tag is 17 (0x31), a SEQUENCE's is 16 (0x30). The
     * distinction only becomes visible where the encoder has to emit a
     * universal tag of its own: the inner tag of an EXPLICIT wrapper, and the
     * per-element wrapper of a {@code SEQUENCE OF <Set>}. An IMPLICIT tag
     * replaces the universal tag outright, so it is unaffected.</p>
     *
     * <p>Like {@link #choice}, the encoder cannot infer this from
     * {@link #children}: a SET and a SEQUENCE look identical once resolved to a
     * field list.</p>
     */
    private boolean set;

    /**
     * True when this field sits in the module family whose decoder mis-reads an
     * IMPLICIT-tagged OPTIONAL CHOICE - the MMTel/AIMS/IMS/UAG/ATS lineage,
     * recognised by its shared {@code InvolvedParty} CHOICE and confirmed
     * against a real EMM-accepted capture.
     *
     * <p>{@code app.cdr.skip-implicit-choice-fields} drops such fields from
     * generation so EMM stops answering "Duplicate Tag". The flag is what keeps
     * that workaround where its evidence is: without it the rule was purely
     * structural and fired in every module, and TAP-0309 lost
     * {@code callEventDetails} - the only part of a TAP file that carries call
     * records - to a defect nobody had observed there.</p>
     */
    private boolean decoderHoistsImplicitChoice;

    /**
     * True when this field's type is a CHOICE carrying a tag that has no written
     * keyword, in a module whose header names no tagging mode - the one case a
     * real decoder has answered against X.680 8.3.
     *
     * <p>8.3 makes any tag on a CHOICE EXPLICIT, because the tag is what
     * identifies the selected alternative, and both {@code BerEncoderService} and
     * {@code BerVerifier} applied that whatever the module default. EMM does not,
     * when the header names no mode. {@code CHFChargingDataTypes16} declares</p>
     *
     * <pre>
     * networkFunctionFQDN [5] EXPLICIT NodeAddress OPTIONAL
     * NodeAddress ::= CHOICE { iPAddress [0] IPAddress, domainName [1] IA5String }
     * </pre>
     *
     * <p>The outer {@code [5]} carries a written EXPLICIT and keeps its wrapper.
     * The inner {@code iPAddress [0]} carries none, and 8.3 made us wrap it too.
     * Round 13 sent the same record three ways: {@code [4]} alone passed,
     * {@code [5]} as {@code A5 08 A0 06 80 04 ..} was refused, and {@code [5]}
     * with that {@code A0} gone - {@code A5 06 80 04 ..} - passed.</p>
     *
     * <p>Deliberately NOT set for a module that writes {@code IMPLICIT TAGS}.
     * The same pattern sits at 163 sites across 31 such modules, two of them
     * EMM-accepted ({@code MMTelChargingDataTypes}, {@code CHAD}), and MMTel is
     * additionally matched layer for layer against a real capture. Nothing has
     * measured that class, so it keeps 8.3. This flag holds the new reading to
     * the 21 sites in 5 keyword-less modules the evidence covers.</p>
     */
    private boolean choiceTagImplicit;

    /**
     * For a repeated field whose ELEMENT type declares a tag of its own, the
     * element as a field: same children and type, not repeated, carrying that
     * tag. Null otherwise, which leaves the element on its universal tag.
     *
     * <p>{@code VasInfo ::= [APPLICATION 7] SEQUENCE OF VasDefinition} puts
     * {@code [APPLICATION 7]} on this field and {@code [APPLICATION 238]} on
     * every element. The collection tag has always been carried here; the
     * element tag had nowhere to live, so {@code encodeRepeated} fell back to a
     * universal SEQUENCE per element.</p>
     */
    private AsnField elementTagCarrier;

    private Integer tagNumber;

    /** BER tag class from the [..] annotation; CONTEXT when only a number is given. */
    private BerTagClass tagClass;

    private boolean explicit;

    /**
     * The tagging keyword the SCHEMA wrote at this site, recorded while parsing
     * rather than derived from {@link #explicit} afterwards.
     *
     * <p>{@link #explicit} is the decision; this is the declaration. They part
     * company wherever a compatibility rule applies - a site that declares
     * EXPLICIT and goes out IMPLICIT reads {@code EXPLICIT} here and
     * {@code false} there - and a verifier that only has the decision can never
     * ask whether the decision was the right one, because the same tree produced
     * the bytes it is checking.</p>
     *
     * <p>{@link AsnDeclaredTagging#NONE} carries real information: three EMM
     * measurements turn on "the schema wrote no keyword here". Null only for
     * fields built without going through the parser (synthetic root bodies).</p>
     */
    private AsnDeclaredTagging declaredTagging;

    /**
     * True when the tag on this field was written on its TYPE
     * ({@code NrFile ::= [APPLICATION 1] SEQUENCE}) rather than on the field
     * itself ({@code name [2] IA5String}).
     *
     * <p>The two were measured separately and could have gone different ways -
     * IMSCDRS answered for field tags in round 6, FDRInput and Audit for type
     * tags in rounds 9 and 10 - so an invariant covering one must be able to
     * exclude the other. They happen to agree, but nothing guaranteed that in
     * advance and nothing guarantees it for the next module either.</p>
     */
    private boolean tagDeclaredOnType;

    /**
     * True when the module this field came from names no tagging mode in its
     * header ({@code Mod DEFINITIONS ::=}).
     *
     * <p>Carried on the field rather than on {@link AsnStructure} for the same
     * reason {@link #choiceTagImplicit} is: {@code BerVerifier} is entered with
     * a bare field list as often as with a structure, and a rule that cannot see
     * the module cannot fire. Constant across every field of one module.</p>
     */
    private boolean moduleNamesNoTaggingMode;

    /**
     * The universal-class tag number this field's type must be encoded with,
     * when its ASN.1 definition re-tags a primitive into the UNIVERSAL class -
     * e.g. {@code GraphicStringImp ::= [UNIVERSAL 25] IMPLICIT IA5String}.
     * {@code null} for the overwhelming majority of fields, which simply use
     * the universal tag implied by their primitive type.
     *
     * <p>The value bytes still follow the underlying type (IA5String here), but
     * the tag on the wire must be 25 (GraphicString), not 22 (IA5String). The
     * resolver otherwise discards the {@code [UNIVERSAL n]} prefix while
     * following the alias chain down to the primitive, which silently loses
     * that distinction; keeping it here is what lets
     * {@code BerEncoderService.wrapLeafInUniversalTlv} emit the right tag.</p>
     *
     * <p>Only ever consulted where the encoder emits a UNIVERSAL tag of its own:
     * an untagged leaf, the inner tag of an EXPLICIT wrapper, and each element
     * of a {@code SEQUENCE OF <primitive>}. An IMPLICIT context tag replaces the
     * universal tag outright, so those fields are unaffected either way.</p>
     */
    private Integer universalTagOverride;

    /**
     * True when this field's type - or, for a collection, its ELEMENT type - is
     * a type the registry KNOWS and that is declared SEQUENCE, SET or CHOICE,
     * but whose body declares no components at all.
     *
     * <p>{@link #children} is null for four different reasons: the type is not
     * in the registry, the recursion guard stopped, the type is an ENUMERATED,
     * or the type is structured and empty. Only the last one is a container, and
     * without this flag the encoder cannot tell it from the other three - it
     * sees an empty child list and writes a primitive leaf.</p>
     *
     * <p>{@code IMS-R8-2009-03} is where that showed:</p>
     *
     * <pre>
     * recordExtensions [25] ManagementExtensions OPTIONAL
     * ManagementExtensions ::= SET OF ManagementExtension
     * ManagementExtension  ::= SET { -- operator specific record extensions }
     * </pre>
     *
     * <p>The body is entirely comment, and the module's IMPORTS clause is
     * commented out too, so nothing else can supply one - confirmed by the
     * schema's owner: {@code GenericChargingDataTypes} is named in that comment
     * but never used. The empty SET is the definition, not a gap in it. X.690
     * 8.11 gives it one encoding, {@code 31 00}, and X.690 8.10 requires each
     * element of the SET OF to carry it; we sent {@code 04 08 4E 47 44 46 ..}
     * instead and EMM refused the record at exactly that offset ("Invalid length
     * 513", the node's end offset).</p>
     *
     * <p>9 sites carry this across the corpus, all of them OPTIONAL, in 3
     * modules: {@code IMS-R8-2009-03} (1, a SET), {@code NRTRDEFdrFile} and
     * {@code NRTRDEFERFile} (4 each, SEQUENCEs). None of the three is an IMPORTS
     * source, so no other module's bytes can change through them.</p>
     *
     * <p>Deliberately set ONLY where the field is also OPTIONAL. Nothing about
     * X.690 8.11 needs that - a mandatory componentless SET would encode
     * {@code 31 00} just the same - but every one of the 9 measured sites is
     * OPTIONAL, and holding the flag to the ground that was measured is what
     * keeps a mandatory field somewhere else from silently changing shape. The
     * condition lives here rather than at the two use sites so
     * {@code CdrRecordBuilder} and {@code BerEncoderService} cannot drift apart:
     * the builder emits an empty body exactly where the encoder expects one, and
     * a mismatch would surface as "is constructed and expects an object value".</p>
     */
    private boolean structuralTypeWithNoComponents;

    /**
     * True when this field's own repetition ({@link #repeated}) wraps an
     * ELEMENT type that is ITSELF a named {@code SEQUENCE OF} / {@code SET OF}
     * alias - two collection layers where {@link #repeated} can only record
     * one.
     *
     * <p>{@code list-of-Call-Transfer-Info [428] SEQUENCE OF
     * Call-Transfer-Info-List} is the case: the field's inline
     * {@code SEQUENCE OF} is the layer {@link #repeated} already carries, but
     * {@code Call-Transfer-Info-List ::= SEQUENCE OF Call-Transfer-Info} is a
     * SECOND collection with no field of its own to hold a flag - so
     * {@link com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver#resolveAlias}
     * opened it and had nowhere to record that it had. {@link #children} ended
     * up holding {@code Call-Transfer-Info}'s fields directly, one collection
     * layer short: the encoder wrote {@code BF 83 2C .. 31 0D .. 31 0D ..} with
     * the middle {@code Call-Transfer-Info-List} wrapper never written - X.690
     * 8.10 makes the content of a {@code SEQUENCE OF T} the concatenation of
     * T's own encodings, and T here (Call-Transfer-Info-List) is itself a
     * {@code SEQUENCE OF}, so it needed its own {@code 30 ..} TLV around the
     * {@code 31 0D} elements. EMM refused the record exactly at that missing
     * node's end offset ("Invalid length 2065").</p>
     *
     * <p>Deliberately narrow: only true when BOTH the field's own type is an
     * inline collection AND the element type it names is itself a named
     * collection alias - the one shape measured to lose a layer. A field whose
     * element type is an ordinary SEQUENCE/SET (the overwhelming majority of
     * repeated fields, including every EMM-accepted one) leaves this false and
     * encodes exactly as before.</p>
     */
    private boolean nestedCollectionElement;

    /**
     * For {@link #nestedCollectionElement}, whether the MIDDLE collection - the
     * named alias itself ({@code Call-Transfer-Info-List ::= SEQUENCE OF
     * Call-Transfer-Info}) - is declared {@code SET OF} rather than
     * {@code SEQUENCE OF}. This is NOT {@link #set}: {@link #set} already
     * correctly names the INNERMOST element's kind (Call-Transfer-Info, a SET,
     * unaffected by this change), while this flag picks the universal tag - 17
     * vs 16 - for the wrapper the middle layer needs around its own elements
     * and did not have anywhere to write before.
     */
    private boolean nestedCollectionElementIsSet;

    private List<AsnField> children;
}