package com.miriyum.domain.auth.jwt;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.global.exception.ErrorCode;
import com.miriyum.global.exception.ServiceException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 Access JWT의 서명·만료·namespace·용도를 검증해 SecurityContext에 등록한다.
 * namespace가 다르면 인증을 채우지 않고 기본 AUTH_004 또는 audience 전용 오류로 거부한다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "com.miriyum.domain.auth.jwt.AUTH_ERROR";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenNamespace requiredNamespace;
    private final ErrorCode namespaceMismatchError;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, TokenNamespace requiredNamespace) {
        this(jwtTokenProvider, requiredNamespace, AuthErrorCode.TOKEN_NAMESPACE_MISMATCH);
    }

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            TokenNamespace requiredNamespace,
            ErrorCode namespaceMismatchError
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.requiredNamespace = requiredNamespace;
        this.namespaceMismatchError = Objects.requireNonNull(namespaceMismatchError);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, AuthErrorCode.ACCESS_TOKEN_REQUIRED);
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());
        try {
            ParsedToken parsedToken = jwtTokenProvider.parseAccessToken(token);

            if (parsedToken.namespace() != requiredNamespace) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, namespaceMismatchError);
                filterChain.doFilter(request, response);
                return;
            }

            AuthenticatedPrincipal principal =
                    new AuthenticatedPrincipal(parsedToken.namespace(), parsedToken.accountId());
            List<SimpleGrantedAuthority> authorities =
                    List.of(new SimpleGrantedAuthority("ROLE_" + parsedToken.namespace().name()));
            var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (ServiceException exception) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, exception.getErrorCode());
        }

        filterChain.doFilter(request, response);
    }
}
