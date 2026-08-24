package com.miriyum.domain.search.interpreter;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.search.expansion.StructuredFoodEvidence;
import com.miriyum.domain.search.expansion.StructuredFoodEvidence.EvidenceTerm;
import com.miriyum.domain.search.expansion.StructuredFoodEvidenceSource;
import org.junit.jupiter.api.Test;

class DeterministicFoodEvidenceExtractorTest {

    private final DeterministicFoodEvidenceExtractor extractor =
            new DeterministicFoodEvidenceExtractor(new FoodEvidenceVocabulary());

    @Test
    void separatesMenuTasteAndIngredientWithoutLosingRawSpan() {
        StructuredFoodEvidence result = extractor.extract("칼칼한 해물 마라탕");

        assertThat(result.rawFoodSpans())
                .extracting(EvidenceTerm::surface)
                .containsExactly("칼칼한 해물 마라탕");
        assertThat(result.menuFamilies())
                .extracting(EvidenceTerm::id)
                .containsExactly("MALATANG");
        assertThat(result.tastes())
                .extracting(EvidenceTerm::id)
                .containsExactly("SPICY_SHARP");
        assertThat(result.ingredients())
                .extracting(EvidenceTerm::id)
                .containsExactly("SEAFOOD");
        assertThat(result.menuFamilies())
                .extracting(EvidenceTerm::source)
                .containsOnly(StructuredFoodEvidenceSource.DETERMINISTIC);
    }

    @Test
    void normalizesBidirectionalAliasesButRejectsGenericFormAsCandidateEvidence() {
        assertThat(extractor.extract("뼈다귀 해장국").menuFamilies())
                .extracting(EvidenceTerm::id)
                .containsExactly("BONE_HANGOVER_SOUP");
        assertThat(extractor.extract("멸치국수").menuFamilies())
                .extracting(EvidenceTerm::id)
                .containsExactly("BANQUET_NOODLES");

        StructuredFoodEvidence generic = extractor.extract("면");
        assertThat(generic.forms()).extracting(EvidenceTerm::surface).containsExactly("면");
        assertThat(generic.hasCandidateEvidence()).isFalse();
    }

    @Test
    void longestAliasWinsOnlyInsideItsDimension() {
        StructuredFoodEvidence result = extractor.extract("불향 해물 짬뽕");

        assertThat(result.menuFamilies())
                .extracting(EvidenceTerm::id)
                .containsExactly("JJAMPPONG");
        assertThat(result.ingredients())
                .extracting(EvidenceTerm::id)
                .containsExactly("SEAFOOD");
        assertThat(result.aromas())
                .extracting(EvidenceTerm::id)
                .containsExactly("FIRE_AROMA");
    }

    @Test
    void doesNotExtractBeefFromNuttyTasteWord() {
        StructuredFoodEvidence result = extractor.extract("고소한 뼈해장국");

        assertThat(result.tastes()).extracting(EvidenceTerm::id).containsExactly("NUTTY");
        assertThat(result.ingredients()).isEmpty();
    }
}
