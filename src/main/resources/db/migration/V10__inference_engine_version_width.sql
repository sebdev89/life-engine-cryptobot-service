-- V10 — KAN-572: the policy layer's Decision Receipt is an L1 inference of engine `policy-engine`
-- whose version is the policy version R_v (`cryptobot-policy-v1`, up to 128 characters by
-- PolicyRules) — wider than the 16 characters V7 sized for `risk-engine 1.0.0`.

ALTER TABLE deterministic_inference ALTER COLUMN engine_id      TYPE VARCHAR(64);
ALTER TABLE deterministic_inference ALTER COLUMN engine_version TYPE VARCHAR(128);
