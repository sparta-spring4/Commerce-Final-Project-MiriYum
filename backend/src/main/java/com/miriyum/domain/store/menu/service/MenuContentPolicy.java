package com.miriyum.domain.store.menu.service;

import com.miriyum.domain.store.core.enums.PickupEligibility;
import com.miriyum.domain.store.core.service.StoreManagementView;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.menu.dto.MenuContentRequest;
import com.miriyum.domain.store.menu.model.MenuContent;
import com.miriyum.domain.store.service.CatalogKind;
import com.miriyum.domain.store.service.CatalogService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MenuContentPolicy {

    private static final Pattern CONTACT = Pattern.compile(
            "(?i).*(?:[\\w.+-]+@[\\w.-]+|(?:tel|phone|kakao|instagram|문의|연락)\\s*[:@]).*");
    private static final Pattern RESERVED = Pattern.compile(
            "(?i).*(?:miriyum|미리윰|official|공식|admin|관리자).*");

    private final CatalogService catalogService;

    public MenuContent validateAndNormalize(
            MenuContentRequest request,
            StoreManagementView store
    ) {
        requireStructure(request);
        List<String> categories = new ArrayList<>();
        categories.add(request.primaryCategoryCode());
        categories.addAll(request.secondaryCategoryCodes());
        if (!catalogService.findUnknownCodes(CatalogKind.MENU_CATEGORY, categories).isEmpty()) {
            throw new ServiceException(StoreErrorCode.CATALOG_CODE_INVALID);
        }
        if (request.pickupSelectionAllowed()
                && store.pickupEligibility() != PickupEligibility.ELIGIBLE) {
            throw new ServiceException(StoreErrorCode.PICKUP_NOT_ELIGIBLE);
        }
        List<String> normalizedTags = normalizeTags(request.localTags());
        return new MenuContent(
                request.name().trim(),
                request.description(),
                request.price(),
                request.representative(),
                request.primaryCategoryCode(),
                request.secondaryCategoryCodes(),
                normalizedTags,
                request.holdSelectionAllowed(),
                request.pickupSelectionAllowed());
    }

    private static void requireStructure(MenuContentRequest request) {
        if (request == null
                || request.name() == null
                || request.name().isBlank()
                || request.name().length() > 100
                || request.description() == null
                || request.description().length() > 1000
                || request.price() < 0
                || request.primaryCategoryCode() == null
                || request.primaryCategoryCode().isBlank()
                || request.secondaryCategoryCodes() == null
                || request.secondaryCategoryCodes().size() > 5
                || request.localTags() == null
                || request.localTags().size() > 10) {
            throw validation();
        }
        Set<String> categories = new HashSet<>();
        categories.add(request.primaryCategoryCode());
        if (request.secondaryCategoryCodes().stream()
                .anyMatch(code -> code == null || code.isBlank() || !categories.add(code))) {
            throw validation();
        }
    }

    private static List<String> normalizeTags(List<String> tags) {
        List<String> normalized = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (String raw : tags) {
            if (raw == null) {
                throw validation();
            }
            String value = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim();
            int length = value.codePointCount(0, value.length());
            String key = value.toLowerCase(Locale.ROOT);
            if (length == 0 || length > 30 || containsControl(value)
                    || looksLikeUrl(value) || CONTACT.matcher(value).matches()
                    || RESERVED.matcher(value).matches()) {
                throw validation();
            }
            if (keys.add(key)) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static boolean looksLikeUrl(String value) {
        String candidate = value.toLowerCase(Locale.ROOT);
        if (candidate.startsWith("www.") || candidate.contains("://")) {
            return true;
        }
        try {
            URI uri = new URI(value);
            return uri.getHost() != null;
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private static ServiceException validation() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
}
