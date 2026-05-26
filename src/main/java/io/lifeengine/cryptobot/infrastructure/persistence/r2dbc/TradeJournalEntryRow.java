package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import io.lifeengine.cryptobot.domain.TradeJournalEntry;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("trade_journal_entry")
public class TradeJournalEntryRow {

    @Id private UUID id;
    private String symbol;

    @Column("entry_time")
    private Instant entryTime;

    private String title;
    private String body;
    private String sentiment;
    private String tags;

    @Column("linked_watchlist_id")
    private UUID linkedWatchlistId;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public TradeJournalEntry toDomain() {
        return new TradeJournalEntry(id, symbol, entryTime, title, body, sentiment, tags,
                linkedWatchlistId, createdAt, updatedAt);
    }

    public static TradeJournalEntryRow fromDomain(TradeJournalEntry j) {
        TradeJournalEntryRow row = new TradeJournalEntryRow();
        row.id = j.id();
        row.symbol = j.symbol();
        row.entryTime = j.entryTime();
        row.title = j.title();
        row.body = j.body();
        row.sentiment = j.sentiment();
        row.tags = j.tags();
        row.linkedWatchlistId = j.linkedWatchlistId();
        row.createdAt = j.createdAt();
        row.updatedAt = j.updatedAt();
        return row;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public Instant getEntryTime() { return entryTime; }
    public void setEntryTime(Instant entryTime) { this.entryTime = entryTime; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getSentiment() { return sentiment; }
    public void setSentiment(String sentiment) { this.sentiment = sentiment; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public UUID getLinkedWatchlistId() { return linkedWatchlistId; }
    public void setLinkedWatchlistId(UUID linkedWatchlistId) { this.linkedWatchlistId = linkedWatchlistId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
