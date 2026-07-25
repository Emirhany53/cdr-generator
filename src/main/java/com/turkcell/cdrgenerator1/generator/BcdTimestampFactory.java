package com.turkcell.cdrgenerator1.generator;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 3GPP TS 32.298 BCD-paketli zaman damgasi uretir ve dogrular.
 *
 * Bicim: YYMMDDHHmmSS + saat dilimi isareti + saat farki + dakika farki,
 * toplam 9 bayt = 18 hex karakter. Ornek: "2607111430152B0300"
 * ('2B' = '+' isareti, '0300' = +03:00 Turkiye saat dilimi).
 */
@Component
public class BcdTimestampFactory {

    private static final DateTimeFormatter BCD_DIGITS =
            DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final String PLUS_SIGN_HEX = "2B";
    private static final String TURKEY_OFFSET_HEX = "0300";
    private static final String TIMESTAMP_NAME_TOKEN = "time";
    private static final String BCD_HEX_PATTERN = "^[0-9A-Fa-f]{18}$";
    private static final int BCD_TIMESTAMP_BYTE_LENGTH = 9;
    private static final int MAX_PAST_DAYS = 30;
    private static final int HOURS_IN_DAY = 24;
    private static final int MINUTES_IN_HOUR = 60;

    /** Yakin gecmiste rastgele bir ana ait BCD zaman damgasi. */
    public String randomTimestamp() {
        return toBcd(randomRecentDateTime());
    }

    /** Verilen andan uretilen BCD zaman damgasi. */
    public String toBcd(LocalDateTime dateTime) {
        return dateTime.format(BCD_DIGITS) + PLUS_SIGN_HEX + TURKEY_OFFSET_HEX;
    }

    /**
     * Alan bir 3GPP BCD zaman damgasi mi? OCTET STRING (SIZE(9)) olan ve adinda
     * 'time' gecen alanlar BCD kabul edilir. Karar tek yerde tutulur; hem
     * uretici hem dogrulayici bu metodu kullanir.
     */
    public boolean isBcdTimestamp(String fieldName, Integer byteLength) {
        boolean nameLooksLikeTime = Objects.nonNull(fieldName)
                && fieldName.toLowerCase(Locale.ROOT).contains(TIMESTAMP_NAME_TOKEN);
        boolean hasBcdLength = Objects.nonNull(byteLength) && byteLength == BCD_TIMESTAMP_BYTE_LENGTH;
        return nameLooksLikeTime && hasBcdLength;
    }

    /** Verilen degerin gecerli BCD zaman damgasi bicimine sahip olup olmadigi. */
    public boolean isValidBcd(String value) {
        return Objects.nonNull(value) && value.matches(BCD_HEX_PATTERN);
    }

    private LocalDateTime randomRecentDateTime() {
        return LocalDateTime.now()
                .minusDays(ThreadLocalRandom.current().nextInt(MAX_PAST_DAYS))
                .minusHours(ThreadLocalRandom.current().nextInt(HOURS_IN_DAY))
                .minusMinutes(ThreadLocalRandom.current().nextInt(MINUTES_IN_HOUR))
                .withNano(0);
    }
}