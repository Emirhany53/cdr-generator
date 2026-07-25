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

/**
 * Yapay zekanin urettigi degeri, dogrulamadan gecmesi kosuluyla kullanir.
 * Deger once tam alan yoluyla (ornek: "addr.msisdn"), bulunamazsa ciplak
 * alan adiyla aranir; boylece ic ice yapilarda ayni isimli alanlar birbirine
 * karismaz.
 *
 * ENUMERATED ve isimli-sabitli INTEGER alanlarda AI genelde ismi doner
 * (ornek: "originating"); dogrulamadan once bu isim ASN.1 tanimindaki sayiya
 * cevrilir, boylece BER kodlayici tam sayi bekleyen alanlara metin gormez.
 */
@Component
@RequiredArgsConstructor
public class AiValueSource implements ValueSource {

    private static final int ORDER = 20;

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

    private Optional<String> findValue(Map<String, String> aiRecord, String currentPath, String fieldName) {
        if (Objects.nonNull(currentPath)) {
            String pathValue = aiRecord.get(currentPath);
            if (Objects.nonNull(pathValue)) {
                return Optional.of(pathValue);
            }
        }
        return Optional.ofNullable(aiRecord.get(fieldName));
    }
}