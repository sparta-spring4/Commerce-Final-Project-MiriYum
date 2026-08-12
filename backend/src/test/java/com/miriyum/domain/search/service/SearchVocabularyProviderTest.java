package com.miriyum.domain.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.store.service.CatalogItemView;
import com.miriyum.domain.store.service.CatalogKind;
import com.miriyum.domain.store.service.CatalogService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchVocabularyProviderTest {

    @Mock CatalogService catalogService;

    @Test
    void buildsApprovedAliasesAndFixedSeedVersion() {
        given(catalogService.getItems(CatalogKind.STORE_CATEGORY))
                .willReturn(List.of(new CatalogItemView("KOREAN", "한식")));
        given(catalogService.getItems(CatalogKind.MENU_CATEGORY))
                .willReturn(List.of(new CatalogItemView("NOODLE", "면요리")));
        given(catalogService.getItems(CatalogKind.STORE_TAG))
                .willReturn(List.of(new CatalogItemView("QUIET", "조용한")));

        var vocabulary = new SearchVocabularyProvider(catalogService).current();

        assertThat(vocabulary.version()).isEqualTo("catalog-v1");
        assertThat(vocabulary.regions()).anySatisfy(entry -> {
            assertThat(entry.code()).isEqualTo("SEOUL");
            assertThat(entry.aliases()).containsExactly("SEOUL", "서울");
        });
        assertThat(vocabulary.storeCategories().getFirst().aliases())
                .containsExactly("KOREAN", "한식");
        assertThat(vocabulary.menuCategories().getFirst().aliases())
                .containsExactly("NOODLE", "면요리");
        assertThat(vocabulary.tags().getFirst().aliases())
                .containsExactly("QUIET", "조용한");
    }
}
