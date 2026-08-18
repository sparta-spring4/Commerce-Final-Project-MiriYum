package com.miriyum.domain.search.expansion;

public enum SearchConceptPurpose {
    STORE_SEARCH,
    MENU_ALTERNATIVE;

    public String metricValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
