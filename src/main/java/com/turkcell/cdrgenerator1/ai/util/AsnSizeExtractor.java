package com.turkcell.cdrgenerator1.ai.util;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
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

    /**
     * Matches an INTEGER's own {@code (min..max)} value-range constraint -
     * {@code Milliseconds ::= INTEGER (0..999)} - as opposed to a SIZE(n)
     * constraint, which bounds byte/character length, not the numeric value.
     * Anchored on the literal {@code INTEGER} keyword so it never fires on an
     * OCTET STRING's {@code SIZE(0..999)}-shaped clause.
     */
    private static final Pattern INTEGER_RANGE_PATTERN = Pattern.compile(
            "INTEGER\\s*\\(\\s*(-?\\d+)\\s*\\.\\.\\s*(-?\\d+)\\s*\\)");

    /** The declared {@code (min..max)} bounds of an INTEGER value-range constraint. */
    public record IntegerRange(BigInteger min, BigInteger max) {
    }

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

    /**
     * Extracts an INTEGER's declared {@code (min..max)} value-range constraint,
     * if the type expression carries one.
     *
     * <p>{@code Milliseconds ::= INTEGER (0..999)} has no SIZE() clause at
     * all - {@link #extractMaxLength} returns empty for it - so nothing
     * previously told the AI prompt or the validator that this field only
     * admits 0..999. A generated record carried
     * {@code serviceRequestTimeStampFraction = 260711056963} (a
     * timestamp-shaped number, 12 orders of magnitude out of range) in every
     * one of its 16 Fraction-suffixed fields, and nothing rejected it before
     * it reached the encoded BER.</p>
     */
    public Optional<IntegerRange> extractIntegerRange(String fieldType) {
        if (Objects.isNull(fieldType) || fieldType.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = INTEGER_RANGE_PATTERN.matcher(fieldType);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new IntegerRange(
                new BigInteger(matcher.group(1)),
                new BigInteger(matcher.group(2))));
    }
}