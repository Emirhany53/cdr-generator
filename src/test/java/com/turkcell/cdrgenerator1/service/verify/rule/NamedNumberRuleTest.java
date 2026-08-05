package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NamedNumberRuleTest {

    private final NamedNumberRule rule = new NamedNumberRule();
    private final TlvReader reader = new TlvReader();

    private static byte[] hex(String hex) {
        hex = hex.replaceAll("\\s+", "");
        byte[] ans = new byte[hex.length() / 2];
        for (int i = 0; i < ans.length; i++) {
            ans[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return ans;
    }

    @Test
    void acceptsValueInNamedNumberSet() {
        byte[] data = hex("80 01 00");
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldType("ENUMERATED{originating(0), terminating(1)}").build();
        VerificationContext context = new VerificationContext("Test", data);

        rule.check(new NodeContext(node, field, false), context);

        assertThat(context.findings()).isEmpty();
    }

    @Test
    void reportsValueNotInSet() {
        byte[] data = hex("80 01 05");
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldType("ENUMERATED{originating(0), terminating(1)}").build();
        VerificationContext context = new VerificationContext("Test", data);

        rule.check(new NodeContext(node, field, false), context);

        assertThat(context.findings()).hasSize(1);
        assertThat(context.findings().get(0).severity()).isEqualTo(FindingSeverity.ERROR);
        assertThat(context.findings().get(0).message()).contains("not in named numbers");
    }

    @Test
    void reportsLargeValueNotInSet() {
        byte[] data = hex("80 03 01 4C 2E");
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldType("INTEGER{home(0), visiting(1), roaming(2)}").build();
        VerificationContext context = new VerificationContext("Test", data);

        rule.check(new NodeContext(node, field, false), context);

        assertThat(context.findings()).hasSize(1);
        assertThat(context.findings().get(0).severity()).isEqualTo(FindingSeverity.ERROR);
    }

    @Test
    void handlesNegativeValues() {
        byte[] data = hex("80 01 FF");
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldType("INTEGER{neg(-1), zero(0)}").build();
        VerificationContext context = new VerificationContext("Test", data);

        rule.check(new NodeContext(node, field, false), context);

        assertThat(context.findings()).isEmpty();
    }

    @Test
    void skipsFieldWithoutNamedNumbers() {
        byte[] data = hex("80 01 05");
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldType("INTEGER (0..999)").build();
        VerificationContext context = new VerificationContext("Test", data);

        rule.check(new NodeContext(node, field, false), context);

        assertThat(context.findings()).isEmpty();
    }

    @Test
    void skipsConstructedNode() {
        byte[] data = hex("A0 03 80 01 00");
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldType("ENUMERATED{originating(0), terminating(1)}").build();
        VerificationContext context = new VerificationContext("Test", data);

        rule.check(new NodeContext(node, field, false), context);

        assertThat(context.findings()).isEmpty();
    }

    @Test
    void skipsNullField() {
        byte[] data = hex("80 01 00");
        TlvNode node = reader.read(data, 0);
        VerificationContext context = new VerificationContext("Test", data);

        rule.check(new NodeContext(node, null, false), context);

        assertThat(context.findings()).isEmpty();
    }

    @Test
    void skipsCollectionWrapper() {
        byte[] data = hex("80 01 05");
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldType("ENUMERATED{originating(0), terminating(1)}").build();
        VerificationContext context = new VerificationContext("Test", data);

        rule.check(new NodeContext(node, field, true), context);

        assertThat(context.findings()).isEmpty();
    }

    /**
     * Regresyon: kural, tip ifadesinin TAMAMINI {@code name(number)} icin
     * tariyordu ve KISIT sozdizimi de bu kaliba uyuyor - {@code SIZE(30)},
     * adi "SIZE" degeri 30 olan bir adlandirilmis sayi gibi okunuyordu. Sonucta
     * sabit genislikli bir metin alaninin harfleri isaretli tamsayi olarak
     * cozulup "aralik disi" diye raporlaniyordu.
     *
     * <p>Uydurma degil: AllModulesRoundTripTest 808 modulu gezdiginde
     * VRCGPRSUsage'in 55 alaninin tamami, TelcoDB, VerazCdr, SesPlus ve
     * onlarca modul bu sekilde binlerce sahte hata uretti. Adlandirilmis sayi
     * YALNIZCA suslu parantez icinde ve YALNIZCA sayisal bir tipte bulunur.</p>
     */
    @Test
    void aSizeConstraintIsNotANamedNumberList() {
        // IA5String "ABCDEFGHIJ" - sema disi hicbir sey yok, sadece metin.
        byte[] data = hex("16 0A 41 42 43 44 45 46 47 48 49 4A");
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder()
                .fieldName("cdrSequence").fieldType("IA5STRING (SIZE(30) CODE(\"LEFT\"))").build();
        VerificationContext context = new VerificationContext("Test", data);

        rule.check(new NodeContext(node, field, false), context);

        assertThat(context.findings()).isEmpty();
    }

    /** Ayni koruma sayisal olmayan her tip icin gecerli: OCTET STRING de metin tasir. */
    @Test
    void anOctetStringWithASizeConstraintIsLeftAlone() {
        byte[] data = hex("04 03 AB CD EF");
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder()
                .fieldName("payload").fieldType("OCTET STRING (SIZE(3))").build();
        VerificationContext context = new VerificationContext("Test", data);

        rule.check(new NodeContext(node, field, false), context);

        assertThat(context.findings()).isEmpty();
    }

    @Test
    void acceptsSecondValidValue() {
        byte[] data = hex("80 01 01");
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldType("ENUMERATED{originating(0), terminating(1)}").build();
        VerificationContext context = new VerificationContext("Test", data);

        rule.check(new NodeContext(node, field, false), context);

        assertThat(context.findings()).isEmpty();
    }
}
