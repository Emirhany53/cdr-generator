package com.turkcell.cdrgenerator1.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the {@code .txt} writer does with a character US-ASCII cannot hold.
 *
 * <p>{@code CdrFileWriterService} writes with
 * {@link StandardCharsets#US_ASCII}. 48 modules declare a {@code UTF8String}
 * field and 15 a {@code GraphicString}, and the values that reach those fields
 * come from the user or from the AI provider - neither of which is restricted
 * to ASCII. The random generator draws from an ASCII alphabet, which is why no
 * generated file has ever shown this.</p>
 *
 * <p>These tests pin the behaviour down rather than judge it. Whether EMM wants
 * ASCII, Latin-9 or UTF-8 on the Token-Separated side is not something any
 * result has told us - {@code Multicloud} is the only external confirmation the
 * format has, and its values were ASCII.</p>
 */
class TextCharsetTest {

    private final CdrFileWriterService writer = new CdrFileWriterService();

    private Map<String, Object> record(Object... keyValues) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            record.put((String) keyValues[i], keyValues[i + 1]);
        }
        return record;
    }

    private byte[] bytesOf(Map<String, Object> record) throws IOException {
        Path file = writer.writeCdrFile("Sample", List.of(record));
        return Files.readAllBytes(file);
    }

    /** The ordinary case: an ASCII value survives byte for byte. */
    @Test
    void asciiValuesSurviveUnchanged() throws IOException {
        byte[] out = bytesOf(record("name", "\"ABC123\""));

        assertThat(new String(out, StandardCharsets.US_ASCII).stripTrailing()).isEqualTo("ABC123");
    }

    /**
     * A character US-ASCII cannot hold does not become {@code ?} - it aborts the
     * write.
     *
     * <p>{@code Files.write(path, lines, US_ASCII)} configures its encoder to
     * REPORT rather than REPLACE, so the first Turkish letter throws
     * {@link java.nio.charset.UnmappableCharacterException}. Nothing catches it
     * on the way out: {@code writeCdrFile} declares {@code IOException} and the
     * exception surfaces from the endpoint as a server error whose message says
     * "Input length = 1" and names no field.</p>
     *
     * <p>No data is silently corrupted, which is the good half. The bad half is
     * that 48 modules declare {@code UTF8String}, the values there come from the
     * user or the AI provider, and a single {@code ç} makes the whole file
     * unproducible with a message nobody can act on.</p>
     */
    @Test
    void aNonAsciiCharacterAbortsTheWrite() {
        assertThatThrownBy(() -> bytesOf(record("name", "\"ÇAĞRI\"")))
                .isInstanceOf(UnmappableCharacterException.class);
    }

    /** One character is enough, wherever it sits in the record. */
    @Test
    void oneNonAsciiCharacterAnywhereIsEnough() {
        assertThatThrownBy(() -> bytesOf(record("a", "\"AAAAA\"", "b", "\"ş\"")))
                .isInstanceOf(UnmappableCharacterException.class);
    }

    /** A value carrying the separator would break the line; the writer is not the guard. */
    @Test
    void theSeparatorInsideAValueIsNotEscaped() throws IOException {
        byte[] out = bytesOf(record("a", "\"x|y\"", "b", "\"z\""));

        assertThat(new String(out, StandardCharsets.US_ASCII).stripTrailing())
                .as("three columns arrive where two fields were written")
                .isEqualTo("x|y|z");
    }
}
