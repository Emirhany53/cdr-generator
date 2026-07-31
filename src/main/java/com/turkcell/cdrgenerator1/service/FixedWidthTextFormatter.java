package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pads a textual value out to the exact width its ASN.1 type fixes.
 *
 * <p>X.680 49.4: a single-value size constraint is exact, so
 * {@code IA5STRING (SIZE(15) CODE("LEFT"))} describes a 15-character field - not
 * an "at most 15" one. The TAP/FCMS family of modules is built almost entirely
 * from such fields and marks the justification with {@code CODE}: {@code "LEFT"}
 * means the value starts at the left and the remainder is filler, {@code "RIGHT"}
 * means the opposite.</p>
 *
 * <p>Without this step a generated FCMSTAPIN record carried
 * {@code duration = "120"} for a {@code SIZE(10)} field and {@code imei} at 15
 * characters for {@code SIZE(17)} - 36 short fields in every single record.
 * Padding happens at encoding time rather than at generation time so that it
 * holds for user-supplied and AI-supplied values too, not only for the random
 * fallback.</p>
 *
 * <p>Only padding is applied. An over-long value is left untouched: silently
 * cutting a caller's data is worse than emitting it and letting validation
 * report it.</p>
 */
@Component
@RequiredArgsConstructor
public class FixedWidthTextFormatter {

    /** Marks the justification of a fixed-width field, e.g. {@code CODE("LEFT")}. */
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "CODE\\s*\\(\\s*\"([^\"]*)\"\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final String RIGHT_JUSTIFIED = "RIGHT";
    private static final char PAD_CHARACTER = ' ';

    private final AsnSizeExtractor asnSizeExtractor;

    /**
     * Returns {@code text} widened to the field's fixed size, or {@code text}
     * unchanged when the type fixes no size or the value already fills it.
     */
    public String pad(String fieldType, String text) {
        if (Objects.isNull(text)) {
            return text;
        }
        Optional<Integer> fixedLength = asnSizeExtractor.extractFixedLength(fieldType);
        if (fixedLength.isEmpty()) {
            return text;
        }
        int missing = fixedLength.get() - text.length();
        if (missing <= 0) {
            return text;
        }
        String filler = String.valueOf(PAD_CHARACTER).repeat(missing);
        return isRightJustified(fieldType) ? filler + text : text + filler;
    }

    private boolean isRightJustified(String fieldType) {
        if (Objects.isNull(fieldType)) {
            return false;
        }
        Matcher matcher = CODE_PATTERN.matcher(fieldType);
        // Absent CODE defaults to left justification, matching the overwhelming
        // majority of the schema (2933 LEFT against a single RIGHT).
        return matcher.find() && RIGHT_JUSTIFIED.equalsIgnoreCase(matcher.group(1));
    }
}
