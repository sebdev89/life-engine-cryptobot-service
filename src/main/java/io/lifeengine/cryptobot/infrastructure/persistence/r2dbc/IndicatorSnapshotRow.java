package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import io.lifeengine.cryptobot.domain.IndicatorSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("indicator_snapshot")
public class IndicatorSnapshotRow {

    @Id private UUID id;
    private String symbol;
    private String timeframe;

    @Column("indicator_name")
    private String indicatorName;

    private Integer period;

    @Column("computed_at")
    private Instant computedAt;

    @Column("value_numeric")
    private BigDecimal valueNumeric;

    @Column("value_json")
    private String valueJson;

    @Column("created_at")
    private Instant createdAt;

    public IndicatorSnapshot toDomain() {
        return new IndicatorSnapshot(id, symbol, timeframe, indicatorName, period, computedAt,
                valueNumeric, valueJson, createdAt);
    }

    public static IndicatorSnapshotRow fromDomain(IndicatorSnapshot s) {
        IndicatorSnapshotRow row = new IndicatorSnapshotRow();
        row.id = s.id();
        row.symbol = s.symbol();
        row.timeframe = s.timeframe();
        row.indicatorName = s.indicatorName();
        row.period = s.period();
        row.computedAt = s.computedAt();
        row.valueNumeric = s.valueNumeric();
        row.valueJson = s.valueJson();
        row.createdAt = s.createdAt();
        return row;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
    public String getIndicatorName() { return indicatorName; }
    public void setIndicatorName(String indicatorName) { this.indicatorName = indicatorName; }
    public Integer getPeriod() { return period; }
    public void setPeriod(Integer period) { this.period = period; }
    public Instant getComputedAt() { return computedAt; }
    public void setComputedAt(Instant computedAt) { this.computedAt = computedAt; }
    public BigDecimal getValueNumeric() { return valueNumeric; }
    public void setValueNumeric(BigDecimal valueNumeric) { this.valueNumeric = valueNumeric; }
    public String getValueJson() { return valueJson; }
    public void setValueJson(String valueJson) { this.valueJson = valueJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
