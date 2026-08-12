package com.miriyum.domain.auth.social.client;

import com.miriyum.domain.auth.social.config.KakaoOAuthProperties;
import com.miriyum.domain.auth.social.dto.KakaoOAuthUser;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 카카오 인가 코드 교환과 사용자 회원번호 조회를 서버에서 수행한다. */
@Component
public class KakaoRestOAuthClient implements KakaoOAuthClient {

    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String USER_URL = "https://kapi.kakao.com/v2/user/me";

    private final KakaoOAuthProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public KakaoRestOAuthClient(
            KakaoOAuthProperties properties,
            ObjectMapper objectMapper,
            RestClient restClient
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public String createAuthorizationUrl(String state, String redirectUri) {
        requireAvailableConfiguration(redirectUri);
        return UriComponentsBuilder.newInstance()
                .scheme("https")
                .host("kauth.kakao.com")
                .path("/oauth/authorize")
                .queryParam("client_id", properties.restApiKey())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .encode()
                .toUriString();
    }

    @Override
    public KakaoOAuthUser authenticate(String authorizationCode, String redirectUri) {
        requireAvailableConfiguration(redirectUri);
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
        }

        try {
            String kakaoAccessToken = requestAccessToken(authorizationCode, redirectUri);
            return new KakaoOAuthUser(requestProviderSubject(kakaoAccessToken));
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()
                    && exception.getStatusCode().value() != 429) {
                throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
            }
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        } catch (RestClientException | IllegalArgumentException | JacksonException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private String requestAccessToken(String authorizationCode, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.restApiKey());
        form.add("redirect_uri", redirectUri);
        form.add("code", authorizationCode);
        form.add("client_secret", properties.clientSecret());

        String body = restClient.post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        return requiredText(readResponse(body), "access_token");
    }

    private String requestProviderSubject(String kakaoAccessToken) {
        String body = restClient.get()
                .uri(USER_URL)
                .headers(headers -> headers.setBearerAuth(kakaoAccessToken))
                .retrieve()
                .body(String.class);
        return requiredText(readResponse(body), "id");
    }

    private JsonNode readResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Kakao response body must not be blank");
        }
        return objectMapper.readTree(body);
    }

    private String requiredText(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null) {
            throw new IllegalArgumentException("Kakao response does not contain " + fieldName);
        }
        String value = field.asString();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Kakao response does not contain " + fieldName);
        }
        return value;
    }

    private void requireAvailableConfiguration(String redirectUri) {
        if (!properties.isEnabled()
                || properties.restApiKey().isBlank()
                || properties.clientSecret().isBlank()
                || !properties.isAllowedRedirectUri(redirectUri)) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
