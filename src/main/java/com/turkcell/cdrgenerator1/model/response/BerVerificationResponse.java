package com.turkcell.cdrgenerator1.model.response;

import com.turkcell.cdrgenerator1.service.verify.BerVerificationResult;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * JSON shape of a {@link BerVerificationResult} for {@code POST /api/cdr/verify-ber}.
 *
 * <p>This is the endpoint for a file this application did NOT produce - a
 * reference capture, or a file EMM sent back - so it exists independently of
 * anything the generator does. It replaces running {@code tools/*.py} by hand.</p>
 */
@Data
@Builder
public class BerVerificationResponse {
    private String structureName;
    private int recordCount;
    private boolean clean;
    private int errorCount;
    private int warningCount;
    private List<BerFindingResponse> findings;

    public static BerVerificationResponse from(BerVerificationResult result) {
        return BerVerificationResponse.builder()
                .structureName(result.structureName())
                .recordCount(result.recordCount())
                .clean(result.isClean())
                .errorCount(result.errors().size())
                .warningCount(result.warnings().size())
                .findings(result.findings().stream().map(BerFindingResponse::from).toList())
                .build();
    }
}
