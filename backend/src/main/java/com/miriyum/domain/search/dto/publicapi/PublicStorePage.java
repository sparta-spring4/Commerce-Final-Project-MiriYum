package com.miriyum.domain.search.dto.publicapi;

import com.miriyum.global.response.PageMetadata;
import java.util.List;
import org.springframework.data.domain.Page;

public record PublicStorePage(
        List<PublicStoreSummary> items,
        PageMetadata page
) {
    public static PublicStorePage from(Page<PublicStoreSummary> source) {
        return new PublicStorePage(
                List.copyOf(source.getContent()),
                new PageMetadata(
                        source.getNumber(), source.getSize(), source.getTotalElements(),
                        source.getTotalPages(), source.hasNext()));
    }
}
