package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.BerPrimitiveType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

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
    private static final String JITTER_SUFFIX_PATTERN = ".*\\d{4}$";
    private static final String NUMERIC_LITERAL_PATTERN = "^-?\\d+$";
    private static final String HEX_LITERAL_PATTERN = "^([0-9A-Fa-f]{2})+$";
    private static final int JITTER_LENGTH = 4;
    private static final int DEFAULT_STRING_LENGTH = 8;
    private static final int DEFAULT_INTEGER_BOUND = 100_000;
    private static final int MAX_GENERATED_LENGTH = 18;
    /** OCTET STRING hex dump uretiminde her bayt 2 hex karakter olmali. */
    private static final int HEX_CHARS_PER_BYTE = 2;

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

        Optional<String> seeded = generateFromRuleExample(field);
        if (seeded.isPresent()) {
            return seeded.get();
        }
        return switch (BerPrimitiveType.fromTypeExpression(field.getFieldType())) {
            case INTEGER, ENUMERATED ->
                    String.valueOf(ThreadLocalRandom.current().nextInt(DEFAULT_INTEGER_BOUND));
            case BOOLEAN -> ThreadLocalRandom.current().nextBoolean() ? TRUE_VALUE : FALSE_VALUE;
            case OCTET_STRING -> randomHex(hexLengthFor(field));
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
            case INTEGER, ENUMERATED -> examples.stream().allMatch(this::isNumericLiteral);
            case BOOLEAN -> examples.stream().allMatch(this::isBooleanLiteral);
            case OCTET_STRING -> examples.stream().allMatch(this::isHexLiteral);
            case STRING -> true;
        };
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
        int effectiveMax = asnSizeExtractor.extractMaxLength(field.getFieldType())
                .map(maxLength -> effectiveMaxLength(field, maxLength))
                .orElse(Integer.MAX_VALUE);
        return value.length() > effectiveMax ? value.substring(0, effectiveMax) : value;
    }

    /** OCTET STRING'de SIZE bayt cinsindendir; hex metin uzunlugu 2 katidir. */
    private int effectiveMaxLength(AsnField field, int sizeConstraint) {
        boolean isOctetString =
                BerPrimitiveType.fromTypeExpression(field.getFieldType()) == BerPrimitiveType.OCTET_STRING;
        return isOctetString ? sizeConstraint * HEX_CHARS_PER_BYTE : sizeConstraint;
    }
}