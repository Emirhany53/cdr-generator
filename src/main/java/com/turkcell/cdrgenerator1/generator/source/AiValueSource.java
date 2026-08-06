package com.turkcell.cdrgenerator1.generator.source;

import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.generator.EnumValueResolver;
import com.turkcell.cdrgenerator1.generator.validation.FieldValueValidator;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.BerPrimitiveType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Yapay zekanin urettigi degeri, dogrulamadan gecmesi kosuluyla kullanir.
 * Deger once tam alan yoluyla (ornek: "addr.msisdn"), bulunamazsa indekssiz
 * yoluyla (ornek: bir "list-Of-X[0].sIP-URI" icin "list-Of-X.sIP-URI"),
 * o da bulunamazsa ciplak alan adiyla aranir; boylece ic ice yapilarda ayni
 * isimli alanlar birbirine karismaz.
 *
 * ENUMERATED ve isimli-sabitli INTEGER alanlarda AI genelde ismi doner
 * (ornek: "originating"); dogrulamadan once bu isim ASN.1 tanimindaki sayiya
 * cevrilir, boylece BER kodlayici tam sayi bekleyen alanlara metin gormez.
 */
@Component
@RequiredArgsConstructor
public class AiValueSource implements ValueSource {

    private static final int ORDER = 20;
    /** CdrRecordBuilder'in tekrarli alanlar icin path'e ekledigi "[0]", "[1]" gibi indeksler. */
    private static final Pattern INDEX_SEGMENT = Pattern.compile("\\[\\d+\\]");
    /** Bir tekrarli elemani cesitlendirmek icin degerin en az bu kadar uzun olmasi gerekir. */
    private static final int MIN_DIVERSIFY_LENGTH = 4;
    private static final int DIGIT_CLASS = 10;
    private static final int LETTER_CLASS = 26;

    private final FieldValueValidator fieldValueValidator;
    private final EnumValueResolver enumValueResolver;
    private final AiConfigProperties aiConfigProperties;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Optional<String> resolve(ValueSourceContext context, AsnField field) {
        List<Map<String, String>> records = context.getAiGeneratedRecords();

        if (Objects.isNull(records) || context.getRecordIndex() >= records.size()) {
            return Optional.empty();
        }
        Map<String, String> aiRecord = records.get(context.getRecordIndex());

        return findValue(aiRecord, context.getCurrentPath(), field.getFieldName())
                .flatMap(candidate -> normalize(field, candidate))
                .filter(candidate -> fieldValueValidator.isValid(field, candidate))
                .map(valid -> diversifyForRepeatedElement(field, valid, context.getCurrentPath()));
    }

    /**
     * Ayni tekrarli alanin butun elemanlari AI'dan gelen ayni indekssiz degeri
     * paylasir (bkz. {@link #findValue}). Ilk eleman disindaki elemanlarda,
     * deger yeterince uzunsa son ALFANUMERIK karakteri eleman indeksine gore
     * kendi sinifinda kaydiririz (rakam->rakam, harf->harf): bir telefon
     * numarasi telefon, bir sIP/tel-URI de gecerli bir URI olarak kalir ama
     * elemanlar farklilasir. Kisa degerler (enum/bayrak) esikle, kalanlar da
     * yeniden dogrulama ile korunur - kaydirilan deger tipin kisitini bozarsa
     * (ornek: isimli-sayi kumesi disina cikarsa) orijinali kullanilir.
     */
    private String diversifyForRepeatedElement(AsnField field, String value, String currentPath) {
        int salt = repeatedElementSalt(currentPath);
        if (salt == 0 || Objects.isNull(value) || value.length() < MIN_DIVERSIFY_LENGTH) {
            return value;
        }
        char[] chars = value.toCharArray();
        for (int i = chars.length - 1; i >= 0; i--) {
            char shifted = shiftWithinClass(chars[i], salt);
            if (shifted != chars[i]) {
                chars[i] = shifted;
                String varied = new String(chars);
                return fieldValueValidator.isValid(field, varied) ? varied : value;
            }
        }
        return value;
    }

    /** Yoldaki tum {@code [n]} indekslerinin toplami; 0 ise her yerde ilk eleman. */
    private int repeatedElementSalt(String currentPath) {
        if (Objects.isNull(currentPath)) {
            return 0;
        }
        Matcher matcher = INDEX_SEGMENT.matcher(currentPath);
        int sum = 0;
        while (matcher.find()) {
            String token = matcher.group();
            sum += Integer.parseInt(token.substring(1, token.length() - 1));
        }
        return sum;
    }

    /** Karakteri kendi sinifinda (rakam/kucuk/buyuk harf) kaydirir; digerleri degismez. */
    private char shiftWithinClass(char c, int salt) {
        if (c >= '0' && c <= '9') {
            return (char) ('0' + (c - '0' + salt) % DIGIT_CLASS);
        }
        if (c >= 'a' && c <= 'z') {
            return (char) ('a' + (c - 'a' + salt) % LETTER_CLASS);
        }
        if (c >= 'A' && c <= 'Z') {
            return (char) ('A' + (c - 'A' + salt) % LETTER_CLASS);
        }
        return c;
    }

    /**
     * AI'in dondurdugu degeri BER'in bekledigi bicime cevirir: ENUMERATED
     * icin isim->sayi, BOOLEAN icin true/false->1/0. Diger tipler degismeden geçer.
     */
    private Optional<String> normalize(AsnField field, String candidate) {
        BerPrimitiveType type = BerPrimitiveType.fromTypeExpression(field.getFieldType());
        if (type == BerPrimitiveType.BOOLEAN) {
            return enumValueResolver.resolveBoolean(candidate);
        }
        if (type == BerPrimitiveType.OCTET_STRING) {
            Optional<AiConfigProperties.FieldRule> rule = aiConfigProperties.findRuleFor(field.getFieldName());
            if (rule.isPresent() && rule.get().isTextContent()) {
                return Optional.of(encodeAsciiHex(candidate));
            }
            // Bir OCTET STRING hicbir zaman isimli-sayi tasimaz, bu yuzden
            // resolveToNumber burada yalnizca ZARAR verirdi: sayisal olmayan her
            // degere Optional.empty() dondugu icin, AI'in urettigi gecerli ama
            // icinde a-f gecen bir hex dokumu ("12345a") sessizce dusuyor ve alan
            // rastgeleye kaliyordu. Deger oldugu gibi birakilir; bicimsel karari
            // zaten FieldValueValidator veriyor.
            return Optional.of(candidate);
        }
        return enumValueResolver.resolveToNumber(field.getFieldType(), candidate);
    }

    private String encodeAsciiHex(String text) {
        StringBuilder hex = new StringBuilder(text.length() * 2);
        for (char c : text.toCharArray()) {
            hex.append(String.format("%02x", (int) c));
        }
        return hex.toString();
    }

    /**
     * Once tam yolla, sonra indekssiz halinin, sonra ciplak alan adiyla arar.
     *
     * <p>AiRecordSupplier'in AI'a gonderdigi ve Gemini'nin geri dondurdugu
     * yollar ASLA indeks tasimaz ("list-Of-Calling-Party-Address.sIP-URI"),
     * ama CdrRecordBuilder.buildRepeatedGroup her tekrarli alan icin path'e
     * "[0]", "[1]" ekler ("list-Of-Calling-Party-Address[0].sIP-URI"). Bu
     * ikisi hicbir zaman birebir eslesmiyordu: aramanin tam-yol adimi hep
     * basarisiz oluyor, ciplak-ad yedegi de ic ice alanlarda ise yaramiyordu
     * (AI'daki anahtar ciplak ad degil, TAM yol). Sonuc: MMTelRecord'daki
     * SEQUENCE OF gorunumune sahip her yapinin (listOfSSDetails,
     * list-Of-Called-Asserted-Identity, enhancedPhoneFeatures1/3,
     * cTPullInformation, pChargingVectorExtensions, tpasInformation,
     * list-of-subscription-ID, list-Of-Early-SDP-Media-Components, vb.)
     * ALTINDAKI HER YAPRAK ALAN, AI gecerli bir deger dondurmus olsa bile
     * RandomValueSource'a dusuyordu. Bir tanı logu ile dogrulandi: Gemini'ye
     * istenen 80 yolun TAMAMI birebir geri donuyor (diff ile karsilastirildi,
     * fark yok) - sorun hep bu taraftaydi.
     *
     * <p>AI'ya tek bir indekssiz deger sorulduğu icin bu arama ayni tekrarli
     * alanin TUM elemanlarina ayni degeri dondururur (ornek: listOfSSDetails[0]
     * VE [1]). Elemanlar arasi cesitlilik {@link #diversifyForRepeatedElement}
     * ile geri kazanilir: ilk eleman disindakiler kendi degerlerinin son
     * alfanumerik karakteri indeksle kaydirilarak farklilastirilir.</p>
     */
    private Optional<String> findValue(Map<String, String> aiRecord, String currentPath, String fieldName) {
        if (Objects.nonNull(currentPath)) {
            String pathValue = aiRecord.get(currentPath);
            if (Objects.nonNull(pathValue)) {
                return Optional.of(pathValue);
            }
            String indexless = INDEX_SEGMENT.matcher(currentPath).replaceAll("");
            if (!indexless.equals(currentPath)) {
                String indexlessValue = aiRecord.get(indexless);
                if (Objects.nonNull(indexlessValue)) {
                    return Optional.of(indexlessValue);
                }
            }
        }
        return Optional.ofNullable(aiRecord.get(fieldName));
    }
}