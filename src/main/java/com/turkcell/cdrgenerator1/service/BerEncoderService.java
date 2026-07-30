package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.exception.BerEncodingException;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.BerTagClass;
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

    private final TlvWriter tlvWriter;

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

    /** Concatenates the TLV of every field, pulling each value by field name. */
    private byte[] encodeFieldList(List<AsnField> fields, Map<?, ?> values) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (AsnField field : fields) {
            Object value = values.get(field.getFieldName());
            buffer.writeBytes(encodeField(field, value));
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

        ByteArrayOutputStream elementBuffer = new ByteArrayOutputStream();
        for (Object element : elements) {
            byte[] inner;
            if (elementIsChoice) {
                inner = encodeConstructed(field, element);
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
            case OCTET_STRING -> BARE_HEX.matcher(text).matches()
                    ? tlvWriter.encodeHex(text)
                    : tlvWriter.encodeString(text);
            case NULL -> new byte[0];
            case STRING -> tlvWriter.encodeString(text);
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

    /** A field is constructed when the resolver attached child definitions to it. */
    private boolean isConstructed(AsnField field) {
        return Objects.nonNull(field.getChildren()) && !field.getChildren().isEmpty();
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

        // X.680 8.3: a CHOICE can never carry an IMPLICIT tag, because the tag
        // is what identifies the selected alternative. Any tag on a CHOICE is
        // therefore encoded as EXPLICIT, whatever the module's default tagging.
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
