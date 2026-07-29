package com.miriyum.domain.auth.cookie;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 재발급 요청의 Origin(없으면 Referer)이 허용된 프론트엔드 Origin과 같은지 확인한다.
 */
@Component
public class OriginValidator {

    private final String allowedOrigin;
    private final URI allowedOriginUri;

    public OriginValidator(@Value("${miriyum.security.allowed-origin}") String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
        this.allowedOriginUri = URI.create(allowedOrigin);
    }

    public boolean isSameOrigin(String originHeader, String refererHeader) {
        if (originHeader != null && !originHeader.isBlank()) {
            return allowedOrigin.equals(originHeader);
        }
        if (refererHeader == null) {
            return false;
        }
        return isSameOrigin(refererHeader, allowedOriginUri);
    }

    private boolean isSameOrigin(String refererHeader, URI allowed) {
        URI referer;
        try {
            referer = new URI(refererHeader);
        } catch (URISyntaxException exception) {
            return false;
        }
        return equalsIgnoreCase(referer.getScheme(), allowed.getScheme())
                && equalsIgnoreCase(referer.getHost(), allowed.getHost())
                && effectivePort(referer) == effectivePort(allowed);
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && left.equalsIgnoreCase(right);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return Objects.equals("https", uri.getScheme()) ? 443 : 80;
    }
}
