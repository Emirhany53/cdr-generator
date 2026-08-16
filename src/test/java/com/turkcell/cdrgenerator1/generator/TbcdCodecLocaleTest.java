package com.turkcell.cdrgenerator1.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The TBCD field-name test must not depend on the host's locale.
 *
 * <h2>What went wrong</h2>
 *
 * <p>{@code isLikelyTbcd} folded the field name with {@code toLowerCase()} - no
 * locale. On a Turkish JVM, and this service runs on Turkish hosts,
 * {@code 'I'} lowercases to U+0131, the dotless {@code ı}. So
 * {@code "servedMSISDN"} became {@code "servedmsısdn"} and matched none of the
 * tokens the method looks for. Every token carrying an {@code i} - {@code msisdn},
 * {@code imsi}, {@code imei} - was dead; {@code callingparty} and
 * {@code otherparty} kept working, which is why the failure looked partial
 * rather than total.</p>
 *
 * <p>Measured on a {@code tr_TR} JVM before the fix: of 194 candidate OCTET
 * STRING leaves across 77 modules, 68 were recognised. Three call sites read
 * this answer - {@code FieldValueGenerator} packs the digits,
 * {@code FieldValueValidator} checks them, and {@code CdrPromptBuilder} tells
 * the model to pack them - so on those 126 fields all three quietly did the
 * wrong thing, and only on Turkish machines.</p>
 *
 * <p>A test that simply called {@code isLikelyTbcd} would have passed on an
 * English CI. These run the fold under the Turkish locale explicitly, so the
 * bug cannot come back unnoticed on any host.</p>
 */
class TbcdCodecLocaleTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private final TbcdCodec codec = new TbcdCodec();

    /** Runs a body under a given default locale, restoring whatever was there. */
    private void withDefaultLocale(Locale locale, Runnable body) {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(locale);
            body.run();
        } finally {
            Locale.setDefault(original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"servedMSISDN", "SERVEDMSISDN", "servedIMSI", "servedIMEISV",
            "subscriberMSISDN", "callingPartyNumber", "otherParty", "cAMELDestinationNumber"})
    void aSubscriberNumberFieldIsRecognisedUnderTheTurkishLocale(String fieldName) {
        withDefaultLocale(TURKISH, () ->
                assertThat(codec.isLikelyTbcd(fieldName, 9))
                        .as("%s must be recognised as TBCD on a Turkish host too", fieldName)
                        .isTrue());
    }

    /** The same names, judged the same way, on a locale where I/i is unremarkable. */
    @ParameterizedTest
    @ValueSource(strings = {"servedMSISDN", "servedIMSI", "servedIMEISV"})
    void theAnswerIsTheSameUnderEnglish(String fieldName) {
        withDefaultLocale(Locale.ENGLISH, () ->
                assertThat(codec.isLikelyTbcd(fieldName, 9)).isTrue());
    }

    /** Widening the fold must not start matching fields that are not numbers. */
    @ParameterizedTest
    @ValueSource(strings = {"duration", "recordSequenceNumber", "causeForRecClosing",
            "accessPointNameNI", "transactionCurrency"})
    void anUnrelatedFieldIsStillNotTbcd(String fieldName) {
        withDefaultLocale(TURKISH, () ->
                assertThat(codec.isLikelyTbcd(fieldName, 9)).isFalse());
    }

    /**
     * The packed value is hex, so uppercasing it must not turn an {@code i} into
     * {@code İ} either. The digits here can only be 0-9 and the pad nibble F, so
     * this is a guard rather than a repair - it pins the encoder's output as
     * locale-independent alongside the name test.
     */
    @Test
    void thePackedValueIsTheSameUnderEitherLocale() {
        String[] packed = new String[2];
        withDefaultLocale(TURKISH, () -> packed[0] = codec.encode("05301234567").orElseThrow());
        withDefaultLocale(Locale.ENGLISH, () -> packed[1] = codec.encode("05301234567").orElseThrow());

        assertThat(packed[0]).isEqualTo(packed[1]).isEqualTo("5003214365F7");
        assertThat(codec.decode(packed[0])).contains("05301234567");
    }
}
