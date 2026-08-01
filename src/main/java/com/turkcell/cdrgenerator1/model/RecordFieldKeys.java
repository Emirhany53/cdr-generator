package com.turkcell.cdrgenerator1.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Assigns each field of one SEQUENCE/SET body a key that is unique WITHIN that
 * body, so a generated record can hold a separate value per field.
 *
 * <p>The record is a {@code Map} keyed per field, and the obvious key - the
 * field name - is not unique: 26 bodies across 23 of the 808 modules declare the
 * same name twice or more under DIFFERENT tags. {@code IMSTCELLCDRS} has
 * {@code eventTypeContentLength} at both [7] and [41], {@code BroadSoftEnriched}
 * has {@code startTime} at [8] and [33], and {@code KKTCellISOforGSN} repeats
 * {@code reserved} seventeen times. Keying by name alone meant the second field
 * overwrote the first while the record was being built, and then the encoder -
 * which looks each field up by the same name - wrote that one value under BOTH
 * tags. The record stayed structurally valid but carried duplicated data, and
 * where the two fields had different types it could not even be encoded.</p>
 *
 * <p>The first field of a given name keeps the bare name, so records written by
 * hand (and every body without duplicates, which is the overwhelming majority)
 * are unaffected. Only the second and later occurrences are suffixed.</p>
 *
 * <p>Both sides of the pipeline must agree on these keys, so
 * {@code CdrRecordBuilder} and {@code BerEncoderService} derive them from the
 * same ordered field list through this class.</p>
 */
public final class RecordFieldKeys {

    /** Separates a repeated field name from its occurrence number. */
    public static final String DISAMBIGUATOR = "#";

    private RecordFieldKeys() {
    }

    /**
     * Keys for {@code fields}, positionally aligned with it.
     *
     * @param fields the direct members of one body, in declaration order
     */
    public static List<String> forFields(List<AsnField> fields) {
        if (Objects.isNull(fields) || fields.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> seenSoFar = new HashMap<>();
        List<String> keys = new ArrayList<>(fields.size());
        for (AsnField field : fields) {
            String name = field.getFieldName();
            int occurrence = seenSoFar.merge(name, 1, Integer::sum) - 1;
            keys.add(occurrence == 0 ? name : name + DISAMBIGUATOR + occurrence);
        }
        return keys;
    }
}
