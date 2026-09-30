# Auditoría A — Núcleo de ejecución (`cryptobot-service`, módulo raíz)

> Redacted for publication (2026-09-30): `ticket-NNN` references point to the private issue tracker; local paths are
> shown as `<workspace>/…` and lab hostnames/IPs as placeholders. The findings themselves are unchanged.

Worktree: `<workspace>/active/.worktrees/tae-audit-cryptobot` @ `2b69b37` (origin/main, "Merge pull request #29 from sebdev89/ticket-573-observabilidad-demo").
Alcance: `src/main/java/io/lifeengine/cryptobot/**` + `src/main/resources/**` + `src/test/**`. READ-ONLY. Rutas relativas al worktree. Todo KAN citado fue verificado con `python3 .claude/bin/jira.py get` (ver §16-bis al final).

Tamaño: 266 archivos main / 23.260 líneas; 105 archivos test / 17.817 líneas; 10 migraciones Flyway (644 líneas SQL).

---

## 1. Inventario de paquetes y responsabilidad real

| Paquete | Responsabilidad REAL (leída del código) | Clases clave | Depende de |
|---|---|---|---|
| `domain/transactions` | Agregado `ActionProposal` (JSONB doc) + `ProposalStatus` (máquina de estados) + `ProposalTransition` (commit atómico estado+audit+outbox) + `ExecutionRecord`/`SimulationOutcome`/`PreparedTransaction`/`ApprovalRecord`/`AuditEvent` | `ActionProposal.java:20-46`, `ProposalStatus.java:14-48`, `ProposalTransition.java:16-44`, `ExecutionRecord.java:19-81` | `domain/policy`, `domain/risk`, `domain/strategy`, `domain/reliability` |
| `domain/policy` | Policy determinista `(I,S,R_v)→verdict` (ticket-436): `PolicyRules` (R_v hasheado), `PolicyPredicate` (11 predicados), `DeterministicPolicyEngine.evaluate` (puro, sin I/O), `PolicyVerdict`, `PolicyInput`, `PolicyDecision` (dos capas: `allowed`/`executable`), `CanonicalJson` (JCS local, package-private) | `DeterministicPolicyEngine.java:42-87`, `PolicyRules.java:25-57`, `PolicyPredicate.java:11-33`, `PolicyDecision.java:37-46` | sólo `domain/oracle` |
| `domain/intent` | Intent canónico ticket-435: `TradingIntent` (13 campos, validación por acción), `IntentSchema` (parser fail-closed), `JsonCanonicalizer` (RFC 8785), `IntentHash` (`sha256:` + `toOperationId()`), `AssetId`, `IntentAction` | `TradingIntent.java:31-128`, `IntentHash.java:416-419`, `IntentSchema.java:35-80` | nada |
| `domain/risk` | RiskEngine determinista ticket-392 (`DeterministicRiskEngine`, `RiskWeights` con weightsHash, `RiskInput.quantize`, `RiskVerdict`, `DeterministicDecision`) — riesgo de PORTFOLIO (concentración, stables, dust, sharp move, unknown tokens) | `DeterministicRiskEngine.java`, `RiskWeights.java` | nada |
| `domain/receipt` | Intelligence Receipts ticket-391: `ReceiptBody` (ir/1), `ReceiptCanonicalizer` (hash con dominio + mensaje de firma), `ReceiptSigningKey` (Ed25519 JDK), `MerkleTree`, `ReceiptAnchor`, `AnchorMemo`, `DeterministicInference`, `ReceiptEdge`, `ReceiptKind` | `ReceiptBody.java:35-55`, `ReceiptCanonicalizer.java:21-56`, `ReceiptKind.java:10-27` | `domain/intent` (canonicalizador) |
| `domain/reliability` | `OutboxEvent` (PENDING/PUBLISHED/FAILED), `DeadLetter` (source OUTBOX/RECONCILIATION, outcome RESOLVED/REQUEUED), `TradeEvents` (nombres `trade.*`) | `OutboxEvent.java:17-36`, `DeadLetter.java:17-42`, `TradeEvents.java:20-28` | nada |
| `domain/oracle` | Consenso multi-fuente ticket-439: `PriceOracle` (mediana, desviación, breaker), `OracleReading` (quotesHash, limitsHash), `OracleConsensus`, `OracleRefusal` | `PriceOracle.java`, `OracleReading.java` | nada |
| `domain/strategy` | `RebalanceIntent` (Map<String,BigDecimal> pesos %), `RebalancePlan`, `RebalanceLeg` — el intent REAL del pipeline hoy | `RebalanceIntent.java:7-13` | nada |
| `domain/portfolio`, `domain/wallet`, `domain/advisor`, `domain/quotes` | snapshot valuado; `Wallet` (**importa `adapters.solana.SolanaCluster`**, `Wallet.java:3`); mensajes del advisor; cotizaciones ARS | — | `Wallet` → adapters (única fuga dominio→adapter) |
| `application/controlplane` | El pipeline: `ProposalService` (plan→risk→simulate→policy→espera humano), `ExecutionService` (único camino a la cadena), `PolicyEngine` (reglas nombradas + adapter a `DeterministicPolicyEngine`), `RiskEngine` (adapter), `SimulationService`, `ExecutionReceipts`, `Receipts` (fábrica de drafts), `WalletService`, `PortfolioService`, `RebalancePlanner`, `AdvisorService`, `AnalysisReuse`, `AuditService`, properties | `ExecutionService.java:74-494`, `ProposalService.java:49-460`, `PolicyEngine.java:64-562` | adapters.solana (7 clases), integration.signer/validator, infrastructure.persistence, infrastructure.runtime |
| `application/reliability` | `ReconciliationService` (sweep contra la cadena; retry idempotente ticket-571; DLQ), `ReconciliationJob`, `OutboxPublisher` (FOR UPDATE SKIP LOCKED, backoff exponencial), `OutboxSink`/`LoggingOutboxSink`, `DeadLetterService` (resolve/requeue ticket-501), `ReliabilityProperties` | `ReconciliationService.java:58-364`, `DeadLetterService.java:46-200`, `OutboxPublisher.java:34-137` | adapters.solana, persistence |
| `application/receipt` | `ReceiptService` (seal/issue/verify), `AnchorService` (+`AnchorJob`), `LineageService`, `DeterministicReproducer` (re-ejecuta risk-engine y policy-engine), `ReceiptKeyConfig`, `TenantSalts` | `ReceiptService.java:52-284`, `AnchorService.java` | persistence, integration.signer (sign-anchor) |
| `application/chaos` | Fault injection del demo: `BroadcastChaos`, `ChaosSolanaRpcClient extends SolanaRpcClient` `@Primary` sólo con `cryptobot.chaos.enabled=true`, `PriceChaos` | `ChaosConfiguration.java:53`, `ChaosSolanaRpcClient.java:25` | adapters.solana |
| `application/oracle`, `application/quotes`, `application/*` (MarketReview*, Monitoring*, Watchlist, PriceZones, TradeJournal, Indicators, MarketObservations) | `PriceOracleService` (lee fuentes, aplica `PriceOracle`); cotizaciones ARS; y el vertical "market review" que llama al Runtime LLM (`crypto.market-review.v1`) — **no toca firma ni cadena** | `PriceOracleService.java`, `MarketReviewRunService.java` | adapters.marketdata, infrastructure.runtime |
| `api/controlplane` | Controllers REST del control plane: Wallets, Proposals, Receipts, Lineage, Anchors, DeadLetters, Chaos, PriceChaos + `ControlPlaneExceptionHandler` + `Principals` (identidad SIEMPRE del JWT) | ver §10 | **importa `adapters.solana.SolanaRpcClient` en `WalletsController.java:3` (usa `rpc.getSignaturesForAddress` directo, línea 74) y `infrastructure.persistence.*Repository` en `ProposalsController.java:10-11`** |
| `api/*` (raíz) | Endpoints del vertical de observación de mercado (watchlist, zones, journal, observations, indicators, snapshots, market-review, monitoring) | — | application |
| `adapters/solana` | `SolanaRpcClient` (JSON-RPC: getBalance, getTokenAccountsByOwner, getSignaturesForAddress, getBlockHeight, getLatestBlockhash, simulateTransaction, sendTransaction (**gate mainnet en 214-218**), getSignatureStatus, getTransaction), `SolanaCluster`, `ExecutionProperties` (allow-mainnet), `MainnetDisabledException`, `Base58`, `tx/` (`LegacyTransaction`, `SystemProgram.transfer`, `MemoProgram`, `SolanaKeypair.verify`, `CompactU16`), `authority/` (`IntentAuthorityProgram`, `ProgramDerivedAddress` — cliente del programa ticket-437) | `SolanaRpcClient.java:185-240` | domain.oracle (PriceObservation) |
| `adapters/marketdata`, `adapters/quotes` | fuentes de precio (Pyth Hermes, CoinGecko, Coinbase, Jupiter) y de cotización ARS (Bitso, Ripio, Buenbit); `TokenRegistry` | — | domain |
| `infrastructure/persistence/controlplane` | Stores R2DBC (`@Profile("!test")`): `ActionProposalR2dbcStore` (commit guardado por version+status en una tx), `OutboxEventR2dbcStore`, `DeadLetterR2dbcStore`, `AuditEventR2dbcStore`, `ReceiptR2dbcStore`, `LineageR2dbcStore`, `AnchorR2dbcStore`, `WalletR2dbcStore`, `PortfolioSnapshotR2dbcStore`, `AdvisorMessageR2dbcStore` + interfaces `*Repository` | `ActionProposalR2dbcStore.java:59-85` | domain, application (ControlPlaneExceptions) |
| `infrastructure/persistence/r2dbc` | Spring Data R2DBC del vertical de observación (`*Row` + `*Repository`) | — | — |
| `infrastructure/runtime`, `integration/lifeengine` | `RuntimeClient` (start run / poll), `AdvisorRuntimeClient` — el LLM vive en Runtime, no acá | — | — |
| `integration/signer`, `integration/validator` | `SignerClient` (`/api/signer/identity`, `/sign`, `/sign-anchor`; header `X-Signer-Token`), `ValidatorClient` (`/api/validator/identity`, `/validate`; header `X-Validator-Token`) | `SignerClient.java:89-116`, `ValidatorClient.java:114-176` | domain.policy, domain.transactions |
| `infrastructure/snapshot`, `infrastructure/solana`, `infrastructure/binance` | proveedores de snapshot de mercado (deterministic-local, binance-public, solana-public via GeckoTerminal+Jupiter) | — | — |
| `observability` | `CryptobotMetrics` (nombres Prometheus), `ErrorCode` (CB-*), `LogContext`/`LogFields` (MDC), `RequestCorrelationWebFilter`, `BuildIdentity*`, `ApiErrorLoggingWebExceptionHandler` | `CryptobotMetrics.java:86-127` | — |
| `security` | JWT HS256/JWKS (`CryptobotJwtService`), derivación `RUNTIME_*` desde `role` (bridge fase 1), `CryptobotSecurityConfig` (matchers por path), S2S `ServiceTokenClient` + `ServiceTokenStartupValidator`, CORS | `CryptobotSecurityConfig.java:49-80` | — |
| `health` | `GET /api/cryptobot/health` (permitAll) | — | — |

**Dependencias que rompen la dirección deseada (dominio ← app ← adapters):**
- `domain/wallet/Wallet.java:3` importa `adapters.solana.SolanaCluster` (el enum de cluster vive en adapters).
- `api/controlplane/WalletsController.java:3,74` llama `SolanaRpcClient.getSignaturesForAddress` directo (lectura, no firma).
- `api/controlplane/ProposalsController.java:10-11,73` lee `OutboxRepository`/`DeadLetterRepository` directo (lectura).
- `application/*` importa 7 clases de `adapters.solana` y las 2 de `integration/*` (medido: `grep -rhn "^import io.lifeengine.cryptobot.(adapters|infrastructure|integration)" application`). No hay puertos `ChainExecutionPort`/`ChainSimulationPort`/`ChainObservationPort`/`AssetPort`: **NO EXISTEN** (buscado con `grep -rn "Port\b" src/main/java` → sólo `ArsQuotesPort`, `MarketSnapshotProvider`, `PriceProvider`/`PriceSource`, `OutboxSink`).

## 2. Secuencia de ejecución REAL (POST → CONFIRMED / reconciled)

### 2a. Creación de la propuesta (`POST /api/cryptobot/wallets/{walletId}/proposals`)
1. `WalletsController.propose` (`WalletsController.java:96-110`): sólo `kind=REBALANCE` (línea 100-102), construye `RebalanceIntent(targetWeights, counterAsset)` — **NO pasa por `IntentSchema`/`TradingIntent`**.
2. `ProposalService.createRebalance` (`ProposalService.java:143-197`): `portfolio.latest` → `RebalancePlanner.plan` (145) → noop ⇒ 400 → `riskEngine.evaluate(projected)` (152, **risk de portfolio, no bloquea**: 153-158 sólo loguea `RISK_HIGH`) → `new ActionProposal(... PROPOSED ..., expiresAt=now+proposalTtl)` (161-163) → `proposals.insert` (175) → audit `PROPOSAL_CREATED` (176) → recibos `STRATEGY` y `RISK_DECISION` (187-189) → `simulate` (190) → `evaluatePolicy` (192).
3. `ProposalService.simulate` (207-222) → `SimulationService.simulate` (`SimulationService.java:45-68`): económica (spot×amount) + **on-chain `simulateTransaction` con `sigVerify=false`** (60) sobre un `SystemProgram.transfer(wallet→vault, lamports)` construido con blockhash fresco (`prepareTransfer`, 71-81). Sólo el SELL leg de SOL es ejecutable (49-53). Commit `PROPOSED→SIMULATED` + audit `SIMULATED` (212-218) → recibo `SIMULATION` (219).
4. `ProposalService.evaluatePolicy` (230-300): lee `oracle.read(assets)` (232) + estado de la wallet (últimas 20 propuestas EXECUTED → cooldown y exposición 24h, 233-245) + `signer.identity()` + `validator.identity()` (246) → `policy.evaluate(...)` (`PolicyEngine.java:177-289`) → `policy.requireValidator` (297-311) → `next = allowed ? AWAITING_APPROVAL : BLOCKED_BY_POLICY` (251) → commit con audit `POLICY_EVALUATED` + `AWAITING_APPROVAL|BLOCKED_BY_POLICY` (270-286) + outbox `trade.requested` si allowed (287-290) → **Decision Receipt** (`RISK_DECISION` con engine `policy-engine`, `Receipts.policyDecision`, `Receipts.java:198-263`) (292-298).

### 2b. Aprobación (`POST /proposals/{id}/approve|reject|cancel`)
5. `ProposalService.decide` (310-336): exige `AWAITING_APPROVAL` (312) → `executableAt = policy.executableAt(now, p.policy())` (timelock ticket-438, 319; `TimelockProperties.forVerdict`: ALLOW⇒`autonomous`(0s), otro⇒`escalated`(30m)) → `expiresAt` empujado a `executableAt+executionWindow` (322-325) → commit `APPROVED|REJECTED` + audit + outbox `trade.approved|rejected` (330-334).
6. `cancel` (343-357): sólo `APPROVED` → `REJECTED` + `CANCELLED` (audit y outbox).
7. Expiración lazy: `expireIfDue` (379-392) al leer una propuesta AWAITING_APPROVAL/APPROVED vencida ⇒ `EXPIRED`.

### 2c. Ejecución (`POST /proposals/{id}/execute`)
8. `ProposalsController.execute` (108-127): `operationId` = header `Idempotency-Key` (UUID o `sha256:<64hex>` → `IntentHash.toOperationId()`), o body `operationId`, o `UUID.randomUUID()` (117).
9. `ExecutionService.doExecute` (`ExecutionService.java:138-172`):
   - mismo `operationId` que el de la fila ⇒ `suppressDuplicate` (140-142, devuelve la fila, sin tx);
   - en vuelo con otro operationId ⇒ 409 (143-146);
   - `policy.executionPreconditions(p)` (147; `PolicyEngine.java:505-544`): APPROVED, kill switch, `executable`, verdict presente y no DENY, **`rules.hash()==verdict.policyHash()` (policy binding, 521-523)**, `(I,S)` grabado, oracle grabado, approval APPROVED, timelock vencido (533-538), no expirada (540-542);
   - re-lectura del oráculo + `policy.priceViolations` (155-162, fresh);
   - `requireClusterAllowed` (163-164; 179-196): gate mainnet #1 sobre cluster de wallet Y de la propuesta (`ExecutionProperties.permits`), y wallet.cluster == proposal.cluster.
10. `start` (232-239): commit guardado `APPROVED→EXECUTING` con `operation_id` (unique index) + audit `EXECUTION_STARTED`; si `StaleProposal` y la fila fresca ya tiene nuestro operationId ⇒ duplicado suprimido.
11. `run` (252-285):
    1. `simulation.prepareTransfer(wallet, lamports)` (259): **blockhash fresco**, lamports del `PreparedTransaction` grabado en la propuesta (253);
    2. **re-simulación `rpc.simulateTransaction(..., sigVerify=false)`** de esos bytes exactos (261-263) ⇒ error ⇒ 409;
    3. `validator.authorize(executing, tx)` (268-275; `ValidatorClient.java:114-154`): manda `(I,S)` grabado, `policyHash`, `expectedVerdictHash`, `messageHash=SHA256(message bytes)`, `cluster` → verifica `ALLOW|ESCALATE`, mismo policyHash, mismo verdictHash, attestation contiene `message_hash` y `cluster` (156-176; **comparación por `String.contains` sobre el payload**, 169-174);
    4. `signer.sign(proposalId, unsignedTx, feePayer, cluster, attestation)` (278; `SignerClient.java:89-116`): sin attestation ⇒ `SignerRefused` local (97-99);
    5. `verifySigned` (288-305): forma (1 firma + 64 + message), message idéntico, **Ed25519 verify contra la pubkey de la wallet** (300);
    6. `persistSigned` (307-335): `ExecutionRecord(SIGNED, signature, blockhash, lastValidBlockHeight)` + audit `EXECUTION_VALIDATED` + `EXECUTION_SIGNED` (+`EXECUTION_RETRIED` en retry) — **antes del broadcast**;
    7. cualquier error hasta acá ⇒ `fail(...)` ⇒ `FAILED` (283; 454-470) — nada pudo llegar a la cadena.
12. `broadcast` (337-364): `rpc.sendTransaction` (gate mainnet #2 en `SolanaRpcClient.java:214-218`) → commit `EXECUTING→SUBMITTED` + audit `EXECUTION_SUBMITTED` + outbox `trade.submitted` (347-352) → `confirm` (401-415): poll `getSignatureStatus` 20×1.5s → `finish` (417-451): failed ⇒ `FAILED` + outbox `trade.failed` + recibo `EXECUTION`; pending ⇒ queda `SUBMITTED` + audit `EXECUTION_CONFIRMATION_PENDING` (431-439); confirmed/finalized ⇒ `EXECUTED` + outbox `trade.confirmed` + **recibo `EXECUTION`** (`ExecutionReceipts.receiptFor`, `ExecutionReceipts.java:43-60`).
13. Broadcast falló (372-391): JSON-RPC error sin causa ⇒ `FAILED` (RPC rechazó); `MainnetDisabledException` ⇒ `FAILED`; cualquier otra cosa (timeout, reset, DB) ⇒ audit `EXECUTION_BROADCAST_UNCERTAIN`, fila queda EXECUTING con firma.

### 2d. Reconciliación (`ReconciliationJob` al arrancar y cada `interval`=30s; `ReconciliationService.sweep`)
14. `findInFlight(updated_at < now-grace(2m), batch 100)` (`ReconciliationService.java:116-136`) → `reconcile` (174-200): sin firma ⇒ `FAILED` (EXECUTING) o DLQ `inconsistent` (SUBMITTED); con firma ⇒ `verdict` (206-233): `getSignatureStatuses(searchTransactionHistory=true)` → Confirmed ⇒ `executed` (271-294, `EXECUTED` + audit `RECONCILED`+`EXECUTED` + outbox `trade.confirmed{reconciled:true}` + recibo EXECUTION); Failed ⇒ `fail` (296-318); Expired (no vista y `getBlockHeight > lastValidBlockHeight`) ⇒ `expired` (247-269): `retries < maxRetries(2)` ⇒ **`execution.retry(p)`** (mismo operationId, pipeline completo: re-simulación, validator, signer; `ExecutionService.retry` 206-219) ⇒ `RETRIED`; si no ⇒ DLQ `retries_exhausted`; Pending ⇒ `stillPending` (320-331): `attempts >= maxAttempts(20)` ⇒ DLQ `ambiguous`.
15. Humano: `POST /dead-letters/{id}/resolve` ⇒ `settle` (151-172: la cadena decide; expirada-no-vista ⇒ FAILED; sin veredicto ⇒ 409); `POST .../requeue` ⇒ resetea attempts y `reconcile` inmediato (`DeadLetterService.java:107-148`).

**Simulación antes de firmar: SÍ, dos veces** — en creación (`SimulationService.java:60`) y en ejecución con los bytes exactos que se firman (`ExecutionService.java:261`). Resultado de simulación NO se hashea como `simulationHash` independiente: el recibo `SIMULATION` (`Receipts.simulation`, 330-350) captura `economic`+`onchain{ok,error,unitsConsumed,cluster}` (497). La re-simulación de ejecución no deja recibo ni se persiste en la fila (sólo gate).

## 3. Máquina de estados actual

### 3a. `ProposalStatus` (`domain/transactions/ProposalStatus.java:14-26`)
`PROPOSED, SIMULATED, BLOCKED_BY_POLICY, AWAITING_APPROVAL, APPROVED, REJECTED, EXECUTING, SUBMITTED, EXECUTED, FAILED, EXPIRED` (11). `terminal()`: BLOCKED_BY_POLICY, REJECTED, EXECUTED, FAILED, EXPIRED. `inFlight()`: EXECUTING, SUBMITTED.

**Tabla de transiciones explícita** (`canTransitionTo`, líneas 36-47) — un `switch` central, no if/else disperso; `ActionProposal.withStatus` (48-56) lanza `IllegalStateException` si no está listada:

```
PROPOSED          → SIMULATED | FAILED
SIMULATED         → AWAITING_APPROVAL | BLOCKED_BY_POLICY
AWAITING_APPROVAL → APPROVED | REJECTED | EXPIRED
APPROVED          → EXECUTING | EXPIRED | REJECTED   (REJECTED = cancel dentro del timelock)
EXECUTING         → SUBMITTED | EXECUTED | FAILED
SUBMITTED         → EXECUTING | EXECUTED | FAILED    (SUBMITTED→EXECUTING = retry idempotente ticket-571)
terminales        → nada
```

Diagrama CURRENT (texto):
```
POST proposals ─► PROPOSED ─simulate─► SIMULATED ─policy─┬─► AWAITING_APPROVAL ─approve─► APPROVED ─execute─► EXECUTING ─send─► SUBMITTED ─confirm─► EXECUTED
                     │                                   └─► BLOCKED_BY_POLICY          │  │                      │   ▲              │
                     └─► FAILED                                       reject ◄──────────┘  └─cancel─► REJECTED    │   └──retry(ticket-571)┘
                                                              expire ◄── EXPIRED ◄── expire            FAILED ◄───┴─ (pre-broadcast / RPC reject / on-chain err / reconciler)
```

### 3b. Sub-estados y otros enums
- `ExecutionRecord.status` (String, `ExecutionRecord.java:42-45`): `SIGNED | SUBMITTED | EXECUTED | FAILED` — vive en el JSONB `doc`, **sin CHECK**.
- `ApprovalRecord.Decision`: `APPROVED | REJECTED`.
- `OutboxEvent.Status` (`OutboxEvent.java:31-35`): `PENDING | PUBLISHED | FAILED` — CHECK `chk_outbox_event_status` (V5:143).
- `DeadLetter.Source`: `OUTBOX | RECONCILIATION` (CHECK V5:165); `DeadLetter.Outcome`: `RESOLVED | REQUEUED` (CHECK V9:16).
- `ReconciliationService.Result` (72-83): `MATCHED | CORRECTED | RETRIED | DEAD_LETTERED | SKIPPED` — **coinciden con los nombres del mandato §13**; sólo en memoria/métrica (`cryptobot_reconciliation_total{outcome}`), no persistidos.
- `ReceiptAnchor.status` (V8:147): `PENDING | SUBMITTED | FINALIZED | FAILED | ABANDONED`.
- `PolicyVerdict.Decision`: `ALLOW | DENY | ESCALATE`; `Escalation`: `NONE | REQUIRE_SECOND_AGENT | REQUIRE_HUMAN_SIGNATURE`; `AutonomyTier`: `AUTONOMOUS | SECOND_AGENT | HUMAN_SIGNATURE | OVER_LIMIT`.
- `MarketReviewRunStatus` (vertical observación, V3:212): `PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED`.

### 3c. Quién persiste
Toda transición pasa por `ActionProposalRepository.commit(ProposalTransition)` (`ActionProposalR2dbcStore.java:59-85`): `UPDATE ... WHERE id AND owner_user_id AND version = :expectedVersion AND status = :expectedStatus` + INSERT de audit + INSERT de outbox, en **una transacción** (`tx.transactional`, 82); 0 filas ⇒ `StaleProposal` (409); unique violation ⇒ `DuplicateOperation`. Columna `status` con CHECK `chk_action_proposal_status` (V5:115-117, los 11 valores). `insert` (41-56) sólo para PROPOSED.

## 4. Modelo de intent (ticket-435) vs. mandato §4

**Estado:** `TradingIntent` está **implementado pero NO cableado** al pipeline. Uso en `src/main`: sólo `ProposalsController.java:7,131-133` (`IntentHash.parse(key).toOperationId()` para derivar el operationId de un `Idempotency-Key` con forma `sha256:`). Usado en tests: `IntentSchemaTest` (25), `IntentVectorsTest` (2), `benchmark/*`. El pipeline real consume `RebalanceIntent` (`domain/strategy/RebalanceIntent.java:7`, `Map<String,BigDecimal>` en porcentaje + `counterAsset`). **Confirma ticket-457 (Backlog)**: "unir el intent canónico con el Policy Engine".

Campos actuales de `TradingIntent` (`TradingIntent.java:31-44`): `agentId, action{BUY,SELL,SWAP,CANCEL,HOLD,REBALANCE}, strategyId, policyVersion, validUntilSlot, nonce, inputAsset, outputAsset, inputAmount(BigInteger u64), maxSlippageBps, targetIntentHash, targetWeightsBps, counterAsset`; `schema_version="1"` (46). Hash `sha256:` RFC 8785 (`hash()`, 202-204). Sin `expiresAt` temporal (usa slot), sin `idempotencyKey` explícito (es el hash), sin `requestId/correlationId`.

Cómo se llena hoy `(I)` para el policy engine (`PolicyEngine.policyInput`, 447-485): `agentId=proposal.requestedBy` (email del humano), `strategyId=proposal.kind` ("REBALANCE"), **`policyVersion=rules.version()` — tomado del propio engine, así que `POLICY_BOUND` es tautológico**, `asset`=leg BUY de mayor peso, `tradeValueCents`=turnover, **`maxSlippageBps=authorization.executorSlippageBps` (config, no del intent)**, `validUntilSlot=expiresAt.epochSecond`; `(S)`: `currentSlot=now.epochSecond`, `nonceUnused` derivado del estado de la fila (474-475), `agentPermitted = ownerUserId == wallet.ownerUserId`.

| Campo mandato §4 | TradingIntent (ticket-435) | ActionProposal / pipeline real | Estado |
|---|---|---|---|
| intentId | `hash()` (`sha256:`) | `ActionProposal.id` (UUID aleatorio) | equivalente (dos identidades distintas, no unidas) |
| schemaVersion | `SCHEMA_VERSION="1"` | NO (el doc JSONB no tiene versión) | existe sólo en TradingIntent |
| actor | `agentId` | `requestedBy` (email) / `ownerUserId` | equivalente |
| tenant | NO | `ownerUserId` (= tenant, `Receipts.tenantOf`) | equivalente |
| source | NO | `runtimeRunId` (opcional), `kind` | **falta** |
| operation | `action` (6 valores) | `kind="REBALANCE"` únicamente | parcial |
| assetIn / assetOut | `inputAsset/outputAsset` (`AssetId`: símbolo o mint) | `RebalanceLeg.symbol/mint/counterAsset` | equivalente |
| amount | `inputAmount` (u64 string) | `RebalanceLeg.amount` (BigDecimal UI) + `PreparedTransaction.lamports` | equivalente |
| maxAmount | NO | NO | **falta** |
| destination | NO | `PreparedTransaction.destination` = `cryptobot.policy.rebalance-vault` (config) | **falta en el intent**; viene de config |
| chain / network | NO / NO | `cluster` (devnet/mainnet-beta) | parcial (network sí, chain implícito Solana) |
| constraints.maxSlippageBps | `maxSlippageBps` | config `executor-slippage-bps` | existe en TradingIntent, no en pipeline |
| constraints.maxFee | NO | NO | **falta** |
| constraints.deadline | `validUntilSlot` | `expiresAt` (Instant) | equivalente |
| allowedProtocols/Assets/Destinations | NO (viven en `PolicyRules`/config) | policy | **falta en el intent** (diseño: son de la policy) |
| riskContext | NO | `riskBefore/riskAfter` (RiskReport) | equivalente parcial |
| createdAt / expiresAt | NO / slot | `createdAt` / `expiresAt` | equivalente |
| correlationId / requestId | NO | MDC `correlationId`/`requestId` (no persistidos en la fila) | **falta persistir** |
| idempotencyKey | = hash | `operationId` (UUID; `uq_action_proposal_operation`) | equivalente |
| metadataHash | NO | NO | **falta** |
| nonce | `nonce` (u64) | `operationId`/estado de la fila (`nonceUnused`) | equivalente débil |
| policyVersion | `policyVersion` | `PolicyVerdict.policyVersion/policyHash` en el doc | existe |

## 5. Policy engine y risk

### 5a. Reglas nombradas de `application/controlplane/PolicyEngine` (nombres exactos, `PolicyEngine.java:66-115`)
Bloqueantes (⇒ `BLOCKED_BY_POLICY`): `ASSET_ALLOWLIST`, `MAX_TRADE_USD`, `MAX_TRADE_PCT_OF_PORTFOLIO`, `COOLDOWN`, `PRICE_QUORUM`, `PRICE_STALE`, `PRICE_DEVIATION`, `PRICE_CIRCUIT_BREAKER`, `PRICE_DRIFT`, `AUTHORIZATION` (= DENY del engine determinista).
De ejecución (aprobable como paper, `executable=false`): `EXECUTION_ENABLED` (kill switch), `EXECUTION_CLUSTER`, `REBALANCE_VAULT_CONFIGURED`, `MAX_LAMPORTS_PER_TX`, `ONCHAIN_SIMULATION_PASSED`, `SIGNER_CONTROLS_WALLET`, `SIGNER_DESTINATION_ALLOWLISTED`, `SIGNER_MAX_LAMPORTS`, `SIGNER_CLUSTER`, `VALIDATOR_AVAILABLE` (297-311), `TIMELOCK_ELAPSED` (sólo en `executionPreconditions`, 533-538, no como Violation).
Total: 21 nombres. Son **código** (if/else en `evaluate`, 184-288), con umbrales de `PolicyProperties` (`cryptobot.policy.*`).

### 5b. `domain/policy/DeterministicPolicyEngine` (ticket-436)
11 predicados (`PolicyPredicate.java:13-33`, orden fijo, sin short-circuit): `POLICY_BOUND, ASSET_ALLOWED, TRADE_WITHIN_MAX, DAILY_LIMIT, ASSET_CONCENTRATION, SLIPPAGE_WITHIN_MAX, ORACLE_FRESH, AGENT_PERMITTED, STRATEGY_ENABLED, NONCE_UNUSED, NOT_EXPIRED`. Decisión: `ALLOW | DENY | ESCALATE` (+ `Escalation`, `AutonomyTier` por bandas de `tradeValueCents`, 59-85). `Unknown ⇒ Deny` (null falla el predicado, 107-131).
- **policy es data versionada**: `PolicyRules` record (`PolicyRules.java:25-35`) con `version`, `allowedAssets`, `enabledStrategies`, `maxTradeValueCents`, `dailyLimitCents`, `maxAssetExposureBps`, `maxSlippageBps`, `maxOracleAgeSeconds`, `autonomousUpToCents`, `secondAgentUpToCents`; `canonicalJson()`/`hash()` = `H_R` (76-84). Origen: `AuthorizationProperties.rules(PolicyProperties)` (`AuthorizationProperties.java:76-88`) — YAML `cryptobot.policy.authorization.*` (`application.yml:217-228`) convertido a enteros al arranque; tiers no monotónicos ⇒ el servicio no arranca (`PolicyRules.java:51-56`).
- **Persistido por decisión**: `PolicyVerdict{decision, escalation, tier, failedPredicates, evaluatedPredicates, policyVersion, policyHash, inputHash}` + `hash()` (`PolicyVerdict.java:24-32, 86-88`) dentro de `PolicyDecision.authorization` en el JSONB de la propuesta; `PolicyDecision{allowed, executable, violations[rule,message], executionViolations, rulesApplied, evaluatedAt, authorization, input(I,S), oracle}` (`PolicyDecision.java:37-46`). Además en audit `POLICY_EVALUATED` (`ProposalService.java:271-284`: `policyVersion, policyHash, inputHash, verdictHash, failedPredicates, rulesApplied, oracle*`) y en el Decision Receipt (engine `policy-engine@R_v`, `weightsHash=H_R`, input `POLICY_INPUT`, output `policy-verdict/1`, L1 reproducible, `Receipts.java:198-263`).
- `matchedRules` ≈ `rulesApplied` (todas las evaluadas) + `violations` (las que fallaron); `reasonCodes` ≈ `failedPredicates` (enum) y `Violation.rule` (String). **No hay tabla `policy_evaluation`**: vive en el doc JSONB + audit + receipt.
- Sin tabla de policies en DB ni endpoint de administración: cambiar R_v = redeploy con env. `executionPreconditions` rechaza ejecutar bajo otro `H_R` (521-523).

### 5c. Risk (`domain/risk`, ticket-392)
Entrada `RiskInput.quantize(snapshot, diff)` (posiciones en bps/micro-USD), pesos `RiskWeights` v1 (`src/main/resources/risk-engine/weights-v1.json`, `weightsHash`), salida `RiskVerdict{overall LOW|MEDIUM|HIGH, score, signals[CONCENTRATION, NO_STABLES, DUST_POSITIONS, SHARP_MOVE, UNKNOWN_TOKENS, EMPTY_PORTFOLIO]}`; `DeterministicDecision` con `engineId/engineVersion/weightsHash/input/output` → recibo `RISK_DECISION` L1 y tabla `deterministic_inference` (V7). Golden de 200 casos (`RiskEngineGoldenTest`).

### 5d. ¿Mezcla trading risk / execution policy (§18)?
**Separados en código, mezclados en nombre y en el recibo:**
- Trading/portfolio risk = `domain/risk` (concentración, stables, dust, sharp move). No bloquea nada (`ProposalService.java:153-158` sólo WARN). Se persiste en `riskBefore/riskAfter`.
- Execution policy = `domain/policy` + `PolicyEngine` (límites, allowlists, cluster, slippage, oracle, simulación, signer caps).
- **PERO**: `PolicyPredicate.ASSET_CONCENTRATION` (`asset_exposure_after_bps <= max_asset_exposure_bps`) y `DAILY_LIMIT` son reglas de riesgo de portfolio dentro del policy engine (`DeterministicPolicyEngine.java:112-114`), y el Decision Receipt de la policy se emite con `ReceiptKind.RISK_DECISION` (`Receipts.java:252`) porque el CHECK `chk_intelligence_receipt_kind` (V6:42-44) no tiene un kind `POLICY_DECISION`. Es el punto a dividir en §3 del mandato.

## 6. Idempotencia (§11) — escenario por escenario

| Escenario | Cubierto | Evidencia |
|---|---|---|
| HTTP retry mismo `Idempotency-Key` | **SÍ** | `ProposalsController.java:108-127` (header o body, UUID o `sha256:`); `ExecutionService.java:140-142` devuelve la fila sin tx; `start` 237-238 resuelve la carrera de dos clics simultáneos; test `samOperationIdIsIdempotent`, `operationIdIsBoundBeforeSigning` (`ExecutionServiceReliabilityTest.java:49,81`) |
| HTTP retry SIN key | **parcial** | key ausente ⇒ `UUID.randomUUID()` (117): 2º request mientras está en vuelo ⇒ 409 (143-146); después de terminal ⇒ `executionPreconditions` rechaza (status ≠ APPROVED). No hay doble tx, pero el cliente no recibe "el mismo resultado" |
| Key reutilizada en OTRA propuesta | **SÍ** | `uq_action_proposal_operation` (V5:110-112) ⇒ `DuplicateOperation` 409 (`ActionProposalR2dbcStore.java:83`); test `operationIdIsUniqueAcrossProposals` (293) |
| Restart del servicio con fila EXECUTING sin firma | **SÍ** | reconciler ⇒ FAILED, nunca retry (`ReconciliationService.java:182-187`); test `crashBeforeSigningFails` |
| Restart entre SIGNED y sendTransaction | **SÍ** | firma persistida antes del broadcast (`persistSigned` 307-335); reconciler busca la firma; expirada-no-vista ⇒ retry mismo operationId (247-269); test `crashBetweenSubmitAndConfirmIsReconciled`, `expiredUnseenSignatureIsRetriedIdempotently` |
| Timeout DB al escribir SUBMITTED tras enviar | **SÍ** | `broadcastFailed` 372-391 trata todo lo que no es JSON-RPC error como incierto; queda EXECUTING con firma ⇒ reconciliación |
| Timeout RPC en sendTransaction | **SÍ** | ídem; test `transportErrorOnBroadcastIsNotAFailure` |
| Submitted-but-lost (SUBMITTED, nunca vista, blockhash vencido) | **SÍ** | `Verdict.Expired(mismatch=true)` ⇒ métrica `reconciliation_mismatch_total` + retry o DLQ `retries_exhausted` (247-259); test `submittedNeverSeenAndExpiredWithoutRetriesIsDeadLettered` |
| Doble evento del outbox | **SÍ (a nivel fila)** | `FOR UPDATE SKIP LOCKED` en `OutboxEventR2dbcStore.java:54-65`; el sink debe ser idempotente por `event.id` (`OutboxSink.java` contrato); hoy el único sink es un log (`LoggingOutboxSink`) |
| Rerun del reconciler / dos reconcilers | **SÍ** | cada write es `ProposalTransition` guardado por `version` ⇒ `StaleProposal` ⇒ `SKIPPED` (120-123); test `retryIsGuardedByTheVersion` (`ExecutionRecoveryTest.java:313`). **Matiz**: en `retry`, validator y signer se llaman ANTES del commit guardado (`run` 268-281) ⇒ dos reconcilers podrían obtener dos firmas; sólo una se persiste y broadcastea (la otra muere en `persistSigned`→`StaleProposal`). Sin doble tx, pero con una firma "huérfana" en el log del signer |
| Agente repite el mismo intent | **parcial** | si manda `Idempotency-Key: sha256:<H_I>` ⇒ `toOperationId()` determinista (`IntentHash.java:416-419`); pero `POST /wallets/{id}/proposals` NO es idempotente: cada llamada crea una propuesta nueva (`ProposalService.java:161`, `UUID.randomUUID()`); no hay `nonce` persistido ni unique en creación |
| Key ligada a la acción económica | **NO** | el operationId es un UUID opaco del cliente (o aleatorio); no está ligado a `(wallet, lamports, destination, blockhash)`. Sólo si el cliente usa `sha256:<H_I>` la key deriva del intent, y ese intent no es el que el pipeline ejecuta (RebalanceIntent) |

## 7. Outbox / DLQ / retry / reconciliación

- **Tablas** (V5:129-173): `outbox_event(id, aggregate_type, aggregate_id, owner_user_id, event_type, payload JSONB, status, attempts, next_attempt_at, last_error, created_at, published_at)` + `idx_outbox_event_due` parcial; `dead_letter(id, source, ref_id, proposal_id, owner_user_id, reason, payload, created_at, resolved_at)` + V9 `resolved_by, resolution, outcome` (CHECK RESOLVED/REQUEUED) + `idx_dead_letter_created`.
- **Outbox** (`OutboxPublisher.java`): tick cada 2s, batch 50, `maxAttempts=8`, backoff `1s × 2^(n-1)` cap 5m (`backoff`, 123-129), **sin jitter** (NO EXISTE: `grep -n jitter application/reliability` → 0). Agotado ⇒ `FAILED` + `DeadLetter(OUTBOX)` (`deliver`, 98-120). Eventos: `trade.requested|approved|rejected|expired|cancelled|submitted|confirmed|failed` (`TradeEvents.java:20-28`); `receipt.created` reservado, **nadie lo emite** (comentario línea 15 y `grep -rn RECEIPT_CREATED src/main` → sólo la constante).
- **Reconciliación** (`ReliabilityProperties.Reconciliation`, 47-57): `interval=30s`, `grace=2m`, `maxAttempts=20` (sweeps sin veredicto ⇒ DLQ `ambiguous`), `batchSize=100`, `maxRetries=2` (retries idempotentes tras blockhash vencido ⇒ DLQ `retries_exhausted`). Backoff de reconciliación: fijo por intervalo, sin exponencial.
- **Outcomes reales**: `MATCHED | CORRECTED | RETRIED | DEAD_LETTERED | SKIPPED` (`ReconciliationService.Result`, 72-83) — coinciden con el mandato §13. Métrica `cryptobot_reconciliation_total{outcome}` + legacy `trade_reconciled_total{result=matched|corrected}`.
- **Taxonomía de fallos TRANSIENT/PERMANENT/POLICY/SECURITY/CHAIN/DEPENDENCY/UNKNOWN: NO EXISTE** (buscado con `grep -rn "TRANSIENT\|PERMANENT" src/main/java` → 0). Lo más cercano: `CryptobotMetrics.FailureStage{PREFLIGHT, VALIDATE, SIGN, BROADCAST, ONCHAIN, RPC, OTHER}` (116-127), `ErrorCode` (CB-POLICY/RISK/EXEC/SOLANA/RECON/DLQ/RUNTIME/AUTH/HTTP), y la clasificación implícita de `broadcastFailed` (JSON-RPC error = permanente; resto = incierto) y del reconciler (`Verdict.Confirmed|Failed|Expired|Pending`).
- **DLQ kinds**: `ambiguous`, `retries_exhausted`, `inconsistent`, `outbox` (`ReconciliationService.java:68-70`, `OutboxPublisher.java:311`).
- **API resolve/requeue**: `GET /api/cryptobot/dead-letters[?resolved=false|true|all&source&proposalId&limit&offset]`, `GET /{id}`, `POST /{id}/resolve`, `POST /{id}/requeue` (`DeadLettersController.java:41-74`), `RUNTIME_ADMIN` (`CryptobotSecurityConfig.java:72-73`). Runbook: `docs/runbooks/dead-letter.md` (existe, 4 kinds documentados con acción por defecto).
- **¿Replay ciego posible?** NO: `resolve` nunca reintenta (`settle`: la cadena decide o 409); `requeue` sólo resetea `reconciliationAttempts` y llama `reconcile`, cuyo retry exige `Verdict.Expired` probado contra la cadena (`getSignatureStatus` + `getBlockHeight > lastValidBlockHeight`) y está guardado por `version` y `maxRetries`. Una letra se resuelve una sola vez (`UPDATE ... WHERE resolved_at IS NULL`, `DeadLetterR2dbcStore.java:99-100`). Tests: `ExecutionRecoveryTest` (7), `resolveWithoutVerdictIsRefused`, `rpcDownAmbiguousThenRequeueRetriesOnce`.

## 8. Receipts (ticket-391/392/394/572) vs. ExecutionReceipt §15

**Tabla `intelligence_receipt`** (V6:19-53): `receipt_hash PK (sha256:…)`, `tenant_id`, `owner_id`, `kind` (CHECK 8 valores: WALLET_SNAPSHOT, HUMAN_IDEA, MARKET_ANALYSIS, RISK_DECISION, STRATEGY, SIMULATION, EXECUTION, PROJECT_ANALYSIS), `schema_version`, `nonce` (**UNIQUE (tenant_id, nonce)**), `wallet_id`, `proposal_id`, `body JSONB`, `canonical BYTEA`, `signature BYTEA`, `key_id`, `reproducibility` (L0..L3), `anchor_chain/tx/slot/root/proof` (fuera del hash), `created_at`. `receipt_edge(child,parent,role∈{DERIVES_FROM,VALIDATES,EXECUTES,REUSES})` FK ambos; `artifact`; `deterministic_inference` (V7, input/output árboles + engine + weights_hash); `receipt_anchor` + `receipt_anchor_member` (V8).

**Canonicalización/hash/firma** (`ReceiptCanonicalizer.java:21-56`): `canonical = JCS(body)` (RFC 8785 con `JsonCanonicalizer` compartido con el intent); `receiptHash = SHA-256("life-engine.cryptobot.receipt" ‖ 0x00 ‖ canonical)`; `signature = Ed25519(serviceKey, "life-engine.cryptobot.receipt.sig" ‖ 0x00 ‖ receiptHashBytes)`. Vectores pinneados en `src/test/resources/receipt/vectors-v1.json`.

**Clave de firma**: `ReceiptSigningKey` (Ed25519 JDK, layout 64 bytes seed‖pub de `solana-keygen`), cargada de `cryptobot.receipts.signing-key` (env `CRYPTOBOT_RECEIPT_SIGNING_KEY`, `ReceiptKeyConfig.java:24-37`, `application.yml:303-306`); **sin clave ⇒ clave efímera con WARN** (`receipt_signing_key_ephemeral`): los recibos no se pueden verificar tras reiniciar. Vive en el proceso de `cryptobot-service` (no en el signer). `key_id` en cada recibo para rotación; **`verify` sólo verifica si `key.keyId().equals(r.signature().keyId())`** (`ReceiptService.java:245`) ⇒ tras una rotación los recibos viejos dan `signatureValid=false` (no hay keyring).

**Verificación**: `POST /api/cryptobot/receipts/{hash}/verify` (`ReceiptsController.java:73-78`) ⇒ `Verification{hashMatchesCanonical, bodyMatchesCanonical, signatureValid, keyId, parentsPresent, level, reproduced, reproduction, valid}` (`ReceiptService.verify`, 234-273; L1 re-ejecuta `risk-engine` y `policy-engine` desde `deterministic_inference`) + `anchor{proofValid…}` (Merkle). `GET /receipts/signing-key` (permitido a cualquier `RUNTIME_OPERATOR`) publica `publicKeyHex`, dominios y "RFC 8785 (JCS)" ⇒ **verificable offline sin confiar en la UI** (con la clave pública y `canonical`). Lineage: `GET /receipts/{hash}/{lineage|ancestors|descendants|parents|children|reused-by}`, `GET /proposals/{id}/lineage`.

**Ancla Merkle en devnet** (`AnchorService`): batch de recibos sin ancla → `MerkleTree` (hojas ordenadas) → memo `ir/1 root=<sha256> n=<count> ts=<ISO>` (`AnchorMemo`, regex fija, ≤128 bytes) → el **signer** firma el memo tx (`SignerClient.signAnchor`, endpoint `/api/signer/sign-anchor`; re-deriva el memo y sólo devnet) → `FINALIZED` antes de estampar `anchor_*`; re-ancla idempotente por root. `POST /anchors` (RUNTIME_ADMIN), `POST /anchors/{root}/verify` lee la tx de la cadena (`getTransaction` finalized) y compara memo. Job off por default (`cryptobot.anchor.enabled=false`).

**Recibo `EXECUTION` hoy** (`Receipts.execution`, 353-398): kind `EXECUTION`, agent `AGENT_EXECUTION`, parents `SIMULATION` (DERIVES_FROM) + `STRATEGY` (EXECUTES), inputs `TRANSACTION`=hash(message), `POLICY_VERDICT`=verdict.hash(), `ORACLE_READING`=quotesHash, `APPROVAL`=hash(decision,by,at); output `execution/1` = hash{status, cluster, lamports, signature, signerPublicKey, confirmationStatus, error, recentBlockhash}; nonce `exec:<operationId>`; L0_SIGNED; refs wallet/proposal/snapshot; **emitido sólo en terminal (EXECUTED/FAILED)**, también por el reconciler (`ExecutionReceipts.java:43-60`).

| Campo `ExecutionReceipt` §15 | Hoy | Estado |
|---|---|---|
| receiptId / version | `receipt_hash` / `schemaVersion=ir/1` | existe |
| intentHash / intentId | `refs.proposalId` (UUID); `STRATEGY` parent con `output.hash=hash(plan)`; **no H_I de un TradingIntent** | equivalente débil |
| actor / source | `ownerId`, `agentId="execution-agent"`; approval `by` sólo hasheado | parcial (source falta) |
| policyHash / policyVersion / policyDecision | vía input `POLICY_VERDICT` (verdict.hash cubre policy_hash, version, decision); explícitos sólo en el Decision Receipt (`params.policyVersion`, `engine.weightsHash=H_R`) | **indirecto** (hay que abrir el verdict) |
| simulationHash | parent `SIMULATION` receipt (su output = hash(economic+onchain)) | equivalente (por linaje) |
| approvalEvidence | input `APPROVAL` = hash(decision, by, at) | existe (hash) |
| attestationHash | **NO** (la attestation queda en `audit_event EXECUTION_VALIDATED.payload.attestationSignature`, no en el recibo) | **falta** |
| validatorResult | NO en recibo (sí en audit) | **falta** |
| transactionMessageHash | input `TRANSACTION` = SHA256(messageBase64 bytes) | existe |
| signerIdentity | output `signerPublicKey` | existe |
| chain / network | `params.cluster`, output `cluster` | existe (network); chain implícito |
| signature (tx) | output `signature` | existe |
| submittedAt / confirmedAt / reconciledAt | **NO** (sólo `startedAt`/`completedAt` del recibo); están en `ExecutionRecord` del doc | **falta** |
| expected / actualOutcome | output `status`,`confirmationStatus`,`error`; expected = parent SIMULATION | parcial |
| finalStatus | output `status` (SIGNED/SUBMITTED/EXECUTED/FAILED de `ExecutionRecord`) | existe |
| receiptHash | `receipt_hash` | existe |
| verify(receipt) sin UI | `POST /receipts/{hash}/verify` + `GET /receipts/signing-key` | existe |

## 9. DB — conceptos del §26

Tablas (V1..V10): `watchlist_entry, price_zone, market_observation, trade_journal_entry, indicator_snapshot` (V1, vertical observación), `market_review_run` (V3), `wallet, portfolio_snapshot, advisor_message, action_proposal, audit_event` (V4), `outbox_event, dead_letter` (V5), `intelligence_receipt, receipt_edge, artifact` (V6), `deterministic_inference` (V7), `receipt_anchor, receipt_anchor_member` (V8). Total 18 tablas.

`action_proposal` (V4:55-79 + V5:107-127): `id PK, wallet_id FK→wallet ON DELETE CASCADE, owner_user_id, status (CHECK 11), kind, runtime_run_id, doc JSONB, created_at, updated_at, version BIGINT, operation_id UUID (UNIQUE parcial)`; índices `wallet_time`, `owner_open` (parcial), `in_flight` (parcial EXECUTING/SUBMITTED). **Todo el agregado (plan, risk, policy, simulation, transaction, approval, execution) vive en `doc` JSONB** — sin columnas ni CHECKs para `ExecutionRecord.status`, `policyHash`, `signature`.
`audit_event` (V4:82-94): **append-only por convención** ("no UPDATE path in code", sin trigger ni REVOKE), `event_type VARCHAR(48)`, `payload JSONB`, índices por proposal y wallet. Sin hash-chain, sin `prev_hash`.

| Concepto §26 | Tabla / lugar | Estado |
|---|---|---|
| intent (+version) | `action_proposal.doc.intent` (RebalanceIntent) + `.plan`; sin `schema_version` | parcial (JSONB) |
| policy evaluation | `doc.policy` (PolicyDecision+PolicyVerdict+input+oracle) + `audit_event POLICY_EVALUATED` + `intelligence_receipt(kind=RISK_DECISION, engine=policy-engine)` + `deterministic_inference` | existe (sin tabla propia) |
| simulation | `doc.simulation` + `doc.transaction` + receipt `SIMULATION` | existe (sin tabla propia); la re-simulación de ejecución no se persiste |
| approval | `doc.approval` + audit `APPROVED/REJECTED/CANCELLED` | existe |
| attestation | `audit_event EXECUTION_VALIDATED.payload{validator, verdictHash, attestationSignature, expiresAt}` | sólo en audit |
| validation | ídem | sólo en audit |
| signing attempt | `doc.execution{SIGNED, signature, signerPublicKey, blockhash}` + audit `EXECUTION_SIGNED`; fallos pre-broadcast: audit `EXECUTION_FAILED{stage}` | existe (un slot por propuesta; retries via `previousSignature`) |
| submission | `doc.execution{SUBMITTED, submittedAt}` + audit + outbox `trade.submitted` | existe |
| chain observation | `doc.execution{confirmationStatus, confirmedAt}`; no se persiste slot/blockTime | parcial |
| reconciliation | `doc.execution{reconciliationAttempts, reconciledAt}` + audit `RECONCILED/RECONCILIATION_*` | existe (sin tabla) |
| retry | `doc.execution{retries, previousSignature}` + audit `EXECUTION_RETRIED` | existe |
| dead letter | `dead_letter` | tabla propia |
| receipt | `intelligence_receipt` + edges + anchor | tabla propia |
| audit event | `audit_event` | tabla propia, append-only por convención |
| outbox | `outbox_event` | tabla propia |

## 10. APIs HTTP (todas), auth y clasificación

Auth global (`CryptobotSecurityConfig.java:49-80`): JWT obligatorio salvo `GET /actuator/health(/**)`, `/actuator/prometheus`, `/actuator/info`, `GET /api/cryptobot/health`. `RUNTIME_ADMIN`: `POST /anchors`, `/dead-letters/**`, `/demo/**`. Resto de `/api/cryptobot/**`: `RUNTIME_OPERATOR`. `anyExchange().denyAll()`. `CRYPTOBOT_SECURITY_ENABLED=false` ⇒ `permitAll` (27-31). Identidad/tenant siempre del JWT (`Principals.java:16-19`), nunca de header.

| Grupo | Método y path | Controller:línea | Auth |
|---|---|---|---|
| **negocio** | `POST /api/cryptobot/wallets` · `GET /wallets` · `GET /wallets/{id}/portfolio` · `POST /wallets/{id}/refresh` · `GET /wallets/{id}/activity` · `POST /wallets/{id}/ask` · `GET /wallets/{id}/messages` · `POST /wallets/{id}/proposals` · `GET /wallets/{id}/proposals` | `WalletsController.java:42,50,55,62,69,78,90,96,112` | OPERATOR |
| **negocio** | `GET /proposals` · `GET /proposals/{id}` · `GET /proposals/{id}/audit` · `GET /proposals/{id}/events` · `POST /proposals/{id}/approve` · `POST .../reject` · `POST .../cancel` · `POST .../execute` (header `Idempotency-Key`) | `ProposalsController.java:47,52,59,69,77,83,93,108` | OPERATOR (owner-scoped) |
| **negocio** | `GET /receipts/signing-key` · `GET /receipts/{hash}` · `POST /receipts/{hash}/verify` · `GET /proposals/{id}/receipts` · `GET /wallets/{id}/receipts` | `ReceiptsController.java:52,58,73,81,88` | OPERATOR |
| **negocio** | `GET /receipts/{hash}/lineage|ancestors|descendants|parents|children|reused-by` · `GET /proposals/{id}/lineage` | `LineageController.java:47-87` | OPERATOR |
| **ops** | `GET /anchors` · `GET /anchors/{root}` · `POST /anchors/{root}/verify` | `AnchorsController.java:50,56,64` | OPERATOR |
| **ops** | `POST /anchors[?wait]` (abre+firma batch memo vía signer) | `AnchorsController.java:44` | ADMIN |
| **ops** | `GET /dead-letters` · `GET /dead-letters/{id}` · `POST /dead-letters/{id}/resolve` · `POST /dead-letters/{id}/requeue` | `DeadLettersController.java:41,54,61,69` | ADMIN |
| **chaos-demo** | `GET|PUT|DELETE /demo/chaos` · `GET|PUT|DELETE /demo/price` | `ChaosController.java:44,49,65`, `PriceChaosController.java:47,52,65` | ADMIN + `cryptobot.chaos.enabled=true` (`@ConditionalOnProperty`) |
| **vertical observación (LLM/mercado)** | `POST /market-review` · `POST /monitoring/run-once` · `GET /market-reviews/latest` · `GET /market-reviews` · `GET|POST /watchlist` · `GET|POST /zones` · `GET|POST /journal` · `GET|POST /observations` · `GET /indicators` · `GET /snapshots/{symbol}` · `GET /quotes/{asset}` | `api/*.java` | OPERATOR |
| **health** | `GET /api/cryptobot/health` | `CryptobotHealthController.java:30` | permitAll |

**Endpoints genéricos de bypass: NO EXISTEN.** No hay endpoint que reciba bytes arbitrarios para firmar ni que salte policy (buscado con `grep -rn "sign\|unsignedTransaction" api/`). Los dos caminos que llegan al signer son `ExecutionService.run` (attestation obligatoria) y `AnchorService.submit` (memo `ir/1` re-derivado por el signer, devnet only, ADMIN). `POST /wallets/{id}/ask` reenvía el JWT del usuario al Runtime (`p.rawToken()`, `WalletsController.java:85`) — no firma nada.

**Comparación con §28**: `POST /intents` ≈ `POST /wallets/{id}/proposals` (acoplado a wallet y a REBALANCE); `/intents/{id}/approve|execute` ≈ `/proposals/{id}/approve|execute` (+ `reject`, `cancel`, que el mandato no lista); `GET /executions/{id}` ≈ `GET /proposals/{id}` (execution embebida en `doc.execution`; no hay id de execution separado del proposal); `/executions/{id}/receipt` ≈ `GET /proposals/{id}/receipts` (lista de 4 kinds); `POST /receipts/verify` ≈ `POST /receipts/{hash}/verify` (por hash, no por body). Compatibilidad: la UI y `scripts/demo/*` consumen estos paths; los nuevos deben ser aditivos.

## 11. Observabilidad

**Métricas Prometheus** (`CryptobotMetrics.java:30-60` doc, 86-114 constantes; `PrometheusMeterNamesTest` fija 12 nombres): `market_analysis_total{result,asset}`, `risk_analysis_total{result}`, `strategies_total{result,asset}`, `approvals_total{result}`, `trade_requested_total{result,asset}`, `trade_submitted_total{asset}`, `trade_confirmed_total{result,asset}`, `trade_failed_total{stage,asset}`, `trade_reconciled_total{result}`, `reconciliation_mismatch_total`, `duplicate_trade_suppressed_total`, `outbox_pending`, `outbox_failed`, `dlq_size`, `cryptobot_reconciliation_total{outcome}`, `cryptobot_dead_letter_total{reason}`, `cryptobot_dead_letter_open`, `policy_verdicts_total{decision,escalation}`, `policy_predicate_failed_total{predicate}`, `oracle_execution_refused_total`, `solana_rpc_errors_total{method,cluster,kind}`, `solana_confirmation_latency_seconds{result,cluster}`, `intelligence_receipts_total{result}`, `deterministic_inference_total`, `deterministic_mismatch_total`, `validator_attestations_total{result}`, `receipt_anchors_total{result}`, `anchored_receipts_total`, `anchor_pending`, `anchor_finality_latency_seconds`, `artifact_reuse_total{external}`, `provenance_depth`. Common tags: `environment, service, version, commit` (BuildIdentityConfig). Label `asset` acotado a allow-list, otro ⇒ `other`. **No hay métrica por `tenantId`, `intentId`, ni de "validated" separada de "signed"** (el funnel de ticket-573 usa `validator_attestations_total{issued}` como "validados").
**MDC** (`LogContext.java:38-63`): `requestId, correlationId, tenantId, proposalId, operationId, runtimeRunId` + `traceId/spanId` (Micrometer tracing siempre activo, export OTLP off por default, `application.yml:354-365`). `LogFields`: `event, status, operationId, stage, proposalId, errorCode`. **No hay `executionId`, `signature`, `receiptId` en MDC** (van como campos del mensaje).
**ErrorCode** (`ErrorCode.java:21-78`): `CB-POLICY-001..003, CB-RISK-001..002, CB-EXEC-001..004, CB-SOLANA-001..002, CB-RECON-001..003, CB-DLQ-001..003, CB-RUNTIME-001, CB-AUTH-001, CB-HTTP-400/401/403/404/4XX, CB-INTERNAL-500`.
**Eventos de dominio (audit `event_type`)**: `PROPOSAL_CREATED, SIMULATED, POLICY_EVALUATED, AWAITING_APPROVAL, BLOCKED_BY_POLICY, APPROVED, REJECTED, EXPIRED, CANCELLED` (`ProposalService.java:53-62`); `EXECUTION_STARTED, EXECUTION_VALIDATED, EXECUTION_SIGNED, EXECUTION_SUBMITTED, EXECUTION_BROADCAST_UNCERTAIN, EXECUTION_RETRIED, EXECUTION_CONFIRMATION_PENDING, EXECUTION_DUPLICATE_SUPPRESSED (constante, no usada en código: `grep EV_DUPLICATE_SUPPRESSED` → sólo la declaración), EXECUTED, EXECUTION_FAILED` (`ExecutionService.java:77-88`); `RECONCILED, RECONCILIATION_AMBIGUOUS, RECONCILIATION_RETRIES_EXHAUSTED` (`ReconciliationService.java:62-65`); `DEAD_LETTER_RESOLVED, DEAD_LETTER_REQUEUED` (`DeadLetterService.java:49-50`); `WALLET_REGISTERED` (WalletService). Outbox: `trade.*` (§7). Vocabulario del mandato §27 (`IntentCreated … ReceiptIssued`): parcialmente cubierto por audit+outbox, sin `ReceiptIssued` emitido.
**SSE: NO EXISTE en este servicio** (`grep -rn "text/event-stream\|ServerSentEvent" src/main/java` → 0). `AskResponse.ssePath` es el path SSE del **Runtime**.

## 12. Tests

**Ejecución real**: `cd <worktree> && ./mvnw -q test` (background, timeout 15 min; terminó en ~4 min, `EXIT=0`). Conteo agregado de `target/surefire-reports/*.txt` (86 clases): **Tests run=517, Failures=0, Errors=0, Skipped=1** (el skipped es `RiskEngineGoldenTest.writeGolden`, gated por `-Drisk.golden.write`: benigno). Log en `scratchpad/audit-A-mvn-test.log`. (Nota: `signer/target` y `validator/target` tienen reportes con timestamp de la misma hora, 61 tests verdes; no los corrí yo — el pom raíz no agrega módulos.)

**Inventario** (105 archivos; 14 sin `@Test` son soporte: `Fixtures`, `ExecutionHarness`, `InMemory*Repositories`, `FakeAuthServer`, `benchmark/*`, `ReferencePolicyValidator`):
- Unit puro (dominio): `DeterministicPolicyEngineTest`(10), `PolicyRulesTest`(6), `PolicyDeterminismTest`(3, segunda implementación `ReferencePolicyValidator`), `PolicyVectorsTest`(2), `IntentSchemaTest`(25), `IntentVectorsTest`(2), `JsonCanonicalizerTest`(7), `DeterministicRiskEngineTest`(7), `RiskEngineGoldenTest`(4), `ReceiptVectorsTest`(4), `MerkleTreeTest`(4), `AnchorMemoTest`(2), `ProposalStatusTest`(4), `ExecutionRecordJsonTest`(2), `PriceOracleTest`(13), `LegacyTransactionTest`(6), `Base58Test`(4), `IntentAuthorityProgramTest`(9), `ProgramDerivedAddressTest`(5), `QuoteRankingTest`(7)…
- Unit de aplicación con repos in-memory (`InMemoryControlPlaneRepositories`, mocks de RPC/signer/validator): `PolicyEngineTest`(28), `ExecutionServiceReliabilityTest`(11), `ExecutionRecoveryTest`(7), `ExecutionServiceMainnetGateTest`(5), `ExecutionServiceValidatorTest`(4), `ExecutionServiceOracleTest`(2), `ExecutionServiceMetricsTest`(5), `ProposalServiceTimelockTest`(3), `OutboxPublisherTest`(4), `ReceiptServiceTest`(5), `PolicyDecisionReproducerTest`(6), `AnchorServiceTest`(8), `LineageServiceTest`(9), `RiskEngineTest`(6), `RebalancePlannerTest`(5), `SolanaRpcClientTest`(8), `SolanaRpcClientMainnetGateTest`(5), `ChaosSolanaRpcClientTest`(4), `PriceChaosTest`(5), `ValidatorClientTest`(4)…
- `@SpringBootTest` con `StubRepositoriesConfiguration` (**sin Postgres**): `ControlPlaneFlowTest`(7: `fullDemoFlowAsPaperTradeWithCompleteAuditTrail`, `oversizedTradeIsBlockedByPolicyAndRecorded`, `adversarialPriceIsBlockedWithAVerifiableDecisionReceipt`, `rejectIsTerminal`, …), `DeadLettersAndChaosApiTest`(4), `LineageFlowTest`(2), `AnchorFlowTest`(1), `DemoPathJsonLogTest`(2), `PrometheusMeterNamesTest`(3), `JsonLogLineTest`(3), `CryptobotCorsTest`(4), `SnapshotsControllerSecurityTest`(3), `MonitoringControllerTest`(2), `MarketReview*`(8), `QuotesControllerTest`(6).
- Property/invariantes (ticket-440, `benchmark/InvariantsTest.java:30-36, 63-314`): **I1** límite ⇒ ¬Execute · **I2** nonce consumido ⇒ ¬Execute · **I3** agente no autorizado ⇒ ¬Execute · **I4** Execute ⇒ policyHash activo · **I5** Execute ⇒ asset permitido · **I6** Unknown ⇒ Deny · **I7** mismo (I,S,R) ⇒ mismo verdict en engine, validador y 2ª corrida. + `AdversarialBenchmarkTest`(10, corpus 10.000 intents) + `ChaosTest`(14). **Corren sobre `AuthorityLayer` (harness de test que usa TradingIntent), no sobre `ExecutionService`/HTTP.**
- Integración con Postgres: `ReceiptR2dbcStoreIT`(3), `AnchorR2dbcStoreIT`(2), `LineageR2dbcStoreIT`(1) — **gated por `@EnabledIfEnvironmentVariable(CRYPTOBOT_IT_PG_HOST)`** (`LineageR2dbcStoreIT.java:39`, `AnchorR2dbcStoreIT.java:44`, `ReceiptR2dbcStoreIT.java:51`), surefire no los incluye (`*IT`), y **Testcontainers NO EXISTE** (`grep -n testcontainers pom.xml` → 0). **`ActionProposalR2dbcStore`, `OutboxEventR2dbcStore`, `DeadLetterR2dbcStore` no tienen ningún test contra Postgres real** (`grep -rl ActionProposalR2dbcStore src/test/java` → 0): el optimistic lock, el unique de `operation_id` y `FOR UPDATE SKIP LOCKED` sólo están probados en la réplica in-memory (`InMemoryControlPlaneRepositories.java:162-178`).
- E2E: `e2e/E2EDevnetIT` (3 tests: `realExecutionEndToEnd`, `mainnetIntentIsRefused`, `injectedFailureIsRecoveredWithoutDoubleExecution`; `E2EDevnetIT.java:108,229,266`), sólo con perfil `-Pe2e-devnet` (Failsafe, `pom.xml:219-260`) contra `docker-compose.demo.yml` + devnet.

**Invariantes §29 que faltan como test**: "DENIED never SIGNED" y "unvalidated never SUBMITTED" existen para el harness (I1-I6) y para `ExecutionService` en unit (`validatorRefusalFailsClosedBeforeSigning`, `validatorSilenceIsDeny`) pero **no como property test sobre `ExecutionService` real**; "same key ≠ two executions" sólo in-memory; "mainnet disabled ⇒ no execute" sí (`ExecutionServiceMainnetGateTest`, `SolanaRpcClientMainnetGateTest`, E2E `mainnetIntentIsRefused`); "tampered receipt fails verify": `ReceiptServiceTest` cubre hash/firma, **no hay test que altere `body` y espere `valid=false` vía API** (`grep -rn "tamper" src/test` → 0); "expired approval can't sign": `executionPreconditions` (expiresAt) cubierto en `PolicyEngineTest`, timelock en `ProposalServiceTimelockTest`; "stale simulation ⇒ re-simulate": implícito (siempre re-simula), sin test que pruebe que una simulación vieja se descarta.

## 13. Configuración (`application.yml`) — flags y dónde se leen

| Clave | Env | Default | Se lee en |
|---|---|---|---|
| `cryptobot.execution.allow-mainnet` | `CRYPTOBOT_ALLOW_MAINNET` | **false** (198-199) | `ExecutionProperties.permits` → `ExecutionService.requireClusterAllowed` (179-196) y `SolanaRpcClient.sendTransaction` (214-218); signer tiene `SIGNER_ALLOW_MAINNET` propio |
| `cryptobot.policy.execution-enabled` | `CRYPTOBOT_EXECUTION_ENABLED` | true (202) | `PolicyEngine` regla `EXECUTION_ENABLED` (229-232) y `executionPreconditions` (510-512) |
| `cryptobot.policy.execution-cluster` | — | `devnet` (203, sin env) | regla `EXECUTION_CLUSTER` (234-237) |
| `max-trade-usd` / `max-trade-pct-of-portfolio` / `allowed-assets` / `cooldown` / `max-lamports-per-tx` / `proposal-ttl` | `CRYPTOBOT_POLICY_MAX_TRADE_USD`(500), `_MAX_TRADE_PCT`(50), — (SOL,USDC,USDT), `_COOLDOWN`(60s), `_MAX_LAMPORTS`(2e9), — (30m) | `PolicyProperties` (defaults también en el record, `PolicyProperties.java:24-33`) | `PolicyEngine.evaluate` |
| `rebalance-vault` | `CRYPTOBOT_REBALANCE_VAULT` | vacío ⇒ no ejecutable | `SimulationService.prepareTransfer` (75: **destino de la tx**), regla `REBALANCE_VAULT_CONFIGURED`, `SIGNER_DESTINATION_ALLOWLISTED` |
| `policy.authorization.*` (R_v) | `CRYPTOBOT_POLICY_VERSION`(cryptobot-policy-v1), `_AUTONOMOUS_UP_TO_USD`(100), `_SECOND_AGENT_UP_TO_USD`(250), `_DAILY_LIMIT_USD`(2500), `_MAX_ASSET_EXPOSURE_BPS`(8000), `_MAX_SLIPPAGE_BPS`(100), `_EXECUTOR_SLIPPAGE_BPS`(50), `_MAX_ORACLE_AGE`(15m), enabled-strategies=REBALANCE | 217-228 | `AuthorizationProperties.rules()` → `PolicyRules` (H_R) |
| `policy.timelock.{autonomous,escalated,execution-window}` | `CRYPTOBOT_TIMELOCK_*` | 0s / 30m / 30m (233-236) | `TimelockProperties.forVerdict` → `ProposalService.decide`, `PolicyEngine.executableAt` |
| `signer.{enabled,base-url,token,timeout}` | `CRYPTOBOT_SIGNER_ENABLED`(**false**), `_BASE_URL`(localhost:8096), `_TOKEN` | 237-242 | `SignerClient` |
| `validator.{enabled,base-url,token,timeout}` | `CRYPTOBOT_VALIDATOR_ENABLED`(**false**), `_BASE_URL`(localhost:8097), `_TOKEN` | 247-252 | `ValidatorClient` |
| `solana.rpc.{devnet-url,mainnet-url,timeout}` | `CRYPTOBOT_SOLANA_DEVNET_RPC`, `_MAINNET_RPC`, `_RPC_TIMEOUT`(8s) | 93-96 | `SolanaRpcProperties` → `SolanaRpcClient` |
| `marketdata.oracle.{min-sources,max-age,max-deviation-bps,max-move-bps,move-interval}` | `CRYPTOBOT_ORACLE_*` | 2 / 300s / 100 / 1000 / 5m (145-150) | `PriceOracleService` → `OracleLimits` (limitsHash) |
| `reliability.outbox.*` / `reliability.reconciliation.*` | `CRYPTOBOT_OUTBOX_ENABLED`, `_POLL_INTERVAL`; `CRYPTOBOT_RECONCILIATION_ENABLED`, `_INTERVAL`, `_GRACE`, `_MAX_ATTEMPTS`, `_MAX_RETRIES` | 259-277 | `ReliabilityProperties` |
| `chaos.{enabled,broadcast,shots,price-override}` | `CRYPTOBOT_CHAOS_ENABLED`(false), … | 280-288 | `ChaosConfiguration` (`@Primary` RPC), controllers `/demo/**` |
| `receipts.{key-id,signing-key,salt-secret,price-table-version,reuse-window}` | `CRYPTOBOT_RECEIPT_KEY_ID`, `_SIGNING_KEY` (secreto), `_SALT_SECRET` (secreto) | vacíos ⇒ **efímeros** (303-310) | `ReceiptKeyConfig` |
| `anchor.{enabled,cluster,interval,batch-size,max-attempts,finality-wait}` | `CRYPTOBOT_ANCHOR_*` | false / devnet / 60s / 256 / 5 / 60s (317-324) | `AnchorProperties`, `AnchorJob` |
| `security.enabled` | `CRYPTOBOT_SECURITY_ENABLED` | true | `CryptobotSecurityConfig` |
| `lifeengine.cryptobot.security.derive-runtime-authorities-from-role` | `CRYPTOBOT_DERIVE_RUNTIME_AUTHORITIES` | true | `CryptobotJwtService` (ADMIN⇒RUNTIME_ADMIN, OPERATOR/BO_ADMIN⇒OPERATOR, USER/VIEWER⇒VIEWER) |

Demo (`docker-compose.demo.yml:109-131`): `CRYPTOBOT_EXECUTION_ENABLED=true`, `CRYPTOBOT_ALLOW_MAINNET=false`, `SIGNER_ENABLED=true`, `VALIDATOR_ENABLED=true`, `SIGNER_REQUIRE_ATTESTATION=true`, `SIGNER_ALLOW_MAINNET=false`, reconciliation+outbox on, `CRYPTOBOT_CHAOS_ENABLED=true`. Test profile (`application-test.yml`): signer/validator/chaos off, `rebalance-vault` fijo, execution-enabled true.

## 14. Gaps y mapping CURRENT → TARGET (§3)

### 14a. Mapping por paquete/clase

| Hoy | → Módulo objetivo | Acción |
|---|---|---|
| `domain/transactions/{ActionProposal, ProposalStatus, ProposalTransition, ExecutionRecord, ApprovalRecord, AuditEvent, PreparedTransaction, SimulationOutcome}` | **trusted-execution-core** {intent, approval, submission, audit} | queda; renombrar conceptualmente `ActionProposal`→`ExecutionIntent`+`Execution` sin renombrar estados persistidos; `PreparedTransaction`/`SimulationOutcome.Onchain` son Solana-específicos ⇒ mover su construcción al adapter, dejar el tipo genérico (hash+bytes) en el core |
| `domain/intent/*` (TradingIntent, IntentSchema, JsonCanonicalizer, IntentHash, AssetId) | **trusted-execution-core**/intent | queda; **se generaliza**: `TradingIntent`→`ExecutionIntent` canónico §4 (operation genérica, source, tenant, destination, constraints); `JsonCanonicalizer` es la única canonicalización (también absorbe `domain/policy/CanonicalJson`, hoy duplicado package-private) |
| `domain/policy/*` | **trusted-execution-core**/policy | queda; `PolicyPredicate.ASSET_CONCENTRATION` y `DAILY_LIMIT` se evalúan como "execution policy" (velocity/limits) — definir si concentración se queda (es exposición de wallet) o va a trading-risk |
| `application/controlplane/PolicyEngine` (21 reglas nombradas) | **trusted-execution-core**/policy (`PRICE_*`, `MAX_*`, `ASSET_ALLOWLIST`, `COOLDOWN`, `EXECUTION_*`, `SIGNER_*`, `VALIDATOR_AVAILABLE`, `TIMELOCK_ELAPSED`, `ONCHAIN_SIMULATION_PASSED`) | **dividir**: `policyInput()` (447-485) es el adaptador `RebalancePlan→(I,S)` ⇒ va a **cryptobot-trading**/intent-producer; `REBALANCE_VAULT_CONFIGURED` es de trading/config; el resto queda y pasa a leer del `ExecutionIntent` |
| `domain/risk/*`, `application/controlplane/RiskEngine` | **cryptobot-trading**/trading-risk | mover entero (riesgo de portfolio: concentración, stables, dust, sharp move) |
| `domain/strategy/*`, `application/controlplane/RebalancePlanner`, `PortfolioService`, `domain/portfolio/*`, `AnalysisReuse`, `AdvisorService`, `domain/advisor`, `integration/lifeengine/*`, `infrastructure/runtime/*` | **cryptobot-trading**/{strategy, portfolio, position, signals} | mover; `AdvisorService` es LLM (decisión probabilística) — nunca toca el core |
| `application/controlplane/ProposalService` | **dividir**: `createRebalance` (143-197: plan+risk) → **cryptobot-trading**/intent-producer; `simulate`+`evaluatePolicy`+`decide`+`cancel`+`expireIfDue`+`commit` → **trusted-execution-core**/{simulation, policy, approval} | dividir |
| `application/controlplane/ExecutionService` | **trusted-execution-core**/{validator, signing, submission, retry} | queda; `prepareTransfer`+`simulateTransaction`+`sendTransaction`+`getSignatureStatus` pasan por puertos `ChainExecutionPort/ChainSimulationPort/ChainObservationPort` |
| `application/controlplane/SimulationService` | **dividir**: `economic()` (83-90) → trading; `prepareTransfer`+on-chain → **solana-execution-adapter**/{transaction-builder, simulation} | dividir |
| `application/controlplane/{AuditService, ExecutionReceipts, Receipts (execution/policyDecision/simulation)}` | **trusted-execution-core**/{audit, receipts} | queda; `Receipts.{strategy, riskDecision, walletSnapshot, humanIdea, marketAnalysis}` → trading |
| `application/reliability/*`, `domain/reliability/*` | **trusted-execution-core**/{reconciliation, retry, dlq, idempotency} | queda tal cual (ya es genérico salvo `assetOf` para métricas) |
| `application/receipt/{ReceiptService, ReceiptKeyConfig, DeterministicReproducer, LineageService}` | **trusted-execution-core**/receipts | queda; `AnchorService`/`AnchorJob` → **solana-execution-adapter**/chain-observer+memo (o queda en core como "anchoring port") |
| `adapters/solana/{SolanaRpcClient, SolanaCluster, ExecutionProperties, MainnetDisabledException, Base58, tx/*}` | **solana-execution-adapter**/{rpc, transaction-builder, confirmation, chain-observer} | queda; `ExecutionProperties.allowMainnet` se duplica como regla del core (`network`, `mainnetEnabled`) |
| `adapters/solana/authority/*` + `programs/intent-authority` (ticket-437) | **solana-execution-adapter**/program (opcional) | **hoy no cableado a nada en `src/main`** (`grep -rln IntentAuthorityProgram src/main` → sólo su paquete); decidir USE LATER |
| `adapters/marketdata/*`, `application/oracle/*`, `domain/oracle/*` | **cryptobot-trading**/market-data (`MarketDataPort`) + el consenso `PriceOracle` como servicio que el core consume por puerto (precio de referencia para `PRICE_*`) | dividir: fuentes → trading; `OracleReading`/`quotesHash` → core input |
| `adapters/quotes/*`, `application/quotes/*`, `domain/quotes/*`, `api/quotes` | fuera de los 3 módulos (feature ARS) | queda como está |
| `api/*` raíz (market-review, monitoring, watchlist, zones, journal, observations, indicators, snapshots), `application/MarketReview*`, `infrastructure/persistence/r2dbc/*`, `infrastructure/snapshot/*`, `infrastructure/solana/SolanaPublicClient`, `infrastructure/binance/*` | **cryptobot-trading**/{market-data, signals} (vertical de observación, LLM) | queda en trading |
| `api/controlplane/{ProposalsController, WalletsController.propose, ReceiptsController, LineageController, DeadLettersController, AnchorsController}` | **trusted-execution-core**/api (`/intents`, `/executions`, `/receipts`) — aditivo, manteniendo `/proposals/**` | queda + alias |
| `api/controlplane/{ChaosController, PriceChaosController}`, `application/chaos/*` | demo (fuera del core; `ChaosSolanaRpcClient` se vuelve un decorator del puerto) | queda gated |
| `domain/wallet/Wallet`, `WalletService`, `WalletR2dbcStore` | core/actor (wallet = actor+destino) | queda; quitar el import de `adapters.solana.SolanaCluster` (mover `SolanaCluster`/`Network` al core) |
| `infrastructure/persistence/controlplane/*` | core/persistence | queda |
| `security/*`, `observability/*`, `health/*` | transversal | queda |

### 14b. Gaps principales (prioridad mandato: demo > seguridad > correctitud > reuso)

1. **Intent canónico desconectado (ticket-457, Backlog)**: `TradingIntent` sólo deriva `operationId`; `(I)` del policy engine se fabrica desde `RebalancePlan` y desde la propia config (`policyVersion=rules.version()`, `maxSlippageBps=executorSlippageBps`), por lo que `POLICY_BOUND` y `SLIPPAGE_WITHIN_MAX` no verifican nada que venga del productor. Sin `source`, `tenant` explícito, `destination`, `maxFee`, `metadataHash`, `schema_version` en el doc persistido.
2. **Destino no ligado entre approval y execution**: `prepareTransfer` lee `cryptobot.policy.rebalance-vault` en ejecución (`SimulationService.java:75`); **NO EXISTE** comparación `tx.destination()` con `proposal.transaction().destination()` en `ExecutionService`/`PolicyEngine` (grep `destination()` → 0). Si la config cambia entre aprobar y ejecutar, el humano aprobó un destino y se firma otro (mitigación: allowlist del signer `SIGNER_ALLOWED_DESTINATIONS`, vacía por default ⇒ cualquier destino; el validador sólo atesta `messageHash`).
3. **Sin taxonomía de fallos** TRANSIENT/PERMANENT/… ni jitter en backoff (§14); clasificación implícita repartida entre `broadcastFailed`, `Verdict` y `FailureStage`.
4. **Recibo `EXECUTION` incompleto vs §15**: sin `attestationHash/validatorResult`, sin `submittedAt/confirmedAt/reconciledAt`, `policyHash/decision` sólo por hash del verdict; el Decision Receipt de policy se guarda como `kind=RISK_DECISION` (CHECK V6 sin `POLICY_DECISION`).
5. **Persistencia no probada contra Postgres**: `ActionProposalR2dbcStore.commit` (optimistic lock + unique `operation_id` + tx atómica) y `OutboxEventR2dbcStore.processDue` (SKIP LOCKED) sólo tienen la réplica in-memory; sin Testcontainers; los 3 `*IT` existentes están gated por env y no cubren proposals.
6. **Clave de firma de recibos efímera por default** y `verify` sin keyring (rotación ⇒ recibos históricos "inválidos").
7. **Audit append-only sólo por convención** (sin hash-chain, sin trigger/REVOKE).
8. **Sin puertos de cadena** (`ChainExecutionPort` etc.): `application` importa `SolanaRpcClient` en 7 sitios; `Wallet` importa `SolanaCluster`.
9. **Vertical y core en el mismo servicio y en el mismo agregado**: `ActionProposal` mezcla `intent/plan/riskBefore/riskAfter` (trading) con `policy/simulation/transaction/approval/execution` (core).
10. **Validador: comparación por `String.contains` del payload de la attestation** (`ValidatorClient.java:169-174`) en vez de parsear el JSON canónico; y la attestation no viaja al recibo.
11. **`nonceUnused` deriva del estado de la fila** (`PolicyEngine.java:474-475`), no de un nonce del intent; el programa on-chain de nonces (ticket-437) está implementado (cliente + `programs/intent-authority`) pero **no cableado**.
12. **Métricas**: no hay counter "validated" independiente del validador (`validator_attestations_total{issued}` hace de proxy) ni dimensión `tenant`; sin `receiptId/signature/executionId` en MDC.
13. **Creación de propuesta no idempotente** (`POST /wallets/{id}/proposals` sin key ni nonce): un agente que repite crea N propuestas AWAITING_APPROVAL (no N ejecuciones).
14. ticket-439 está en **Backlog** en Jira mientras el código del oráculo (`PriceOracleService`, reglas `PRICE_*`) está mergeado en main (etiquetas `ticket-439` en `PolicyEngine.java:48,83`, `application.yml:97`).

### Lo que YA satisface el mandato (no duplicar)
- Máquina de estados explícita y guardada por versión (§5, §12: `EXECUTING ≠ SUBMITTED ≠ EXECUTED`, `reconciledAt` separado).
- Idempotencia por `operationId` único + `Idempotency-Key` + supresión de duplicados (§11) con 11+7 tests.
- Policy determinista versionada con `H_R`, `Unknown⇒Deny`, `ALLOW/DENY/ESCALATE`, verdict hasheado, policy-binding en ejecución (§6).
- Simulación antes de firmar, dos veces, con los bytes exactos (§10).
- Validador independiente + signer con attestation obligatoria + verificación local de la firma contra la pubkey de la wallet + 3 gates de mainnet (§8, §9, §25).
- Reconciliación repetible con outcomes `matched/corrected/retried/dead_lettered/skipped` y DLQ con resolve/requeue auditados y runbook (§13, §14).
- Recibos firmados, content-addressed, con linaje, L1 reproducible, verificables offline, anclados en devnet (§15 parcial).
- Invariantes I1-I7 y benchmark adversarial (§29 parcial), E2E devnet con los 3 demos (`valid→CONFIRMED`, `mainnet→DENIED`, `injected failure→no double execution`) (§30 parcial: faltan como E2E excessive amount/slippage/unauthorized mint/duplicate request/signer down/tampered receipt, aunque `ControlPlaneFlowTest` cubre oversized y adversarial price como paper).

## 16-bis. Jira verificado (`jira.py get`)
ticket-435 Finalizada · ticket-436 Finalizada · ticket-437 Finalizada (post-v1) · ticket-438 Finalizada · **ticket-439 Backlog (post-v1) — código en main** · ticket-440 Finalizada · **ticket-457 Backlog** · ticket-403 Finalizada · ticket-571 Revisar · ticket-501 Revisar · ticket-391 Finalizada · ticket-392 Finalizada · ticket-393 Revisar · ticket-394 Finalizada · ticket-493 Revisar · ticket-572 Revisar · ticket-573 Revisar · ticket-402 Backlog · ticket-390 Epic Backlog.
