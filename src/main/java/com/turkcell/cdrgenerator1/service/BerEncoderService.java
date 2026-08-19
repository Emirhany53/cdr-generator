package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.exception.BerEncodingException;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.model.RecordFieldKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a generated record tree into Basic Encoding Rules (BER) bytes.
 *
 * <p>Encoding rules applied here:</p>
 * <ul>
 *   <li>Each record is wrapped in a universal SEQUENCE TLV (0x30 ...), so a
 *       multi-record file is a valid concatenation of self-delimiting TLVs
 *       and a decoder can always find record boundaries.</li>
 *   <li>Fields with a [n] annotation use their declared tag class and number.
 *       IMPLICIT (the default) replaces the underlying type's tag; EXPLICIT
 *       first encodes the value with its universal tag and then wraps that
 *       TLV in a constructed context/application tag.</li>
 *   <li>Fields without any tag annotation fall back to the universal tag of
 *       their primitive type (INTEGER 2, BOOLEAN 1, OCTET STRING 4,
 *       ENUMERATED 10, IA5String 22...), or universal SEQUENCE for
 *       constructed values.</li>
 *   <li>A CHOICE field ({@code AsnField.choice}) is never wrapped: its encoding
 *       is exactly the selected alternative's TLV. A tag on a CHOICE is always
 *       EXPLICIT (X.680 8.3), so it adds an outer constructed tag around that
 *       alternative and nothing else. This applies both to a scalar CHOICE
 *       field and to each element of a {@code SEQUENCE OF <Choice>}
 *       ({@link #encodeRepeated}) - only the collection's own outer tag keeps
 *       normal container wrapping.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BerEncoderService {

    private static final Pattern DECIMAL_LITERAL = Pattern.compile("^'(-?\\d+)'D$");

    private static final Pattern HEX_LITERAL = Pattern.compile("^'([0-9A-Fa-f]*)'H$");

    private static final Pattern STRING_LITERAL = Pattern.compile("^\"(.*)\"$", Pattern.DOTALL);

    private static final Pattern BARE_HEX = Pattern.compile("^(?:[0-9A-Fa-f]{2})+$");

    private static final String BOOLEAN_TRUE_TEXT = "true";
    private static final String BOOLEAN_FALSE_TEXT = "false";
    private static final String BOOLEAN_TRUE_DIGIT = "1";
    private static final String BOOLEAN_FALSE_DIGIT = "0";

    /** Identifier and length bits {@link #retagOutermost} has to read (X.690 8.1). */
    private static final int CONSTRUCTED_BIT = 0x20;
    private static final int HIGH_TAG_MARKER = 0x1F;
    private static final int CONTINUATION_BIT = 0x80;
    private static final int BYTE_MASK = 0xFF;
    private static final int LONG_FORM_LENGTH_MARKER = 0x80;

    private final TlvWriter tlvWriter;
    private final FixedWidthTextFormatter fixedWidthTextFormatter;

    /**
     * Encodes one record according to its root kind.
     *
     * <p>For a normal SEQUENCE/SET root the record is wrapped in a universal
     * SEQUENCE TLV. For a CHOICE root the record IS the selected alternative, so
     * it is encoded directly with the alternative's own tag (e.g.
     * {@code commandRecord [APPLICATION 0] ...}) and is NOT wrapped in an
     * artificial SEQUENCE - which would produce BER that no longer matches the
     * ASN.1 schema.</p>
     */
    public byte[] encodeRecord(AsnStructure structure, Map<String, Object> record) {
        Objects.requireNonNull(structure, "structure must not be null");
        Objects.requireNonNull(record, "record must not be null");

        // A root type that tags itself IS that tag's TLV. The carrier holds the
        // tag and the record's fields as its children, so the ordinary field
        // path writes it - IMPLICIT replaces the universal SEQUENCE/SET tag,
        // EXPLICIT keeps it and wraps, exactly as for any other tagged field.
        AsnField rootTagCarrier = structure.getRootTagCarrier();
        if (Objects.nonNull(rootTagCarrier)) {
            byte[] encoded = encodeField(rootTagCarrier, record);
            log.debug("Encoded record tagged by its own type ('{}') into {} BER bytes",
                    rootTagCarrier.getFieldName(), encoded.length);
            return encoded;
        }

        if (!structure.isChoiceRoot()) {
            return encodeRecord(structure.getFields(), record, structure.isSetRoot());
        }

        List<AsnField> fields = structure.getFields();
        if (Objects.isNull(fields) || fields.isEmpty()) {
            throw new BerEncodingException(
                    "CHOICE structure '" + structure.getStructureName() + "' has no selectable alternative");
        }
        AsnField alternative = fields.get(0);
        byte[] encoded = encodeField(alternative, record.get(alternative.getFieldName()));
        log.debug("Encoded CHOICE record ('{}') into {} BER bytes",
                alternative.getFieldName(), encoded.length);
        return encoded;
    }

    /**
     * Encodes one record as a single universal SEQUENCE TLV whose content is
     * the concatenation of the encoded top-level fields.
     */
    public byte[] encodeRecord(List<AsnField> topLevelFields, Map<String, Object> record) {
        return encodeRecord(topLevelFields, record, false);
    }

    /**
     * Encodes one record, wrapping it in a universal SET TLV instead of a
     * SEQUENCE when {@code setRoot} is true (X.690 8.11).
     */
    public byte[] encodeRecord(List<AsnField> topLevelFields, Map<String, Object> record, boolean setRoot) {
        Objects.requireNonNull(topLevelFields, "topLevelFields must not be null");
        Objects.requireNonNull(record, "record must not be null");

        byte[] content = encodeFieldList(topLevelFields, record);
        BerUniversalTag rootTag = setRoot ? BerUniversalTag.SET : BerUniversalTag.SEQUENCE;
        byte[] encoded = tlvWriter.buildTlv(BerTagClass.UNIVERSAL,
                rootTag.getTagNumber(), true, content);
        log.debug("Encoded record into {} BER bytes", encoded.length);
        return encoded;
    }

    /**
     * Concatenates the TLV of every field, pulling each value by its record key.
     *
     * <p>The key is the field name only while that name is unique in this body.
     * A body may declare the same name twice under different tags, and looking
     * both up by name handed the encoder ONE value to write under BOTH tags -
     * so the keys come from {@link RecordFieldKeys}, exactly as
     * {@code CdrRecordBuilder} produced them from this same field list.</p>
     */
    private byte[] encodeFieldList(List<AsnField> fields, Map<?, ?> values) {
        List<String> keys = RecordFieldKeys.forFields(fields);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (int index = 0; index < fields.size(); index++) {
            Object value = values.get(keys.get(index));
            buffer.writeBytes(encodeField(fields.get(index), value));
        }
        return buffer.toByteArray();
    }

    /** Encodes one field of any kind; null values (unfilled OPTIONALs) are omitted. */
    private byte[] encodeField(AsnField field, Object value) {
        if (Objects.isNull(value)) {
            return new byte[0];
        }

        // A repeated (SEQUENCE OF / SET OF) field always encodes as a collection:
        // every element must be its own self-delimiting TLV inside the outer tag.
        // A scalar value (e.g. one user-supplied string for a SEQUENCE OF
        // primitive) is treated as a single-element list - writing raw value
        // bytes directly into a constructed TLV would produce invalid BER.
        if (field.isRepeated()) {
            List<?> elements = (value instanceof List<?> list) ? list : List.of(value);
            return encodeRepeated(field, elements);
        }

        byte[] content = isConstructed(field)
                ? encodeConstructed(field, value)
                : encodeLeafValue(field, value);

        return wrapInTlv(field, content);
    }

    /** SEQUENCE OF / SET OF: one inner encoding per list element. */
    private byte[] encodeRepeated(AsnField field, List<?> elements) {
        // The element type may itself be a CHOICE (e.g. Charging-Function-Address.ccf
        // ::= SEQUENCE OF NodeAddress). Each element is then already the selected
        // alternative's complete, self-tagged TLV (same shape as the scalar CHOICE
        // case in wrapInTlv). Wrapping it in a per-element universal SEQUENCE would
        // hide that tag behind 0x30, which matches none of the CHOICE's
        // alternatives - the exact bug fixed for the scalar case, just recurring
        // once per list element instead of once for the field.
        boolean elementIsChoice = field.isChoice();

        // The element type may declare a tag of its own - VasInfo ::=
        // [APPLICATION 7] SEQUENCE OF VasDefinition writes [APPLICATION 7] once
        // around the collection and VasDefinition's [APPLICATION 238] on every
        // element. The carrier IS that element as a field, so the ordinary field
        // path writes it and the universal fallback below stays for collections
        // whose element type tags nothing.
        AsnField elementTagCarrier = field.getElementTagCarrier();

        ByteArrayOutputStream elementBuffer = new ByteArrayOutputStream();
        for (Object element : elements) {
            byte[] inner;
            if (elementIsChoice) {
                inner = encodeConstructed(field, element);
            } else if (Objects.nonNull(elementTagCarrier)) {
                inner = encodeField(elementTagCarrier, element);
            } else if (isConstructed(field)) {
                // Every element of a SEQUENCE OF <SEQUENCE> must be its own
                // self-delimiting TLV, otherwise the elements merge together.
                // The element's own universal tag is SET (17) when the element
                // type is a SET - e.g. SEQUENCE OF SubscriptionID, where
                // SubscriptionID ::= SET.
                inner = tlvWriter.buildTlv(BerTagClass.UNIVERSAL,
                        elementUniversalTag(field), true,
                        encodeConstructed(field, element));
            } else {
                inner = wrapLeafInUniversalTlv(field, encodeLeafValue(field, element));
            }
            elementBuffer.writeBytes(inner);
        }
        return wrapInTlv(field, elementBuffer.toByteArray());
    }

    /** Constructed value: concatenation of the child fields' TLVs. */
    private byte[] encodeConstructed(AsnField field, Object value) {
        if (!(value instanceof Map<?, ?> childValues)) {
            throw new BerEncodingException(
                    "Field '" + field.getFieldName() + "' is constructed and expects an object value");
        }
        // A structured type that declares no components has no child list to
        // walk, and X.690 8.11 gives it zero content octets - so the container
        // is written, empty, rather than skipped or filled with a leaf.
        if (Objects.isNull(field.getChildren())) {
            return new byte[0];
        }
        return encodeFieldList(field.getChildren(), childValues);
    }

    private byte[] encodeLeafValue(AsnField field, Object value) {
        // X.690 8.8: NULL carries NO contents octets. This is checked before the
        // value is looked at all, because the generator still produces some
        // placeholder for a NULL field and any of the branches below would
        // happily turn that into content bytes - which is exactly the defect
        // that made EMM reject a record with "Invalid length" (a NULL field is
        // a pure presence marker; the tag alone carries the whole meaning).
        if (BerPrimitiveType.fromTypeExpression(field.getFieldType()) == BerPrimitiveType.NULL) {
            return new byte[0];
        }
        if (value instanceof Boolean bool) {
            return tlvWriter.encodeBoolean(bool);
        }
        if (value instanceof Number number) {
            return tlvWriter.encodeInteger(number.longValue());
        }

        String text = String.valueOf(value).trim();

        Matcher decimal = DECIMAL_LITERAL.matcher(text);
        if (decimal.matches()) {
            return tlvWriter.encodeInteger(Long.parseLong(decimal.group(1)));
        }

        Matcher hex = HEX_LITERAL.matcher(text);
        if (hex.matches()) {
            return tlvWriter.encodeHex(hex.group(1));
        }

        Matcher quoted = STRING_LITERAL.matcher(text);
        if (quoted.matches()) {
            text = quoted.group(1);
        }

        return encodeByFieldType(field.getFieldType(), text);
    }

    /** Encodes an unwrapped scalar according to the field's ASN.1 type expression. */
    private byte[] encodeByFieldType(String fieldType, String text) {
        BerPrimitiveType primitiveType = BerPrimitiveType.fromTypeExpression(fieldType);

        return switch (primitiveType) {
            case BOOLEAN -> encodeBooleanText(fieldType, text);
            case INTEGER, ENUMERATED -> encodeIntegerText(fieldType, text);
            case OCTET_STRING -> encodeOctetStringText(fieldType, text);
            // A BIT STRING's value is a hex dump like an OCTET STRING's, but its
            // contents must additionally carry the leading unused-bit count.
            case BIT_STRING -> tlvWriter.encodeBitString(encodeOctetStringText(fieldType, text));
            case OBJECT_IDENTIFIER -> tlvWriter.encodeObjectIdentifier(text);
            case REAL -> tlvWriter.encodeReal(text);
            case NULL -> new byte[0];
            // Only a character-string type is padded. An OCTET STRING's SIZE
            // counts BYTES and its text is a hex dump (two characters per byte),
            // so spaces would corrupt it; an INTEGER's SIZE bounds its value
            // range, which fitIntegerToByteWidth already handles.
            case STRING -> tlvWriter.encodeString(fixedWidthTextFormatter.pad(fieldType, text));
        };
    }

    private byte[] encodeBooleanText(String fieldType, String text) {
        if (BOOLEAN_TRUE_TEXT.equalsIgnoreCase(text) || BOOLEAN_TRUE_DIGIT.equals(text)) {
            return tlvWriter.encodeBoolean(true);
        }
        if (BOOLEAN_FALSE_TEXT.equalsIgnoreCase(text) || BOOLEAN_FALSE_DIGIT.equals(text)) {
            return tlvWriter.encodeBoolean(false);
        }
        throw new BerEncodingException(
                "Value '" + text + "' is not a valid boolean for type " + fieldType);
    }

    private byte[] encodeIntegerText(String fieldType, String text) {
        try {
            return tlvWriter.encodeInteger(Long.parseLong(text));
        } catch (NumberFormatException e) {
            throw new BerEncodingException(
                    "Value '" + text + "' is not a valid integer for type " + fieldType);
        }
    }

    /**
     * An OCTET STRING's text is always a hex dump (two hex characters per
     * byte) by this codebase's convention - never literal characters. Before
     * this check existed, a malformed hex value (odd length, or a stray
     * non-hex character) silently fell through to {@link TlvWriter#encodeString},
     * which wrote the text's raw UTF-8 bytes as if it were the intended
     * content: no exception, just quietly wrong bytes on the wire. An empty
     * value is allowed through as zero content octets (a valid empty OCTET
     * STRING), matching the same "absent means empty, not literal" rule as
     * NULL above.
     */
    private byte[] encodeOctetStringText(String fieldType, String text) {
        if (text.isEmpty()) {
            return new byte[0];
        }
        if (!BARE_HEX.matcher(text).matches()) {
            throw new BerEncodingException(
                    "Value '" + text + "' is not a valid hex dump for OCTET STRING type " + fieldType);
        }
        return tlvWriter.encodeHex(text);
    }

    /**
     * A field is constructed when the resolver attached child definitions to it,
     * or when it names a structured type that declares no components at all.
     *
     * <p>The second case has no children to count and still has to go out as a
     * container: {@code ManagementExtension ::= SET { -- operator specific }} is
     * an empty SET, and X.690 8.11 encodes it {@code 31 00}. Reading only the
     * child list sent {@code 04 08 4E 47 44 46 ..} in its place and EMM refused
     * the record at that node. See
     * {@link AsnField#isStructuralTypeWithNoComponents()}.</p>
     */
    private boolean isConstructed(AsnField field) {
        return field.isStructuralTypeWithNoComponents()
                || (Objects.nonNull(field.getChildren()) && !field.getChildren().isEmpty());
    }

    /**
     * Wraps encoded content in this field's TLV.
     *
     * <p>Untagged fields fall back to their universal tag so the output stays a
     * valid TLV stream. IMPLICIT tagging replaces that universal tag with the
     * declared one; EXPLICIT tagging keeps the universal TLV and wraps it in a
     * constructed tag of the declared class and number.</p>
     */
    private byte[] wrapInTlv(AsnField field, byte[] content) {
        boolean constructed = isConstructed(field) || field.isRepeated();

        // A CHOICE is not a container - its encoding IS the selected
        // alternative's TLV, which `content` already holds complete with the
        // alternative's own tag. Wrapping it in a universal SEQUENCE (or
        // re-tagging it implicitly) would hide that tag, and a decoder matching
        // the incoming element against the CHOICE's alternatives would find no
        // match. Repeated fields are excluded: there the outer tag wraps the
        // collection, not a single alternative.
        boolean choice = field.isChoice() && !field.isRepeated();

        if (Objects.isNull(field.getTagNumber())) {
            if (choice) {
                return content;
            }
            return constructed
                    ? tlvWriter.buildTlv(BerTagClass.UNIVERSAL,
                            containerUniversalTag(field), true, content)
                    : wrapLeafInUniversalTlv(field, content);
        }

        BerTagClass tagClass = Objects.nonNull(field.getTagClass())
                ? field.getTagClass()
                : BerTagClass.CONTEXT;

        // An IMPLICIT tag on a CHOICE replaces the tag of the alternative the
        // content already carries instead of wrapping it. Only reachable where
        // the resolver set the flag; see AsnField.isChoiceTagImplicit.
        if (choice && field.isChoiceTagImplicit()) {
            return retagOutermost(content, tagClass, field.getTagNumber());
        }

        // X.680 8.3: a CHOICE can never carry an IMPLICIT tag, because the tag
        // is what identifies the selected alternative. Any tag on a CHOICE is
        // therefore encoded as EXPLICIT, whatever the module's default tagging -
        // except in the case just above, which a real decoder answered against.
        if (field.isExplicit() || choice) {
            byte[] inner;
            if (choice) {
                inner = content;
            } else if (constructed) {
                inner = tlvWriter.buildTlv(BerTagClass.UNIVERSAL,
                        containerUniversalTag(field), true, content);
            } else {
                inner = wrapLeafInUniversalTlv(field, content);
            }
            return tlvWriter.buildTlv(tagClass, field.getTagNumber(), true, inner);
        }
        return tlvWriter.buildTlv(tagClass, field.getTagNumber(), constructed, content);
    }

    /**
     * Rewrites the identifier octets of a TLV, keeping its constructed bit and
     * its contents. This is what an IMPLICIT tag does to the value it tags.
     *
     * <p>What the measurement pinned down, and what it did not: round 13's
     * accepted file carries {@code A5 06 80 04 ..} where the refused one carried
     * {@code A5 08 A0 06 80 04 ..}. At that site the alternative's tag and the
     * inner one are both {@code [0]}, so replacing the tag and dropping it
     * outright produce identical bytes and the evidence cannot separate them.
     * Replacing is what implicit tagging means, so that is what this does - but
     * the two readings diverge wherever the numbers differ, and no site like
     * that has been measured.</p>
     */
    private byte[] retagOutermost(byte[] content, BerTagClass tagClass, int tagNumber) {
        if (content.length == 0) {
            return content;
        }
        boolean constructed = (content[0] & CONSTRUCTED_BIT) != 0;
        int cursor = 1;
        if ((content[0] & HIGH_TAG_MARKER) == HIGH_TAG_MARKER) {
            while (cursor < content.length && (content[cursor] & CONTINUATION_BIT) != 0) {
                cursor++;
            }
            cursor++;
        }
        int firstLengthOctet = content[cursor++] & BYTE_MASK;
        if (firstLengthOctet >= LONG_FORM_LENGTH_MARKER) {
            cursor += firstLengthOctet - LONG_FORM_LENGTH_MARKER;
        }
        byte[] value = new byte[content.length - cursor];
        System.arraycopy(content, cursor, value, 0, value.length);
        log.debug("Re-tagged a CHOICE alternative onto {} [{}], {} content byte(s)",
                tagClass, tagNumber, value.length);
        return tlvWriter.buildTlv(tagClass, tagNumber, constructed, value);
    }

    /**
     * Universal tag for the wrapper the encoder emits around a field's own
     * content: SET (17) when the field's type is a SET, SEQUENCE (16)
     * otherwise.
     *
     * <p>A repeated field is always SEQUENCE here even when its ELEMENTS are
     * SETs: the wrapper at this level is the {@code SEQUENCE OF} collection
     * itself, and the per-element SET tags are emitted by
     * {@link #elementUniversalTag}.</p>
     */
    private int containerUniversalTag(AsnField field) {
        boolean isSet = field.isSet() && !field.isRepeated();
        return (isSet ? BerUniversalTag.SET : BerUniversalTag.SEQUENCE).getTagNumber();
    }

    /** Universal tag for one element of a SEQUENCE OF / SET OF collection. */
    private int elementUniversalTag(AsnField field) {
        return (field.isSet() ? BerUniversalTag.SET : BerUniversalTag.SEQUENCE).getTagNumber();
    }

    /**
     * Builds the universal-class TLV matching the leaf field's primitive type.
     *
     * <p>A type may re-tag itself into the UNIVERSAL class - the MMTel family's
     * {@code GraphicStringImp ::= [UNIVERSAL 25] IMPLICIT IA5String} encodes its
     * VALUE as an IA5String but must carry GraphicString's tag 25 on the wire.
     * {@link AsnField#getUniversalTagOverride()} carries that number when the
     * schema declares one, and it wins over the tag implied by the resolved
     * primitive type; otherwise nothing changes.</p>
     */
    private byte[] wrapLeafInUniversalTlv(AsnField field, byte[] content) {
        Integer override = field.getUniversalTagOverride();
        int tagNumber = Objects.nonNull(override)
                ? override
                : BerUniversalTag.forPrimitiveType(field.getFieldType()).getTagNumber();
        return tlvWriter.buildTlv(BerTagClass.UNIVERSAL, tagNumber, false, content);
    }
}
