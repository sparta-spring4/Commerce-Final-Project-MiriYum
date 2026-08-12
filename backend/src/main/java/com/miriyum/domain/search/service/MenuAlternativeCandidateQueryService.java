package com.miriyum.domain.search.service;

import com.miriyum.domain.search.dto.contract.MenuAlternativeCandidateView;
import com.miriyum.domain.search.dto.contract.MenuAlternativeSourceView;
import com.miriyum.domain.search.config.StoreSearchCandidateLimit;
import com.miriyum.domain.search.geo.BoundingBox;
import com.miriyum.domain.search.repository.MenuAlternativeCandidateRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MenuAlternativeCandidateQueryService {
    private final MenuAlternativeCandidateRepository repository;
    private final StoreSearchCandidateLimit candidateLimit;

    public MenuAlternativeCandidateQueryService(MenuAlternativeCandidateRepository repository,
            StoreSearchCandidateLimit candidateLimit) {
        this.repository = repository;
        this.candidateLimit = candidateLimit;
    }

    public MenuAlternativeSourceView findSource(long storeId, long menuId) {
        return repository.findSource(storeId, menuId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.MENU_NOT_FOUND));
    }

    public List<MenuAlternativeCandidateView> findSameStoreCandidates(
            MenuAlternativeSourceView source) {
        return repository.findSameStoreCandidates(
                source.storeId(), source.menuId(), candidateLimit.value());
    }

    public List<MenuAlternativeCandidateView> findNearbyCandidates(
            MenuAlternativeSourceView source, BoundingBox box) {
        return repository.findNearbyCandidates(
                source.storeId(), source.menuId(), box, candidateLimit.value());
    }
}
