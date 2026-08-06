package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.generator.source.AiValueSource;
import com.turkcell.cdrgenerator1.generator.source.RandomValueSource;
import com.turkcell.cdrgenerator1.generator.source.UserProvidedValueSource;
import com.turkcell.cdrgenerator1.generator.source.ValueSource;
import com.turkcell.cdrgenerator1.generator.validation.FieldValueValidator;

import java.util.List;

/**
 * Testler icin deger kaynagi zinciri kurar. Kural listesi bos birakilir,
 * boylece uretim yalnizca ASN.1 tipine ve SIZE kisitina gore yapilir.
 */
final class TestValueSources {

    private TestValueSources() {
    }

    static List<ValueSource> chain() {
        AiConfigProperties properties = new AiConfigProperties();
        AsnSizeExtractor sizeExtractor = new AsnSizeExtractor();
        BcdTimestampFactory bcdTimestampFactory = new BcdTimestampFactory();
        TbcdCodec tbcdCodec = new TbcdCodec();

        FieldValueValidator validator =
                new FieldValueValidator(tbcdCodec,properties, sizeExtractor, bcdTimestampFactory);
        FieldValueGenerator generator =
                new FieldValueGenerator(tbcdCodec,properties, sizeExtractor, bcdTimestampFactory);

        return List.of(
                new UserProvidedValueSource(),
                new AiValueSource(validator, new EnumValueResolver(), properties),
                new RandomValueSource(generator));
    }
}