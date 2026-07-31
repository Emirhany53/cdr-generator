package com.turkcell.cdrgenerator1.generator.validation;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.generator.BcdTimestampFactory;
import com.turkcell.cdrgenerator1.generator.TbcdCodec;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production'da yakalanan gercek hata: AI, SIZE'i olmayan (dolayisiyla
 * BCD/TBCD adi da eslesmeyen) bir OCTET STRING alana "41253"/"90212" gibi
 * duz, tek uzunluklu sayilar dondurdu. Eskiden matchesOctetString bunlari
 * "uyumlu" sayiyordu, deger encoder'a kadar ilerliyor ve orada
 * BerEncodingException ile pathliyordu - halbuki burada reddedilip zincirin
 * rastgele uretime (her zaman gecerli hex dokumu uretir) dusmesi gerekiyordu.
 */
class FieldValueValidatorTest {

    private FieldValueValidator validator;

    @BeforeEach
    void setUp() {
        AiConfigProperties properties = new AiConfigProperties();
        validator = new FieldValueValidator(
                new TbcdCodec(), properties, new AsnSizeExtractor(), new BcdTimestampFactory());
    }

    private AsnField field(String name, String type) {
        return AsnField.builder().fieldName(name).fieldType(type)
                .tagNumber(1).tagClass(BerTagClass.CONTEXT).build();
    }

    @Test
    void anOddLengthNumericValueIsRejectedForAPlainOctetString() {
        // Seen live: cAMELDestinationNumber (OCTET STRING, no SIZE, not a
        // msisdn/imsi/imei/subscriberNumber name) got "41253" from the AI.
        assertFalse(validator.isValid(field("cAMELDestinationNumber", "OCTET STRING"), "41253"));
        assertFalse(validator.isValid(field("someField", "OCTET STRING"), "90212"));
    }

    @Test
    void aWellFormedHexDumpIsAcceptedForAPlainOctetString() {
        assertTrue(validator.isValid(field("someField", "OCTET STRING"), "0A0B"));
        assertTrue(validator.isValid(field("someField", "OCTET STRING"), "DEADBEEF"));
    }

    @Test
    void aSingleNonHexCharacterIsRejected() {
        assertFalse(validator.isValid(field("someField", "OCTET STRING"), "ZZ"));
    }
}
