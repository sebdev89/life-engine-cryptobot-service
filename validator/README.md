# cryptobot-validator

The **independent validator** of paper §20 — level 5 of §28 (*intent → independent validator →
limited signer → on-chain verification*). A process that is **not** the agent: it holds its own
copy of the policy, pinned by hash, re-derives every verdict with its own implementation of the
decision table, and attests — with a key that moves no funds — that the exact bytes about to be
signed were authorized under that policy. `cryptobot-signer` signs nothing without that
attestation.

- **Input** (`POST /api/validator/validate`, `X-Validator-Token`): `proposalId`, the `policyHash`
  the agent claims it decided under, the `expectedVerdictHash` it recorded, the `messageHash`
  (SHA-256 hex of the transaction message bytes) and the facts `intent` / `state` in the schema's
  snake_case (`trade_value_cents`, `oracle_age_seconds`, `agent_permitted`, …). A missing key, a
  wrong type or a non-integer number is an **unknown** fact, never coerced — and unknown fails
  its predicate (§17).
- **Checks:** `VALIDATOR_DISABLED` · `POLICY_HASH_MISMATCH` (claimed `H_R` ≠ the one pinned here)
  · the eleven predicates of `IndependentPolicyTable` · `VERDICT_DISAGREEMENT` (the agent's
  recorded verdict hash ≠ the one derived here). Any of them ⇒ `DENY`.
- **Output:** the verdict (decision, escalation, tier, failed predicates, refusals, `H_R`, input
  hash, verdict hash) and an **attestation**: canonical JSON `{schema_version, proposal_id,
  message_hash, policy_hash, input_hash, verdict_hash, decision, escalation, validator, issued_at,
  expires_at}` signed with Ed25519, valid `VALIDATOR_ATTESTATION_TTL` (90 s — a Solana blockhash
  lives about that long). A DENY is attested too: it is evidence, and the signer refuses it.
- `GET /api/validator/identity` → public key, policy version, `H_R`, `pinned`, `enabled`.

```bash
# attestation key: solana-keygen layout, 64 bytes; NOT the wallet key, never the same file
solana-keygen new -o ~/.cryptobot-demo/validator.json --no-bip39-passphrase
export VALIDATOR_KEYPAIR_PATH=~/.cryptobot-demo/validator.json
export VALIDATOR_TOKEN='<shared service token>'                  # same value as CRYPTOBOT_VALIDATOR_TOKEN
export VALIDATOR_POLICY_HASH=sha256:…                             # H_R the operator approved (see below)
mvn -f validator/pom.xml spring-boot:run                         # :8097
```

## The policy, pinned by hash

`validator.policy.*` is this process's **own** `R_v`, in the integer units of the schema (cents,
basis points, seconds). No value has a default: a missing limit is a policy that does not load,
and **the process does not start**. `VALIDATOR_POLICY_HASH` pins `H_R`: if the loaded policy
hashes to anything else the process refuses to start too. Empty ⇒ it starts with a WARN that
prints the hash to pin.

It must hash to the same `H_R` as the service's `cryptobot.policy.authorization` (the service
converts USD to cents rounding down, minutes to seconds): the defaults on both sides agree
(`$500 / $2 500 / $100 / $250`, 15 min → `50000 / 250000 / 10000 / 25000`, 900). Otherwise every
validation is `POLICY_HASH_MISMATCH` and every execution fails closed — which is the point:
changing the policy is a two-process change, and the agent alone cannot do it.

## What "independent" means here

- No shared jar with the service (copy-not-reuse, like `signer/`): `validator/policy` is a copy of
  the value types and the canonicalizer, and `IndependentPolicyTable` is a **second
  implementation** of the §8 decision table, hand-inlined, that must reproduce the same golden
  vectors (`src/test/resources/policy/vectors-v1.json`, hashes made with `sha256sum`) hash for
  hash. Two independent readings of the schema agree, or nothing is signed.
- The service compares the returned `H_R` and verdict hash with what it recorded and refuses on
  any difference; the signer verifies the attestation with the **pinned** validator key for the
  **exact** bytes it decodes. A compromised service can ask; it cannot attest.
- What it does **not** verify yet: that the recorded facts are true (post-MVP: read state from
  the chain) and that the human approval happened (post-MVP: the human signs the intent hash).

`VALIDATOR_ENABLED=false` is this process's emergency stop: every verdict becomes DENY.
