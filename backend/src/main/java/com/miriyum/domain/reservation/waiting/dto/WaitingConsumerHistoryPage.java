package com.miriyum.domain.reservation.waiting.dto;

import com.miriyum.global.response.PageMetadata;
import java.util.List;
import org.springframework.data.domain.Page;

public record WaitingConsumerHistoryPage(
        List<WaitingConsumerHistoryItem> items,
        PageMetadata page
) {
    public static WaitingConsumerHistoryPage of(Page<WaitingConsumerHistoryItem> source) {
        return new WaitingConsumerHistoryPage(
                List.copyOf(source.getContent()),
                new PageMetadata(source.getNumber(), source.getSize(), source.getTotalElements(),
                        source.getTotalPages(), source.hasNext()));
    }
}
