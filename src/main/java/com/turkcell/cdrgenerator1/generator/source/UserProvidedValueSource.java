package com.turkcell.cdrgenerator1.generator.source;

import com.turkcell.cdrgenerator1.model.AsnField;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Kullanicinin acikca gonderdigi deger her zaman kazanir.
 * Deger once tam alan yoluyla (ornek: "addr.msisdn"), bulunamazsa ciplak
 * alan adiyla aranir; ic ice yapilarda ayni isimli alanlari ayirt etmek
 * icin yol formu, kolaylik icin ciplak ad formu desteklenir.
 */
@Component
public class UserProvidedValueSource implements ValueSource {

    private static final int ORDER = 10;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Optional<String> resolve(ValueSourceContext context, AsnField field) {
        Map<String, String> userValues = context.getUserProvidedValues();
        if (Objects.isNull(userValues)) {
            return Optional.empty();
        }
        return findValue(userValues, context.getCurrentPath(), field.getFieldName())
                .filter(value -> !value.isBlank());
    }

    private Optional<String> findValue(Map<String, String> userValues, String currentPath, String fieldName) {
        if (Objects.nonNull(currentPath)) {
            String pathValue = userValues.get(currentPath);
            if (Objects.nonNull(pathValue)) {
                return Optional.of(pathValue);
            }
        }
        return Optional.ofNullable(userValues.get(fieldName));
    }
}