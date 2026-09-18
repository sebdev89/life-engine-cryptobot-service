# cryptobot-signer

The only CryptoBot process that holds a private key. It is deliberately dumb:

- **Input:** an unsigned legacy Solana transaction (base64) + the proposal id.
- **Checks, from the bytes themselves:** one signer and it is us · one instruction and it is
  `SystemProgram.transfer` · from us · to an allow-listed destination · lamports ≤ cap.
- **Output:** the same bytes, signed. It never broadcasts, never calls an RPC, never sees the
  LLM, the policy engine or the database.

```bash
export SIGNER_KEYPAIR_PATH=~/.cryptobot-demo/demo-wallet.json   # solana-keygen id.json, 64 bytes
export SIGNER_TOKEN='<shared service token>'                     # same value as CRYPTOBOT_SIGNER_TOKEN
export SIGNER_ALLOWED_DESTINATIONS=<rebalance vault pubkey>
export SIGNER_MAX_LAMPORTS=2000000000                            # 2 SOL
mvn -f signer/pom.xml spring-boot:run                            # :8096
```

`GET /api/signer/identity` · `POST /api/signer/sign` · `POST /api/signer/sign-anchor` — all require
`X-Signer-Token`. `SIGNER_ENABLED=false` is the emergency stop on this side;
`CRYPTOBOT_EXECUTION_ENABLED=false` is the one on the service side. Either alone is enough.

`sign-anchor` (KAN-394) is the only non-transfer this signer signs: a transaction whose single
instruction is an SPL Memo with **no accounts** and whose text is exactly
`ir/1 root=<sha256> n=<count> ts=<…>` for the `root`/`receiptCount` the caller claims — the
signer re-derives it from the bytes, never from the request. Refused on any cluster but devnet
(`anchor_cluster_not_devnet`). The memo moves nothing; the fee payer is the signer key.

The Solana wire classes under `signer/solana` are a copy of the service's
`adapters/solana` (copy-not-reuse, so the signer does not depend on the service jar).
