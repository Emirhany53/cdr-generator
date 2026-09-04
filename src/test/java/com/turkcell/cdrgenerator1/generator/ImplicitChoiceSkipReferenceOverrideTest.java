package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.generator.source.RandomValueSource;
import com.turkcell.cdrgenerator1.generator.source.UserProvidedValueSource;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import com.turkcell.cdrgenerator1.service.BerEncoderService;
import com.turkcell.cdrgenerator1.service.FixedWidthTextFormatter;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import com.turkcell.cdrgenerator1.service.TlvWriter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1: {@code skip-implicit-choice-fields} bypassed on ONE call site, only in
 * reference mode, only where the caller explicitly described that site.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>{@link ImplicitChoiceSkipMmtelTest} and {@link ImplicitChoiceSkipScopeTest}
 * already pin the rule this test does not touch: skip stays on globally, and it
 * fires only in the MMTel-party-addressing family. What neither covers is a
 * REPEATED CHOICE such as {@code list-Of-Calling-Party-Address} - the EMM
 * reference record carries it, but the global rule drops it unconditionally,
 * for every caller, regardless of what they asked for. Reproducing that
 * reference record needs a narrower answer: drop the field when nobody
 * described it, keep it when a caller in reference mode named this exact
 * path.</p>
 *
 * <h2>What must stay true</h2>
 *
 * <ul>
 *   <li>{@code app.cdr.skip-implicit-choice-fields}'s default is untouched
 *       (pinned directly on a fresh {@link CdrConfigProperties}).</li>
 *   <li>{@code referenceMode=false} never engages the bypass, described or
 *       not - the workaround must not leak into ordinary generation.</li>
 *   <li>Describing an unrelated field must not accidentally unlock this
 *       one.</li>
 * </ul>
 *
 * <p>Assertions go all the way to the encoded BER and back, not just the
 * builder's map: a context tag can only be trusted present or absent once it
 * has actually been walked on the wire, and scenario 2 additionally confirms
 * the caller's own value - not merely a tag - reached the bytes.</p>
 */
class ImplicitChoiceSkipReferenceOverrideTest {

    /** The InvolvedParty CHOICE is the family fingerprint the resolver keys on. */
    private static final String FAMILY_FINGERPRINT = """
            InvolvedParty ::= CHOICE {
                sip-uri [0] IA5String,
                tel-uri [1] IA5String
            }
            """;

    /**
     * Mirrors the real MMTel field exactly: {@code [6] EXPLICIT SEQUENCE OF
     * <CHOICE> OPTIONAL}. The written EXPLICIT is neutralized regardless of
     * family (an IMPLICIT-TAGS module never keeps it for a repeated field -
     * see {@code AsnFieldTreeResolver.effectiveExplicit}), but
     * {@code decoderHoistsImplicitChoice} IS family-gated, which is why
     * {@link #FAMILY_FINGERPRINT} has to be present for skip to fire at all.
     */
    private static final String RECORD = """
            Record ::= SEQUENCE {
                recordType    [0] IMPLICIT INTEGER,
                callingParty  [6] EXPLICIT ListOfInvolvedParties OPTIONAL
            }
            ListOfInvolvedParties ::= SEQUENCE OF InvolvedParty
            """;

    private final StructureParserService parser = new StructureParserService(
            null, new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());

    private String module() {
        return "Mod DEFINITIONS IMPLICIT TAGS ::= BEGIN\n" + RECORD + FAMILY_FINGERPRINT + "END\n";
    }

    private byte[] generate(boolean referenceMode, Map<String, String> userValues) {
        CdrConfigProperties config = new CdrConfigProperties();
        config.setDefaultRecordCount(1);
        config.setMaxRecordCount(100);
        // Deliberately left at its default (true) rather than set explicitly -
        // globalSkipDefaultStaysTrue() below is the one place that pins the
        // default itself; every other test here exercises that default as a
        // caller would actually see it.

        AsnSizeExtractor sizes = new AsnSizeExtractor();
        BcdTimestampFactory timestamps = new BcdTimestampFactory();
        CdrRecordBuilder builder = new CdrRecordBuilder(parser, timestamps, config, List.of(
                new UserProvidedValueSource(),
                new RandomValueSource(new FieldValueGenerator(
                        new TbcdCodec(), new AiConfigProperties(), sizes, timestamps))));

        AsnStructure structure = parser.parseFromContents("Mod", module());
        Map<String, Object> record = builder.buildRecordFromFields(
                structure.getFields(), 0, userValues, List.of(), referenceMode);

        BerEncoderService encoder = new BerEncoderService(new TlvWriter(), new FixedWidthTextFormatter(sizes));
        return encoder.encodeRecord(structure.getFields(), record);
    }

    // ---------------------------------------------------------------- TLV helpers

    /** {contentStart, contentLength} for the TLV at {@code offset}. Single-byte tags only, sufficient here. */
    private int[] readTlv(byte[] buffer, int offset) {
        int cursor = offset + 1;
        int length = buffer[cursor++] & 0xFF;
        if ((length & 0x80) != 0) {
            int lengthBytes = length & 0x7F;
            length = 0;
            for (int i = 0; i < lengthBytes; i++) {
                length = (length << 8) | (buffer[cursor++] & 0xFF);
            }
        }
        return new int[] {cursor, length};
    }

    private byte[] contentOf(byte[] buffer, int offset) {
        int[] tlv = readTlv(buffer, offset);
        byte[] content = new byte[tlv[1]];
        System.arraycopy(buffer, tlv[0], content, 0, tlv[1]);
        return content;
    }

    /** Whether a CONTEXT-class TLV tagged {@code wantedTag} sits at the top level of {@code body}. */
    private boolean hasTopLevelContextTag(byte[] body, int wantedTag) {
        int i = 0;
        while (i < body.length) {
            int tagByte = body[i] & 0xFF;
            int tagClass = tagByte >> 6;
            int tagNumber = tagByte & 0x1F;
            int[] tlv = readTlv(body, i);
            if (tagClass == 2 && tagNumber == wantedTag) {
                return true;
            }
            i = tlv[0] + tlv[1];
        }
        return false;
    }

    // ---------------------------------------------------------------- sanity

    /**
     * Confirms the test fixture actually reaches every flag
     * {@code shouldSkipImplicitChoice} checks - if one of these were wrong the
     * scenarios below would pass for the wrong reason (or not exercise the
     * rule at all).
     */
    @Test
    void theFieldResolvesWithEveryFlagTheSkipRuleChecks() {
        AsnStructure structure = parser.parseFromContents("Mod", module());
        AsnField callingParty = structure.getFields().stream()
                .filter(f -> "callingParty".equals(f.getFieldName()))
                .findFirst()
                .orElseThrow();

        assertThat(callingParty.isChoice()).isTrue();
        assertThat(callingParty.isRepeated()).isTrue();
        assertThat(callingParty.isOptional()).isTrue();
        assertThat(callingParty.isExplicit())
                .as("a repeated field's written EXPLICIT is neutralized under IMPLICIT TAGS")
                .isFalse();
        assertThat(callingParty.isDecoderHoistsImplicitChoice())
                .as("the InvolvedParty fingerprint must gate the workaround on")
                .isTrue();
    }

    @Test
    void globalSkipDefaultStaysTrue() {
        assertThat(new CdrConfigProperties().isSkipImplicitChoiceFields())
                .as("P1 must not touch the global default - only a path-scoped bypass under referenceMode")
                .isTrue();
    }

    // ---------------------------------------------------------------- scenario 1

    @Test
    void skipOnReferenceModeOnUndescribedPathIsOmitted() {
        byte[] encoded = generate(true, Map.of());

        assertThat(hasTopLevelContextTag(contentOf(encoded, 0), 6))
                .as("caller described nothing, so the workaround must still drop the field")
                .isFalse();
    }

    /** An unrelated described field must not accidentally unlock this one. */
    @Test
    void skipOnReferenceModeOnUnrelatedDescribedFieldDoesNotUnlockIt() {
        byte[] encoded = generate(true, Map.of("recordType", "83"));

        assertThat(hasTopLevelContextTag(contentOf(encoded, 0), 6))
                .as("describing recordType must not unlock callingParty")
                .isFalse();
    }

    // ---------------------------------------------------------------- scenario 2

    /**
     * The case P1 exists for. Currently RED: {@code shouldSkipImplicitChoice}
     * does not look at reference mode or caller-described paths at all, so the
     * field is dropped unconditionally regardless of what the caller supplied.
     */
    @Test
    void skipOnReferenceModeOnDescribedPathIsGenerated() {
        Map<String, String> user = Map.of("callingParty[0].sip-uri", "sip:p1-test@example.org");
        byte[] encoded = generate(true, user);

        assertThat(hasTopLevelContextTag(contentOf(encoded, 0), 6))
                .as("caller explicitly described this CHOICE path in reference mode; it must not be dropped")
                .isTrue();
        assertThat(new String(encoded, StandardCharsets.ISO_8859_1))
                .as("the described value must reach the wire, not just the tag")
                .contains("sip:p1-test@example.org");
    }

    // ---------------------------------------------------------------- scenario 3

    @Test
    void referenceModeOffKeepsTheFieldOmittedWithNothingDescribed() {
        byte[] encoded = generate(false, Map.of());

        assertThat(hasTopLevelContextTag(contentOf(encoded, 0), 6)).isFalse();
    }

    /**
     * The decisive regression guard: the SAME path that unlocks the field in
     * scenario 2 must do nothing when referenceMode is off. If the bypass were
     * gated on "was this path described" alone - forgetting the referenceMode
     * check - this is the test that would catch it.
     */
    @Test
    void referenceModeOffKeepsTheFieldOmittedEvenWhenDescribed() {
        Map<String, String> user = Map.of("callingParty[0].sip-uri", "sip:p1-test@example.org");
        byte[] encoded = generate(false, user);

        assertThat(hasTopLevelContextTag(contentOf(encoded, 0), 6))
                .as("referenceMode=false must never engage the path-scoped bypass, described or not")
                .isFalse();
        assertThat(new String(encoded, StandardCharsets.ISO_8859_1))
                .doesNotContain("sip:p1-test@example.org");
    }
}
