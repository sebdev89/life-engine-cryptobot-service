-- V9 — KAN-571 / KAN-501: the dead-letter queue gets a way out.
--
-- Until now `resolved_at` existed but nothing ever wrote it (DeadLetterRepository was append /
-- findByProposal / countUnresolved). A human now resolves a letter through the API:
--   * resolved_by  — the actor (JWT subject/email) who closed it;
--   * resolution   — their note (why, what was verified on the explorer);
--   * outcome      — RESOLVED (closed by hand) | REQUEUED (given back to the system: outbox event
--                    PENDING again, or the trade reconciled/retried again under the same operationId).
-- A letter is resolved at most once (the UPDATE is guarded by resolved_at IS NULL).

ALTER TABLE dead_letter ADD COLUMN resolved_by VARCHAR(160);
ALTER TABLE dead_letter ADD COLUMN resolution  TEXT;
ALTER TABLE dead_letter ADD COLUMN outcome     VARCHAR(16);

ALTER TABLE dead_letter ADD CONSTRAINT chk_dead_letter_outcome CHECK (outcome IS NULL OR outcome IN ('RESOLVED', 'REQUEUED'));

-- The global listing (GET /api/cryptobot/dead-letters): newest first, open or all.
CREATE INDEX idx_dead_letter_created ON dead_letter (created_at DESC);
