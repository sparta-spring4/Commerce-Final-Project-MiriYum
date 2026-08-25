package com.miriyum.domain.search.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 정확한 메뉴 버전에 결속된 내부 검색용 구조화 프로필이다. */
@Entity
@Table(name = "menu_search_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuSearchProfile {

    private static final int MAX_SCHEMA_VERSION_LENGTH = 40;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_search_profile_id")
    private Long id;

    @Column(name = "menu_version_id", nullable = false, unique = true)
    private Long menuVersionId;

    @Column(name = "schema_version", nullable = false, length = 40)
    private String schemaVersion;

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<MenuSearchProfileTerm> terms = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private MenuSearchProfile(Long menuVersionId, String schemaVersion, Instant now) {
        if (menuVersionId == null || menuVersionId <= 0) {
            throw new IllegalArgumentException("menuVersionId must be positive");
        }
        this.menuVersionId = menuVersionId;
        this.schemaVersion = normalizeSchemaVersion(schemaVersion);
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    /** 메뉴 버전 하나에 대응하는 빈 검색 프로필을 만든다. */
    public static MenuSearchProfile create(
            Long menuVersionId,
            String schemaVersion,
            Instant now
    ) {
        return new MenuSearchProfile(menuVersionId, schemaVersion, now);
    }

    /** 중복되지 않은 정규화 term을 프로필에 추가한다. */
    public MenuSearchProfileTerm addTerm(
            MenuSearchProfileDimension dimension,
            String normalizedTerm,
            BigDecimal confidence,
            MenuSearchProfileSource source,
            Instant now
    ) {
        MenuSearchProfileTerm term = MenuSearchProfileTerm.create(
                this, dimension, normalizedTerm, confidence, source, now);
        boolean duplicate = terms.stream().anyMatch(existing ->
                existing.getDimension() == term.getDimension()
                        && existing.getNormalizedTerm().toLowerCase(Locale.ROOT)
                        .equals(term.getNormalizedTerm().toLowerCase(Locale.ROOT)));
        if (duplicate) {
            throw new IllegalArgumentException("duplicate profile dimension and term");
        }
        terms.add(term);
        updatedAt = term.getCreatedAt();
        return term;
    }

    private static String normalizeSchemaVersion(String value) {
        if (value == null) {
            throw new IllegalArgumentException("schemaVersion must not be null");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_SCHEMA_VERSION_LENGTH) {
            throw new IllegalArgumentException("schemaVersion has invalid length");
        }
        return normalized;
    }
}
