package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.ai.AiFieldValueProvider;
import com.turkcell.cdrgenerator1.ai.model.AiGenerationRequest;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.model.AsnField;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiRecordSupplier {

    private static final String PATH_SEPARATOR = ".";
    private static final String EMPTY_PREFIX = "";
    private static final int ROOT_DEPTH = 0;
    private static final int MAX_DEPTH = 10;

    private final AiConfigProperties aiConfigProperties;
    private final ObjectProvider<AiFieldValueProvider> aiFieldValueProvider;

    public List<Map<String, String>> supply(String structureName,
                                            List<AsnField> rootFields,
                                            Map<String, String> userProvidedValues,
                                            int recordCount) {
        if (!aiConfigProperties.isEnabled()) {
            log.debug("Yapay zeka devre disi, rastgele uretim kullanilacak.");
            return List.of();
        }
        AiFieldValueProvider provider = aiFieldValueProvider.getIfAvailable();
        if (Objects.isNull(provider)) {
            log.warn("Yapay zeka etkin ancak '{}' saglayicisi icin bean bulunamadi.",
                    aiConfigProperties.getProvider());
            return List.of();
        }

        // Agac duzlestirilir; AI yalnizca yaprak alanlar icin deger uretir.
        // Yollar indekssizdir (addr.msisdn); repeated dallarda CdrRecordBuilder
        // indeksli yolu bulamayinca ciplak alan adina duser.
        List<PathedField> leafFields = new ArrayList<>();
        collectLeaves(rootFields, EMPTY_PREFIX, leafFields, ROOT_DEPTH);

        List<AsnField> fieldsToFill = leafFields.stream()
                .filter(pathed -> !hasUserValue(userProvidedValues, pathed))
                .map(PathedField::asPathNamedField)
                .limit(aiConfigProperties.getMaxFieldsPerRequest())
                .toList();

        if (fieldsToFill.isEmpty()) {
            log.debug("Doldurulacak alan kalmadi, yapay zeka cagrilmayacak.");
            return List.of();
        }
        log.debug("Yapay zekaya gonderilecek yaprak alan sayisi: {}", fieldsToFill.size());
        return supplyInBatches(provider, structureName, fieldsToFill, userProvidedValues, recordCount);
    }

    private void collectLeaves(List<AsnField> fields, String prefix,
                               List<PathedField> target, int depth) {
        if (Objects.isNull(fields) || depth > MAX_DEPTH) {
            return;
        }
        fields.forEach(field -> {
            final String path = buildPath(prefix, field.getFieldName());
            if (isConstructed(field)) {
                collectLeaves(field.getChildren(), path, target, depth + 1);
                return;
            }
            target.add(new PathedField(field, path));
        });
    }

    private boolean isConstructed(AsnField field) {
        return Objects.nonNull(field.getChildren()) && !field.getChildren().isEmpty();
    }

    private String buildPath(String prefix, String fieldName) {
        return prefix.isEmpty() ? fieldName : prefix + PATH_SEPARATOR + fieldName;
    }

    private boolean hasUserValue(Map<String, String> userProvidedValues, PathedField pathed) {
        if (Objects.isNull(userProvidedValues)) {
            return false;
        }
        return userProvidedValues.containsKey(pathed.path())
                || userProvidedValues.containsKey(pathed.field().getFieldName());
    }

    private List<Map<String, String>> supplyInBatches(AiFieldValueProvider provider,
                                                      String structureName,
                                                      List<AsnField> fieldsToFill,
                                                      Map<String, String> fixedValues,
                                                      int recordCount) {
        List<Map<String, String>> allRecords = new ArrayList<>(recordCount);
        final int batchSize = aiConfigProperties.getBatchSize();

        for (int produced = 0; produced < recordCount; produced += batchSize) {
            int currentBatch = Math.min(batchSize, recordCount - produced);

            AiGenerationRequest request = AiGenerationRequest.builder()
                    .structureName(structureName)
                    .fieldsToFill(fieldsToFill)
                    .fixedValues(fixedValues)
                    .recordCount(currentBatch)
                    .build();

            allRecords.addAll(provider.generate(request).getRecords());
        }
        log.info("Yapay zeka toplam {} kayit dondurdu, istenen {}.", allRecords.size(), recordCount);
        return allRecords;
    }

    /** Yaprak alan ve agactaki tam yolu. */
    private record PathedField(AsnField field, String path) {

        /**
         * Alani, adi tam yol olacak sekilde kopyalar. AI bu adla deger doner,
         * CdrRecordBuilder de ayni yolla arar.
         */
        AsnField asPathNamedField() {
            return field.toBuilder().fieldName(path).build();
        }
    }
}