package com.turkcell.cdrgenerator1.generator.source;

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

    private final FieldValueValidator fieldValueValidator;
    private final EnumValueResolver enumValueResolver;

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
                .filter(candidate -> fieldValueValidator.isValid(field, candidate));
    }

    /**
     * AI'in dondurdugu degeri BER'in bekledigi bicime cevirir: ENUMERATED
     * icin isim->sayi, BOOLEAN icin true/false->1/0. Diger tipler degismeden geçer.
     */
    private Optional<String> normalize(AsnField field, String candidate) {
        if (BerPrimitiveType.fromTypeExpression(field.getFieldType()) == BerPrimitiveType.BOOLEAN) {
            return enumValueResolver.resolveBoolean(candidate);
        }
        return enumValueResolver.resolveToNumber(field.getFieldType(), candidate);
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
     * <p>AI'ya tek bir indekssiz deger sorulduğu icin, ayni tekrarli alanin
     * TUM elemanlari (ornek: listOfSSDetails[0] VE [1]) bu ayni degeri
     * paylasir - farkli rastgele degerlere dusmekten iyidir, ama elemanlar
     * arasi cesitlilik saglamaz; bu bilinen bir sinirlamadir.</p>
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