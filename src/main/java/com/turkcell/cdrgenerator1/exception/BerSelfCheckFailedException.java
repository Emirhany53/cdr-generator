package com.turkcell.cdrgenerator1.exception;

import com.turkcell.cdrgenerator1.service.verify.BerFinding;

import java.util.List;

/**
 * Raised in strict mode when the generator's own verification found something
 * a decoder could reject.
 *
 * <p>Distinct from {@link BerEncodingException}, which means the bytes could
 * not be produced at all. Here they were produced and then found wanting -
 * which is worse to ship silently, because the file looks perfectly normal
 * until EMM refuses it a day later.</p>
 */
public class BerSelfCheckFailedException extends RuntimeException {

    private final transient List<BerFinding> findings;

    public BerSelfCheckFailedException(String structureName, List<BerFinding> findings) {
        super("Generated BER for '" + structureName + "' failed self-check with "
                + findings.size() + " error(s): " + summarise(findings));
        this.findings = List.copyOf(findings);
    }

    public List<BerFinding> getFindings() {
        return findings;
    }

    /** Only the first few, so one bad structure cannot produce a wall of text. */
    private static String summarise(List<BerFinding> findings) {
        int shown = Math.min(findings.size(), 3);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                text.append("; ");
            }
            text.append(findings.get(i).path()).append(" - ").append(findings.get(i).message());
        }
        if (findings.size() > shown) {
            text.append("; ... and ").append(findings.size() - shown).append(" more");
        }
        return text.toString();
    }
}
