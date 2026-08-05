package com.turkcell.cdrgenerator1.generator.validation;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.generator.BcdTimestampFactory;
import com.turkcell.cdrgenerator1.generator.TbcdCodec;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.BerPrimitiveType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
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
    private static final String DOTTED_OID_PATTERN = "^\\d+(\\.\\d+)+$";
    private static final String DECIMAL_LITERAL_PATTERN = "^-?\\d+(\\.\\d+)?$";
    private static final String TRUE_LITERAL = "1";
    private static final String FALSE_LITERAL = "0";
    private static final int BITS_PER_BYTE = 8;
    /** OCTET STRING'de SIZE bayt cinsindendir; hex metin uzunlugu 2 katidir. */
    private static final int HEX_CHARS_PER_BYTE = 2;
    /**
     * BerEncoderService.encodeOctetStringText'in kabul ettigi tek bicim: cift
     * uzunlukta, yalnizca hex karakter. Uretimde AI, SIZE'i olmayan (dolayisiyla
     * BCD/TBCD adi da eslesmeyen) bir OCTET STRING alana "41253", "90212" gibi
     * tek basamak sayisi tek (5 haneli) duz sayilar dondurdu; asagidaki kontrol
     * olmadan bu deger "uyumlu" sayilip encoder'a kadar ilerliyor ve orada
     * BerEncodingException ile patliyordu - halbuki burada reddedilip zincirin
     * rastgele uretime (RandomValueSource, her zaman gecerli hex dokumu uretir)
     * dusmesi gerekiyordu.
     */
    private static final Pattern HEX_DUMP_PATTERN = Pattern.compile("^(?:[0-9A-Fa-f]{2})+$");
    /**
     * Same pattern FieldValueGenerator uses to read the {@code name(number)}
     * pairs the resolver compacts a named-number body into. That fix only
     * closed the RandomValueSource path: FieldValueGenerator now keeps a
     * generated INTEGER/ENUMERATED inside its declared list. AiValueSource
     * goes through this class instead, and matchesPrimitiveType used to only
     * check "is this numeric" - an AI value like the original
     * epf1-Role-of-Node = 85038 (declared range 0..7) would have sailed
     * through here just as easily as it used to through the generator.
     */
    private static final Pattern NAMED_NUMBER_ENTRY = Pattern.compile(
            "[A-Za-z][\\w-]*\\s*\\(\\s*(-?\\d+)\\s*\\)");

    private final TbcdCodec tbcdCodec;
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
            case INTEGER, ENUMERATED -> value.matches(NUMERIC_LITERAL_PATTERN)
                    && isDeclaredNumber(field.getFieldType(), value);
            case BOOLEAN -> TRUE_LITERAL.equals(value) || FALSE_LITERAL.equals(value);
            case OCTET_STRING, BIT_STRING -> matchesOctetString(field, value);
            // A NULL encodes as zero-length whatever stands here (X.690 8.8), so
            // there is no value shape to reject - and rejecting would only push
            // the chain into a pointless regeneration loop.
            // The encoder parses these; a malformed value must be rejected here so
            // the chain falls through to random generation instead of throwing.
            case OBJECT_IDENTIFIER -> value.matches(DOTTED_OID_PATTERN);
            case REAL -> value.matches(DECIMAL_LITERAL_PATTERN);
            case NULL -> true;
            case STRING -> true;
        };
    }

    /** True when the type declares no named-number list at all, or declares exactly this value. */
    private boolean isDeclaredNumber(String fieldType, String value) {
        if (Objects.isNull(fieldType) || fieldType.indexOf('{') < 0) {
            return true;
        }
        Matcher matcher = NAMED_NUMBER_ENTRY.matcher(fieldType);
        boolean any = false;
        while (matcher.find()) {
            any = true;
            if (matcher.group(1).equals(value.trim())) {
                return true;
            }
        }
        // No named-number pairs actually matched despite the '{' - not a named-number
        // type after all (e.g. a SIZE/CODE annotation), so nothing to restrict.
        return !any;
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
        if (tbcdCodec.isLikelyTbcd(field.getFieldName(), byteLength)) {
            // AI TBCD hex dogrudan uretebilir (nadiren) ya da ASCII-hex/duz
            // metin uretip biz onu TBCD'ye cevirebiliriz; matchesRule zaten
            // bu ikinci yolu deniyor, burada sadece bicimsel gecerliligi kontrol ediyoruz.
            return tbcdCodec.decode(value).isPresent();
        }
        // Ozel bir BCD/TBCD alani degilse deger yine de bir OCTET STRING'dir:
        // encoder'in kabul ettigi TEK bicim cift uzunlukta hex dokumudur.
        return HEX_DUMP_PATTERN.matcher(value).matches();
    }

    private boolean exceedsMaxLength(AsnField field, String value) {
        BerPrimitiveType type = BerPrimitiveType.fromTypeExpression(field.getFieldType());

        // A (min..max) value-range constraint (Milliseconds ::= INTEGER (0..999))
        // carries no SIZE() clause at all, so the byte-width check below never
        // even ran for it: sizeConstraint came back empty and exceedsMaxLength
        // returned false unconditionally. A generated record carried
        // serviceRequestTimeStampFraction = 260711056963 - a timestamp-shaped
        // number, not a 0..999 fraction - straight into the encoded BER because
        // nothing here rejected it. This must be checked BEFORE falling back to
        // the byte-width check, and independently of whether a SIZE() is present.
        if (type == BerPrimitiveType.INTEGER || type == BerPrimitiveType.ENUMERATED) {
            Optional<AsnSizeExtractor.IntegerRange> declaredRange =
                    asnSizeExtractor.extractIntegerRange(field.getFieldType());
            if (declaredRange.isPresent()) {
                return exceedsDeclaredRange(value, declaredRange.get());
            }
        }

        Optional<Integer> sizeConstraint = asnSizeExtractor.extractMaxLength(field.getFieldType());
        if (sizeConstraint.isEmpty()) {
            return false;
        }

        if (type == BerPrimitiveType.INTEGER || type == BerPrimitiveType.ENUMERATED) {
            return exceedsIntegerRange(value, sizeConstraint.get());
        }

        int effectiveMax = effectiveMaxLength(field, sizeConstraint.get());
        if (value.length() > effectiveMax) {
            return true;
        }
        return violatesFixedLength(field, value, type, effectiveMax);
    }

    /**
     * X.680 49.4: a single-value {@code SIZE(n)} - unlike a range
     * {@code SIZE(min..max)} - fixes the length EXACTLY. Nothing checked for
     * "shorter than n" before this: servedIMEISV ({@code IMEI ::= OCTET
     * STRING (SIZE(8))}) repeatedly got a 4-byte AI value and otherParty
     * ({@code OCTET STRING (SIZE(12))}) a 6-byte one - both exactly half the
     * required length, because the prompt's length hint stated the byte
     * count as if it were the hex-character count (fixed separately in
     * CdrPromptBuilder). This is the safety net for whenever that still
     * slips - a range constraint is deliberately left alone, since SIZE(2..12)
     * genuinely allows anything from 2 to 12 bytes.
     */
    private boolean violatesFixedLength(AsnField field, String value, BerPrimitiveType type, int effectiveMax) {
        if (type != BerPrimitiveType.OCTET_STRING && type != BerPrimitiveType.BIT_STRING) {
            return false;
        }
        return asnSizeExtractor.extractFixedLength(field.getFieldType()).isPresent()
                && value.length() != effectiveMax;
    }

    /** True when the value falls outside a declared INTEGER (min..max) constraint. */
    private boolean exceedsDeclaredRange(String value, AsnSizeExtractor.IntegerRange range) {
        BigInteger parsed;
        try {
            parsed = new BigInteger(value.trim());
        } catch (NumberFormatException ex) {
            // Not an integer literal at all - matchesPrimitiveType already rejects that.
            return false;
        }
        return parsed.compareTo(range.max()) > 0 || parsed.compareTo(range.min()) < 0;
    }

    /**
     * True when the value cannot be encoded within SIZE(n) bytes.
     *
     * <p>Counting digits is not equivalent: 9053435 has 7 digits, the most a
     * 3-byte signed integer can show, yet it is above 8388607 and BER has to
     * spend a fourth byte on it. The range is what the constraint actually
     * limits, so that is what gets checked.</p>
     */
    private boolean exceedsIntegerRange(String value, int byteWidth) {
        BigInteger parsed;
        try {
            parsed = new BigInteger(value.trim());
        } catch (NumberFormatException ex) {
            // Not an integer literal at all - matchesPrimitiveType already rejects that.
            return false;
        }
        BigInteger max = BigInteger.ONE.shiftLeft(byteWidth * BITS_PER_BYTE - 1).subtract(BigInteger.ONE);
        BigInteger min = max.negate().subtract(BigInteger.ONE);
        return parsed.compareTo(max) > 0 || parsed.compareTo(min) < 0;
    }

    /**
     * OCTET STRING'de SIZE bayt cinsindendir; hex metin uzunlugu 2 katidir.
     * INTEGER/ENUMERATED bu yoldan gecmez: onlar icin basamak sayisi degil,
     * {@link #exceedsIntegerRange} ile gercek deger araligi kontrol edilir.
     */
    private int effectiveMaxLength(AsnField field, int sizeConstraint) {
        BerPrimitiveType type = BerPrimitiveType.fromTypeExpression(field.getFieldType());
        return switch (type) {
            case OCTET_STRING, BIT_STRING -> sizeConstraint * HEX_CHARS_PER_BYTE;
            default -> sizeConstraint;
        };
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

        if (BerPrimitiveType.fromTypeExpression(field.getFieldType()) == BerPrimitiveType.OCTET_STRING) {
            Optional<String> decoded = decodeAsciiHex(value);
            if (decoded.isPresent() && compiled.matcher(decoded.get()).matches()) {
                return true;
            }
            Optional<String> tbcdDecoded = tbcdCodec.decode(value);
            if (tbcdDecoded.isPresent() && compiled.matcher(tbcdDecoded.get()).matches()) {
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