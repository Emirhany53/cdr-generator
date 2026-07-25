package com.turkcell.cdrgenerator1.controller;

import com.turkcell.cdrgenerator1.ai.AiFieldValueProvider;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.service.AiRecordSupplier;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Controller testleri icin yapay zeka bagimliliklarini kurar.
 * Saglayici bos donduruldugu icin testler hicbir dis servise cikmaz;
 * deger uretimi zincirin rastgele halkasina duser.
 */
final class TestAiSupport {

    private TestAiSupport() {
    }

    static AiRecordSupplier disabledSupplier(AiConfigProperties properties) {
        return new AiRecordSupplier(properties, emptyProvider());
    }

    static ObjectProvider<AiFieldValueProvider> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public AiFieldValueProvider getObject(Object... args) {
                return null;
            }

            @Override
            public AiFieldValueProvider getIfAvailable() {
                return null;
            }

            @Override
            public AiFieldValueProvider getIfUnique() {
                return null;
            }

            @Override
            public AiFieldValueProvider getObject() {
                return null;
            }
        };
    }
}