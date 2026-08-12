package com.miriyum.domain.search.service;

import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.search.interpreter.SearchVocabulary;
import com.miriyum.domain.search.interpreter.VocabularyEntry;
import com.miriyum.domain.store.service.CatalogItemView;
import com.miriyum.domain.store.service.CatalogKind;
import com.miriyum.domain.store.service.CatalogService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 승인된 Region과 활성 Catalog를 RuleInterpreter 사전 스냅샷으로 제공한다. */
@Component
public class SearchVocabularyProvider {

    static final String VOCABULARY_VERSION = "catalog-v1";

    private static final Map<Region, String> REGION_DISPLAY_NAMES = Map.of(
            Region.SEOUL, "서울",
            Region.BUSAN, "부산",
            Region.DAEGU, "대구",
            Region.DAEJEON, "대전",
            Region.GWANGJU, "광주");

    private final CatalogService catalogService;

    public SearchVocabularyProvider(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** 현재 2차 MVP seed와 활성 Catalog에 대응하는 결정적 사전을 만든다. */
    public SearchVocabulary current() {
        return new SearchVocabulary(
                VOCABULARY_VERSION,
                regionEntries(),
                catalogEntries(CatalogKind.STORE_CATEGORY),
                catalogEntries(CatalogKind.MENU_CATEGORY),
                catalogEntries(CatalogKind.STORE_TAG));
    }

    private static List<VocabularyEntry> regionEntries() {
        return List.of(Region.values()).stream()
                .map(region -> new VocabularyEntry(
                        region.name(),
                        List.of(region.name(), REGION_DISPLAY_NAMES.get(region))))
                .toList();
    }

    private List<VocabularyEntry> catalogEntries(CatalogKind kind) {
        return catalogService.getItems(kind).stream()
                .map(SearchVocabularyProvider::toEntry)
                .toList();
    }

    private static VocabularyEntry toEntry(CatalogItemView item) {
        List<String> aliases = item.code().equalsIgnoreCase(item.displayName())
                ? List.of(item.code())
                : List.of(item.code(), item.displayName());
        return new VocabularyEntry(item.code(), aliases);
    }
}
