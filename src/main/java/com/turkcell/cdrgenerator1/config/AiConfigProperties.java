package com.turkcell.cdrgenerator1.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Yapay zeka katmaninin tum konfigurasyonu.
 * Telefon prefiksi, regex, model adi gibi hicbir sabit kodda yer almaz.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.cdr.ai")
public class AiConfigProperties {

    /** camelCase gecisleri ve ayrac karakterleri kelime siniri sayilir. */
    private static final Pattern WORD_BOUNDARY = Pattern.compile(
            "(?<=[a-z0-9])(?=[A-Z])|[_.\\-\\[\\]]+");

    /**
     * Bu uzunlugun altindaki match ifadeleri yalnizca TAM kelime olarak eslesir.
     * Daha uzun ifadeler (ornek: "servedmsisdn") alt dize olarak da aranir,
     * cunku birlesik yazilmis alan adlarini yakalamak icin gereklidir.
     */
    private static final int MIN_SUBSTRING_MATCH_LENGTH = 6;

    /** Kill switch. false ise sistem tamamen rastgele uretime doner. */
    private boolean enabled;

    /** Aktif saglayici. Gemini adapter'i yalnizca "gemini" degerinde yuklenir. */
    private String provider;

    private long timeoutMs;
    private int maxRetries;
    private long retryBackoffMs;
    private int maxFieldsPerRequest;
    private int batchSize;

    private Gemini gemini = new Gemini();
    private List<FieldRule> fieldRules = new ArrayList<>();

    /** octet-string-content degerleri. */
    private static final String TEXT_CONTENT = "text";
    private static final String BINARY_CONTENT = "binary";

    @Data
    public static class Gemini {
        private String baseUrl;
        private String model;
        private String apiKey;
        private double temperature;
    }

    @Data
    public static class FieldRule {
        private String name;
        private List<String> match = new ArrayList<>();
        private String description;
        private String pattern;
        private List<String> examples = new ArrayList<>();
        /**
         * Bu kuralin dustugu bir OCTET STRING alanin icerigi ne tasir:
         * {@code text} ise deger yazdirilabilir ASCII metindir ve baytlari o
         * metnin ASCII karsiligidir; {@code binary} ise deger zaten paketlenmis
         * baytlarin hex dokumudur. Bos birakilirsa uretici bicime bakip karar
         * verir (sigiyorsa ASCII, aksi halde hex).
         *
         * <p>Ayrimi konfigurasyona tasiyan sey su: EMM'in kabul ettigi MMTel
         * yakalamasinda octet-string alanlarin cogu ASCII metin tasiyor
         * (userLocationInformation "81821600d0eaef0d", serviceContextID
         * "10.32275@3gpp.org"), ama hepsi degil - bu yuzden tahmin yerine alan
         * bazinda soylenebilmeli.</p>
         */
        private String octetStringContent;

        /** OCTET STRING icerigi ASCII metin mi? */
        public boolean isTextContent() {
            return TEXT_CONTENT.equalsIgnoreCase(octetStringContent);
        }

        /** OCTET STRING icerigi paketlenmis ikili veri (hex dokumu) mu? */
        public boolean isBinaryContent() {
            return BINARY_CONTENT.equalsIgnoreCase(octetStringContent);
        }
    }

    /**
     * Alan adina uyan ILK kurali doner. Siralama onemlidir.
     *
     * Eslesme alan adinin KELIMELERI uzerinden yapilir: ad camelCase ve ayrac
     * sinirlarindan bolunur, parcalar kucuk harfe cevrilir ve match ifadeleriyle
     * tam olarak karsilastirilir. Boylece 'transactionCurrency' alani, icinde
     * 'tac' harf dizisi gectigi icin cellId kuralina takilmaz.
     */
    public Optional<FieldRule> findRuleFor(String fieldName) {
        if (Objects.isNull(fieldName)) {
            return Optional.empty();
        }
        Set<String> words = splitIntoWords(fieldName);
        final String normalizedFieldName = fieldName.toLowerCase(Locale.ROOT);

        return fieldRules.stream()
                .filter(rule -> matches(rule, words, normalizedFieldName))
                .findFirst();
    }

    private boolean matches(FieldRule rule, Set<String> words, String normalizedFieldName) {
        return rule.getMatch().stream().anyMatch(candidate -> {
            final String normalizedCandidate = candidate.toLowerCase(Locale.ROOT);
            if (words.contains(normalizedCandidate)) {
                return true;
            }
            return normalizedCandidate.length() >= MIN_SUBSTRING_MATCH_LENGTH
                    && normalizedFieldName.contains(normalizedCandidate);
        });
    }

    private Set<String> splitIntoWords(String fieldName) {
        return Arrays.stream(WORD_BOUNDARY.split(fieldName))
                .filter(word -> !word.isBlank())
                .map(word -> word.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    public boolean isGeminiConfigured() {
        return Objects.nonNull(gemini.getApiKey()) && !gemini.getApiKey().isBlank();
    }
}