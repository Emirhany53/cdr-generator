package com.turkcell.cdrgenerator1.model.response;

import com.turkcell.cdrgenerator1.service.verify.BerFinding;
import lombok.Builder;
import lombok.Data;

/** JSON shape of one {@link BerFinding}, decoupled from the internal record. */
@Data
@Builder
public class BerFindingResponse {
    private String severity;
    private String rule;
    private String path;
    private int byteOffset;
    private String message;

    public static BerFindingResponse from(BerFinding finding) {
        return BerFindingResponse.builder()
                .severity(finding.severity().name())
                .rule(finding.ruleName())
                .path(finding.path())
                .byteOffset(finding.byteOffset())
                .message(finding.message())
                .build();
    }
}
