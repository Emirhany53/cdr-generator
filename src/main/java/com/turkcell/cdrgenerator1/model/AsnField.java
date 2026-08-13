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

    private List<AsnField> children;
}