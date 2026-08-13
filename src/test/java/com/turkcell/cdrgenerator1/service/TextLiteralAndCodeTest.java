package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.model.AsnField;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the {@code .txt} path treats ASN.1 value literals and the
 * {@code CODE(...)} annotation.
 *
 * <p>Neither had a test. {@code CODE(...)} appears 9,788 times across 399
 * modules and literals appear in every schema that declares a DEFAULT, so what
 * these two do is worth stating before the {@code .txt} format is offered to a
 * consumer that has only ever seen one file from it.</p>
 *
 * <p>As with {@code DatColumnStabilityTest}, these describe today's behaviour
 * rather than a specification. Two of them describe something asymmetric enough
 * to be worth a decision.</p>
 */
class TextLiteralAndCodeTest {

    private final CdrFileWriterService writer = new CdrFileWriterService();
    private final BerEncoderService encoder =
            new BerEncoderService(new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));

    private Map<String, Object> record(Object... keyValues) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            record.put((String) keyValues[i], keyValues[i + 1]);
        }
        return record;
    }

    private String datLine(Map<String, Object> record) throws IOException {
        Path file = writer.writeCdrFile("Sample", List.of(record));
        return Files.readString(file).stripTrailing();
    }

    // literals

    /** {@code '42'D} is the decimal wrapper INTEGER and BOOLEAN DEFAULTs use. */
    @Test
    void stripsTheDecimalWrapper() throws IOException {
        assertThat(datLine(record("count", "'42'D"))).isEqualTo("42");
    }

    /** A quoted string arrives without its quotes. */
    @Test
    void stripsQuotesFromAStringLiteral() throws IOException {
        assertThat(datLine(record("name", "\"ABC\""))).isEqualTo("ABC");
    }

    /**
     * The hex wrapper is NOT stripped - it reaches the file with its ASN.1
     * syntax intact.
     *
     * <p>{@code AsnLiteralFormatter.strip} handles {@code "..."} and
     * {@code '...'D} and returns anything else unchanged. The schemas carry 73
     * {@code '...'H} literals, and the BER encoder accepts that form and turns
     * it into bytes - so the same user-supplied value takes two different
     * meanings depending on the output format. The random generator emits bare
     * hex, which is why no generated file shows this today.</p>
     */
    @Test
    void doesNotStripTheHexWrapper() throws IOException {
        assertThat(datLine(record("cellId", "'AB12'H")))
                .as("the literal reaches the file verbatim")
                .isEqualTo("'AB12'H");
    }

    /** The other half of that asymmetry: BER reads the same literal as bytes. */
    @Test
    void berReadsTheSameHexLiteralAsBytes() {
        AsnField field = AsnField.builder().fieldName("cellId").fieldType("OCTET STRING").build();
        byte[] out = encoder.encodeRecord(List.of(field), record("cellId", "'AB12'H"));

        assertThat(out).endsWith(new byte[]{(byte) 0xAB, 0x12});
    }

    // CODE(...)

    /**
     * {@code CODE("DEC")} and {@code CODE("HEX")} change nothing on the
     * {@code .txt} side.
     *
     * <p>{@code FixedWidthTextFormatter} reads the annotation for one purpose
     * only - whether the value is RIGHT justified - so every other CODE value
     * falls through as "not right". That covers 3,311 declarations across 296
     * modules, and no external result says whether it should.</p>
     */
    @Test
    void decAndHexCodesDoNotChangeTheDatValue() throws IOException {
        assertThat(datLine(record("counter", "7"))).isEqualTo("7");
        assertThat(datLine(record("counter", "'7'D"))).isEqualTo("7");
    }

    /** Padding is a BER-side decision: CODE("RIGHT") pads on the left there. */
    @Test
    void rightJustifiedCodePadsOnTheBerSide() {
        AsnField field = AsnField.builder()
                .fieldName("tag").fieldType("IA5String (SIZE(6) CODE(\"RIGHT\"))").build();
        byte[] out = encoder.encodeRecord(List.of(field), record("tag", "\"AB\""));

        assertThat(new String(out)).endsWith("    AB");
    }

    /** Anything that is not RIGHT - including DEC - is treated as left justified. */
    @Test
    void everyOtherCodeIsTreatedAsLeftJustified() {
        AsnField dec = AsnField.builder()
                .fieldName("tag").fieldType("IA5String (SIZE(6) CODE(\"DEC\"))").build();
        byte[] out = encoder.encodeRecord(List.of(dec), record("tag", "\"AB\""));

        assertThat(new String(out)).endsWith("AB    ");
    }

    /**
     * And the pad never reaches the {@code .txt} file at all - the formatter is
     * wired into the encoder only, so a Token-Separated column carries the bare
     * value whatever width its type fixes.
     */
    @Test
    void theDatPathNeverPads() throws IOException {
        assertThat(datLine(record("tag", "\"AB\""))).isEqualTo("AB");
    }
}
