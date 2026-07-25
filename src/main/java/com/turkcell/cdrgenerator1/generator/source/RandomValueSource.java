package com.turkcell.cdrgenerator1.generator.source;

import com.turkcell.cdrgenerator1.generator.FieldValueGenerator;
import com.turkcell.cdrgenerator1.model.AsnField;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RandomValueSource implements ValueSource {

    private static final int ORDER = 30;

    private final FieldValueGenerator fieldValueGenerator;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Optional<String> resolve(ValueSourceContext context, AsnField field) {
        return Optional.of(fieldValueGenerator.generate(field));
    }
}