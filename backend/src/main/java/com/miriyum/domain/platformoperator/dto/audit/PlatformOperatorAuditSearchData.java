package com.miriyum.domain.platformoperator.dto.audit;

import com.miriyum.global.response.PageMetadata;
import java.util.List;

public record PlatformOperatorAuditSearchData(
        List<PlatformOperatorAuditEventData> content,
        PageMetadata page
) {
    public PlatformOperatorAuditSearchData {
        content = List.copyOf(content);
    }
}
