package com.turkcell.cdrgenerator1.generator;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Madde 4: kayit-ici zaman tutarliligi. Bir kaydin zaman damgalari tek bir
 * anchor'dan turetildigi icin serviceDeliveryStart <= End (ve butun 3GPP olay
 * zinciri) her zaman kronolojik sirada olmalidir.
 */
class BcdTimestampFactoryTest {

    private final BcdTimestampFactory factory = new BcdTimestampFactory();

    // BCD zaman damgasinin yyMMddHHmmss on eki. Sifir dolgulu oldugu icin
    // sozluksel siralama == kronolojik siralama (yuzyil sarmasi haric, ki bir
    // kaydin ~1 saatlik penceresinde olmaz).
    private String chrono(String bcd) {
        return bcd.substring(0, 12);
    }

    @Test
    void deliveryStartIsNeverAfterDeliveryEndForOneAnchor() {
        LocalDateTime anchor = factory.newRecordAnchor();
        String start = factory.timestampAt(anchor, "serviceDeliveryStartTimeStamp");
        String end = factory.timestampAt(anchor, "serviceDeliveryEndTimeStamp");
        assertTrue(chrono(start).compareTo(chrono(end)) <= 0,
                "start " + start + " must not be after end " + end);
    }

    @Test
    void theFullEventChainStaysOrderedAcrossManyAnchors() {
        for (int i = 0; i < 500; i++) {
            LocalDateTime anchor = factory.newRecordAnchor();
            String request = chrono(factory.timestampAt(anchor, "serviceRequestTimeStamp"));
            String opening = chrono(factory.timestampAt(anchor, "recordOpeningTime"));
            String start = chrono(factory.timestampAt(anchor, "serviceDeliveryStartTimeStamp"));
            String end = chrono(factory.timestampAt(anchor, "serviceDeliveryEndTimeStamp"));
            String closure = chrono(factory.timestampAt(anchor, "recordClosureTime"));

            assertTrue(request.compareTo(start) <= 0, "request>start at anchor " + anchor);
            assertTrue(opening.compareTo(start) <= 0, "opening>start at anchor " + anchor);
            assertTrue(start.compareTo(end) <= 0, "start>end at anchor " + anchor);
            assertTrue(end.compareTo(closure) <= 0, "end>closure at anchor " + anchor);
        }
    }

    @Test
    void sameAnchorAndFieldProducesTheSameValue() {
        LocalDateTime anchor = factory.newRecordAnchor();
        assertEquals(factory.timestampAt(anchor, "serviceDeliveryEndTimeStamp"),
                factory.timestampAt(anchor, "serviceDeliveryEndTimeStamp"),
                "derivation must be deterministic so start and end stay consistent");
    }

    @Test
    void anchoredValuesAreStillValidBcd() {
        LocalDateTime anchor = factory.newRecordAnchor();
        assertTrue(factory.isValidBcd(factory.timestampAt(anchor, "serviceDeliveryEndTimeStamp")),
                "anchored timestamp must be 18 hex chars");
        assertTrue(factory.isValidBcdDate(factory.dateAt(anchor)),
                "anchored date must be 6 hex chars");
    }
}
