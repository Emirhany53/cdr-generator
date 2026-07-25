package com.turkcell.cdrgenerator1.generator.validation;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.generator.BcdTimestampFactory;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.BerPrimitiveType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Yapay zekadan gelen degerleri asamali olarak denetler: ASN.1 tip uyumu,
 * SIZE kisiti, BCD zaman damgasi/tarih bicimi, ve yml'de tanimli alan kurali.
 * Herhangi biri basarisiz olursa deger reddedilir ve zincir rastgele uretime
 * duser.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FieldValueValidator {

    private static final String NUMERIC_LITERAL_PATTERN = "^-?\\d+$";
    private static final String TRUE_LITERAL = "1";
    private static final String FALSE_LITERAL = "0";
    /** OCTET STRING'de SIZE bayt cinsindendir; hex metin uzunlugu 2 katidir. */
    private static final int HEX_CHARS_PER_BYTE = 2;

    private final AiConfigProperties aiConfigProperties;
    private final AsnSizeExtractor asnSizeExtractor;
    private final BcdTimestampFactory bcdTimestampFactory;

    private final Map<String, Pattern> compiledPatterns = new ConcurrentHashMap<>();

    public boolean isValid(AsnField field, String value) {
        if (Objects.isNull(value) || value.isBlank()) {
            return false;
        }
        if (!matchesPrimitiveType(field, value)) {
            log.warn("Yapay zeka degeri alan tipiyle uyumsuz. Alan: {}, Tip: {}, Deger: {}",
                    field.getFieldName(), field.getFieldType(), value);
            return false;
        }
        if (exceedsMaxLength(field, value)) {
            log.warn("Yapay zeka degeri azami uzunlugu asti. Alan: {}, Deger: {}",
                    field.getFieldName(), value);
            return false;
        }
        if (isBcdField(field)) {
            // BCD zaman damgasi/tarih alanlari icin dogrulama matchesOctetString'de
            // tamamlanmistir; yml'deki genel date/timestamp kurallari (8/12-14 hane
            // bekleyen regex'ler) bu alanlara carpip dogru BCD degerini reddedebilir.
            return true;
        }
        return matchesRule(field, value);
    }

    /** Alan BCD zaman damgasi ya da BCD tarih ise yml kural kontrolu atlanir. */
    private boolean isBcdField(AsnField field) {
        Integer byteLength = asnSizeExtractor.extractMaxLength(field.getFieldType()).orElse(null);
        return bcdTimestampFactory.isBcdTimestamp(field.getFieldName(), byteLength)
                || bcdTimestampFactory.isBcdDate(field.getFieldName(), byteLength);
    }

    /**
     * INTEGER alana metin, BOOLEAN alana 0/1 disi deger gelmesini engeller.
     * BerEncoderService bu durumda BerEncodingException firlatirdi.
     */
    private boolean matchesPrimitiveType(AsnField field, String value) {
        return switch (BerPrimitiveType.fromTypeExpression(field.getFieldType())) {
            case INTEGER, ENUMERATED -> value.matches(NUMERIC_LITERAL_PATTERN);
            case BOOLEAN -> TRUE_LITERAL.equals(value) || FALSE_LITERAL.equals(value);
            case OCTET_STRING -> matchesOctetString(field, value);
            case STRING -> true;
        };
    }

    /**
     * BCD zaman damgasi alanlari 18 hex, BCD tarih alanlari 6 hex karakter
     * olmali. AI yanlis format uretirse reddedilir ve zincir rastgele
     * BCD uretimine duser.
     */
    private boolean matchesOctetString(AsnField field, String value) {
        Integer byteLength = asnSizeExtractor.extractMaxLength(field.getFieldType()).orElse(null);
        if (bcdTimestampFactory.isBcdTimestamp(field.getFieldName(), byteLength)) {
            return bcdTimestampFactory.isValidBcd(value);
        }
        if (bcdTimestampFactory.isBcdDate(field.getFieldName(), byteLength)) {
            return bcdTimestampFactory.isValidBcdDate(value);
        }
        return true;
    }

    private boolean exceedsMaxLength(AsnField field, String value) {
        return asnSizeExtractor.extractMaxLength(field.getFieldType())
                .map(maxLength -> effectiveMaxLength(field, maxLength))
                .map(effectiveMax -> value.length() > effectiveMax)
                .orElse(false);
    }

    /** OCTET STRING'de SIZE bayt cinsindendir; hex metin uzunlugu 2 katidir. */
    private int effectiveMaxLength(AsnField field, int sizeConstraint) {
        boolean isOctetString =
                BerPrimitiveType.fromTypeExpression(field.getFieldType()) == BerPrimitiveType.OCTET_STRING;
        return isOctetString ? sizeConstraint * HEX_CHARS_PER_BYTE : sizeConstraint;
    }

    private boolean matchesRule(AsnField field, String value) {
        Optional<AiConfigProperties.FieldRule> rule =
                aiConfigProperties.findRuleFor(field.getFieldName());

        if (rule.isEmpty() || Objects.isNull(rule.get().getPattern())) {
            return true;
        }
        final String pattern = rule.get().getPattern();
        Pattern compiled = compiledPatterns.computeIfAbsent(pattern, Pattern::compile);

        if (compiled.matcher(value).matches()) {
            return true;
        }

        // AsciiString/Currency/HexString/NumberString gibi tipler ASN.1'de
        // OCTET STRING olarak tanimlanir ama gercek icerikleri ASCII metindir
        // (TAP-0309 standardinin kendi yorumu bunu belirtir). AI dogru metni
        // uretip ASCII-hex'e cevirebilir (ornek: "949" -> "393439"); bu durumda
        // hex'i geri cozup kurali tekrar dene.
        if (BerPrimitiveType.fromTypeExpression(field.getFieldType()) == BerPrimitiveType.OCTET_STRING) {
            Optional<String> decoded = decodeAsciiHex(value);
            if (decoded.isPresent() && compiled.matcher(decoded.get()).matches()) {
                return true;
            }
        }

        log.warn("Yapay zeka degeri kurala uymadi, reddedildi. Alan: {}, Deger: {}, Regex: {}",
                field.getFieldName(), value, pattern);
        return false;
    }

    /**
     * Cift uzunluktaki hex metni ASCII karakterlere cozer. Her bayt gecerli
     * yazdirilabilir bir ASCII karakter degilse bos doner (gercek binary
     * veriyi metin sanip yanlis pozitif vermemek icin).
     */
    private Optional<String> decodeAsciiHex(String hex) {
        if (hex.length() % 2 != 0) {
            return Optional.empty();
        }
        StringBuilder decoded = new StringBuilder(hex.length() / 2);
        for (int i = 0; i < hex.length(); i += 2) {
            int codePoint;
            try {
                codePoint = Integer.parseInt(hex.substring(i, i + 2), 16);
            } catch (NumberFormatException ex) {
                return Optional.empty();
            }
            if (codePoint < 0x20 || codePoint > 0x7E) {
                return Optional.empty();
            }
            decoded.append((char) codePoint);
        }
        return Optional.of(decoded.toString());
    }
}