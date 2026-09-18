-- V7 — KAN-392: what makes an L1 receipt re-executable (Endgame §5, §10).
--
-- deterministic_inference — one row per receipt issued by a deterministic engine (today: the
--   risk engine). The receipt carries the hashes of the canonical input and output; this row
--   carries the trees themselves, plus the engine id + version (the code) and the weights hash
--   (the numbers). `verify` recomputes both hashes from the trees, re-runs the engine named here
--   with the weights it ships, and compares the output hash with the receipt's — a stored hash is
--   never trusted on its own. Written in the same transaction as the receipt; no update path.
--
-- Nothing secret: the input is the quantised portfolio (mints, basis points, micro-dollars), the
-- output the discrete verdict. Owner scoping goes through the receipt.

CREATE TABLE deterministic_inference (
    receipt_hash    VARCHAR(71)  PRIMARY KEY REFERENCES intelligence_receipt (receipt_hash),
    tenant_id       VARCHAR(64)  NOT NULL,
    engine_id       VARCHAR(32)  NOT NULL,
    engine_version  VARCHAR(16)  NOT NULL,
    weights_hash    VARCHAR(71)  NOT NULL,
    input           JSONB        NOT NULL,
    input_hash      VARCHAR(71)  NOT NULL,
    output          JSONB        NOT NULL,
    output_hash     VARCHAR(71)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_deterministic_inference_weights CHECK (weights_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_deterministic_inference_input   CHECK (input_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_deterministic_inference_output  CHECK (output_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_deterministic_inference_engine ON deterministic_inference (engine_id, engine_version, weights_hash);
