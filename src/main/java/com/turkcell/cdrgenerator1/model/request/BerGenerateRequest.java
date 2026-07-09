package com.turkcell.cdrgenerator1.model.request;

import lombok.Data;

import java.util.Map;

/**
 * Body of POST /api/cdr/ber/generate.
 * <p>
 * Example:
 * <pre>
 * {
 *   "structureName": "MMTelChargingDataTypesV3",
 *   "fields": {
 *     "sIP-Method": "BYE",
 *     "duration": 15,
 *     "nodeAddress": { "domainName": "IM3MAVAS1.ims..." },
 *     "list-of-subscription-ID": [
 *       { "subscriptionIDType": 0, "subscriptionIDData": "905343545097" }
 *     ]
 *   }
 * }
 * </pre>
 */
@Data
public class BerGenerateRequest {

    /** Name of the ASN.1 structure to encode against (from datastructure.json). */
    private String structureName;

    /** Nested field-name keyed value tree. Scalars, objects and arrays allowed. */
    private Map<String, Object> fields;
}