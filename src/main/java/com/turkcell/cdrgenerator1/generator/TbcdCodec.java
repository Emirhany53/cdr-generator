package com.turkcell.cdrgenerator1.generator;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 3GPP TS 29.002 TBCD (Telephony Binary Coded Decimal) kodlayici/cozucu.
 * MSISDN, IMSI, IMEI gibi rakam dizilerini yarim-bayt (nibble) paketler:
 * her ciftte rakam sirasi ters cevrilir, tek sayida rakamda son nibble
 * 'F' ile doldurulur. Ornek: "905321112233" -> "098523111132F3"... vb.
 */
@Component
public class TbcdCodec {

    private static final String PADDING_NIBBLE = "F";
    private static final Pattern DIGITS_ONLY = Pattern.compile("^[0-9]+$");
    private static final Pattern TBCD_HEX = Pattern.compile("^([0-9A-Fa-f]{2})*$");

    /** Rakam dizisini TBCD hex string'e paketler. */
    public Optional<String> encode(String digits) {
        if (Objects.isNull(digits) || !DIGITS_ONLY.matcher(digits).matches()) {
            return Optional.empty();
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < digits.length(); i += 2) {
            char low = digits.charAt(i);
            char high = (i + 1 < digits.length()) ? digits.charAt(i + 1) : PADDING_NIBBLE.charAt(0);
            result.append(high).append(low);
        }
        return Optional.of(result.toString().toUpperCase());
    }

    /** TBCD hex string'i rakam dizisine cozer, dolgu nibble'i ('F') atar. */
    public Optional<String> decode(String hex) {
        if (Objects.isNull(hex) || !TBCD_HEX.matcher(hex).matches()) {
            return Optional.empty();
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            char high = hex.charAt(i);
            char low = hex.charAt(i + 1);
            digits.append(low);
            if (Character.toUpperCase(high) != PADDING_NIBBLE.charAt(0)) {
                digits.append(high);
            }
        }
        return Optional.of(digits.toString());
    }

    /**
     * Alan TBCD kabul edilmeli mi? Adi bir abone-numarasi kuralina benziyor
     * (msisdn/imsi/imei) ve SIZE'i ASCII-hex'e sigmayacak kadar kucuk.
     */
    /**
     * Alan TBCD kabul edilmeli mi? Belirleyici olan alan adidir (msisdn/imsi/
     * imei/subscriber-number gibi); SIZE burada ayirt edici degildir cunku
     * gercek semalarda 15-18 bayt arasinda genis bir yelpazede goruluyor
     * (bazilari dolgu payi birakiyor, bazilari daha siki). Yanlis pozitif
     * riski dusuktur cunku bu metot yalnizca OCTET STRING alanlarda ve
     * yalnizca bu dar isim kumesiyle eslesince cagrilir.
     */
    public boolean isLikelyTbcd(String fieldName, Integer byteLength) {
        if (Objects.isNull(fieldName) || Objects.isNull(byteLength) || byteLength <= 0) {
            return false;
        }
        String normalized = fieldName.toLowerCase();
        return normalized.contains("msisdn") || normalized.contains("imsi")
                || normalized.contains("imei") || normalized.contains("subscribernumber")
                || normalized.contains("subscribermsisdn");
    }
}