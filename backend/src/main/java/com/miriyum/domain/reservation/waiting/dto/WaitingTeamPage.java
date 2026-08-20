package com.miriyum.domain.reservation.waiting.dto;

import java.util.List;

/** 안정적인 FIFO keyset 목록과 다음 opaque cursor다. */
public record WaitingTeamPage(List<WaitingTeamListItem> items, String nextCursor) {

    public WaitingTeamPage {
        items = List.copyOf(items);
    }
}
