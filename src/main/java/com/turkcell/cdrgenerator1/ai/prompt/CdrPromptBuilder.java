package com.turkcell.cdrgenerator1.ai.prompt;

import com.turkcell.cdrgenerator1.ai.model.AiGenerationRequest;
import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.generator.BcdTimestampFactory;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.BerPrimitiveType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CdrPromptBuilder implements PromptBuilder {

    private static final String LINE = System.lineSeparator();
    private static final String RULE_SEPARATOR = " | ";

    private final AiConfigProperties aiConfigProperties;
    private final AsnSizeExtractor asnSizeExtractor;
    private final BcdTimestampFactory bcdTimestampFactory;

    @Override
    public String build(AiGenerationRequest request) {
        StringBuilder prompt = new StringBuilder();
        appendRole(prompt);
        appendContext(prompt, request);
        appendFixedValues(prompt, request);
        appendFields(prompt, request);
        appendRules(prompt);
        return prompt.toString();
    }

    private void appendRole(StringBuilder prompt) {
        prompt.append("Sen bir telekomunikasyon test verisi ureticisisin. ")
                .append("Turkiye GSM sebekesi icin gercekci CDR (Call Detail Record) ")
                .append("alan degerleri uretiyorsun. Uretilen veriler yalnizca test ")
                .append("amaclidir, gercek abone verisi degildir.")
                .append(LINE).append(LINE);
    }

    private void appendContext(StringBuilder prompt, AiGenerationRequest request) {
        prompt.append("ASN.1 yapi adi: ").append(request.getStructureName()).append(LINE)
                .append("Uretilecek kayit sayisi: ").append(request.getRecordCount())
                .append(LINE).append(LINE);
    }

    private void appendFixedValues(StringBuilder prompt, AiGenerationRequest request) {
        Map<String, String> fixedValues = request.getFixedValues();
        if (Objects.isNull(fixedValues) || fixedValues.isEmpty()) {
            return;
        }
        prompt.append("Kullanici asagidaki alanlari SABITLEDI. Bu degerleri uretme; ")
                .append("yalnizca urettigin alanlarin bunlarla tutarli olmasini sagla:")
                .append(LINE);
        fixedValues.forEach((fieldName, value) ->
                prompt.append("- ").append(fieldName).append(" = ").append(value).append(LINE));
        prompt.append(LINE);
    }

    private void appendFields(StringBuilder prompt, AiGenerationRequest request) {
        prompt.append("Doldurulacak alanlar:").append(LINE);

        request.getFieldsToFill().forEach(field -> {
            prompt.append("- ").append(field.getFieldName())
                    .append(" (tip: ").append(field.getFieldType()).append(")");

            Integer maxLength = asnSizeExtractor.extractMaxLength(field.getFieldType()).orElse(null);
            if (Objects.nonNull(maxLength)) {
                prompt.append(RULE_SEPARATOR)
                        .append("azami uzunluk: ").append(maxLength).append(" karakter");
            }

            appendOctetStringGuidance(prompt, field, maxLength);
            appendRuleIfPresent(prompt, field);
            prompt.append(LINE);
        });
    }

    private void appendOctetStringGuidance(StringBuilder prompt, AsnField field, Integer maxLength) {
        if (!isOctetString(field.getFieldType())) {
            return;
        }
        prompt.append(RULE_SEPARATOR)
                .append("bu alan OCTET STRING'dir, deger SADECE hex karakterlerden ")
                .append("(0-9, A-F) olusmali, cift sayida karakter olmali");

        if (bcdTimestampFactory.isBcdTimestamp(field.getFieldName(), maxLength)) {
            prompt.append(RULE_SEPARATOR)
                    .append("bu alan 3GPP BCD zaman damgasidir: YYMMDDHHmmSS + isaret(2B/2D) + ")
                    .append("saat farki(4 hane), toplam 18 hex karakter. Ornek: ")
                    .append(bcdTimestampFactory.randomTimestamp());
        } else if (bcdTimestampFactory.isBcdDate(field.getFieldName(), maxLength)) {
            prompt.append(RULE_SEPARATOR)
                    .append("bu alan BCD tarihtir: YYMMDD, toplam 6 hex karakter. Ornek: ")
                    .append(bcdTimestampFactory.randomDate());
        }
    }

    // --- YENI EKLENEN METOT ---
    private boolean isOctetString(String fieldType) {
        return BerPrimitiveType.fromTypeExpression(fieldType) == BerPrimitiveType.OCTET_STRING;
    }

    private void appendRuleIfPresent(StringBuilder prompt, AsnField field) {
        Optional<AiConfigProperties.FieldRule> rule =
                aiConfigProperties.findRuleFor(field.getFieldName());
        if (rule.isEmpty()) {
            return;
        }
        AiConfigProperties.FieldRule fieldRule = rule.get();

        if (Objects.nonNull(fieldRule.getDescription())) {
            prompt.append(RULE_SEPARATOR).append("aciklama: ").append(fieldRule.getDescription());
        }
        if (Objects.nonNull(fieldRule.getPattern())) {
            prompt.append(RULE_SEPARATOR).append("regex: ").append(fieldRule.getPattern());
        }
        if (!fieldRule.getExamples().isEmpty()) {
            prompt.append(RULE_SEPARATOR).append("ornekler: ")
                    .append(String.join(", ", fieldRule.getExamples()));
        }
    }

    private void appendRules(StringBuilder prompt) {
        prompt.append(LINE).append("Uyulmasi zorunlu kurallar:").append(LINE)
                .append("1. Regex verilen alanlar MUTLAKA o regex ile eslesmelidir.").append(LINE)
                .append("2. Hicbir deger belirtilen azami uzunlugu asmamalidir.").append(LINE)
                .append("3. Kayitlar birbirinden farkli olmalidir.").append(LINE)
                .append("4. Ayni kayit icindeki alanlar tutarli olmalidir: bitis zamani ")
                .append("baslangictan sonra, sure bu farkla uyumlu, arayan ve aranan ayni degil.")
                .append(LINE)
                .append("5. Degerler duz metin olmalidir; birim, aciklama, tirnak, bosluk yok.")
                .append(LINE)
                .append("6. Yanit yalnizca JSON dizisi olmalidir; markdown veya aciklama yok.")
                .append(LINE);
    }
}