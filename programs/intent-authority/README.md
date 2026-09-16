# intent-authority — CryptoBot's on-chain authority (paper level 4, KAN-437)

Levels 1–3 live in the service: canonical intent and `H_I` (KAN-435), deterministic policy and
`H_R` (KAN-436), Ed25519 signatures and the 10 000-intent benchmark (KAN-440). All of that is
**off-chain**: a compromised service could still lie about it. This program makes four facts
unforgeable by the client, because the validator checks them, not the service:

| invariant | what the program verifies | refusal (`Custom(n)`) |
|---|---|---|
| **I1** authorized agent | the transaction is signed by `agent`, and the policy account is the PDA of `(agent, policy_version)` that an authority registered and did not revoke | `AgentSignatureMissing` 0 · `PolicyAccountMismatch` 1 · `PolicyNotRegistered` 2 · `PolicyRevoked` 3 |
| **I4** policy binding | the `policy_hash` the intent claims equals the `H_R` committed in that PDA | `PolicyHashMismatch` 4 |
| **I2** not expired | `Clock.slot ≤ valid_until_slot` | `IntentExpired` 5 |
| **I3** single execution | the receipt PDA of `H_I` does not exist, and the nonce PDA of `(agent, nonce)` does not exist | `IntentAlreadyExecuted` 7 · `NonceAlreadyUsed` 6 |

Only when all hold does it create the nonce account and the receipt account **atomically**. The
"execution" at this level is the receipt itself (paper §12): no transfer, no swap — the issue's
minimal scope, devnet only. The AI is nowhere near this: it emitted an intent; the service hashed
it; the agent key signed a transaction carrying the hash; the chain checked the hash, not the
reasoning (§10, §27).

```
policy  = PDA["policy",  agent(32), policy_version(u32 LE)]   H_R commitment — immutable, revocable
nonce   = PDA["nonce",   agent(32), nonce(u64 LE)]            exists ⇔ consumed
receipt = PDA["receipt", intent_hash(32)]                     exists ⇔ this H_I executed
```

Anti-replay is account creation: the runtime cannot create the same address twice. "Exists" means
*initialized by this program* (owner + data), so a stranger sending lamports to a nonce PDA does
not burn the nonce (`prefunding_a_nonce_pda_does_not_burn_the_nonce`).

## Layout

| file | what |
|---|---|
| `src/rules.rs` | `check_execute(facts)` — the decision as a pure function, in the order I1 → I4 → I2 → I3. Unit-tested without a runtime. |
| `src/lib.rs` | the processor: collects the facts from signed accounts, PDAs and `Clock`, refuses if it cannot, then writes the two accounts |
| `src/state.rs` | `Policy` (111 B) · `Nonce` (82 B) · `Receipt` (126 B), borsh with a leading discriminator |
| `src/instruction.rs` | `RegisterPolicy` · `RevokePolicy` · `Execute` (borsh enum) |
| `src/error.rs` | the 13 refusals; numbers are the contract, append only |
| `tests/program.rs` | 14 scenarios against a real bank (`solana-program-test`, native processor): the happy path, every invariant broken through a signed transaction, revoke, immutability, griefing, malformed PDAs |
| `tests/vectors.rs` | writes/asserts `../../src/test/resources/authority/vectors-v1.json`: PDAs, bumps, instruction bytes, account bytes and 30 on-curve answers from the SDK |
| `scripts/deploy-devnet.sh` | `cargo build-sbf` + `solana program deploy`, devnet only |

No `declare_id!`: every PDA is derived from the `program_id` the program is invoked with, so the
same binary is valid under any deployed address.

## Java side

`io.lifeengine.cryptobot.adapters.solana.authority`:

- `ProgramDerivedAddress` — `find` / `create` / `isOnCurve` exactly as the SDK (dalek decompression semantics), pinned against the 30 SDK answers.
- `IntentAuthorityProgram` — the three PDAs, the three instructions as `LegacyTransaction.Instruction`, the three account decoders, and `AuthorityError` (code → name → invariant).

Both sides assert the **same** `vectors-v1.json`; the Rust test regenerates it from the SDK, the
Java tests must reproduce it byte for byte. v1 freezes with the first devnet deployment.

## Tests

```bash
cd programs/intent-authority
cargo test                       # 13 unit + 14 bank-simulator + 2 vectors (host, no SBF toolchain needed)
cargo test --test vectors -- --ignored write_vectors   # regenerate the shared vectors (then re-run the Java tests)
```

## Deploy (devnet, human-driven)

Requires the Solana CLI (`cargo build-sbf`, `solana`), which is **not** part of this repo's
build and is not installed by CI. See `scripts/deploy-devnet.sh`. Wiring the service to the
deployed program (`cryptobot.authority.program-id`, agent signature by the isolated signer,
execute after human approval, receipt read-back into the audit trail) is the next issue in the
epic, not this one.
