package com.miriyum.domain.platformoperator.adminstore.service;

import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionType;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

public final class StoreSanctionFingerprint {
    private StoreSanctionFingerprint() {}
    public static String shape(SanctionType type, Set<RestrictedFeature> features,
                               Instant startsAt, Instant endsAt) {
        String canonical = type + "|" + features.stream().map(Enum::name).sorted().toList()
                + "|" + startsAt + "|" + endsAt;
        return sha256(canonical);
    }
    public static String digest(String caseId, long storeId, long caseVersion, long enforcementVersion,
                                String shape, long reservations, long waiting, long pickups, long payments) {
        return sha256(caseId+"|"+storeId+"|"+caseVersion+"|"+enforcementVersion+"|"+shape
                +"|"+reservations+"|"+waiting+"|"+pickups+"|"+payments);
    }
    public static String impactDigest(String caseId, long storeId, long caseVersion, long enforcementVersion,
                                      String shape, List<String> reservations, List<String> waiting,
                                      List<String> pickups, List<String> payments) {
        return sha256(caseId + "|" + storeId + "|" + caseVersion + "|" + enforcementVersion + "|" + shape
                + "|reservations=" + canonical(reservations) + "|waiting=" + canonical(waiting)
                + "|pickups=" + canonical(pickups) + "|payments=" + canonical(payments));
    }
    private static List<String> canonical(List<String> values) {
        return values.stream().sorted().toList();
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
