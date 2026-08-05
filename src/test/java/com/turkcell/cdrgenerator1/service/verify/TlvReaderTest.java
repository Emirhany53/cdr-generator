package com.turkcell.cdrgenerator1.service.verify;

import com.turkcell.cdrgenerator1.exception.BerDecodingException;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.service.TlvWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlvReaderTest {

    private final TlvReader reader = new TlvReader();
    private final TlvWriter writer = new TlvWriter();

    private static byte[] hex(String text) {
        String clean = text.replace(" ", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // --- identifier and length forms ---

    @Test
    void readsPrimitiveWithShortFormLength() {
        byte[] data = hex("83 03 01 02 03");

        TlvNode node = reader.read(data, 0);

        assertEquals(BerTagClass.CONTEXT, node.tagClass());
        assertEquals(3, node.tagNumber());
        assertTrue(node.isPrimitive());
        assertEquals(3, node.valueLength());
        assertEquals(5, node.end());
        assertArrayEquals(hex("01 02 03"), reader.valueBytes(data, node));
    }

    @Test
    void readsConstructedNodeAndItsChildren() {
        // 31 = UNIVERSAL SET, holding [3] and [8]
        byte[] data = hex("31 08 83 02 AA BB 88 02 CC DD");

        TlvNode set = reader.read(data, 0);

        assertEquals(BerTagClass.UNIVERSAL, set.tagClass());
        assertEquals(17, set.tagNumber());
        assertTrue(set.constructed());
        assertEquals(2, set.children().size());
        assertEquals(3, set.children().get(0).tagNumber());
        assertEquals(8, set.children().get(1).tagNumber());
        assertArrayEquals(hex("CC DD"), reader.valueBytes(data, set.children().get(1)));
    }

    @Test
    void readsLongFormLength() {
        // 0x82 announces two length octets: 0x0100 = 256 content octets.
        byte[] data = new byte[4 + 256];
        System.arraycopy(hex("84 82 01 00"), 0, data, 0, 4);

        TlvNode node = reader.read(data, 0);

        assertEquals(256, node.valueLength());
        assertEquals(4, node.valueStart());
        assertEquals(260, node.end());
    }

    /**
     * enhancedPhoneFeatures1 is [509], the tag at the centre of the EMM
     * rejection, and it is the reason the high-tag-number form has to work:
     * 509 = 3 * 128 + 125, so the identifier is BF 83 7D.
     */
    @Test
    void readsHighTagNumberForm() {
        // [509] holding one Epf1Service-shaped SET, which in turn holds [0].
        byte[] data = hex("BF 83 7D 05 31 03 80 01 00");

        TlvNode node = reader.read(data, 0);

        assertEquals(509, node.tagNumber());
        assertEquals(BerTagClass.CONTEXT, node.tagClass());
        assertTrue(node.constructed());
        assertEquals(4, node.valueStart());
        assertEquals(1, node.children().size());
        assertEquals(17, node.children().get(0).tagNumber());
        assertEquals(0, node.children().get(0).children().get(0).tagNumber());
    }

    // --- writer/reader symmetry ---

    @Test
    void readsBackWhatTheWriterProduced() {
        byte[] written = writer.buildTlv(BerTagClass.CONTEXT, 561, true,
                writer.buildTlv(BerTagClass.CONTEXT, 1, false, writer.encodeString("AVMAVAS1")));

        TlvNode node = reader.read(written, 0);

        assertEquals(561, node.tagNumber());
        assertTrue(node.constructed());
        assertEquals(1, node.children().size());
        assertEquals("AVMAVAS1",
                new String(reader.valueBytes(written, node.children().get(0))));
        assertEquals(written.length, node.totalLength());
    }

    // --- the shape the EMM rejection turns on ---

    /**
     * The exact bytes our encoder produced for Epf1Service.impu in
     * F1_choice_only_noScalar01.ber: an EXPLICIT [4] wrapper holding the
     * chosen CHOICE alternative, sIP-URI [0]. Whatever EMM decides about this
     * shape, the reader has to see it as one constructed node with one child,
     * because that is what every rule will be asked to judge.
     */
    @Test
    void readsExplicitChoiceWrapperAsOneNodeWithOneChild() {
        byte[] data = hex("A4 0A 80 08 31 43 4F 44 49 57 44 37");

        TlvNode impu = reader.read(data, 0);

        assertEquals(4, impu.tagNumber());
        assertTrue(impu.constructed());
        assertEquals(1, impu.children().size());

        TlvNode alternative = impu.children().get(0);
        assertEquals(0, alternative.tagNumber());
        assertTrue(alternative.isPrimitive());
        assertEquals("1CODIWD7", new String(reader.valueBytes(data, alternative)));
    }

    // --- indefinite length ---

    @Test
    void readsIndefiniteLengthTerminatedByEoc() {
        byte[] data = hex("A0 80 83 01 07 00 00");

        TlvNode node = reader.read(data, 0);

        assertTrue(node.indefiniteLength());
        assertEquals(1, node.children().size());
        assertEquals(7, data[node.children().get(0).valueStart()]);
        // valueEnd stops before the EOC pair, end includes it.
        assertEquals(5, node.valueEnd());
        assertEquals(7, node.end());
    }

    @Test
    void rejectsPrimitiveWithIndefiniteLength() {
        BerDecodingException error =
                assertThrows(BerDecodingException.class, () -> reader.read(hex("80 80 00 00"), 0));
        assertTrue(error.getMessage().contains("Primitive"));
    }

    @Test
    void rejectsIndefiniteLengthWithNoEoc() {
        assertThrows(BerDecodingException.class, () -> reader.read(hex("A0 80 83 01 07"), 0));
    }

    // --- malformed input ---

    @Test
    void rejectsValueRunningPastTheBuffer() {
        assertThrows(BerDecodingException.class, () -> reader.read(hex("83 05 01 02"), 0));
    }

    @Test
    void rejectsChildOverflowingItsParent() {
        // The [3] child claims 6 octets but its parent only has 4 to give.
        assertThrows(BerDecodingException.class, () -> reader.read(hex("31 04 83 06 AA BB"), 0));
    }

    @Test
    void rejectsTruncatedLengthOctet() {
        assertThrows(BerDecodingException.class, () -> reader.read(hex("83"), 0));
    }

    // --- whole files ---

    @Test
    void readsEveryTopLevelRecordInAConcatenatedFile() {
        byte[] first = writer.buildTlv(BerTagClass.CONTEXT, 83, true,
                writer.buildTlv(0, false, writer.encodeInteger(1)));
        byte[] second = writer.buildTlv(BerTagClass.CONTEXT, 83, true,
                writer.buildTlv(0, false, writer.encodeInteger(2)));
        byte[] file = new byte[first.length + second.length];
        System.arraycopy(first, 0, file, 0, first.length);
        System.arraycopy(second, 0, file, first.length, second.length);

        List<TlvNode> records = reader.readAll(file);

        assertEquals(2, records.size());
        assertEquals(83, records.get(0).tagNumber());
        assertEquals(first.length, records.get(1).start());
        assertEquals(file.length, records.get(1).end());
    }

    @Test
    void readsEmptyInputAsNoRecords() {
        assertTrue(reader.readAll(new byte[0]).isEmpty());
        assertTrue(reader.readAll(null).isEmpty());
    }

    @Test
    void readsConstructedNodeWithNoChildren() {
        TlvNode node = reader.read(hex("30 00"), 0);

        assertTrue(node.constructed());
        assertTrue(node.children().isEmpty());
        assertEquals(0, node.valueLength());
        assertFalse(node.indefiniteLength());
    }
}
