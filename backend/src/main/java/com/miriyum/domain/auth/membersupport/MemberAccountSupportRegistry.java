package com.miriyum.domain.auth.membersupport;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class MemberAccountSupportRegistry {
    private final Map<MemberAccountType, MemberAccountSupportPort> ports;

    public MemberAccountSupportRegistry(List<MemberAccountSupportPort> ports) {
        EnumMap<MemberAccountType, MemberAccountSupportPort> indexed = new EnumMap<>(MemberAccountType.class);
        for (MemberAccountSupportPort port : ports) {
            MemberAccountSupportPort previous = indexed.put(port.accountType(), Objects.requireNonNull(port));
            if (previous != null) throw new IllegalStateException("duplicate member account support port");
        }
        this.ports = Map.copyOf(indexed);
    }

    public MemberAccountSupportPort require(MemberAccountType accountType) {
        MemberAccountSupportPort port = ports.get(Objects.requireNonNull(accountType));
        if (port == null) throw new IllegalStateException("missing member account support port: " + accountType);
        return port;
    }
}
