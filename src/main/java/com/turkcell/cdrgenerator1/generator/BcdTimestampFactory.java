package com.turkcell.cdrgenerator1.generator;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
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
    private static final String DATE_NAME_TOKEN = "date";
    private static final String BCD_DATE_PATTERN = "^[0-9A-Fa-f]{6}$";
    private static final int BCD_DATE_BYTE_LENGTH = 3;
    private static final DateTimeFormatter BCD_DATE_DIGITS = DateTimeFormatter.ofPattern("yyMMdd");

    // Kayit-ici kronoloji: bir kaydin cesitli zaman damgalari tek bir "kayit ani"
    // (recordAnchor, en gec olay) etrafinda turetilir; erken olaylar bu andan
    // geriye dogru cikarilir. Boylece ayni kayitta serviceDeliveryStart <= End
    // her zaman saglanir. Offset'ler cagri basina degil ANCHOR'dan tohumlanmis
    // Random ile uretilir, yani ayni anchor icin START ve END bagimsiz
    // cagrilarda ayni sureyi gorur - tutarlilik kurulusla garanti.
    private static final int MAX_RING_SECONDS = 15;        // istek -> teslim baslangici (calma)
    private static final int MAX_DURATION_SECONDS = 3600;  // baslangic -> bitis (oturum suresi)
    private static final int MAX_CLOSE_DELAY_SECONDS = 6;  // bitis -> kayit kapanisi (0..5)

    /**
     * Bir zaman damgasi alaninin kayit icindeki kronolojik rolu. Ad benzerligiyle
     * saptanir; bilinen 3GPP alanlari (serviceRequestTimeStamp,
     * serviceDeliveryStart/EndTimeStamp, recordOpeningTime, recordClosureTime)
     * disindaki her sey GENERIC olur ve anchor anini alir.
     */
    private enum TimestampRole { REQUEST, OPENING, START, END, CLOSURE, GENERIC }

    /** Yakin gecmiste rastgele bir ana ait BCD zaman damgasi. */
    public String randomTimestamp() {
        return toBcd(randomRecentDateTime());
    }

    /**
     * Bir kayit icin tek bir "kayit ani" uretir: yakin gecmiste, en gec olay
     * (recordClosureTime) icin referans. Kaydin diger zaman damgalari bu andan
     * {@link #timestampAt} ile turetilir. Kayit basina bir kez cagrilir.
     */
    public LocalDateTime newRecordAnchor() {
        return randomRecentDateTime();
    }

    /**
     * Kayit anindan (anchor) ve alan adindan, alanin kronolojik rolune gore
     * geriye kaydirilmis BCD zaman damgasi. Ayni anchor icin
     * serviceDeliveryStartTimeStamp her zaman serviceDeliveryEndTimeStamp'ten
     * once (veya esit) dusecek sekilde uretilir.
     */
    public String timestampAt(LocalDateTime recordAnchor, String fieldName) {
        return toBcd(recordAnchor.minusSeconds(secondsBeforeAnchor(recordAnchor, fieldName)));
    }

    /**
     * Kayit anindan uretilen BCD tarih. Ayni kayittaki tarih alanlari boylece
     * ayni gunu gosterir.
     */
    public String dateAt(LocalDateTime recordAnchor) {
        return recordAnchor.format(BCD_DATE_DIGITS);
    }

    private long secondsBeforeAnchor(LocalDateTime anchor, String fieldName) {
        // Tohum anchor'in kendisinden: ayni kayit icin ring/duration/closeDelay
        // sabit kalir, boylece START ve END ayri cagrilarda uyumlu olur.
        Random seeded = new Random(anchor.toEpochSecond(ZoneOffset.UTC));
        long ring = 1L + seeded.nextInt(MAX_RING_SECONDS);
        long duration = 1L + seeded.nextInt(MAX_DURATION_SECONDS);
        long closeDelay = seeded.nextInt(MAX_CLOSE_DELAY_SECONDS);
        return switch (roleOf(fieldName)) {
            case CLOSURE, GENERIC -> 0L;
            case END -> closeDelay;
            case START -> closeDelay + duration;
            case REQUEST, OPENING -> closeDelay + duration + ring;
        };
    }

    private TimestampRole roleOf(String fieldName) {
        String name = Objects.isNull(fieldName) ? "" : fieldName.toLowerCase(Locale.ROOT);
        if (name.contains("request")) {
            return TimestampRole.REQUEST;
        }
        if (name.contains("opening") || name.contains("open")) {
            return TimestampRole.OPENING;
        }
        if (name.contains("closure") || name.contains("closing") || name.contains("close")) {
            return TimestampRole.CLOSURE;
        }
        if (name.contains("start")) {
            return TimestampRole.START;
        }
        if (name.contains("end") || name.contains("stop") || name.contains("release")) {
            return TimestampRole.END;
        }
        return TimestampRole.GENERIC;
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

    public boolean isBcdDate(String fieldName, Integer byteLength) {
        boolean nameLooksLikeDate = Objects.nonNull(fieldName)
                && fieldName.toLowerCase(Locale.ROOT).contains(DATE_NAME_TOKEN);
        boolean hasDateLength = Objects.nonNull(byteLength) && byteLength == BCD_DATE_BYTE_LENGTH;
        return nameLooksLikeDate && hasDateLength;
    }

    /** Yakin gecmiste rastgele bir gune ait BCD tarih (yyMMdd, 6 hex). */
    public String randomDate() {
        return randomRecentDateTime().format(BCD_DATE_DIGITS);
    }

    /** Verilen degerin gecerli bir BCD tarih bicimine (6 hex) sahip olup olmadigi. */
    public boolean isValidBcdDate(String value) {
        return Objects.nonNull(value) && value.matches(BCD_DATE_PATTERN);
    }
}