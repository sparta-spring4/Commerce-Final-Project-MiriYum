package com.miriyum.domain.menu.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "representative_menu_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepresentativeMenuEntry {

    @EmbeddedId
    private Key id;

    @MapsId("storeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private RepresentativeMenuSetting setting;

    @Column(name = "menu_id", nullable = false)
    private long menuId;

    RepresentativeMenuEntry(
            RepresentativeMenuSetting setting,
            int displayOrder,
            long menuId
    ) {
        this.setting = setting;
        this.id = new Key(setting.getStoreId(), displayOrder);
        this.menuId = menuId;
    }

    public int getDisplayOrder() {
        return id.displayOrder;
    }

    @Embeddable
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {

        @Column(name = "store_id", nullable = false)
        private long storeId;

        @Column(name = "display_order", nullable = false)
        private int displayOrder;

        private Key(long storeId, int displayOrder) {
            this.storeId = storeId;
            this.displayOrder = displayOrder;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return storeId == key.storeId && displayOrder == key.displayOrder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(storeId, displayOrder);
        }
    }
}
