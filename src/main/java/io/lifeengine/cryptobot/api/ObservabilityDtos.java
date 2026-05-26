package io.lifeengine.cryptobot.api;

import io.lifeengine.cryptobot.domain.IndicatorSnapshot;
import io.lifeengine.cryptobot.domain.MarketObservation;
import io.lifeengine.cryptobot.domain.PriceZone;
import io.lifeengine.cryptobot.domain.TradeJournalEntry;
import io.lifeengine.cryptobot.domain.WatchlistEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTOs for the cryptobot observability REST surface. Each request type carries only the user-supplied
 * subset of fields; ids and timestamps are minted server-side.
 */
public final class ObservabilityDtos {

    private ObservabilityDtos() {}

    // -------- Watchlist --------

    public record WatchlistEntryResponse(
            UUID id,
            String symbol,
            String displayName,
            String assetType,
            String sectorTheme,
            String exchange,
            int priority,
            boolean active,
            String notes,
            Instant createdAt,
            Instant updatedAt) {
        public static WatchlistEntryResponse from(WatchlistEntry e) {
            return new WatchlistEntryResponse(e.id(), e.symbol(), e.displayName(), e.assetType(),
                    e.sectorTheme(), e.exchange(), e.priority(), e.active(), e.notes(), e.createdAt(), e.updatedAt());
        }
    }

    public record CreateWatchlistEntryRequest(
            String symbol,
            String displayName,
            String assetType,
            String sectorTheme,
            String exchange,
            Integer priority,
            Boolean active,
            String notes) {
        public WatchlistEntry toDomain() {
            return new WatchlistEntry(
                    null,
                    symbol,
                    displayName,
                    assetType,
                    sectorTheme,
                    exchange,
                    priority == null ? 100 : priority,
                    active == null ? Boolean.TRUE : active,
                    notes,
                    null,
                    null);
        }
    }

    // -------- Zones --------

    public record PriceZoneResponse(
            UUID id,
            String symbol,
            String timeframe,
            String zoneKind,
            BigDecimal lowerBound,
            BigDecimal upperBound,
            BigDecimal confidence,
            Instant validFrom,
            Instant validUntil,
            String label,
            Instant createdAt,
            Instant updatedAt) {
        public static PriceZoneResponse from(PriceZone z) {
            return new PriceZoneResponse(z.id(), z.symbol(), z.timeframe(), z.zoneKind(),
                    z.lowerBound(), z.upperBound(), z.confidence(), z.validFrom(), z.validUntil(),
                    z.label(), z.createdAt(), z.updatedAt());
        }
    }

    public record CreatePriceZoneRequest(
            String symbol,
            String timeframe,
            String zoneKind,
            BigDecimal lowerBound,
            BigDecimal upperBound,
            BigDecimal confidence,
            Instant validFrom,
            Instant validUntil,
            String label) {
        public PriceZone toDomain() {
            return new PriceZone(null, symbol, timeframe, zoneKind, lowerBound, upperBound,
                    confidence, validFrom, validUntil, label, null, null);
        }
    }

    // -------- Market observations --------

    public record MarketObservationResponse(
            UUID id,
            String symbol,
            String venue,
            Instant observedAt,
            String timeframe,
            BigDecimal lastPrice,
            BigDecimal changePct24h,
            BigDecimal volumeQuote24h,
            BigDecimal spreadBps,
            BigDecimal liquidityScore,
            String regime,
            Instant createdAt) {
        public static MarketObservationResponse from(MarketObservation o) {
            return new MarketObservationResponse(o.id(), o.symbol(), o.venue(), o.observedAt(),
                    o.timeframe(), o.lastPrice(), o.changePct24h(), o.volumeQuote24h(), o.spreadBps(),
                    o.liquidityScore(), o.regime(), o.createdAt());
        }
    }

    public record CreateMarketObservationRequest(
            String symbol,
            String venue,
            Instant observedAt,
            String timeframe,
            BigDecimal lastPrice,
            BigDecimal changePct24h,
            BigDecimal volumeQuote24h,
            BigDecimal spreadBps,
            BigDecimal liquidityScore,
            String regime) {
        public MarketObservation toDomain() {
            return new MarketObservation(null, symbol, venue, observedAt, timeframe, lastPrice,
                    changePct24h, volumeQuote24h, spreadBps, liquidityScore, regime, null);
        }
    }

    // -------- Journal --------

    public record TradeJournalEntryResponse(
            UUID id,
            String symbol,
            Instant entryTime,
            String title,
            String body,
            String sentiment,
            String tags,
            UUID linkedWatchlistId,
            Instant createdAt,
            Instant updatedAt) {
        public static TradeJournalEntryResponse from(TradeJournalEntry j) {
            return new TradeJournalEntryResponse(j.id(), j.symbol(), j.entryTime(), j.title(), j.body(),
                    j.sentiment(), j.tags(), j.linkedWatchlistId(), j.createdAt(), j.updatedAt());
        }
    }

    public record CreateTradeJournalEntryRequest(
            String symbol,
            Instant entryTime,
            String title,
            String body,
            String sentiment,
            String tags,
            UUID linkedWatchlistId) {
        public TradeJournalEntry toDomain() {
            return new TradeJournalEntry(null, symbol, entryTime, title, body, sentiment, tags,
                    linkedWatchlistId, null, null);
        }
    }

    // -------- Indicators --------

    public record IndicatorSnapshotResponse(
            UUID id,
            String symbol,
            String timeframe,
            String indicatorName,
            Integer period,
            Instant computedAt,
            BigDecimal valueNumeric,
            String valueJson,
            Instant createdAt) {
        public static IndicatorSnapshotResponse from(IndicatorSnapshot s) {
            return new IndicatorSnapshotResponse(s.id(), s.symbol(), s.timeframe(), s.indicatorName(),
                    s.period(), s.computedAt(), s.valueNumeric(), s.valueJson(), s.createdAt());
        }
    }
}
