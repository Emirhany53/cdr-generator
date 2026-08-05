package com.turkcell.cdrgenerator1.service.verify;

import java.util.List;

/**
 * Everything the rules had to say about one file.
 *
 * @param structureName the ASN.1 structure the bytes were checked against
 * @param recordCount   how many top-level records were read
 * @param findings      every finding, in the order the walk produced them
 */
public record BerVerificationResult(
        String structureName,
        int recordCount,
        List<BerFinding> findings) {

    public BerVerificationResult {
        findings = List.copyOf(findings);
    }

    /** True when no rule objected at all. */
    public boolean isClean() {
        return findings.isEmpty();
    }

    /** True when at least one finding is severe enough to justify a rejection. */
    public boolean hasErrors() {
        return findings.stream().anyMatch(f -> f.severity() == FindingSeverity.ERROR);
    }

    public List<BerFinding> errors() {
        return findings.stream().filter(f -> f.severity() == FindingSeverity.ERROR).toList();
    }

    public List<BerFinding> warnings() {
        return findings.stream().filter(f -> f.severity() == FindingSeverity.WARNING).toList();
    }

    /** One-line summary for a log statement. */
    public String summary() {
        return structureName + ": " + recordCount + " record(s), "
                + errors().size() + " error(s), " + warnings().size() + " warning(s)";
    }
}
