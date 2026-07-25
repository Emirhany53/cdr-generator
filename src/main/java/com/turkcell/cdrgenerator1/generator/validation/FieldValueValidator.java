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
 * Yapay zekadan gelen degerleri uc asamada denetler: ASN.1 tip uyumu,
 * SIZE kisiti, ve yml'de tanimli alan kurali. Herhangi biri basarisiz
 * olursa deger reddedilir ve zincir rastgele uretime duser.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FieldValueValidator {

    private static final String NUMERIC_LITERAL_PATTERN = "^-?\\d+$";
    private static final String TRUE_LITERAL = "1";
    private static final String FALSE_LITERAL = "0";

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
        return matchesRule(field, value);
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
     * BCD zaman damgasi alanlari 18 hex karakter olmali. AI duz tarih
     * (20260711120005) uretirse reddedilir ve zincir rastgele BCD uretimine duser.
     */
    private boolean matchesOctetString(AsnField field, String value) {
        Integer byteLength = asnSizeExtractor.extractMaxLength(field.getFieldType()).orElse(null);
        if (bcdTimestampFactory.isBcdTimestamp(field.getFieldName(), byteLength)) {
            return bcdTimestampFactory.isValidBcd(value);
        }
        return true;
    }

    private boolean exceedsMaxLength(AsnField field, String value) {
        return asnSizeExtractor.extractMaxLength(field.getFieldType())
                .map(maxLength -> value.length() > maxLength)
                .orElse(false);
    }

    private boolean matchesRule(AsnField field, String value) {
        Optional<AiConfigProperties.FieldRule> rule =
                aiConfigProperties.findRuleFor(field.getFieldName());

        if (rule.isEmpty() || Objects.isNull(rule.get().getPattern())) {
            return true;
        }
        final String pattern = rule.get().getPattern();
        boolean matches = compiledPatterns.computeIfAbsent(pattern, Pattern::compile)
                .matcher(value)
                .matches();
        if (!matches) {
            log.warn("Yapay zeka degeri kurala uymadi, reddedildi. Alan: {}, Deger: {}, Regex: {}",
                    field.getFieldName(), value, pattern);
        }
        return matches;
    }
}