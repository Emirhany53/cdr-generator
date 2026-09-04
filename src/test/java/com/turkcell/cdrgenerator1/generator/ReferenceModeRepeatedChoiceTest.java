package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.service.BerEncoderService;
import com.turkcell.cdrgenerator1.service.FixedWidthTextFormatter;
import com.turkcell.cdrgenerator1.service.TlvWriter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 (B + C): given a repeated CHOICE field whose children ALREADY carry more
 * than one alternative - the shape P2-A is responsible for producing in
 * {@code StructureParserService} - {@code CdrRecordBuilder} must derive the
 * instance count from the caller's indexed data and, per instance, encode only
 * the alternative that instance actually named.
 *
 * <h2>Why the tree is built by hand here</h2>
 *
 * <p>P2-A (the {@code StructureParserService} side: expanding a resolved
 * CHOICE field's single default alternative into the union the caller's
 * indexed keys name) does not exist yet - see
 * {@link com.turkcell.cdrgenerator1.parser.IndexedChoiceExpansionTest} for
 * that half, which targets a {@code StructureParserService} overload that has
 * not been written. This class tests the OTHER half against
 * TODAY'S real, unmodified {@code CdrRecordBuilder} and
 * {@code BerEncoderService}: it hand-builds the tree A would produce (two
 * alternatives already resolved as siblings, exactly {@code AsnField.builder()}
 * shapes {@code AsnFieldTreeResolver} itself would emit) so B and C can be
 * proven end-to-end - real values in, real encoded BER out, decoded back -
 * without waiting on A's wiring.</p>
 *
 * <h2>What must be true once B and C exist</h2>
 *
 * <ul>
 *   <li>{@code [0]} is really {@code sIP-URI}: right tag, right value.</li>
 *   <li>{@code [1]} is really {@code tEL-URI}.</li>
 *   <li>No single instance ever carries both alternatives - the literal
 *       duplicate/conflicting-tag shape this whole feature exists to
 *       avoid.</li>
 *   <li>The collection has exactly 2 elements, not more, not the random
 *       1..2 {@link CdrRecordBuilder#CHOICE_ELEMENT_COUNT} default masks
 *       today.</li>
 *   <li>{@code referenceMode=false} keeps today's behaviour - including
 *       against a hand-built two-alternative tree, as a defense-in-depth
 *       check independent of whether A ever runs.</li>
 *   <li>An ordinary (non-CHOICE) repeated group - the KN-1 territory - is
 *       untouched by whatever new branch B/C add.</li>
 * </ul>
 */
class ReferenceModeRepeatedChoiceTest {

    private final CdrRecordBuilder builder =
            new CdrRecordBuilder(null, new BcdTimestampFactory(), null, TestValueSources.chain());

    private AsnField alternative(String name, int tag) {
        return AsnField.builder().fieldName(name).fieldType("IA5String")
                .optional(false).tagNumber(tag).tagClass(BerTagClass.CONTEXT).build();
    }

    /** The shape P2-A is meant to produce: a repeated CHOICE field whose
     * children are the UNION of every alternative the caller's indexed keys
     * name, in the order AsnFieldTreeResolver would resolve them (sIP-URI is
     * [0], tEL-URI is [1] in the real InvolvedParty CHOICE). */
    private AsnField twoAlternativeField() {
        return AsnField.builder().fieldName("parties").fieldType("ListOfInvolvedParties")
                .repeated(true).choice(true).optional(true)
                .tagNumber(6).tagClass(BerTagClass.CONTEXT)
                .children(List.of(alternative("sIP-URI", 0), alternative("tEL-URI", 1)))
                .build();
    }

    /** Today's real, unexpanded shape: exactly one alternative, as every
     * CHOICE resolves before P2-A exists. */
    private AsnField oneAlternativeField() {
        return AsnField.builder().fieldName("parties").fieldType("ListOfInvolvedParties")
                .repeated(true).choice(true).optional(true)
                .tagNumber(6).tagClass(BerTagClass.CONTEXT)
                .children(List.of(alternative("sIP-URI", 0)))
                .build();
    }

    private byte[] generate(AsnField field, Map<String, String> userValues, boolean referenceMode) {
        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(field), 0, userValues, List.of(), referenceMode);
        BerEncoderService encoder =
                new BerEncoderService(new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));
        return encoder.encodeRecord(List.of(field), record);
    }

    // ---------------------------------------------------------------- TLV helpers

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

    /** {(tagNumber, contentBytes)} for every top-level TLV in {@code buffer}. */
    private List<int[]> topLevelStarts(byte[] buffer) {
        List<int[]> starts = new java.util.ArrayList<>();
        int i = 0;
        while (i < buffer.length) {
            int tagNumber = buffer[i] & 0x1F;
            int[] tlv = readTlv(buffer, i);
            starts.add(new int[] {tagNumber, i, tlv[0], tlv[1]});
            i = tlv[0] + tlv[1];
        }
        return starts;
    }

    /** The CHOICE collection's elements: each as {tagNumber, contentBytes}. */
    private List<Map.Entry<Integer, byte[]>> collectionElements(byte[] encoded) {
        byte[] recordBody = contentOf(encoded, 0);
        byte[] collectionBody = null;
        for (int[] start : topLevelStarts(recordBody)) {
            if (start[0] == 6) {
                collectionBody = new byte[start[3]];
                System.arraycopy(recordBody, start[2], collectionBody, 0, start[3]);
            }
        }
        if (collectionBody == null) {
            return List.of();
        }
        List<Map.Entry<Integer, byte[]>> elements = new java.util.ArrayList<>();
        for (int[] start : topLevelStarts(collectionBody)) {
            byte[] elementContent = new byte[start[3]];
            System.arraycopy(collectionBody, start[2], elementContent, 0, start[3]);
            elements.add(Map.entry(start[0], elementContent));
        }
        return elements;
    }

    // ---------------------------------------------------------------- the core case

    @Test
    void twoIndexedAlternativesProduceTwoInstancesEachWithItsOwnAlternative() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("parties[0].sIP-URI", "sip:test1@example.org");
        user.put("parties[1].tEL-URI", "tel:0123456789");

        byte[] encoded = generate(twoAlternativeField(), user, true);
        List<Map.Entry<Integer, byte[]>> elements = collectionElements(encoded);

        assertThat(elements)
                .as("exactly 2 CHOICE instances, not CHOICE_ELEMENT_COUNT's default 1")
                .hasSize(2);

        assertThat(elements.get(0).getKey()).as("[0] is really sIP-URI (tag 0)").isEqualTo(0);
        assertThat(new String(elements.get(0).getValue(), java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("sip:test1@example.org");

        assertThat(elements.get(1).getKey()).as("[1] is really tEL-URI (tag 1)").isEqualTo(1);
        assertThat(new String(elements.get(1).getValue(), java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("tel:0123456789");
    }

    // ---------------------------------------------------------------- referenceMode=false

    // A dedicated "no instance carries both alternatives" test was tried and
    // removed: encodeRepeated's elementIsChoice branch writes NO per-element
    // wrapper, so "one malformed instance holding both tags" and "two clean
    // instances holding one tag each" are BYTE-IDENTICAL on the wire - there
    // is no boundary marker between elements to inspect. The only thing that
    // actually distinguishes correct from broken is VALUE correctness: an
    // extra or wrong-value tag is what "both alternatives at once" looks like
    // in practice (this is exactly how today's un-implemented code fails -
    // see the assertion above). twoIndexedAlternativesProduceTwoInstancesEachWithItsOwnAlternative's
    // exact per-tag value AND count assertions already cover this; a
    // structural-only check here would pass vacuously (a leaf alternative can
    // never "wrap" a second tag regardless of whether C exists), so it added
    // no real signal.

    @Test
    void referenceModeFalseKeepsOneInstanceEvenAgainstATwoAlternativeTree() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("parties[0].sIP-URI", "sip:test1@example.org");
        user.put("parties[1].tEL-URI", "tel:0123456789");

        byte[] encoded = generate(twoAlternativeField(), user, false);
        List<Map.Entry<Integer, byte[]>> elements = collectionElements(encoded);

        assertThat(elements)
                .as("CHOICE_ELEMENT_COUNT must still hold when referenceMode is off, "
                        + "even handed a tree P2-A would only produce under referenceMode=true")
                .hasSize(1);
        assertThat(elements.get(0).getKey())
                .as("and that one instance must not accidentally carry the OTHER alternative either")
                .isEqualTo(0);
    }

    @Test
    void referenceModeFalseAgainstTodaysRealSingleAlternativeTreeIsUnaffected() {
        Map<String, String> user = Map.of("parties[0].sIP-URI", "sip:test1@example.org");

        byte[] encoded = generate(oneAlternativeField(), user, false);
        List<Map.Entry<Integer, byte[]>> elements = collectionElements(encoded);

        assertThat(elements).hasSize(1);
        assertThat(elements.get(0).getKey()).isEqualTo(0);
        assertThat(new String(elements.get(0).getValue(), java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("sip:test1@example.org");
    }

    // ---------------------------------------------------------------- non-CHOICE regression

    /**
     * KN-1's territory: an ordinary repeated SEQUENCE (not a CHOICE) must keep
     * deriving its count from indexedGroupCount exactly as before - the new
     * CHOICE-specific branch must never engage for it.
     */
    @Test
    void aNonChoiceRepeatedGroupIsUnaffected() {
        AsnField originatingIoi = AsnField.builder().fieldName("originatingIOI").fieldType("GraphicString")
                .optional(true).tagNumber(0).tagClass(BerTagClass.CONTEXT).build();
        AsnField terminatingIoi = AsnField.builder().fieldName("terminatingIOI").fieldType("GraphicString")
                .optional(true).tagNumber(1).tagClass(BerTagClass.CONTEXT).build();
        AsnField group = AsnField.builder().fieldName("interOperatorIdentifiers").fieldType("InterOperatorIdentifiers")
                .repeated(true).choice(false).optional(true)
                .tagNumber(20).tagClass(BerTagClass.CONTEXT)
                .children(List.of(originatingIoi, terminatingIoi))
                .build();

        Map<String, String> user = new LinkedHashMap<>();
        user.put("interOperatorIdentifiers[0].originatingIOI", "ims.mnc001.mcc286.3gppnetwork.org");
        user.put("interOperatorIdentifiers[1].terminatingIOI", "vodafone.com.tr");

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(group), 0, user, List.of(), true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> instances = (List<Map<String, Object>>) record.get("interOperatorIdentifiers");
        assertThat(instances).as("still 2 instances, exactly as KN-1 already proved").hasSize(2);
        assertThat(instances.get(0)).containsEntry("originatingIOI", "\"ims.mnc001.mcc286.3gppnetwork.org\"");
        assertThat(instances.get(1)).containsEntry("terminatingIOI", "\"vodafone.com.tr\"");
    }
}
