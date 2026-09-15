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

`GET /api/signer/identity` · `POST /api/signer/sign` — both require `X-Signer-Token`.
`SIGNER_ENABLED=false` is the emergency stop on this side; `CRYPTOBOT_EXECUTION_ENABLED=false`
is the one on the service side. Either alone is enough.

The Solana wire classes under `signer/solana` are a copy of the service's
`adapters/solana` (copy-not-reuse, so the signer does not depend on the service jar).
