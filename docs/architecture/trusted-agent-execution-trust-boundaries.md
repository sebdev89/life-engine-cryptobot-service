# Trusted Agent Execution — trust boundaries of the actual implementation

Drawn from `life-engine-cryptobot-service` at `2b69b37` and the UAT deployments (uat-compose `deploy/uat/docker-compose.uat.yml`, k8s-uat `gitops/overlays/uat/cryptobot`). This is what runs today, not the target. Every box names the file that proves it. Companion: [`TRUSTED-AGENT-EXECUTION-AUDIT.md`](TRUSTED-AGENT-EXECUTION-AUDIT.md) §6, §8, §9, §14.

## Where trust changes

```mermaid
flowchart LR
  subgraph UNTRUSTED["UNTRUSTED INPUT"]
    U["Human operator<br/>JWT RUNTIME_OPERATOR<br/>(proposes, approves, executes — same role)"]
    LLM["Runtime LLM<br/>crypto.portfolio-advisor.v1<br/>text/JSON + runtimeRunId only<br/>AdvisorService.java:36-44"]
    MD["Market data<br/>Jupiter · Pyth · CoinGecko · Coinbase<br/>quorum ≥2, median, deviation, breaker<br/>PriceOracle / PolicyEngine.priceViolations:342-411"]
  end

  subgraph SERVICE["cryptobot-service (TB-1: application trust)"]
    direction TB
    API["REST /api/cryptobot/**<br/>identity from JWT only — Principals.java:16-19<br/>no bypass endpoint (audit A §10)"]
    PIPE["ProposalService → PolicyEngine (21 rules)<br/>DeterministicPolicyEngine (11 predicates, R_v, H_R)<br/>ExecutionService (preconditions, mainnet gate #1)"]
    SIM["SimulationService<br/>builds SystemProgram.transfer(wallet→vault)<br/>destination = config rebalance-vault (:75)"]
    REC["ReconciliationService · OutboxPublisher · DeadLetterService"]
    RCPT["ReceiptService<br/>Ed25519 receipt key CRYPTOBOT_RECEIPT_SIGNING_KEY<br/>(ephemeral if unset — ReceiptKeyConfig.java:24-37)"]
    API --> PIPE --> SIM
    PIPE --> REC
    PIPE --> RCPT
  end

  subgraph DB["Postgres life_engine_cryptobot (TB-2: DB truth)"]
    AP["action_proposal<br/>status CHECK (11), version, operation_id UNIQUE<br/>doc JSONB: intent, plan, policy(I,S,H_R,verdict), simulation,<br/>transaction, approval, execution(SIGNED/SUBMITTED/…)"]
    AE["audit_event (append-only by convention)"]
    OB["outbox_event · dead_letter"]
    IR["intelligence_receipt · receipt_edge · receipt_anchor"]
  end

  subgraph VALIDATOR["cryptobot-validator (TB-3: independent policy)"]
    V["POST /api/validator/validate<br/>X-Validator-Token<br/>own R_v copy, pin VALIDATOR_POLICY_HASH<br/>(UNPINNED in UAT: compose :1020, validator.env:19)<br/>re-derives verdict over (I,S) supplied by the service<br/>does NOT decode tx bytes, no RPC, no DB<br/>ValidationService.java:194-265"]
    VK["Attestation key<br/>VALIDATOR_KEYPAIR_JSON (SOPS in k8s; .env.uat in compose)<br/>Ed25519 over {proposal_id, message_hash, cluster,<br/>policy_hash, input_hash, verdict_hash, decision, expiry}"]
    V --- VK
  end

  subgraph SIGNER["cryptobot-signer (TB-4: THE PRIVATE KEY)"]
    S["POST /api/signer/sign<br/>X-Signer-Token<br/>decodes bytes itself — SigningPolicy.java:194<br/>ONE shape: 1 sig · fee payer = self · 1 instruction · System transfer<br/>destination ∈ SIGNER_ALLOWED_DESTINATIONS · ≤ SIGNER_MAX_LAMPORTS<br/>cluster = SIGNER_CLUSTER · SIGNER_ALLOW_MAINNET=false<br/>attestation REQUIRED (AttestationVerifier.java:92-156)<br/>no RPC, no DB, non-root"]
    SK["Wallet private key<br/>SIGNER_KEYPAIR_JSON / _PATH<br/>uat-compose: ENV VAR from .env.uat (compose :1063)<br/>k8s-uat: SOPS Secret cryptobot-signer-secret<br/>demo: file :ro"]
    SA["POST /sign-anchor<br/>devnet memo only, no attestation, no rate limit"]
    S --- SK
    SA --- SK
  end

  subgraph CHAIN["Solana devnet (TB-5: chain truth)"]
    RPC["api.devnet.solana.com (single provider)<br/>simulateTransaction · sendTransaction (mainnet gate #2)<br/>getSignatureStatuses · getTransaction"]
    W["Wallet G4bCRq…4exS → vault FrBNyf…6BmX<br/>12 confirmed transfers, 0 failed, 1 memo anchor"]
    PROG["programs/intent-authority<br/>NOT deployed, NOT wired"]
    RPC --> W
  end

  U -->|"JWT"| API
  LLM -->|"advice only; cannot create/execute"| API
  MD -->|"prices (untrusted, quorum)"| PIPE
  PIPE <-->|"commit(version, status) + audit + outbox in one tx"| AP
  PIPE --> AE
  REC <--> OB
  RCPT --> IR
  SIM -->|"simulateTransaction sigVerify=false"| RPC
  PIPE -->|"(I,S), H_R, verdictHash, SHA-256(message), cluster"| V
  V -->|"attestation (90 s)"| PIPE
  PIPE -->|"unsigned wire + attestation"| S
  S -->|"signed tx (service verifies sig vs wallet pubkey)"| PIPE
  PIPE -->|"sendTransaction (persisted SIGNED first)"| RPC
  REC -->|"getSignatureStatuses / getBlockHeight"| RPC
  RCPT -->|"anchor memo via sign-anchor"| SA
  SA --> RPC

  classDef key fill:#7a1f1f,color:#fff,stroke:#000;
  classDef warn fill:#8a6d00,color:#fff,stroke:#000;
  classDef truth fill:#1f4d7a,color:#fff,stroke:#000;
  class SK,VK key;
  class SA,V warn;
  class AP,AE,IR,W truth;
```

## Reading the diagram

| Boundary | What is trusted on the far side | What crosses it | Where it is weakest today |
|---|---|---|---|
| **TB-1 application** (`cryptobot-service`) | Nothing from the Internet; the JWT `sub` is the tenant. The service holds the validator and signer tokens, the receipt signing key, the DB credentials and the S2S client secret. | HTTP with JWT; Runtime advice; market prices. | A compromised service can *ask* for signatures of any `transfer(wallet → allow-listed destination, ≤ cap)` and any number of anchor memos; it cannot obtain any other shape. It can fabricate `(I,S)` facts because the validator does not verify them. |
| **TB-2 DB truth** | `action_proposal.status` + `version` is the truth of *what the system decided*; `audit_event` is the causal history; receipts are the *verifiable* copy (signed, content-addressed). | Version-guarded transactions. | `messageHash` and the attestation payload are not stored; audit append-only is not enforced by the DB; receipt key ephemeral if unset. |
| **TB-3 independent policy** (`cryptobot-validator`) | Its own copy of `R_v`; its own key; a hand-inlined predicate table. | `(I,S)` facts, hashes, `cluster` — *declared by the service*. | Does not see the bytes or the chain; `current_slot`/`nonce_unused` are frozen values from proposal time; unpinned in UAT. |
| **TB-4 the private key** (`cryptobot-signer`) | Only the bytes it decodes and the attestation it verifies. Trusts the validator's public key (pinned) and its own config. | Unsigned transaction wire + attestation. | Key delivered as an env var in uat-compose; no genesis-hash check (cluster label declarative); no velocity cap; `sign-anchor` without attestation (devnet fee drain only). |
| **TB-5 chain truth** | Finality of the signature (`confirmed`/`finalized`), block height for expiry. | JSON-RPC to one public provider. | Single RPC; the on-chain authority program is not deployed, so no on-chain policy/nonce enforcement exists. |

## Where the private keys are (today)

| Key | Process | Delivery (uat-compose) | Delivery (k8s-uat) | Delivery (demo) |
|---|---|---|---|---|
| Wallet (devnet) | signer only | **env var** `SIGNER_KEYPAIR_JSON` ← `CRYPTOBOT_DEVNET_WALLET_KEY` in `.env.uat` (0600) | SOPS Secret `cryptobot-signer-secret` → `secretKeyRef` | file `~/.cryptobot-demo/*.json` mounted `:ro` |
| Validator attestation | validator only | env var `VALIDATOR_KEYPAIR_JSON` | SOPS Secret `cryptobot-validator-secret` | file `:ro` |
| Receipt signing | service | `CRYPTOBOT_RECEIPT_SIGNING_KEY` (ephemeral if absent) | idem | idem |
| Vault | nobody (private key discarded by `cryptobot-devnet-credentials.sh`) | pubkey `CRYPTOBOT_REBALANCE_VAULT` | pubkey in `cryptobot.env` | pubkey |
| Tokens (service↔signer, service↔validator, S2S) | service + counterpart | `.env.uat` | SOPS `cryptobot-secret` | `.env.demo` |

No key is in git (audit §6). The LLM never sees a key, a raw RPC payload or transaction bytes (`AdvisorService`).

## Where policy lives (today)

- Service: `cryptobot.policy.*` (21 named rules, thresholds) and `cryptobot.policy.authorization.*` → `PolicyRules` (`R_v`, `H_R`) — `application.yml:200-236`.
- Validator: its own `VALIDATOR_POLICY_*` copy → `H_R'`; refuses to start if `VALIDATOR_POLICY_HASH` is set and differs; UAT does not set it.
- Signer: `SIGNER_ALLOWED_DESTINATIONS`, `SIGNER_MAX_LAMPORTS`, `SIGNER_CLUSTER`, `SIGNER_ALLOW_MAINNET`, `SIGNER_REQUIRE_ATTESTATION` — byte-level policy, independent of `R_v`.
- Chain: none (program not deployed).

## Target (after phases 1–5), same processes

```mermaid
flowchart LR
  T["cryptobot-trading<br/>strategy → trading risk → ExecutionIntent v1"] -->|"POST /intents (intentHash idempotent)"| C["trusted-execution-core<br/>policy (I from the intent) · simulation · approval (SoD) · timelock<br/>destination bound approve→execute · attestation persisted<br/>ExecutionReceipt v2 (policyHash, attestationHash, timestamps)"]
  C -->|"ports"| A["solana-execution-adapter<br/>rpc · builder · simulate · submit · observe<br/>genesis-hash check"]
  C -->|"unsigned tx + (I,S) + approval hash"| V2["validator<br/>decodes bytes, own clock,<br/>pinned H_R, attests bytes+approval"]
  V2 -->|"attestation v2"| C
  C -->|"wire + attestation"| S2["signer<br/>same one shape (+ optional memo/ComputeBudget whitelist)<br/>velocity cap · key as file secret"]
  S2 --> C
  A --> D["Solana devnet"]
```
