package com.turkcell.cdrgenerator1.ai.util;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AsnField.getFieldType() icindeki ham ASN.1 tip metninden SIZE(n) kisitini
 * cikarir. AsnField'a ayri bir maxLength alani eklemek yerine, mevcut ham
 * metinden gerektiginde turetilir.
 */
@Component
public class AsnSizeExtractor {

    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "SIZE\\s*\\(\\s*(\\d+)\\s*(?:\\.\\.\\s*(\\d+)\\s*)?\\)");

    public Optional<Integer> extractMaxLength(String fieldType) {
        if (Objects.isNull(fieldType) || fieldType.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = SIZE_PATTERN.matcher(fieldType);
        if (!matcher.find()) {
            return Optional.empty();
        }
        final String upperBound = matcher.group(2);
        return Optional.of(Integer.valueOf(
                Objects.nonNull(upperBound) ? upperBound : matcher.group(1)));
    }
}