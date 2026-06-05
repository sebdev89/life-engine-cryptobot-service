package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.domain.MarketReviewRun;
import io.lifeengine.cryptobot.domain.MarketReviewRunStatus;
import io.lifeengine.cryptobot.domain.MarketReviewVerdict;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Row;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Postgres R2DBC implementation of {@link MarketReviewRunRepository}. Uses {@link DatabaseClient}
 * directly so the {@code metadata_json} JSONB column can be (de)serialised via
 * {@link Json}. The {@link Profile @Profile("!test")} keeps this bean out of the test slice — the
 * test context uses a Mockito stub from {@code StubRepositoriesConfiguration}.
 */
@Profile("!test")
@Component
public class MarketReviewRunR2dbcStore implements MarketReviewRunRepository {

    private static final Logger log = LoggerFactory.getLogger(MarketReviewRunR2dbcStore.class);
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};

    private static final String INSERT_SQL =
            """
            INSERT INTO market_review_run (
                id, symbol, runtime_run_id, workflow_id, status, requested_by,
                started_at, finished_at, verdict, summary,
                linked_journal_id, linked_observation_id, metadata_json
            ) VALUES (
                :id, :symbol, :runtime_run_id, :workflow_id, :status, :requested_by,
                :started_at, :finished_at, :verdict, :summary,
                :linked_journal_id, :linked_observation_id, :metadata_json
            )
            """;

    private static final String UPDATE_SQL =
            """
            UPDATE market_review_run SET
                symbol                 = :symbol,
                workflow_id            = :workflow_id,
                status                 = :status,
                requested_by           = :requested_by,
                started_at             = :started_at,
                finished_at            = :finished_at,
                verdict                = :verdict,
                summary                = :summary,
                linked_journal_id      = :linked_journal_id,
                linked_observation_id  = :linked_observation_id,
                metadata_json          = :metadata_json
            WHERE id = :id
            """;

    private static final String SELECT_COLUMNS =
            """
            id, symbol, runtime_run_id, workflow_id, status, requested_by,
            started_at, finished_at, verdict, summary,
            linked_journal_id, linked_observation_id, metadata_json,
            created_at, updated_at
            """;

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    public MarketReviewRunR2dbcStore(DatabaseClient databaseClient, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<MarketReviewRun> insert(MarketReviewRun run) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient
                .sql(INSERT_SQL)
                .bind("id", run.id())
                .bind("symbol", run.symbol())
                .bind("runtime_run_id", run.runtimeRunId())
                .bind("workflow_id", run.workflowId())
                .bind("status", run.status().name())
                .bind("started_at", run.startedAt())
                .bind("metadata_json", jsonOf(run.metadata()));
        spec = bindCommonNullables(spec, run);
        return spec.fetch().rowsUpdated().then(Mono.defer(() -> findById(run.id())));
    }

    @Override
    public Mono<MarketReviewRun> update(MarketReviewRun run) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient
                .sql(UPDATE_SQL)
                .bind("id", run.id())
                .bind("symbol", run.symbol())
                .bind("workflow_id", run.workflowId())
                .bind("status", run.status().name())
                .bind("started_at", run.startedAt())
                .bind("metadata_json", jsonOf(run.metadata()));
        spec = bindCommonNullables(spec, run);
        return spec.fetch().rowsUpdated().then(Mono.defer(() -> findById(run.id())));
    }

    private static DatabaseClient.GenericExecuteSpec bindCommonNullables(
            DatabaseClient.GenericExecuteSpec spec, MarketReviewRun run) {
        DatabaseClient.GenericExecuteSpec next = spec;
        next = bindNullable(next, "requested_by", run.requestedBy(), String.class);
        next = bindNullable(next, "finished_at", run.finishedAt(), Instant.class);
        next = bindNullable(
                next, "verdict", run.verdict() == null ? null : run.verdict().name(), String.class);
        next = bindNullable(next, "summary", run.summary(), String.class);
        next = bindNullable(next, "linked_journal_id", run.linkedJournalId(), UUID.class);
        next = bindNullable(next, "linked_observation_id", run.linkedObservationId(), UUID.class);
        return next;
    }

    private static <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        if (value == null) {
            return spec.bindNull(name, type);
        }
        return spec.bind(name, value);
    }

    @Override
    public Mono<MarketReviewRun> findById(UUID id) {
        return databaseClient
                .sql("SELECT " + SELECT_COLUMNS + " FROM market_review_run WHERE id = :id")
                .bind("id", id)
                .map((row, meta) -> mapRow(row))
                .one();
    }

    @Override
    public Mono<MarketReviewRun> findByRuntimeRunId(UUID runtimeRunId) {
        return databaseClient
                .sql(
                        "SELECT "
                                + SELECT_COLUMNS
                                + " FROM market_review_run WHERE runtime_run_id = :rid")
                .bind("rid", runtimeRunId)
                .map((row, meta) -> mapRow(row))
                .one();
    }

    @Override
    public Mono<MarketReviewRun> findLatestBySymbol(String symbol) {
        return databaseClient
                .sql(
                        "SELECT "
                                + SELECT_COLUMNS
                                + " FROM market_review_run WHERE symbol = :sym"
                                + " ORDER BY started_at DESC LIMIT 1")
                .bind("sym", normalize(symbol))
                .map((row, meta) -> mapRow(row))
                .one();
    }

    @Override
    public Flux<MarketReviewRun> findRecentBySymbol(String symbol, int limit) {
        return databaseClient
                .sql(
                        "SELECT "
                                + SELECT_COLUMNS
                                + " FROM market_review_run WHERE symbol = :sym"
                                + " ORDER BY started_at DESC LIMIT :limit")
                .bind("sym", normalize(symbol))
                .bind("limit", limit)
                .map((row, meta) -> mapRow(row))
                .all();
    }

    private MarketReviewRun mapRow(Row row) {
        return new MarketReviewRun(
                row.get("id", UUID.class),
                row.get("symbol", String.class),
                row.get("runtime_run_id", UUID.class),
                row.get("workflow_id", String.class),
                MarketReviewRunStatus.valueOf(row.get("status", String.class)),
                row.get("requested_by", String.class),
                row.get("started_at", Instant.class),
                row.get("finished_at", Instant.class),
                verdictOf(row.get("verdict", String.class)),
                row.get("summary", String.class),
                row.get("linked_journal_id", UUID.class),
                row.get("linked_observation_id", UUID.class),
                readJsonMap(row.get("metadata_json", Json.class)),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class));
    }

    private static MarketReviewVerdict verdictOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MarketReviewVerdict.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return MarketReviewVerdict.UNKNOWN;
        }
    }

    private static String normalize(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private Json jsonOf(Map<String, Object> values) {
        try {
            return Json.of(objectMapper.writeValueAsString(values == null ? Map.of() : values));
        } catch (Exception e) {
            log.warn("Failed to serialise market_review_run metadata; storing '{{}}': {}", e.toString());
            return Json.of("{}");
        }
    }

    private Map<String, Object> readJsonMap(Json json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json.asString(), METADATA_TYPE);
        } catch (Exception e) {
            log.warn("Failed to deserialise market_review_run metadata; returning empty: {}", e.toString());
            return Map.of();
        }
    }
}
