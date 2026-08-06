package com.turkcell.cdrgenerator1.generator.source;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.generator.BcdTimestampFactory;
import com.turkcell.cdrgenerator1.generator.EnumValueResolver;
import com.turkcell.cdrgenerator1.generator.TbcdCodec;
import com.turkcell.cdrgenerator1.generator.validation.FieldValueValidator;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Madde 6: tekrarli alan elemanlarina cesitlilik. AI bir tekrarli yola tek bir
 * indekssiz deger dondurur, bu yuzden [0] ve [1] ayni degeri paylasirdi.
 * diversifyForRepeatedElement, ilk eleman disindakilerin son alfanumerik
 * karakterini eleman indeksine gore kaydirir - deger ayni uzunluk ve tipte
 * kalir ama elemanlar farklilasir.
 */
class AiValueSourceDiversityTest {

    private final AiValueSource source = new AiValueSource(
            new FieldValueValidator(new TbcdCodec(), new AiConfigProperties(),
                    new AsnSizeExtractor(), new BcdTimestampFactory()),
            new EnumValueResolver());

    private AsnField leaf(String name, String type) {
        return AsnField.builder().fieldName(name).fieldType(type)
                .tagNumber(0).tagClass(BerTagClass.CONTEXT).build();
    }

    private ValueSourceContext at(String path, List<Map<String, String>> aiRecords) {
        return new ValueSourceContext("S", 0, Map.of(), aiRecords).withCurrentPath(path);
    }

    @Test
    void firstElementKeepsTheSharedValueLaterElementsDiffer() {
        AsnField uri = leaf("uri", "IA5String");
        List<Map<String, String>> ai = List.of(Map.of("list.uri", "905321112233"));

        String first = source.resolve(at("list[0].uri", ai), uri).orElseThrow();
        String second = source.resolve(at("list[1].uri", ai), uri).orElseThrow();

        assertEquals("905321112233", first, "the first element keeps the AI value verbatim");
        assertEquals("905321112234", second, "the second element shifts the last digit by its index");
        assertNotEquals(first, second, "elements of a repeated field must not be identical");
        assertEquals(first.length(), second.length(), "diversification preserves length");
    }

    @Test
    void nestedIndicesSumIntoTheShift() {
        AsnField uri = leaf("uri", "IA5String");
        List<Map<String, String>> ai = List.of(Map.of("a.b.uri", "700000000"));

        // outer[1].inner[1] -> salt 2 -> last digit 0 -> 2
        String value = source.resolve(at("a[1].b[1].uri", ai), uri).orElseThrow();
        assertEquals("700000002", value);
    }

    @Test
    void shortValuesAreLeftUntouched() {
        AsnField code = leaf("code", "INTEGER");
        List<Map<String, String>> ai = List.of(Map.of("list.code", "12"));

        // Below the length threshold: an enum/flag-sized value must not be shifted.
        String second = source.resolve(at("list[1].code", ai), code).orElseThrow();
        assertEquals("12", second);
    }
}
