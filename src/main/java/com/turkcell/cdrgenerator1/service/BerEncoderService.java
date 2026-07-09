package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.exception.BerEncodingException;
import com.turkcell.cdrgenerator1.model.AsnField;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
@Service
@RequiredArgsConstructor
public class BerEncoderService {

    private static final Pattern DECIMAL_LITERAL = Pattern.compile("^'(-?\\d+)'D$");

    private static final Pattern HEX_LITERAL = Pattern.compile("^'([0-9A-Fa-f]*)'H$");

    private static final Pattern STRING_LITERAL = Pattern.compile("^\"(.*)\"$", Pattern.DOTALL);

    private static final Pattern BARE_HEX = Pattern.compile("^(?:[0-9A-Fa-f]{2})+$");

    private final TlvWriter tlvWriter;

    public byte[] encodeRecord(List<AsnField> topLevelFields, Map<String, Object> record) {
        Objects.requireNonNull(topLevelFields, "topLevelFields must not be null");
        Objects.requireNonNull(record, "record must not be null");

        byte[] encoded = encodeFieldList(topLevelFields, record);
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

        // A repeated field only encodes as a collection when the value really is a
        // list. CdrRecordBuilder emits a plain scalar for a SEQUENCE OF whose element
        // type resolved to a primitive (no children), so such a value falls through
        // to the single-value path below instead of failing.
        if (field.isRepeated() && value instanceof List<?>) {
            return encodeRepeated(field, value);
        }

        byte[] content = isConstructed(field)
                ? encodeConstructed(field, value)
                : encodeLeafValue(field, value);

        return wrapInTlv(field, content);
    }

    /** SEQUENCE OF / SET OF: one inner encoding per list element. */
    private byte[] encodeRepeated(AsnField field, Object value) {
        List<?> elements = (List<?>) value;

        ByteArrayOutputStream elementBuffer = new ByteArrayOutputStream();
        for (Object element : elements) {
            byte[] inner = isConstructed(field)
                    ? encodeConstructed(field, element)
                    : encodeLeafValue(field, element);
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
        String upperType = Objects.isNull(fieldType) ? "" : fieldType.toUpperCase(Locale.ROOT);

        if (upperType.startsWith("INTEGER") || upperType.startsWith("ENUMERATED")
                || upperType.startsWith("BOOLEAN")) {
            try {
                return tlvWriter.encodeInteger(Long.parseLong(text));
            } catch (NumberFormatException e) {
                throw new BerEncodingException(
                        "Value '" + text + "' is not a valid integer for type " + fieldType);
            }
        }

        if (upperType.startsWith("OCTET STRING") && BARE_HEX.matcher(text).matches()) {
            return tlvWriter.encodeHex(text);
        }

        return tlvWriter.encodeString(text);
    }

    /** A field is constructed when the resolver attached child definitions to it. */
    private boolean isConstructed(AsnField field) {
        return Objects.nonNull(field.getChildren()) && !field.getChildren().isEmpty();
    }


    private byte[] wrapInTlv(AsnField field, byte[] content) {
        if (Objects.isNull(field.getTagNumber())) {
            return content;
        }

        boolean constructed = isConstructed(field) || field.isRepeated();

        if (field.isExplicit()) {
            byte[] inner = tlvWriter.buildTlv(field.getTagNumber(), constructed, content);
            return tlvWriter.buildTlv(field.getTagNumber(), true, inner);
        }
        return tlvWriter.buildTlv(field.getTagNumber(), constructed, content);
    }
}