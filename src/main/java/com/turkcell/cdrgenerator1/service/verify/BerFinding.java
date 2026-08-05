package com.turkcell.cdrgenerator1.service.verify;

/**
 * One thing a rule objected to.
 *
 * <p>{@code path} is deliberately shaped like the paths EMM prints in its
 * rejection mails -
 * {@code MMTelChargingDataTypes.MMTelServiceRecord.mMTelRecord.recordExtensions.enhancedPhoneFeatures1.[0]}
 * - so a finding produced here can be compared with a rejection received from
 * EMM without translating between two notations.</p>
 *
 * @param severity   whether a decoder may reject the record over this
 * @param ruleName   the rule that raised it, matching its yml toggle
 * @param path       where in the record, in EMM's own notation
 * @param byteOffset offset of the offending TLV, for looking at the bytes
 * @param message    what is wrong, in one sentence
 */
public record BerFinding(
        FindingSeverity severity,
        String ruleName,
        String path,
        int byteOffset,
        String message) {

    @Override
    public String toString() {
        return severity + " [" + ruleName + "] " + path + " @" + byteOffset + ": " + message;
    }
}
