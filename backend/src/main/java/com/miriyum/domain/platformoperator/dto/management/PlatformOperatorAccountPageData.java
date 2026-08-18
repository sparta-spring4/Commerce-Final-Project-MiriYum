package com.miriyum.domain.platformoperator.dto.management;

import com.miriyum.global.response.PageMetadata;
import java.util.List;

public record PlatformOperatorAccountPageData(
        List<PlatformOperatorAccountSummaryData> content,
        PageMetadata page
) {
}
