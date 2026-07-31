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

    /**
     * The length a {@code SIZE(n)} constraint fixes exactly, if it fixes one.
     *
     * <p>X.680 49.4: a single-value size constraint means the value has EXACTLY
     * that many units - {@code IA5String (SIZE(15))} is a 15-character field, not
     * an "up to 15" one. A range ({@code SIZE(1..15)}) fixes nothing, so it
     * returns empty and callers must leave such a value at its natural
     * length.</p>
     */
    public Optional<Integer> extractFixedLength(String fieldType) {
        if (Objects.isNull(fieldType) || fieldType.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = SIZE_PATTERN.matcher(fieldType);
        if (!matcher.find() || Objects.nonNull(matcher.group(2))) {
            return Optional.empty();
        }
        return Optional.of(Integer.valueOf(matcher.group(1)));
    }
}