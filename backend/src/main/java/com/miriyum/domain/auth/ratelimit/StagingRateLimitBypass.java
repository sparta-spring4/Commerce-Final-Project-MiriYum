package com.miriyum.domain.auth.ratelimit;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * staging 부하테스트 실행자의 단일 공인 IP에만 로그인·토큰 갱신 요청 제한 예외를 허용한다.
 * 환경 또는 IP가 비어 있으면 비활성화되며, staging 이외 환경에서는 IP 값이 있어도 적용하지 않는다.
 *
 * @param runtimeEnvironment 현재 backend 실행 환경
 * @param sourceIp staging 부하테스트 실행자의 단일 공인 IP
 */
@ConfigurationProperties("miriyum.rate-limit.staging-bypass")
public record StagingRateLimitBypass(String runtimeEnvironment, String sourceIp) {

    private static final String STAGING = "staging";

    public StagingRateLimitBypass {
        runtimeEnvironment = trim(runtimeEnvironment);
        sourceIp = normalizeIp(sourceIp);
        if (STAGING.equals(runtimeEnvironment) && !sourceIp.isEmpty()) {
            sourceIp = requirePublicAddress(sourceIp).getHostAddress();
        }
    }

    /**
     * 현재 요청이 staging 부하테스트용 요청 제한 예외에 해당하는지 확인한다.
     *
     * @param category 요청 제한 등급
     * @param clientIp 신뢰 프록시 경계를 거친 실제 클라이언트 IP
     * @return staging의 설정 IP에서 온 로그인·토큰 갱신 요청이면 {@code true}
     */
    public boolean allows(RateLimitCategory category, String clientIp) {
        if (!STAGING.equals(runtimeEnvironment) || sourceIp.isEmpty() || !isAuthenticationMeasurement(category)) {
            return false;
        }

        InetAddress address = parseLiteral(clientIp);
        return address != null && sourceIp.equals(address.getHostAddress());
    }

    private static boolean isAuthenticationMeasurement(RateLimitCategory category) {
        return category == RateLimitCategory.LOGIN || category == RateLimitCategory.TOKEN_REFRESH;
    }

    private static InetAddress requirePublicAddress(String value) {
        InetAddress address = parseLiteral(value);
        if (address == null || !isPublicAddress(address)) {
            throw new IllegalArgumentException(
                    "staging load-test source IP must be one public IP literal");
        }
        return address;
    }

    private static InetAddress parseLiteral(String value) {
        String normalized = normalizeIp(value);
        try {
            if (normalized.indexOf(':') >= 0 && normalized.matches("[0-9a-fA-F:.]+")) {
                InetAddress address = InetAddress.getByName(normalized);
                return address instanceof Inet6Address ? address : null;
            }
            return parseIpv4Literal(normalized);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private static InetAddress parseIpv4Literal(String value) throws UnknownHostException {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }

        byte[] address = new byte[4];
        for (int index = 0; index < octets.length; index++) {
            if (octets[index].isEmpty() || !octets[index].matches("[0-9]{1,3}")) {
                return null;
            }
            int octet = Integer.parseInt(octets[index]);
            if (octet > 255) {
                return null;
            }
            address[index] = (byte) octet;
        }
        return InetAddress.getByAddress(address);
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address) {
            return isPublicIpv4(address.getAddress());
        }
        return isPublicIpv6(address.getAddress());
    }

    private static boolean isPublicIpv4(byte[] address) {
        int first = Byte.toUnsignedInt(address[0]);
        int second = Byte.toUnsignedInt(address[1]);
        int third = Byte.toUnsignedInt(address[2]);

        return first != 0
                && first != 10
                && first != 127
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31)
                && !(first == 192 && second == 0 && third == 0)
                && !(first == 192 && second == 0 && third == 2)
                && !(first == 192 && second == 88 && third == 99)
                && !(first == 192 && second == 168)
                && !(first == 198 && (second == 18 || second == 19))
                && !(first == 198 && second == 51 && third == 100)
                && !(first == 203 && second == 0 && third == 113)
                && first < 224;
    }

    private static boolean isPublicIpv6(byte[] address) {
        int first = Byte.toUnsignedInt(address[0]);
        int second = Byte.toUnsignedInt(address[1]);
        return (first & 0xfe) != 0xfc
                && !(first == 0x20 && second == 0x01
                && Byte.toUnsignedInt(address[2]) == 0x0d
                && Byte.toUnsignedInt(address[3]) == 0xb8);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeIp(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
