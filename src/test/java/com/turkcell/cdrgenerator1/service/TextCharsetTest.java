package com.turkcell.cdrgenerator1.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the {@code .txt} writer does with a value the format cannot carry.
 *
 * <p>{@code CdrFileWriterService} writes with
 * {@link StandardCharsets#US_ASCII}. 48 modules declare a {@code UTF8String}
 * field and 15 a {@code GraphicString}, and the values that reach those fields
 * come from the user or from the AI provider - neither of which is restricted
 * to ASCII. The random generator draws from an ASCII alphabet, which is why no
 * generated file has ever shown this.</p>
 *
 * <p>Whether EMM wants ASCII, Latin-9 or UTF-8 on the Token-Separated side is
 * not something any result has told us - {@code Multicloud} is the only external
 * confirmation the format has, and its values were ASCII. So the writer stays on
 * US-ASCII and refuses what it cannot represent.</p>
 *
 * <h2>Refusing, and saying which field</h2>
 *
 * <p>Three characters break a Token-Separated line: {@code |} splits a column,
 * a line break splits a record, and a non-ASCII character cannot be encoded at
 * all. The first two used to pass straight through and corrupt the file's shape
 * silently; the third surfaced as {@code UnmappableCharacterException: Input
 * length = 1} raised from inside {@code Files.write}, which named neither the
 * field nor the character. All three are now one refusal that names the column
 * and the value, so a caller can act on it.</p>
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
     * A character US-ASCII cannot hold aborts the write, and the message says
     * which field carried it.
     */
    @Test
    void aNonAsciiCharacterAbortsTheWrite() {
        assertThatThrownBy(() -> bytesOf(record("name", "\"ÇAĞRI\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name")
                .hasMessageContaining("US-ASCII")
                .hasMessageContaining("ÇAĞRI");
    }

    /** One character is enough, wherever it sits in the record. */
    @Test
    void oneNonAsciiCharacterAnywhereIsEnough() {
        assertThatThrownBy(() -> bytesOf(record("a", "\"AAAAA\"", "b", "\"ş\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'b'");
    }

    /**
     * The separator inside a value is refused rather than written.
     *
     * <p>It used to pass through: two fields went in and three columns came out,
     * with nothing to tell a consumer that the line it was reading by position
     * had shifted. Escaping was the alternative and was rejected - it would put
     * bytes on the wire that no accepted file has ever carried, and
     * {@code Multicloud}, the only external confirmation this format has, used
     * none.</p>
     */
    @Test
    void theSeparatorInsideAValueIsRefused() {
        assertThatThrownBy(() -> bytesOf(record("a", "\"x|y\"", "b", "\"z\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'a'")
                .hasMessageContaining("|");
    }

    /** A line break would split one record into two, so it is refused as well. */
    @Test
    void aLineBreakInsideAValueIsRefused() {
        assertThatThrownBy(() -> bytesOf(record("a", "\"x\ny\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("satir sonu");
    }

    /**
     * The record separator is {@code \n} on every host.
     *
     * <p>The writer used to go through {@code Files.write(path, lines, charset)},
     * which terminates each line with {@link System#lineSeparator()} - so the
     * same source shipped CRLF from a Windows host and LF from this one. The
     * separator belongs to the format the consumer parses, and the one
     * Token-Separated file EMM has accepted carried LF.</p>
     */
    @Test
    void everyRecordEndsWithALineFeedAndNoCarriageReturn() throws IOException {
        byte[] out = bytesOf(record("a", "\"1\"", "b", "\"2\""));

        assertThat(new String(out, StandardCharsets.US_ASCII)).isEqualTo("1|2\n");
    }
}
