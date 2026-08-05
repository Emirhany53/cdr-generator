package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.BerPrimitiveType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Son care ureticisi. Yapay zeka kapali, erisilemez veya gecersiz deger
 * urettiginde devreye girer. Tip siniflandirmasi icin BerPrimitiveType
 * kullanilir, boylece BER kodlamasiyla ayni tip anlayisi paylasilir.
 */
@Component
@RequiredArgsConstructor
public class FieldValueGenerator {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String HEX_ALPHABET = "0123456789ABCDEF";
    private static final String DIGITS = "0123456789";
    private static final String TRUE_VALUE = "1";
    private static final String FALSE_VALUE = "0";
    /**
     * Placeholder for an ASN.1 NULL field: present but empty. Must not be
     * {@code null}, or BerEncoderService would treat the field as an unset
     * OPTIONAL and drop it instead of emitting the zero-length marker.
     */
    private static final String NULL_MARKER_VALUE = "";
    /**
     * Captures the {@code name(number)} pairs the resolver compacts a named-number
     * body into - {@code ENUMERATED{sDP-offer(0),sDP-answer(1)}} or
     * {@code INTEGER{native(0),barred(1),...}}.
     *
     * <p>Such a type admits ONLY the listed numbers. The generator used to fill
     * every INTEGER/ENUMERATED with {@code nextInt(100_000)} and ignore the list
     * entirely, so a generated MMTel record carried
     * {@code epf1-Role-of-Node = 85038} where {@code Epf1RoleType ::= INTEGER
     * {native(0) ... cmfbMember(7)}} allows 0..7 - 27 of the 37 named-number
     * fields in one record were out of range. Across 2000 records of an
     * EMM-accepted reference capture all 26912 such fields hold a declared
     * value, without a single exception. 2545 types across 130 of the 808
     * schema modules declare a named-number list.</p>
     */
    private static final Pattern NAMED_NUMBER_ENTRY = Pattern.compile(
            "[A-Za-z][\\w-]*\\s*\\(\\s*(-?\\d+)\\s*\\)");
    private static final String JITTER_SUFFIX_PATTERN = ".*\\d{4}$";
    private static final String NUMERIC_LITERAL_PATTERN = "^-?\\d+$";
    private static final String HEX_LITERAL_PATTERN = "^([0-9A-Fa-f]{2})+$";
    private static final String DOTTED_OID_PATTERN = "^\\d+(\\.\\d+)+$";
    private static final String DECIMAL_LITERAL_PATTERN = "^-?\\d+(\\.\\d+)?$";
    /** Enterprise arc: any subtree under it is a structurally valid OID. */
    private static final String ENTERPRISE_OID_PREFIX = "1.3.6.1.4.1.";
    private static final double REAL_SCALE = 100.0d;
    private static final int JITTER_LENGTH = 4;
    private static final int DEFAULT_STRING_LENGTH = 8;
    private static final int DEFAULT_INTEGER_BOUND = 100_000;
    private static final int MAX_GENERATED_LENGTH = 18;
    /** OCTET STRING hex dump uretiminde her bayt 2 hex karakter olmali. */
    private static final int HEX_CHARS_PER_BYTE = 2;
    private static final int BITS_PER_BYTE = 8;

    private final TbcdCodec tbcdCodec;
    private final AiConfigProperties aiConfigProperties;
    private final AsnSizeExtractor asnSizeExtractor;
    private final BcdTimestampFactory bcdTimestampFactory;

    public String generate(AsnField field) {
        return trimToMaxLength(field, produce(field));
    }

    private String produce(AsnField field) {
        // BCD zaman damgasi/tarih kontrolu yml kuralindan ONCE yapilir: date/timestamp
        // kurallari bu alanlara ad benzerligiyle carpip duz sayi ornegi verebilir,
        // ancak OCTET STRING SIZE(9)/SIZE(3) alanlar BCD hex olmak zorundadir.
        Optional<String> bcdValue = produceBcdIfApplicable(field);
        if (bcdValue.isPresent()) {
            return bcdValue.get();
        }

        // TBCD (MSISDN/IMSI/IMEI) kontrolu de kural motorundan once gelir; aksi
        // halde generateFromRuleExample duz rakam dizisini (ornek: "905321112233")
        // TBCD'ye paketlemeden dogrudan dondurur, boylece SIZE'a sigmayan uzun
        // bir deger uretilmis olur.
        Optional<String> tbcdValue = produceTbcdIfApplicable(field);
        if (tbcdValue.isPresent()) {
            return tbcdValue.get();
        }

        Optional<String> seeded = generateFromRuleExample(field);
        if (seeded.isPresent()) {
            return seeded.get();
        }
        return switch (BerPrimitiveType.fromTypeExpression(field.getFieldType())) {
            // A named-number list (checked first) and a (min..max) range are
            // mutually exclusive in practice, so trying the list, then the
            // range, then the unconstrained default covers every INTEGER shape.
            // Without the range step, Milliseconds ::= INTEGER (0..999) fell
            // straight to nextInt(100_000) - wrong in the overwhelming majority
            // of draws - whenever AiValueSource's rejection sent a Fraction
            // field down this fallback path.
            case INTEGER, ENUMERATED -> pickDeclaredNumber(field.getFieldType())
                    .or(() -> randomWithinDeclaredRange(field.getFieldType()))
                    .orElseGet(() -> String.valueOf(
                            ThreadLocalRandom.current().nextInt(DEFAULT_INTEGER_BOUND)));
            case BOOLEAN -> ThreadLocalRandom.current().nextBoolean() ? TRUE_VALUE : FALSE_VALUE;
            // A BIT STRING value is a hex dump too; the encoder adds the
            // leading unused-bit octet, so the generated text stays the same shape.
            case OCTET_STRING, BIT_STRING -> randomHex(hexLengthFor(field));
            // A NULL carries no value at all - its presence IS the information
            // (X.690 8.8: no contents octets). An empty string is the honest
            // representation: NOT null, because a null value would make the
            // encoder omit the field entirely instead of emitting the marker.
            // BerEncoderService discards whatever stands here regardless.
            // A dotted OID is what the encoder parses into X.690 8.19 arcs. The
            // enterprise arc 1.3.6.1.4.1.x is always structurally valid.
            case OBJECT_IDENTIFIER -> ENTERPRISE_OID_PREFIX
                    + ThreadLocalRandom.current().nextInt(DEFAULT_INTEGER_BOUND);
            case REAL -> String.valueOf(
                    ThreadLocalRandom.current().nextInt(DEFAULT_INTEGER_BOUND) / REAL_SCALE);
            case NULL -> NULL_MARKER_VALUE;
            case STRING -> randomFrom(ALPHABET, lengthFor(field));
        };
    }

    /**
     * Alan bir 3GPP BCD zaman damgasi (9 bayt, saat dahil) ya da BCD tarih
     * (3 bayt, sadece yil-ay-gun) ise gecerli deger uretir; aksi halde bos doner.
     */
    private Optional<String> produceBcdIfApplicable(AsnField field) {
        if (BerPrimitiveType.fromTypeExpression(field.getFieldType()) != BerPrimitiveType.OCTET_STRING) {
            return Optional.empty();
        }
        Integer byteLength = asnSizeExtractor.extractMaxLength(field.getFieldType()).orElse(null);
        if (bcdTimestampFactory.isBcdTimestamp(field.getFieldName(), byteLength)) {
            return Optional.of(bcdTimestampFactory.randomTimestamp());
        }
        if (bcdTimestampFactory.isBcdDate(field.getFieldName(), byteLength)) {
            return Optional.of(bcdTimestampFactory.randomDate());
        }
        return Optional.empty();
    }

    /**
     * yml'deki kural orneklerinden birini tohum olarak kullanir.
     *
     * Kural eslesmesi alan adinin bir parcasina bakarak yapildigi icin
     * beklenmedik alanlara carpabilir (ornek: 'daRealMoneyFlag' icindeki
     * 'realm'). Bu yuzden ornekler alanin ASN.1 tipiyle uyumlu degilse
     * kural yok sayilir ve tipe uygun rastgele uretime dusulur.
     */
    private Optional<String> generateFromRuleExample(AsnField field) {
        return aiConfigProperties.findRuleFor(field.getFieldName())
                .map(AiConfigProperties.FieldRule::getExamples)
                .filter(examples -> !examples.isEmpty())
                .filter(examples -> isCompatibleWithFieldType(field, examples))
                .map(this::pickAndJitter);
    }

    private boolean isCompatibleWithFieldType(AsnField field, List<String> examples) {
        return switch (BerPrimitiveType.fromTypeExpression(field.getFieldType())) {
            // A yml rule matches on a NAME SUBSTRING, so it can easily land on a
            // named-number field it knows nothing about ("direction" admits only
            // {0,1}, but a rule seeded with 15/127/842 would happily fill it).
            // Its examples are therefore only usable when every one of them is a
            // value the type actually declares.
            case INTEGER, ENUMERATED -> examples.stream().allMatch(this::isNumericLiteral)
                    && examples.stream().allMatch(example -> isDeclaredNumber(field.getFieldType(), example));
            case BOOLEAN -> examples.stream().allMatch(this::isBooleanLiteral);
            case OCTET_STRING, BIT_STRING -> examples.stream().allMatch(this::isHexLiteral);
            // A NULL field holds no value, so no example can ever be compatible
            // with it. Returning false keeps a loosely-matched yml rule (matching
            // is by name substring) from seeding content into a field that must
            // encode as zero-length.
            case OBJECT_IDENTIFIER -> examples.stream().allMatch(this::isDottedOid);
            case REAL -> examples.stream().allMatch(this::isDecimalLiteral);
            case NULL -> false;
            case STRING -> true;
        };
    }

    private Optional<String> produceTbcdIfApplicable(AsnField field) {
        if (BerPrimitiveType.fromTypeExpression(field.getFieldType()) != BerPrimitiveType.OCTET_STRING) {
            return Optional.empty();
        }
        Integer byteLength = asnSizeExtractor.extractMaxLength(field.getFieldType()).orElse(null);
        if (!tbcdCodec.isLikelyTbcd(field.getFieldName(), byteLength)) {
            return Optional.empty();
        }
        // Kural motorundan gecerli bir rakam dizisi al (ornek: msisdn kurali
        // "905321112233" verir), sonra TBCD'ye paketle.
        return generateFromRuleExample(field).flatMap(tbcdCodec::encode);
    }

    /**
     * The numbers a named-number type declares, in declaration order; empty when
     * the type expression carries no such list.
     */
    private List<String> declaredNumbers(String fieldType) {
        if (Objects.isNull(fieldType) || fieldType.indexOf('{') < 0) {
            return List.of();
        }
        Matcher matcher = NAMED_NUMBER_ENTRY.matcher(fieldType);
        List<String> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(matcher.group(1));
        }
        return numbers;
    }

    /** Picks one of the type's declared numbers, or empty for an unconstrained INTEGER. */
    private Optional<String> pickDeclaredNumber(String fieldType) {
        List<String> numbers = declaredNumbers(fieldType);
        return numbers.isEmpty()
                ? Optional.empty()
                : Optional.of(numbers.get(ThreadLocalRandom.current().nextInt(numbers.size())));
    }

    /**
     * Picks a value inside the type's declared {@code (min..max)} range, or
     * empty when the type expression carries no such range.
     */
    private Optional<String> randomWithinDeclaredRange(String fieldType) {
        return asnSizeExtractor.extractIntegerRange(fieldType).flatMap(range -> {
            try {
                long min = range.min().longValueExact();
                long max = range.max().longValueExact();
                if (min >= max) {
                    return Optional.of(String.valueOf(min));
                }
                return Optional.of(String.valueOf(ThreadLocalRandom.current().nextLong(min, max + 1)));
            } catch (ArithmeticException ex) {
                // Range too wide to fit a long - not seen in any current schema
                // module; fall back to the unconstrained default rather than fail.
                return Optional.empty();
            }
        });
    }

    /** True when the type declares no list at all, or declares exactly this value. */
    private boolean isDeclaredNumber(String fieldType, String value) {
        List<String> numbers = declaredNumbers(fieldType);
        return numbers.isEmpty() || numbers.contains(value.trim());
    }

    private boolean isNumericLiteral(String value) {
        return value.matches(NUMERIC_LITERAL_PATTERN);
    }

    private boolean isBooleanLiteral(String value) {
        return TRUE_VALUE.equals(value) || FALSE_VALUE.equals(value);
    }

    private boolean isHexLiteral(String value) {
        return value.matches(HEX_LITERAL_PATTERN);
    }

    private boolean isDottedOid(String value) {
        return value.matches(DOTTED_OID_PATTERN);
    }

    private boolean isDecimalLiteral(String value) {
        return value.matches(DECIMAL_LITERAL_PATTERN);
    }

    private String pickAndJitter(List<String> examples) {
        final String example = examples.get(ThreadLocalRandom.current().nextInt(examples.size()));
        if (!example.matches(JITTER_SUFFIX_PATTERN)) {
            return example;
        }
        return example.substring(0, example.length() - JITTER_LENGTH)
                + randomFrom(DIGITS, JITTER_LENGTH);
    }

    private int lengthFor(AsnField field) {
        return asnSizeExtractor.extractMaxLength(field.getFieldType())
                .map(maxLength -> Math.min(maxLength, MAX_GENERATED_LENGTH))
                .orElse(DEFAULT_STRING_LENGTH);
    }

    /** OCTET STRING icin SIZE(n) bayt sayisini ifade eder; hex metin 2 katidir. */
    private int hexLengthFor(AsnField field) {
        return lengthFor(field) * HEX_CHARS_PER_BYTE;
    }

    private String randomFrom(String alphabet, int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    private String randomHex(int hexCharLength) {
        return randomFrom(HEX_ALPHABET, hexCharLength);
    }

    private String trimToMaxLength(AsnField field, String value) {
        BerPrimitiveType type = BerPrimitiveType.fromTypeExpression(field.getFieldType());

        // Final safety net for a declared (min..max) range, independent of any
        // SIZE() clause: a yml rule can seed an example that is numerically
        // compatible (isDeclaredNumber only checks a {name(n)} list, not a
        // range) yet still outside 0..999 for a Milliseconds-shaped field.
        if (type == BerPrimitiveType.INTEGER || type == BerPrimitiveType.ENUMERATED) {
            Optional<AsnSizeExtractor.IntegerRange> declaredRange =
                    asnSizeExtractor.extractIntegerRange(field.getFieldType());
            if (declaredRange.isPresent()) {
                return fitIntegerToDeclaredRange(value, declaredRange.get());
            }
        }

        Optional<Integer> sizeConstraint = asnSizeExtractor.extractMaxLength(field.getFieldType());
        if (sizeConstraint.isEmpty()) {
            return value;
        }

        if (type == BerPrimitiveType.INTEGER || type == BerPrimitiveType.ENUMERATED) {
            return fitIntegerToByteWidth(value, sizeConstraint.get());
        }
        // Cutting a dotted OID or a decimal REAL mid-way leaves a value the
        // encoder can only reject ("1.3.6." / "12."), so length never applies.
        if (type == BerPrimitiveType.OBJECT_IDENTIFIER || type == BerPrimitiveType.REAL) {
            return value;
        }

        int effectiveMax = effectiveMaxLength(field, sizeConstraint.get());
        return value.length() > effectiveMax ? value.substring(0, effectiveMax) : value;
    }

    /**
     * Brings an INTEGER inside the range a SIZE(n) field can actually hold.
     *
     * <p>Cutting the digit string is not enough. SIZE(3) allows at most 7
     * digits, so a value like 9053435 passes a digit-count check - yet it is
     * above 8388607, the largest signed 3-byte integer, so BER has to spend a
     * fourth byte on it and the SIZE(3) constraint is broken. This was visible
     * in a generated CBSiso record: callingPartySType (INTEGER SIZE(3)) came out
     * as {@code 02 04 00 8A 24 FB}.</p>
     *
     * <p>Out-of-range values are folded back into 0..max rather than clamped to
     * max, so generated records keep some variety instead of every overflowing
     * field showing the same boundary number.</p>
     */
    private String fitIntegerToByteWidth(String value, int byteWidth) {
        BigInteger parsed;
        try {
            parsed = new BigInteger(value.trim());
        } catch (NumberFormatException ex) {
            // Not a plain integer literal - leave it alone and let the validator judge.
            return value;
        }

        BigInteger max = BigInteger.ONE.shiftLeft(byteWidth * BITS_PER_BYTE - 1).subtract(BigInteger.ONE);
        BigInteger min = max.negate().subtract(BigInteger.ONE);
        if (parsed.compareTo(max) <= 0 && parsed.compareTo(min) >= 0) {
            return value;
        }
        return parsed.mod(max.add(BigInteger.ONE)).toString();
    }

    /**
     * Folds an out-of-range value back into a declared {@code (min..max)}
     * INTEGER constraint, the same "wrap, don't clamp" approach
     * {@link #fitIntegerToByteWidth} uses, so generated records keep some
     * variety instead of every overflowing field showing the same boundary.
     */
    private String fitIntegerToDeclaredRange(String value, AsnSizeExtractor.IntegerRange range) {
        BigInteger parsed;
        try {
            parsed = new BigInteger(value.trim());
        } catch (NumberFormatException ex) {
            return value;
        }
        if (parsed.compareTo(range.max()) <= 0 && parsed.compareTo(range.min()) >= 0) {
            return value;
        }
        BigInteger span = range.max().subtract(range.min()).add(BigInteger.ONE);
        return parsed.subtract(range.min()).mod(span).add(range.min()).toString();
    }

    /**
     * OCTET STRING'de SIZE bayt cinsindendir; hex metin uzunlugu 2 katidir.
     * INTEGER/ENUMERATED bu yoldan gecmez: onlar {@link #trimToMaxLength}
     * icinde {@link #fitIntegerToByteWidth} ile deger araligina gore ele alinir,
     * cunku basamak sayisi bayt genisligini dogru temsil etmiyor.
     */
    private int effectiveMaxLength(AsnField field, int sizeConstraint) {
        BerPrimitiveType type = BerPrimitiveType.fromTypeExpression(field.getFieldType());
        return switch (type) {
            case OCTET_STRING, BIT_STRING -> sizeConstraint * HEX_CHARS_PER_BYTE;
            default -> sizeConstraint;
        };
    }
}