package com.turkcell.cdrgenerator1.generator;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ENUMERATED ve isimli-sabitli INTEGER alanlarda AI'in dondurdugu ismi
 * (ornek: "originating") ASN.1 tanimindaki sayiya (ornek: "0") cevirir.
 *
 * fieldType govdesi AsnFieldTreeResolver tarafindan "TIP{ad(sayi),ad(sayi)}"
 * seklinde sikistirilmis olarak gelir; bu sinif o govdeyi okur.
 */
@Component
public class EnumValueResolver {

    private static final Pattern NAMED_NUMBER_ENTRY = Pattern.compile(
            "([A-Za-z][\\w-]*)\\s*\\(\\s*(-?\\d+)\\s*\\)");
    private static final String NUMERIC_LITERAL_PATTERN = "^-?\\d+$";

    private static final String TRUE_WORD = "true";
    private static final String FALSE_WORD = "false";



    /**
     * fieldType govdesinden isim->sayi haritasi cikarir. Govdede hic
     * "ad(sayi)" cifti yoksa bos harita doner (ornek: SIZE kisitli duz tipler).
     */
    public Map<String, Integer> extractNamedNumbers(String fieldType) {
        Map<String, Integer> namedNumbers = new LinkedHashMap<>();
        if (Objects.isNull(fieldType)) {
            return namedNumbers;
        }
        Matcher matcher = NAMED_NUMBER_ENTRY.matcher(fieldType);
        while (matcher.find()) {
            namedNumbers.put(matcher.group(1), Integer.valueOf(matcher.group(2)));
        }
        return namedNumbers;
    }

    /**
     * BOOLEAN alanlar icin AI'in dondurdugu "true"/"false" sozcuklerini
     * BER'in bekledigi "1"/"0" degerine cevirir. Deger zaten 0/1 ise dokunmaz.
     */
    public Optional<String> resolveBoolean(String value) {
        if (Objects.isNull(value)) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (TRUE_WORD.equals(normalized)) {
            return Optional.of("1");
        }
        if (FALSE_WORD.equals(normalized)) {
            return Optional.of("0");
        }
        return Optional.of(value);
    }

    /**
     * Verilen degeri sayiya cevirir. Deger zaten sayiysa oldugu gibi doner.
     * Isimse, once tam eslesme, sonra buyuk/kucuk harf duyarsiz eslesme aranir.
     * Hicbiri tutmazsa bos doner ve cagiran taraf reddetmelidir.
     */
    public Optional<String> resolveToNumber(String fieldType, String value) {
        if (Objects.isNull(value) || value.matches(NUMERIC_LITERAL_PATTERN)) {
            return Optional.ofNullable(value);
        }
        Map<String, Integer> namedNumbers = extractNamedNumbers(fieldType);
        if (namedNumbers.isEmpty()) {
            return Optional.empty();
        }
        if (namedNumbers.containsKey(value)) {
            return Optional.of(String.valueOf(namedNumbers.get(value)));
        }
        return namedNumbers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(value))
                .map(entry -> String.valueOf(entry.getValue()))
                .findFirst();
    }
}