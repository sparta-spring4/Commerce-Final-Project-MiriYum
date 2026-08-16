package com.miriyum.domain.platformoperator.config.membersupport;

import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class MemberRestrictionPolicy {
    public Optional<RestrictedFeature> featureFor(String method, String path) {
        if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")
                || path.contains("account-recovery") || path.contains("account-sanction-appeals")) {
            return Optional.empty();
        }
        if (path.startsWith("/api/v1/consumers/")) {
            if (path.contains("/waiting")) return Optional.of(RestrictedFeature.WAITING);
            if (path.contains("/pickups")) return Optional.of(RestrictedFeature.PICKUP);
            if (path.contains("/reservations")) return Optional.of(RestrictedFeature.RESERVATION);
        }
        if (path.startsWith("/api/v1/store-operators/stores/")) {
            return Optional.of(path.contains("/menus")
                    ? RestrictedFeature.MENU_OPERATION : RestrictedFeature.STORE_OPERATION);
        }
        return Optional.empty();
    }
}
