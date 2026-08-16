package com.miriyum.domain.auth.membersupport;

import java.util.List;

public record MemberAccountPage(List<MemberAccountSnapshot> content, long totalElements) {
    public MemberAccountPage {
        content = List.copyOf(content);
        if (totalElements < content.size()) throw new IllegalArgumentException("invalid totalElements");
    }
}
