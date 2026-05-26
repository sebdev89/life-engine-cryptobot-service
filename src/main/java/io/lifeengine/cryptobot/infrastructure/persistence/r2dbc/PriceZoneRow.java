package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import io.lifeengine.cryptobot.domain.PriceZone;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("price_zone")
public class PriceZoneRow {

    @Id private UUID id;
    private String symbol;
    private String timeframe;

    @Column("zone_kind")
    private String zoneKind;

    @Column("lower_bound")
    private BigDecimal lowerBound;

    @Column("upper_bound")
    private BigDecimal upperBound;

    private BigDecimal confidence;

    @Column("valid_from")
    private Instant validFrom;

    @Column("valid_until")
    private Instant validUntil;

    private String label;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public PriceZone toDomain() {
        return new PriceZone(id, symbol, timeframe, zoneKind, lowerBound, upperBound, confidence,
                validFrom, validUntil, label, createdAt, updatedAt);
    }

    public static PriceZoneRow fromDomain(PriceZone z) {
        PriceZoneRow row = new PriceZoneRow();
        row.id = z.id();
        row.symbol = z.symbol();
        row.timeframe = z.timeframe();
        row.zoneKind = z.zoneKind();
        row.lowerBound = z.lowerBound();
        row.upperBound = z.upperBound();
        row.confidence = z.confidence();
        row.validFrom = z.validFrom();
        row.validUntil = z.validUntil();
        row.label = z.label();
        row.createdAt = z.createdAt();
        row.updatedAt = z.updatedAt();
        return row;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
    public String getZoneKind() { return zoneKind; }
    public void setZoneKind(String zoneKind) { this.zoneKind = zoneKind; }
    public BigDecimal getLowerBound() { return lowerBound; }
    public void setLowerBound(BigDecimal lowerBound) { this.lowerBound = lowerBound; }
    public BigDecimal getUpperBound() { return upperBound; }
    public void setUpperBound(BigDecimal upperBound) { this.upperBound = upperBound; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
