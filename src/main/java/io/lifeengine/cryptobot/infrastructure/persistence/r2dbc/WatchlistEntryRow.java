package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import io.lifeengine.cryptobot.domain.WatchlistEntry;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("watchlist_entry")
public class WatchlistEntryRow {

    @Id private UUID id;
    private String symbol;

    @Column("display_name")
    private String displayName;

    @Column("asset_type")
    private String assetType;

    @Column("sector_theme")
    private String sectorTheme;

    private String exchange;
    private int priority;
    private boolean active;
    private String notes;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public WatchlistEntry toDomain() {
        return new WatchlistEntry(
                id, symbol, displayName, assetType, sectorTheme, exchange, priority, active, notes, createdAt, updatedAt);
    }

    public static WatchlistEntryRow fromDomain(WatchlistEntry e) {
        WatchlistEntryRow row = new WatchlistEntryRow();
        row.id = e.id();
        row.symbol = e.symbol();
        row.displayName = e.displayName();
        row.assetType = e.assetType();
        row.sectorTheme = e.sectorTheme();
        row.exchange = e.exchange();
        row.priority = e.priority();
        row.active = e.active();
        row.notes = e.notes();
        row.createdAt = e.createdAt();
        row.updatedAt = e.updatedAt();
        return row;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public String getSectorTheme() { return sectorTheme; }
    public void setSectorTheme(String sectorTheme) { this.sectorTheme = sectorTheme; }
    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
