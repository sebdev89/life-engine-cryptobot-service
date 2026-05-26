package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import io.lifeengine.cryptobot.domain.MarketObservation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("market_observation")
public class MarketObservationRow {

    @Id private UUID id;
    private String symbol;
    private String venue;

    @Column("observed_at")
    private Instant observedAt;

    private String timeframe;

    @Column("last_price")
    private BigDecimal lastPrice;

    @Column("change_pct_24h")
    private BigDecimal changePct24h;

    @Column("volume_quote_24h")
    private BigDecimal volumeQuote24h;

    @Column("spread_bps")
    private BigDecimal spreadBps;

    @Column("liquidity_score")
    private BigDecimal liquidityScore;

    private String regime;

    @Column("created_at")
    private Instant createdAt;

    public MarketObservation toDomain() {
        return new MarketObservation(id, symbol, venue, observedAt, timeframe, lastPrice, changePct24h,
                volumeQuote24h, spreadBps, liquidityScore, regime, createdAt);
    }

    public static MarketObservationRow fromDomain(MarketObservation o) {
        MarketObservationRow row = new MarketObservationRow();
        row.id = o.id();
        row.symbol = o.symbol();
        row.venue = o.venue();
        row.observedAt = o.observedAt();
        row.timeframe = o.timeframe();
        row.lastPrice = o.lastPrice();
        row.changePct24h = o.changePct24h();
        row.volumeQuote24h = o.volumeQuote24h();
        row.spreadBps = o.spreadBps();
        row.liquidityScore = o.liquidityScore();
        row.regime = o.regime();
        row.createdAt = o.createdAt();
        return row;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getVenue() { return venue; }
    public void setVenue(String venue) { this.venue = venue; }
    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public void setLastPrice(BigDecimal lastPrice) { this.lastPrice = lastPrice; }
    public BigDecimal getChangePct24h() { return changePct24h; }
    public void setChangePct24h(BigDecimal changePct24h) { this.changePct24h = changePct24h; }
    public BigDecimal getVolumeQuote24h() { return volumeQuote24h; }
    public void setVolumeQuote24h(BigDecimal volumeQuote24h) { this.volumeQuote24h = volumeQuote24h; }
    public BigDecimal getSpreadBps() { return spreadBps; }
    public void setSpreadBps(BigDecimal spreadBps) { this.spreadBps = spreadBps; }
    public BigDecimal getLiquidityScore() { return liquidityScore; }
    public void setLiquidityScore(BigDecimal liquidityScore) { this.liquidityScore = liquidityScore; }
    public String getRegime() { return regime; }
    public void setRegime(String regime) { this.regime = regime; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
