-- V18 — KAN-1249 (b2b): the last block height at which a signed swap can still land (its blockhash's
-- lastValidBlockHeight), stored with the signature before the broadcast. Reconciliation uses it: once the chain is
-- past this height and still does not know the signature, the swap never landed and never will.
ALTER TABLE swap_order ADD COLUMN last_valid_block_height BIGINT;
