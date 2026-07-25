package com.turkcell.cdrgenerator1.generator.source;

import com.turkcell.cdrgenerator1.model.AsnField;

import java.util.Optional;

public interface ValueSource {

    /** Kucuk deger once calisir. Araya halka eklenebilsin diye 10'ar artar. */
    int getOrder();

    Optional<String> resolve(ValueSourceContext context, AsnField field);
}