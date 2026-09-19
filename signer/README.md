# cryptobot-signer

The only CryptoBot process that holds a private key. It is deliberately dumb:

- **Input:** an unsigned legacy Solana transaction (base64) + the proposal id.
- **Checks, from the bytes themselves:** one signer and it is us · one instruction and it is
  `SystemProgram.transfer` · from us · to an allow-listed destination · lamports ≤ cap.
- **And, since KAN-438 (paper §20, level 5):** an **attestation** from `cryptobot-validator` —
  the validator's canonical-JSON payload and its Ed25519 signature. The signer verifies the
  signature with the pinned `SIGNER_VALIDATOR_PUBLIC_KEY`, that `proposal_id` is this proposal,
  that `message_hash` is SHA-256 of the message bytes it just decoded, that `decision` is ALLOW
  or ESCALATE, and that it is inside `[issued_at, expires_at]` (30 s skew). Any failure is a 403
  with the reason (`attestation_missing`, `attestation_bad_signature`,
  `attestation_message_mismatch`, `attestation_denied`, `attestation_expired`, …). Without a
  pinned validator key **nothing is ever signed**.
- **Output:** the same bytes, signed. It never broadcasts, never calls an RPC, never sees the
  LLM, the policy engine or the database, and never re-evaluates the policy: it cannot be
  talked into that, only shown a valid attestation.

```bash
export SIGNER_KEYPAIR_PATH=~/.cryptobot-demo/demo-wallet.json   # solana-keygen id.json, 64 bytes
export SIGNER_TOKEN='<shared service token>'                     # same value as CRYPTOBOT_SIGNER_TOKEN
export SIGNER_ALLOWED_DESTINATIONS=<rebalance vault pubkey>
export SIGNER_MAX_LAMPORTS=2000000000                            # 2 SOL
export SIGNER_VALIDATOR_PUBLIC_KEY=<base58 pubkey of the validator's attestation key>
mvn -f signer/pom.xml spring-boot:run                            # :8096
```

`GET /api/signer/identity` · `POST /api/signer/sign` · `POST /api/signer/sign-anchor` — all require
`X-Signer-Token`. The sign body is `{proposalId, unsignedTransactionBase64, expectedFeePayer, cluster,
attestation: {payload, signature}}`. `SIGNER_ENABLED=false` is the emergency stop on this side;
`CRYPTOBOT_EXECUTION_ENABLED=false` is the one on the service side; `VALIDATOR_ENABLED=false` the
one on the validator's. Any one alone is enough. `SIGNER_REQUIRE_ATTESTATION=false` restores the
pre-KAN-438 behaviour and is for tests and empty demo wallets only — since KAN-493 it is accepted
only under the Spring profile `local` or `test`; any other profile refuses to start.

Mainnet is fail-closed here too (KAN-493): `cluster` is required in the sign request, must be the
cluster this signer is configured for (`SIGNER_CLUSTER`), and `mainnet-beta` is refused with
`403 mainnet_disabled` unless `SIGNER_ALLOW_MAINNET=true` (default `false`) — whatever the
attestation says. The attestation must carry the same `cluster` (`attestation_cluster_mismatch`
otherwise). Independent of the service's `CRYPTOBOT_ALLOW_MAINNET`.

`sign-anchor` (KAN-394) is the only non-transfer this signer signs: a transaction whose single
instruction is an SPL Memo with **no accounts** and whose text is exactly
`ir/1 root=<sha256> n=<count> ts=<…>` for the `root`/`receiptCount` the caller claims — the
signer re-derives it from the bytes, never from the request. Refused on any cluster but devnet
(`anchor_cluster_not_devnet`). The memo moves nothing; the fee payer is the signer key. It does
not carry a validator attestation: there is no proposal behind it and no funds move — the
memo's own byte-level check is the gate.

The Solana wire classes under `signer/solana` are a copy of the service's
`adapters/solana` (copy-not-reuse, so the signer does not depend on the service jar).
