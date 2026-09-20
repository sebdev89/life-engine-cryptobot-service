package io.lifeengine.cryptobot.domain.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * KAN-571: {@code retries} / {@code previousSignature} were added to the execution record that
 * lives inside the proposal's JSONB document. Documents written before the change must still read
 * (retries 0, no previous signature), and the new fields must round-trip.
 */
class ExecutionRecordJsonTest {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void legacyDocumentWithoutTheNewFieldsReadsAsFirstAttempt() throws Exception {
        String legacy = "{\"status\":\"SUBMITTED\",\"signature\":\"5ig\",\"explorerUrl\":\"https://x\",\"signerPublicKey\":\"pk\",\"submittedAt\":\"2026-09-20T10:00:00Z\","
                + "\"confirmedAt\":null,\"confirmationStatus\":null,\"error\":null,\"recentBlockhash\":\"bh\",\"lastValidBlockHeight\":1000,\"reconciliationAttempts\":2,\"reconciledAt\":null}";
        ExecutionRecord rec = json.readValue(legacy, ExecutionRecord.class);
        assertThat(rec.retries()).isZero();
        assertThat(rec.previousSignature()).isNull();
        assertThat(rec.signature()).isEqualTo("5ig");
        assertThat(rec.reconciliationAttempts()).isEqualTo(2);
    }

    @Test
    void retriedRecordRoundTrips() throws Exception {
        ExecutionRecord first = new ExecutionRecord(ExecutionRecord.SIGNED, "sig1", "u1", "pk", null, null, null, null, "bh1", 1000L, 3, Instant.parse("2026-09-20T10:00:00Z"));
        ExecutionRecord retried = first.retriedWith("sig2", "u2", "pk", "bh2", 2000L);
        assertThat(retried.status()).isEqualTo(ExecutionRecord.SIGNED);
        assertThat(retried.retries()).isEqualTo(1);
        assertThat(retried.previousSignature()).isEqualTo("sig1");
        assertThat(retried.reconciliationAttempts()).isZero();
        assertThat(retried.lastValidBlockHeight()).isEqualTo(2000L);

        ExecutionRecord back = json.readValue(json.writeValueAsString(retried), ExecutionRecord.class);
        assertThat(back).isEqualTo(retried);
        assertThat(back.withReconciliationAttempts(5, "parked", Instant.EPOCH).retries()).isEqualTo(1);
        assertThat(back.withSubmitted(Instant.EPOCH).previousSignature()).isEqualTo("sig1");
    }
}
