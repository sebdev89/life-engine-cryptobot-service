# Audit B — Fronteras de confianza: signer, validator, Solana, seguridad, primitivas

> Redacted for publication (2026-09-30): `ticket-NNN` references point to the private issue tracker; local paths are
> shown as `<workspace>/…` and lab hostnames/IPs as placeholders. The findings themselves are unchanged.

- Fecha: 2026-09-21 · Auditor B (read-only) · Mandato: MANDATE-TAE.md §0, §8, §9, §21, §24, §32
- Código: worktree `<workspace>/active/.worktrees/tae-audit-cryptobot` = `origin/main` @ `2b69b37` (Merge PR #29). Rutas relativas a ese worktree. CI de `2b69b37`: success (run 35576296231). UAT corre exactamente `2b69b37` en service, signer y validator (deploy/deploy/ops.d/evidence/20260921T08{22,23,25}*-uat-deploy-cryptobot*.md).
- Tests corridos hoy (punto 9): **signer 33/33 OK** · **validator 28/28 OK** (detalle en §9).

---

## 0. Resumen ejecutivo (lo que hay que saber en 12 líneas)

1. El signer es un proceso separado, con **un solo shape firmable**: `SystemProgram.transfer` desde su propia clave a una destination allow-listed, ≤ cap, en el cluster configurado, con attestation Ed25519 del validator sobre el SHA-256 de esos bytes (`signer/.../SigningPolicy.java:260-306`, `AttestationVerifier.java:92-156`). No existe endpoint "firmar cualquier cosa". Segundo shape: memo de ancla en devnet sin attestation (`SignerController.java:108-126`).
2. El validator **no ve los bytes de la tx ni la chain**: recibe hechos `(I,S)` y un `messageHash` que el service le cuenta, re-deriva el veredicto con su propia tabla y su propia copia de policy pinneada, y atesta ese hash (`validator/.../ValidationService.java:194-265`). Su javadoc lo admite: "It does not say the facts are true" (`:129-131`).
3. **Si quito el validator, la propiedad que cambia es**: "una decisión de policy que el service pudo haber inventado o alterado ya no es re-derivada por un segundo proceso con una policy pinneada por hash". NO cambia: cap por tx, allowlist de destino, cluster, fee payer (eso lo garantiza el signer por bytes). Es decir: hoy el validator protege la **capa de policy económica (USD, daily, slippage, oracle age, expiry)** y el signer la **capa de bytes (lamports, destino, programa)**. Ninguno de los dos verifica que los bytes correspondan a los hechos `(I,S)` (gap TOCTOU semántico, §3).
4. Mainnet: **4 capas independientes fail-closed** con archivo:línea en §4.6; ticket-493 está mergeado en `main` (PR #20, commits `dfde797`, `6b79826`, `1b82df6`) aunque Jira dice "Revisar". UAT verifica ambos flags en `smoke-uat.sh:751-759`.
5. La clave privada de la wallet **llega al signer por variable de entorno** en UAT (`SIGNER_KEYPAIR_JSON` ← `CRYPTOBOT_DEVNET_WALLET_KEY`, `deploy/deploy/uat/docker-compose.uat.yml:1063`) desde un `.env.uat` plano (0600, no versionado). En demo va por archivo montado `:ro` (`docker-compose.demo.yml:66,76`). El secreto SOPS que ticket-574 nombra NO EXISTE (buscado con `grep -rln CRYPTOBOT_DEVNET_WALLET_KEY deploy/deploy infra` → sólo compose y scripts).
6. En UAT el validator corre **sin pin de policy hash** (`VALIDATOR_POLICY_HASH: ${CRYPTOBOT_POLICY_HASH:-}` en compose `:1020`; `.env.uat` no tiene `CRYPTOBOT_POLICY_HASH`). El service igual compara hashes (`ValidatorClient.java:323`), pero el "pin aprobado por operador" no existe en UAT.
7. El programa on-chain `programs/intent-authority` (ticket-437, "Finalizada") **no está desplegado en devnet ni cableado al flujo**: NO EXISTE `cryptobot.authority.program-id` ni uso de `IntentAuthorityProgram` fuera de `adapters/solana/authority/` y su test (grep en `src/main`). Obsidian lo confirma ("No está en devnet", `Products/CryptoBot-OnChain-Authority-2026-09-16.md:67`). Es paper + tests de banco.
8. El LLM **no puede ejecutar**: la salida del Runtime (`crypto.portfolio-advisor.v1`) sólo produce texto/JSON de consejo y un `runtimeRunId` de procedencia; las propuestas las crea un humano con JWT `RUNTIME_OPERATOR` vía `POST /api/cryptobot/wallets/{id}/proposals` (`WalletsController.java:96-107`). La única acción económica real es `SOL → vault` configurado (`SimulationService.java:70-81`). No hay swap, no hay SPL transfer, no hay Jupiter write.
9. Falta **separación de funciones**: el mismo principal `RUNTIME_OPERATOR` propone, aprueba y ejecuta (`CryptobotSecurityConfig.java:77-78`; `ProposalsController.java:77,108`). `ESCALATE(REQUIRE_SECOND_AGENT|REQUIRE_HUMAN_SIGNATURE)` sólo agrega timelock de 30 min (`TimelockProperties.java:28-33`); el signer acepta ESCALATE como autorizante (`AttestationVerifier.java:42`).
10. La attestation **no se persiste completa**: el audit event `EXECUTION_VALIDATED` guarda hashes + `attestationSignature` pero NO el `payload` (`ExecutionService.java:328-331`), y el recibo `EXECUTION` no lleva validator/attestation/messageHash (`Receipts.java:378-388`). No se puede re-verificar la attestation desde la DB (§15 del mandato).
11. NO EXISTEN en el código (grep en `src/main`, `signer/src/main`): priority fees / ComputeBudget, durable nonce, genesis-hash binding, límite de velocidad en el signer, caché anti-replay de attestations (inofensivo hoy: Ed25519 es determinista y la misma attestation sólo produce la misma firma sobre los mismos bytes → misma tx).
12. Diferenciación (§32): Turnkey, Privy, Crossmint, Coinbase CDP y Squads ya ofrecen policy engines con límites/allowlists por wallet de agente (URLs en §8). Lo que **ninguno de los cinco publica** como paquete es: validator independiente con policy pinneada por hash + attestation sobre bytes + timelock + receipts firmados con DAG de procedencia + ancla Merkle + reconciliación/DLQ, todo open-source y auto-hosteado. No afirmar "nadie lo hace": Fordefi y Turnkey tienen simulación + policy; Vincent (Lit) tiene policies on-chain.

---

## 1. SIGNER (`signer/`)

### 1.1 Endpoints y contrato
| Endpoint | Auth | Recibe | Archivo:línea |
|---|---|---|---|
| `GET /api/signer/identity` | `X-Signer-Token` (compare constante `MessageDigest.isEqual`) | — | `SignerController.java:55-62,144-150` |
| `POST /api/signer/sign` | idem | `SignRequest{proposalId, unsignedTransactionBase64, expectedFeePayer, cluster, attestation{payload,signature}}` | `:35-36, 64-100` |
| `POST /api/signer/sign-anchor` | idem | `AnchorSignRequest{root, receiptCount, unsignedTransactionBase64, expectedFeePayer}` — **sin attestation** | `:106-126` |

Recibe la **transacción legacy serializada completa (wire con firma en cero)**, no un intent ni una descripción. La decodifica él mismo (`LegacyMessageDecoder`) y decide sólo por los bytes ("the signer does not trust the caller's description", `SigningPolicy.java:194`).

### 1.2 Qué verifica antes de firmar (orden real, `doSign` → `SigningPolicy.evaluate` → `AttestationVerifier.verify`)
| # | Check | Evidencia | Estado |
|---|---|---|---|
| 1 | token de servicio | `SignerController.java:73,144-150` | implementado |
| 2 | `signer.enabled` (kill switch propio) | `SigningPolicy.java:261` | implementado |
| 3 | **cluster**: requerido, canónico (`devnet`\|`mainnet-beta`), mainnet ⇒ `mainnet_disabled` salvo `SIGNER_ALLOW_MAINNET=true`, y debe igualar `SIGNER_CLUSTER` | `SigningPolicy.java:314-329` | implementado (ticket-493) |
| 4 | decodificable, 1 firma requerida, fee payer == nuestra pubkey, `expectedFeePayer` coincide | `:268-283` | implementado |
| 5 | exactamente 1 instrucción, programa == System, es `transfer`, source == fee payer | `:284-297` | implementado |
| 6 | destino ∈ `SIGNER_ALLOWED_DESTINATIONS` (allowlist vacía ⇒ rechaza) | `:298-301` | implementado |
| 7 | lamports ≤ `SIGNER_MAX_LAMPORTS` (default 2 SOL) | `:302-304` | implementado |
| 8 | attestation: validator key pinneada (si no ⇒ nada se firma), firma Ed25519 sobre payload exacto, `validator` == pinned, `proposal_id` == request, `message_hash` == SHA-256(bytes decodificados), `decision ∈ {ALLOW, ESCALATE}`, ventana `[issued_at, expires_at]` ±30 s, `cluster` presente y == request | `AttestationVerifier.java:92-156` | implementado (ticket-438/493) |
| 9 | genesis hash / blockhash pertenece al cluster declarado | — | **NO EXISTE** (`grep -rni genesis signer/src validator/src src/main/java` → 0) |
| 10 | scope/nonce/reuse de attestation (caché de vistas) | — | **NO EXISTE** (ver §1.6) |
| 11 | timelock | — | no es responsabilidad del signer; vive en el service (`PolicyEngine.executionPreconditions:525-531`) |
| 12 | velocity / límite diario en el signer | — | **NO EXISTE** (`grep -rniE "velocity|rate.?limit|daily" signer/src/main` → 0) |

`sign-anchor` (`SigningPolicy.evaluateAnchor:212-254`): sólo devnet, 1 instrucción `MemoSq4…`, **cero accounts**, texto que matchea `^ir/1 root=(sha256:[0-9a-f]{64}) n=(…) ts=…Z$` y coincide con `root`/`receiptCount` del request. Paga 5 000 lamports de fee por memo. Sin attestation ni rate limit ⇒ un service comprometido puede drenar fees a 5 000 lamports por llamada (severidad L, devnet).

### 1.3 Clave
- Carga una vez en boot: `SIGNER_KEYPAIR_PATH` (archivo) o `SIGNER_KEYPAIR_JSON` (env), formato solana-keygen 64 bytes (`SignerKeyStore.java:246-281`). Sólo la pubkey va al log (`:230-231`). Ed25519 del JDK (JEP 339), sin dependencia externa (`signer/solana/SolanaKeypair.java:18-20`).
- ¿Puede loguearla? No hay `log.*` con `keypair|secret|seed` en `signer/src/main` (grep). `server.error.include-message: never` (`application.yml:13-14`). Las excepciones del parser dicen "must be 64 bytes, got N" sin volcar el contenido.
- Dockerfile: usuario no-root `signer` uid 10002, imagen alpine, `HEALTHCHECK` wget local (`signer/Dockerfile:117-123`). Comentario: "Mount it read-only; never bake it in".
- **Cómo llega en cada entorno**: demo → archivo montado `${SIGNER_KEYPAIR_HOST_PATH}:/run/secrets/signer-keypair:ro` (`docker-compose.demo.yml:66,76`). **UAT → `SIGNER_KEYPAIR_JSON: ${CRYPTOBOT_DEVNET_WALLET_KEY}`** (`deploy/deploy/uat/docker-compose.uat.yml:1063`): la clave privada queda en el environment del contenedor (visible con `docker inspect` / `/proc/<pid>/environ` para quien tenga acceso al host). Origen: `deploy/deploy/uat/.env.uat` (0600, no versionado; `scripts/cryptobot-devnet-credentials.sh:149-150` la escribe ahí). SOPS: NO EXISTE para estas variables.

### 1.4 MAINNET fail-closed (ticket-493)
- Mergeado en `main`: `git log HEAD --grep ticket-493` → `ecae2dc Merge pull request #20`, `6b79826 ticket-493: signer — mainnet fail-closed via the attested cluster; REQUIRE_ATTESTATION=false only under local/test`. Jira ticket-493 sigue en **"Revisar"** (estado desactualizado, no el código).
- `SIGNER_ALLOW_MAINNET` default `false` (`SignerProperties.java:324`, `application.yml:39`); compose demo y UAT lo fijan en `"false"` (`docker-compose.demo.yml:73`, `docker-compose.uat.yml:1076`).
- `SIGNER_REQUIRE_ATTESTATION=false` sólo arranca bajo perfil Spring `local`/`test`; con cualquier otro perfil (incluido ninguno) el proceso no inicia (`AttestationRequirementGuard.java:186-207`; tests `refusesToStartOutsideLocalOrTest`, `refusesToStartWithNoProfile`). UAT no declara `SPRING_PROFILES_ACTIVE` para cryptobot* (compose `:887`) ⇒ "ninguno" ⇒ la guardia aplica.
- Observación: `SignerProperties.canonicalCluster` sólo conoce `devnet` y `mainnet-beta`; un signer configurado con `SIGNER_CLUSTER=testnet` o `localnet` rechazaría todo con `cluster_mismatch`/`cluster_unknown` (fail-closed, correcto; el compose local-validator reutiliza la etiqueta `devnet`, `docker-compose.demo.yml:105`).

### 1.5 Auth service→signer
Token estático compartido `CRYPTOBOT_SIGNER_TOKEN` == `SIGNER_TOKEN`, header `X-Signer-Token`, comparado en tiempo constante; token vacío ⇒ todo 401 (`SignerController.java:144-150`). No JWT, no mTLS. Red: en demo el signer no publica `ports:` (sólo red `cryptobot-demo`); en UAT `expose` + red `le-uat-cryptobot` (`docker-compose.uat.yml:1022-1025` validator, `:1078-1081` signer). La superficie del token es "quien esté en la red del compose".

### 1.6 Eventos de auditoría, métricas
- Por intento: `signer_bad_token` (401, `event=auth_rejected`), `signer_bad_request`, `signer_refused` (`event=sign_refused`, `ErrorCode.SIGN_REFUSED|ATTESTATION_REFUSED`), `signer_signed` (`event=signed` con proposalId, lamports, destination, signature, validator, decision, verdictHash), `signer_anchor_refused|signed` (`SignerController.java:77-98,118-124,130`). Logs JSON con MDC `proposalId` (ticket-573). Los rechazos son 403 con `{"reason": …}`.
- Métricas: sólo las de actuator/prometheus por defecto (`application.yml:56-61`); NO EXISTE un contador propio `signer_sign_total{result}` (grep `Counter|MeterRegistry` en `signer/src/main` → sólo `BuildIdentityCommonTags`). El service sí cuenta `validatorAttestation("issued"|"refused")` y `FailureStage.SIGN` (`ExecutionService.java:270-278`).
- Attestation reuse: el signer no guarda attestations vistas. Consecuencia real: dentro de los 90 s de TTL, re-presentar la misma attestation con los mismos bytes produce la **misma firma** (Ed25519 determinista, mismo blockhash) ⇒ misma tx id ⇒ la chain la de-duplica. Con bytes distintos falla `attestation_message_mismatch`. Riesgo residual: nulo para duplicado económico; sí permite N llamadas al signer para la misma tx (ruido). Severidad L.

### 1.7 "Si el service está comprometido, ¿qué puede hacer firmar?"
Con `SIGNER_ALLOW_MAINNET=false`, validator key pinneada y allowlist = vault:
- **Puede**: obtener firmas de `transfer(wallet → vault, ≤ 2 SOL)` en el cluster configurado **sólo si además consigue una attestation válida del validator** para esos bytes (necesita el token del validator — que el service tiene — y hechos `(I,S)` que pasen la tabla; puede inventarlos, ver §2.4). Tantas veces como quiera (sin velocity en el signer): cada una es una tx distinta si cambia el blockhash. Fondos terminan en el vault (cuenta del operador), no en un tercero.
- **Puede**: firmar memos de ancla ilimitados (fee 5 000 lamports c/u), sin attestation.
- **Puede**: enviar al signer bytes con blockhash de mainnet etiquetados `cluster=devnet`; el signer **no verifica genesis** y firmaría si destino ∈ allowlist y ≤ cap; luego el service comprometido los emitiría a mainnet (su propia guardia `sendTransaction` está en el mismo proceso comprometido). Impacto acotado a `≤ cap × N` hacia el vault si la misma keypair tuviera SOL en mainnet. Mitigación real hoy: la wallet es devnet-only por procedimiento. Severidad M (ver threat model T-mainnet-mislabel).
- **No puede**: cambiar destino, programa, cantidad de firmantes, ni firmar SPL/Token-2022/swaps/`AdvanceNonce`/ComputeBudget (rechazo `instruction_count`/`program_not_allowed`); ni mainnet declarado; ni sin attestation; ni con attestation DENY/expirada/otro cluster/otro proposalId.

### 1.8 Tests del signer (corridos, §9): 33
`SigningPolicyTest` 11 (cap, allowlist vacía, fee payer, programas no-transfer, múltiples instrucciones, garbage, disabled, anchor memo exacto, anchor fuera de devnet, mainnet sin flag, request sin cluster) · `AttestationVerifierTest` 9 (fail-closed sin key, forjada/faltante, proposal/bytes, DENY, ventana ±skew, not required, cluster mismatch/missing) · `SignerControllerTest` 7 (identity token, firma+verifica, sin validator nada, 403 con reason, anchor, mainnet con attestation válida ⇒ 403, attestation otro cluster) · `AttestationRequirementGuardTest` 4 · `JsonLogLineTest` 2.

---

## 2. VALIDATOR (`validator/`)

### 2.1 Endpoint y contrato
`POST /api/validator/validate` con `X-Validator-Token` (`ValidatorController.java:51-75,83-89`). Request (`ValidationService.Request:145-152`): `proposalId, policyHash, expectedVerdictHash, messageHash (hex64), intent{agent_id, strategy_id, policy_version, asset, trade_value_cents, max_slippage_bps, valid_until_slot}, state{daily_exposure_cents, asset_exposure_after_bps, oracle_age_seconds, agent_permitted, nonce_unused, current_slot}, cluster`. Response: decision, escalation, tier, failedPredicates, refusals, policyVersion/Hash, inputHash, verdictHash, issuedAt/expiresAt, `attestation{payload, signature, validator}`.

### 2.2 Qué valida "independientemente" — tabla honesta
| Requisito §9 | ¿Lo verifica el validator? | Cómo / evidencia |
|---|---|---|
| Integridad del intent | **Parcial**: re-hashea `(I,S)` canónico (`input_hash`) y re-deriva el veredicto; si `expectedVerdictHash` ≠ ⇒ `VERDICT_DISAGREEMENT` ⇒ DENY (`ValidationService.java:215-228`) | pero los hechos los provee el caller; no hay firma del intent ni lectura de DB/chain |
| Decisión y revisión de policy | **Sí**: `policyHash` del request debe == `H_R` de **su propia** copia (`:221-223`); policy cargada de su `application.yml:243-256` (mismos valores por default que el service en unidades enteras), opcionalmente pinneada con `VALIDATOR_POLICY_HASH`; si el pin no coincide **no arranca** (`PolicyStore.java:28-31`) | independiente de verdad: `IndependentPolicyTable` "hand-inlined, no shared helpers" (`:16-19`); `DefaultPolicyHashParityTest` en el service asegura paridad |
| Simulación | **No**: no recibe ni verifica resultado de simulación | NO EXISTE (`grep -rn simulat validator/src/main` → 0) |
| Mensaje de tx | **No decodifica bytes**: sólo atesta el `messageHash` que le pasan (`:202-204, 236`) | "an independent process… re-derived this verdict over these facts for these bytes" |
| Monto / mint / destino / programas | **No** (no ve la tx). Sólo `trade_value_cents` y `asset` (símbolo, no mint) como hechos declarados | `IndependentPolicyTable.java:42-44` |
| Red | **Sí**: `cluster` requerido, canónico, atestado verbatim (`:210-213, 237, 272-282`) | pero no comprueba que los bytes sean de ese cluster |
| Slippage | `max_slippage_bps ≤ policy` sobre un valor que el service fija en config (`executor-slippage-bps`), no un hecho de la tx (`PolicyEngine.java:466`) | predicado real, hecho débil |
| Expiry | `current_slot ≤ valid_until_slot` en **epoch seconds** provistos por el caller y **congelados al momento de la propuesta** (`PolicyEngine.policyInput:463,485`; `ValidatorClient.authorize` manda `decision.input()` grabado, `:280,299-301`) | no usa su propio reloj ni slot real ⇒ una propuesta expirada seguiría pasando este predicado; la expiry real la aplica el service en `executionPreconditions:534` |
| Nonce / idempotencia | `nonce_unused == true` como hecho declarado (calculado en el service al proponer, `PolicyEngine.java:474-475`) | el validator no puede saber si ya se ejecutó |
| Attestation | **Produce** una; no verifica ninguna entrante | — |
| Fail-closed | Sí: `VALIDATOR_ENABLED=false` ⇒ DENY con `VALIDATOR_DISABLED` (`:218-220`); unknown fact ⇒ predicado falla (`Facts.input:293-330`); malformed ⇒ 400 sin attestation (`:196-213`); policy que no carga ⇒ no arranca | tests `unknownFactsDeny`, `disabledValidatorDeniesEverything`, `refusesToStartWithoutALimit` |

### 2.3 Attestation: clave, formato, qué firma exactamente
- Clave: `VALIDATOR_KEYPAIR_PATH|JSON` (`AttestationKeyStore.java:122-141`), Ed25519 64 bytes formato solana-keygen; **sin configurar genera una efímera con WARN** (dev). En UAT llega como `VALIDATOR_KEYPAIR_JSON: ${CRYPTOBOT_VALIDATOR_ATTESTATION_KEY}` (`docker-compose.uat.yml:1015`); en demo por archivo `:ro` (`docker-compose.demo.yml:43,48`).
- Payload (JSON canónico RFC 8785 vía `CanonicalJson.canonicalize`): `{schema_version:"1", proposal_id, message_hash, cluster, policy_hash, input_hash, verdict_hash, decision, escalation, validator, issued_at, expires_at}` (`ValidationService.java:233-246`). Firma = Ed25519 sobre los bytes UTF-8 del payload canónico, base58 (`:247`). TTL `VALIDATOR_ATTESTATION_TTL` default 90 s (`ValidatorProperties.java:189`).
- Lo que **no** cubre: los bytes de la tx (sólo su hash, dicho por el caller), el resultado de simulación, el approval humano, el timelock, el operationId.

### 2.4 ¿De dónde sale la policy? ¿Es independiente?
- **Copia propia** en `validator/src/main/resources/application.yml:243-256` (env `VALIDATOR_POLICY_*`), pin opcional `VALIDATOR_POLICY_HASH`. No la recibe en el request (el request sólo trae el hash a comparar). Es independiente **del proceso**, pero **la misma persona/operación** configura ambas copias con los mismos defaults; si alguien cambia el service y el validator a la vez, ambos "concuerdan".
- **UAT: unpinned**. `VALIDATOR_POLICY_HASH: ${CRYPTOBOT_POLICY_HASH:-}` (`docker-compose.uat.yml:1020`) y `.env.uat` no contiene `CRYPTOBOT_POLICY_HASH` (`grep -c '^CRYPTOBOT_POLICY_HASH=' .env.uat` → 0). El validator arranca con `validator_policy_unpinned` WARN (`PolicyStore.java:33`). La demo sí pinnea (`demo-devnet.log`: "policy pin: sha256:1882cd0a…").
- **Los hechos `(I,S)` son del caller**: `ValidatorClient.authorize` manda `decision.input().canonicalMap()` grabado al proponer (`ValidatorClient.java:299-301`). Un service comprometido puede mandar `(I,S)` fabricados que pasen la tabla y el `messageHash` de cualquier tx; el validator atestará. **La contención pasa entonces al signer por bytes** (destino/cap/programa). Esto es exactamente lo que dice el javadoc `:129-131` y el README del programa: "a compromised service could still lie about it… This program makes four facts unforgeable" — el programa que no está desplegado (§4.7).

### 2.5 Respuesta explícita: "si quito el validator, ¿qué propiedad de seguridad cambia?"
- **Cambia**: (a) la regla `POLICY_HASH_MISMATCH` — hoy un service que ejecute con una policy distinta a la pinneada en el validator no consigue firma (`ValidationService.java:221-223`; `ValidatorClient.java:323-325`; `executionPreconditions:518-520` también la aplica en el service, pero en el mismo proceso); (b) la re-derivación del veredicto con una implementación distinta ("two independent readings of the schema agreed — not one piece of code run twice", `IndependentPolicyTable.java:16-19`), que detecta bugs/alteraciones en `DeterministicPolicyEngine` del service; (c) el binding attestation↔bytes↔cluster↔ventana de 90 s que el signer exige (`AttestationVerifier.java`) — sin validator, `SIGNER_REQUIRE_ATTESTATION=true` (default) hace que **nada se firme** (fail-closed probado: `withoutTheValidatorNothingIsSigned`, `failsClosedWithoutAValidatorKey`).
- **No cambia**: cap de lamports, allowlist de destino, único programa System transfer, fee payer, mainnet-closed, token — todo eso lo decide el signer solo (`SigningPolicy.evaluate`). Tampoco cambia la protección contra hechos falsos: el validator no los verifica.
- **Conclusión §9 del mandato** ("if removing it changes no security property, redesign"): sí cambia propiedades (a-c), así que no hay que rediseñarlo desde cero; pero para que sea la barrera que el mandato describe (verificar monto, mint, destino, programas, simulación, nonce) tiene que **decodificar los bytes** y **leer estado propio** (chain/DB), no confiar en `(I,S)` del caller. Ver gaps G-V1..G-V4.

### 2.6 Tests del validator (corridos, §9): 28
`ValidationServiceTest` 8 (agrees+attests exact bytes, disagreement ⇒ DENY, otro policy hash ⇒ DENY, unknown facts, disabled, malformed sin attestation, inputHash canónico, cluster requerido/normalizado/atestado) · `IndependentPolicyTableVectorsTest` 11 (vectores `policy/vectors-v1.json` compartidos con el service) · `PolicyStoreTest` 4 (pin ok, pin distinto ⇒ no arranca, límite faltante, tiers no monótonos) · `ValidatorControllerTest` 3 · `JsonLogLineTest` 2.

---

## 3. Integración service ↔ validator ↔ signer: orden real y TOCTOU

### 3.1 Orden real en `ExecutionService.run` (`:248-284`)
```
execute(ownerUserId, proposalId, actor, operationId)                       ExecutionService.java:120-171
  ├─ proposals.require (owner scope)                                         :131
  ├─ operationId == p.operationId ⇒ suppressDuplicate (idempotencia)         :132-134
  ├─ p.status().inFlight() con otro operationId ⇒ 409                        :135-138
  ├─ policy.executionPreconditions(p): APPROVED, kill switch, executable,
  │    verdict != DENY, H_R igual al actual, (I,S) grabado, oracle grabado,
  │    approval record, TIMELOCK, expiresAt                                  PolicyEngine.java:505-538
  ├─ oracle.read + policy.priceViolations (quorum/stale/deviation/breaker/drift) :146-153
  ├─ wallets.require → requireClusterAllowed (MAINNET guard #1)              :154-155, 173-193
  ├─ start(): APPROVED→EXECUTING + operationId, commit guardado por versión  :229-236
  └─ run():
       1. simulation.prepareTransfer(wallet, lamports)  ← blockhash fresco   :254  (SimulationService:71-81)
       2. rpc.simulateTransaction(unsignedBase64, sigVerify=false)           :256-258  (resultado NO persistido aquí)
       3. validator.authorize(executing, tx)  ← hash(messageBase64), (I,S) grabado, H_R, verdictHash, cluster  :268  (ValidatorClient:274-314)
       4. signer.sign(proposalId, unsignedBase64, wallet.address, cluster, attestation)  :278  (SignerClient:89-116)
          verifySigned(): shape 1+64+len, message devuelto == message enviado byte a byte, Ed25519 verify con pubkey de la wallet  :287-304
       5. persistSigned(): ExecutionRecord SIGNED + audit EXECUTION_VALIDATED + EXECUTION_SIGNED, antes del broadcast  :306-336
       6. broadcast(): rpc.sendTransaction(signedBase64) (MAINNET guard #3) → SUBMITTED → confirm() polling 20×1.5 s getSignatureStatuses → EXECUTED | FAILED | pending (queda SUBMITTED para el reconciler)  :338-366, 400-411
```
El validator se llama **después** de construir y simular la tx y **antes** del signer; el signer recibe la tx ya construida (unsigned wire). Retry ticket-571 (`retry:206-219`) repite 1-6 con el **mismo operationId** y attestation nueva.

### 3.2 DTOs por llamada
- service→validator: `{proposalId, policyHash, expectedVerdictHash, messageHash, cluster, intent{…}, state{…}}` (`ValidatorClient.java:292-301`). Respuesta chequeada en `check()`: attestation presente, decision ALLOW/ESCALATE, policyHash == grabado, verdictHash == grabado, payload contiene `"message_hash":"<hash>"` y `"cluster":"<cluster>"` (comparación por substring `:329-334` — funciona porque el JSON es canónico, pero es frágil).
- service→signer: `{proposalId, unsignedTransactionBase64, expectedFeePayer, cluster, attestation{payload, signature}}` (`SignerClient.java:105-110`). Respuesta `{signedTransactionBase64, signer, txHash, signature, validator, verdictHash}`.

### 3.3 TOCTOU: ¿lo validado == lo firmado == lo enviado, byte a byte?
Cadena de hashes:
1. `PreparedTransaction.messageBase64` (mensaje sin firmas) y `unsignedTransactionBase64` (wire = 1 byte count + 64 ceros + mensaje) salen del **mismo** `LegacyTransaction` (`SimulationService.java:74-77`).
2. Validator atesta `SHA-256(base64decode(messageBase64))` (`ValidatorClient.java:290`).
3. Signer decodifica el wire, extrae el message y exige `SHA-256(message) == attestation.message_hash` (`AttestationVerifier.java:127-131`); firma **ese** message (`SignerController.java:86,95,134-142`).
4. Service verifica que el message devuelto == `messageBase64` original y que la firma verifica con la pubkey de la wallet (`ExecutionService.java:293-301`).
5. Broadcast usa `signed.signedBase64` = respuesta del signer, sin tocar (`:339`).
⇒ **Byte a byte encadenado por hash de validator→signer→service→RPC: sí.** Persistido: `messageHash` **no** se guarda en `ExecutionRecord` (sólo signature, blockhash, lastValidBlockHeight, signer pubkey); PR abierta #27 (ticket-500) propone columnas `intent_hash` / `execution_signature`.
- Lo que la cadena **no** ata: que `(I,S)`/`trade_value_cents` correspondan a `lamports` de los bytes (nadie cruza USD↔lamports fuera del service); que la simulación de ejecución (paso 2) sea sobre el blockhash real (`replaceRecentBlockhash=true` cuando `sigVerify=false`, `SolanaRpcClient.java:189`); que el approval/timelock estén dentro de la attestation.
- Ventanas: attestation 90 s (validator) + skew 30 s (signer); blockhash ~90 s (`lastValidBlockHeight` guardado para el reconciler). Entre `persistSigned` y `sendTransaction` no hay re-check — correcto, los bytes ya están fijos.

---

## 4. SOLANA

### 4.1 Cliente RPC
- **Sin librería Solana**: JSON-RPC a mano sobre `WebClient` (`adapters/solana/SolanaRpcClient.java:26-31`, `call():288-329`), `pom.xml` sin `solanaj|sol4k|web3` (grep). Timeout 8 s (`SolanaRpcProperties.java:83`), buffer 16 MiB. Métodos: `getBalance`, `getTokenAccountsByOwner` (Token + Token-2022, sólo lectura `:109-118`), `getSignaturesForAddress`, `getBlockHeight`, `getLatestBlockhash` (**finalized**, `:171-172`), `simulateTransaction` (`confirmed`, `:185-204`), `sendTransaction` (`skipPreflight=false`, `preflightCommitment=confirmed`, `:214-220`), `getSignatureStatuses` (`searchTransactionHistory=true`, `:223`), `getTransaction` (`finalized`, para el ancla, `:251-256`), `requestAirdrop` (`:283`).
- Endpoints públicos por default `api.devnet.solana.com` / `api.mainnet-beta.solana.com` (`SolanaRpcProperties.java:81-82`); UAT sale a Internet sólo por la red declarada en ticket-574 (`docker-compose.uat.yml:60-64`).
- Errores: `SolanaRpcException` con `errorKind` rpc|timeout|transport (`:331-338`), métrica `solanaRpcError(method, cluster, kind)`.

### 4.2 Construcción de tx
- Codificador legacy propio (`adapters/solana/tx/LegacyTransaction`, `CompactU16`, `SystemProgram`, `MemoProgram`); copia idéntica en `signer/solana/` ("copy-not-reuse", `signer/README.md:175-176`).
- **Única acción económica**: `SystemProgram.transfer(wallet → cryptobot.policy.rebalance-vault, lamports)` (`SimulationService.java:70-81`), y sólo para un leg SELL de SOL (`:49-53`: "only a SOL sell leg is executable in this version"). **Swap: NO EXISTE** (`grep -rniE "jup.ag/swap|swapTransaction" src/main/java` → 0; Jupiter sólo se usa para precio, `SolanaPublicClient.java:83`). **SPL / Token-2022 transfer: NO EXISTE** como builder (sólo lectura de balances).
- Versioned tx / ALT: no. Priority fees / ComputeBudget: **NO EXISTE** (grep `ComputeBudget|setComputeUnit` → 0 en `src/main`/`signer`). Durable nonce: **NO EXISTE** (grep `AdvanceNonce` → 0).

### 4.3 simulateTransaction
- Al proponer: `SimulationService.simulate` → `Onchain{ok, error, unitsConsumed, logs, cluster}` **persistido** en la propuesta y en el recibo `SIMULATION` (`ProposalService.java:214-231`); regla `ONCHAIN_SIMULATION_PASSED` (`PolicyEngine.java:250-257`).
- Al ejecutar: re-simulación sobre los bytes exactos (`ExecutionService.java:256-258`), **resultado no persistido** (sólo gating); falla ⇒ `Conflict("Pre-flight simulation failed")` ⇒ FAILED stage PREFLIGHT.
- `sendTransaction` con `skipPreflight=false` ⇒ el nodo simula una tercera vez.

### 4.4 Confirmación
`confirm()`: `getSignatureStatuses` cada 1.5 s × 20 (30 s) hasta `confirmed|finalized` o `err` (`ExecutionService.java:400-411`); si sigue pending queda **SUBMITTED** y lo cierra `ReconciliationService` (`application/reliability/ReconciliationService.java:208-225`: no visto + `blockHeight > lastValidBlockHeight` ⇒ retry idempotente ticket-571 o FAILED; visto ⇒ corrected EXECUTED/FAILED; matched). Outcomes `matched|corrected|retried|dead_lettered|skipped` en `metrics.tradeReconciled` (`:263,281,304,327`). SUBMITTED ≠ CONFIRMED (EXECUTED) ≠ reconciled: sí están separados en estados + eventos (`EV_SUBMITTED`, `EV_EXECUTED`, `EV_CONFIRMATION_PENDING`, `EV_BROADCAST_UNCERTAIN`).

### 4.5 Memo / ancla (ticket-394, "Finalizada")
`AnchorService` (`application/receipt/AnchorService.java:37-57`): Merkle root de recibos → memo `ir/1 root=… n=… ts=…` → signer `sign-anchor` → `sendTransaction` → espera **finalized** (`getTransaction` commitment finalized) → stampa recibos. Devnet-only en `AnchorProperties` y en el signer. Sólo hashes on-chain (§16 cumplido). Endpoint `POST /api/cryptobot/anchors` requiere `RUNTIME_ADMIN` (`CryptobotSecurityConfig.java:69-70`).

### 4.6 Devnet vs mainnet — todas las capas (archivo:línea)
| # | Capa | Dónde | Independiente de… |
|---|---|---|---|
| 1 | Config service `cryptobot.execution.allow-mainnet` default false (`CRYPTOBOT_ALLOW_MAINNET`) | `ExecutionProperties.java:13-24`; `application.yml:198-199` | — |
| 2 | Policy: `EXECUTION_CLUSTER` con `execution-cluster: devnet` **hardcodeado sin env** | `PolicyEngine.java:234-237`; `application.yml:203` | 1 (misma JVM, distinto flag) |
| 3 | `ExecutionService.requireClusterAllowed` (wallet **y** proposal) antes de cambiar estado | `ExecutionService.java:173-193` | usa 1 |
| 4 | `SolanaRpcClient.sendTransaction` rechaza antes del RPC | `SolanaRpcClient.java:214-218` | usa 1 |
| 5 | Validator: `cluster` requerido y atestado | `ValidationService.java:210-213,237` | proceso distinto |
| 6 | Signer: `SIGNER_ALLOW_MAINNET` default false + `cluster_mismatch` vs `SIGNER_CLUSTER` + attestation.cluster == request | `SigningPolicy.java:314-329`; `AttestationVerifier.java:148-154`; compose `:73,1076` | proceso distinto, flag distinto |
| 7 | Deploy: UAT fija ambos `"false"` y `smoke-uat.sh:751-759` lo verifica en los contenedores; demo idem | `docker-compose.uat.yml:927,1076`; `docker-compose.demo.yml:110,73` | — |
| 8 | Tests: `ExecutionServiceMainnetGateTest` (5), signer `mainnetIsRefusedWithoutTheExplicitFlagEvenWithAValidAttestation`, `mainnetIsRefusedUnlessTheSignerHasItsOwnExplicitFlag` | — | — |
**Capas verdaderamente independientes (procesos/flags distintos): 3** — service (flag 1, reutilizado en 3 y 4), validator (5, sólo etiqueta), signer (6). Ninguna verifica genesis hash: la etiqueta `cluster` es declarativa en las tres.

### 4.7 `programs/intent-authority` (ticket-437)
- Qué hace: PDAs `policy(agent, policy_version)` con `H_R`, `nonce(agent, nonce)` y `receipt(intent_hash)`; instrucción `Execute` verifica firma del agente, policy registrada/no revocada, `policy_hash`, `Clock.slot ≤ valid_until_slot`, nonce/receipt inexistentes y crea ambos atómicamente; **no mueve fondos** (`programs/intent-authority/README.md:1-30`). 13 unit + 14 bank + 2 vectors en CI (`ci.yml:65-88`, job "solana program (cargo test, host)").
- **Desplegado en devnet: NO** (`Products/CryptoBot-OnChain-Authority-2026-09-16.md:67`: "No está en devnet. El host y le-ci no tienen el Solana CLI"). **Usado por el flujo real: NO** — `grep -rn "IntentAuthorityProgram\|cryptobot\.authority" src/main` → 0 fuera de `adapters/solana/authority/`. Cliente Java + vectores compartidos existen (`adapters/solana/authority/IntentAuthorityProgram.java`, `src/test/resources/authority/vectors-v1.json`). Estado: **paper + implementado-no-cableado**. Jira "Finalizada" es correcto para el alcance del issue (programa + cliente), pero el mandato §16 debe decidir si vale desplegarlo (ver §7 USE LATER).

### 4.8 RPC caído (ticket-571)
`ChaosSolanaRpcClient` (`application/chaos/ChaosSolanaRpcClient.java`, sólo con `cryptobot.chaos.enabled=true`, `ChaosConfiguration.java:24`) inyecta `RPC_DOWN` / respuesta perdida en `sendTransaction`, `getSignatureStatuses`, `getBlockHeight`. `broadcastFailed` distingue rechazo del nodo (FAILED) de incertidumbre (queda en vuelo con `EV_BROADCAST_UNCERTAIN`, `ExecutionService.java:373-395`). Evidencia demo: `scratchpad/demo-chaos.log`. Endpoints `/api/cryptobot/demo/**` sólo `RUNTIME_ADMIN` y sólo en stack demo (`CryptobotSecurityConfig.java:74-76`; UAT no setea `CRYPTOBOT_CHAOS_ENABLED`, grep en compose UAT → 0).

### 4.9 Direcciones/programas hardcodeados
System `1111…` (`SystemProgram.java:10`), Memo `MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr` (`MemoProgram.java:13`, `SigningPolicy.java:193`), Token `Tokenkeg…` y Token-2022 `TokenzQd…` (`SolanaRpcClient.java:35-36`, sólo lectura), explorer `explorer.solana.com` (`SolanaCluster.java:58-66`). Vault por env `CRYPTOBOT_REBALANCE_VAULT` (obligatorio en compose). Ninguna clave privada en el repo (§5.3).

---

## 5. SEGURIDAD del service

### 5.1 AuthN/AuthZ
- JWT de life-engine-auth: HS256/HS512 con `JWT_SECRET` (≥32 bytes o se deshabilita HS) o RS256 vía `AUTH_JWKS_URI` (`security/CryptobotJwtService.java:24-66,119-131`). Principal = `sub` (userId), `email`, `role`, `authorities` (`CryptobotPrincipal.java:7`). `Principals.require` exige principal verificado; "never from a header" (`api/controlplane/Principals.java:5`).
- `CRYPTOBOT_SECURITY_ENABLED=false` ⇒ `permitAll` a todo (`CryptobotSecurityConfig.java:27-31`). UAT lo fija en `"true"` (`docker-compose.uat.yml:908`); demo hereda default `true`.
- Rutas (`CryptobotSecurityConfig.java:49-80`): health/prometheus/info públicos; `/api/cryptobot/anchors`, `/dead-letters/**`, `/demo/**` ⇒ `RUNTIME_ADMIN`; **todo lo demás `/api/cryptobot/**` ⇒ `RUNTIME_OPERATOR`** — incluye crear wallet, proponer, aprobar, cancelar, ejecutar, receipts.
- Derivación de authorities desde `role` del JWT (`derive-runtime-authorities-from-role: true` default, `application.yml:346`): `ADMIN|ROLE_ADMIN` ⇒ OPERATOR+ADMIN; `OPERATOR|BO_ADMIN` ⇒ OPERATOR; `USER|VIEWER` ⇒ VIEWER (`CryptobotJwtService.java:193-210`). Consecuencia: cualquier `OPERATOR` de la plataforma puede ejecutar trades devnet.
- **Separación de funciones: NO EXISTE.** Mismo principal propone (`WalletsController.java:96`), aprueba (`ProposalsController.java:77`) y ejecuta (`:108`); `ProposalService.decide` no compara `actor` con `requestedBy` (`:310-338`). `REQUIRE_SECOND_AGENT` y `REQUIRE_HUMAN_SIGNATURE` "are recorded, not acted on" (`PolicyEngine.java:56`); sólo cambian el timelock (30 min). El signer trata ESCALATE como autorizante (`AttestationVerifier.java:42`) — coherente hoy porque **toda** propuesta pasa por approve humano, pero es el mismo humano.
- Tenant = `ownerUserId` del JWT; scoping por owner en `proposals.require(ownerUserId, id)` y `wallets.require`; `tenantId` al MDC sólo desde el token (`CryptobotJwtAuthenticationWebFilter.java:81-87`).
- Idempotencia HTTP: `Idempotency-Key` o body `operationId` ⇒ UUID; commit `APPROVED→EXECUTING` guardado por versión optimista (`ExecutionService.java:229-236`); replay ⇒ 200 con la misma firma (demo: "replay: HTTP 200, same signature").

### 5.2 ¿Puede el LLM/Runtime ejecutar sin policy?
- Entrada del LLM: `AdvisorService.ask` → `AdvisorRuntimeClient.start(workflow crypto.portfolio-advisor.v1)` → espera terminal → parsea JSON estricto → persiste `AdvisorMessage` + recibo `MARKET_ANALYSIS` con commitment del prompt (`AdvisorService.java:36-44,88-135`). "The LLM sees numbers we computed; it never sees keys, the raw RPC payloads, or the transaction bytes". La respuesta **no crea propuestas**: sólo se enlaza por `runtimeRunId` como procedencia (`ProposalService.createRebalance:143,157-166`).
- Camino Runtime→service (`crypto.market-review.v1`, `POST /api/cryptobot/market-review`): sólo lectura/análisis, `RUNTIME_OPERATOR`. Runtime con token S2S `sub=service:cryptobot` obtiene… lo que el `role` del token S2S derive. **Pendiente de verificar en life-engine-auth** qué role lleva ese token (fuera de este worktree); si derivara `OPERATOR`, el Runtime podría llamar `/proposals/{id}/execute`. Marcar como pregunta abierta P-1.
- Todo camino a la chain pasa por `ExecutionService.execute` (único `sendTransaction` fuera del ancla: grep `sendTransaction(` en `src/main` → `ExecutionService.java:339`, `AnchorService`, `ChaosSolanaRpcClient`). No hay endpoint que reciba bytes de tx desde afuera.
- Tratamiento "untrusted" de la salida del LLM: parseo estricto con `JsonNode.path(...).asText("")` (`AdvisorService.java:244-259`), sin ejecución de tools; el texto va al chat y al recibo como hash, no como instrucción. Correcto para §2 del mandato.

### 5.3 Secretos
- **En git**: 0 arrays JSON de 64 bytes, 0 strings base58 de 87-88 chars en archivos versionados (`git ls-files | xargs grep -lE …`). `.gitignore` excluye `.env.hackathon`, `.env.local`, `.env.demo`, `.env.demo-uat`, `out/`. Único "secreto" versionado: `src/test/resources/receipt/vectors-v1.json` con `secret_key_base64`/`secret_utf8` de **vectores de test** (clave de firma de recibos de prueba), guardado por `src/test/java/.../security/DevSecretNotShippedTest.java`. No es la wallet.
- **Cómo llegan** (nombres): service `JWT_SECRET`, `CRYPTOBOT_SIGNER_TOKEN`, `CRYPTOBOT_VALIDATOR_TOKEN`, `CRYPTOBOT_S2S_CLIENT_SECRET`, `CRYPTOBOT_RECEIPT_SIGNING_KEY`, `CRYPTOBOT_RECEIPT_SALT_SECRET`, `CRYPTOBOT_DB_PASSWORD`; signer `SIGNER_TOKEN`, `SIGNER_KEYPAIR_PATH|JSON`, `SIGNER_VALIDATOR_PUBLIC_KEY` (pública); validator `VALIDATOR_TOKEN`, `VALIDATOR_KEYPAIR_PATH|JSON`, `VALIDATOR_POLICY_HASH` (pública). Demo: `.env.demo` + archivos en `~/.cryptobot-demo` montados `:ro`. UAT: `deploy/deploy/uat/.env.uat` (0600, no versionado, escrito por `scripts/cryptobot-devnet-credentials.sh:149-150`) ⇒ env vars del contenedor. **SOPS para cryptobot: NO EXISTE** (grep en `deploy/deploy` e `infra`).
- **En logs**: grep `log.*` × `token|secret|keypair|seed|password` en los tres `src/main` → sólo pubkeys y `s2s_token_obtained clientId=… audience=…` (sin el token; `ServiceTokenClient.java:162-165,178-180`: "Ni el cuerpo de la respuesta ni el secreto presentado van al log"). `include-message: never` en los tres `application.yml`.
- Riesgo de exposición residual: la clave del signer en **env var** en UAT (§1.3); `SignerClient.SignerRefused("HTTP 403 " + body)` propaga el body del signer al error del service (sólo `{"reason":…}`, sin secreto).

---

## 6. THREAT MODEL (§24) — una fila por amenaza

Severidad = impacto en fondos/duplicación dado el estado actual (devnet, cap 2 SOL, vault propio). H/M/L.

| Amenaza | Activo | Camino | Mitigación existente (archivo:línea) | Qué falta | Sev | Cambio propuesto | Test que lo probaría |
|---|---|---|---|---|---|---|---|
| Usuario malicioso con JWT OPERATOR | fondos de su wallet devnet | propone/aprueba/ejecuta hasta cap | scoping por owner (`ProposalService.require`); policy MAX_TRADE_USD/daily/cooldown (`PolicyEngine.java:195-212`); signer cap+allowlist | separación proponer↔aprobar; velocity en signer | M | regla `approver != requestedBy` para tier ≥ SECOND_AGENT; `SIGNER_MAX_LAMPORTS_PER_DAY` | `ProposalServiceTest.selfApprovalRefusedForEscalatedTier`; `SigningPolicyTest.refusesOverDailyCap` |
| Prompt injection al advisor | integridad de la decisión | texto LLM → humano | LLM no crea ni ejecuta propuestas (`AdvisorService.java:36-44`); recibo commitment | nada crítico | L | — | ya cubierto: no hay camino de código |
| LLM/strategy comprometido | idem | idem | policy determinista + approve humano + validator + signer | idem | L | — | `ExecutionServiceValidatorTest.validatorRefusalFailsClosedBeforeSigning` (existe) |
| Credencial API (JWT) comprometida | wallet devnet del usuario | ejecuta dentro de límites | límites de policy, timelock 30 min si ESCALATE (`TimelockProperties.java:28-33`), cancel | timelock 0 s para ALLOW; sin 2FA/segundo actor | M | timelock mínimo > 0 en ALLOW para hackathon "safety visible" (ya configurable `CRYPTOBOT_TIMELOCK_AUTONOMOUS`) | `ProposalServiceTimelockTest` (existe) |
| Replay HTTP / duplicado | duplicación | mismo `/execute` ×N | `operationId` + versión optimista (`ExecutionService.java:131-138,229-236`); attestation ligada a proposalId+bytes | — | L | — | `ExecutionServiceReliabilityTest` (existe); demo replay |
| Duplicado por reconciler/restart | duplicación | SUBMITTED + RPC caído | firma persistida antes de broadcast (`:307`); retry sólo si blockhash expiró y no vista (`ReconciliationService.java:208-225`) | — | L | — | `ExecutionRecoveryTest` (existe) |
| Signer comprometido | clave privada | proceso con la clave | único proceso con clave, non-root, sin RPC, sin DB (`SignerApplication.java:85-89`) | clave en env var (UAT); sin HSM/TEE; sin velocity | H | montar como archivo `:ro`/secret de compose; a futuro Turnkey/HSM | `smoke-uat`: `env_of le-uat-cryptobot-signer SIGNER_KEYPAIR_JSON` debe estar vacío |
| Validator comprometido | autorización | emite attestations ALLOW arbitrarias | signer sigue acotando por bytes (cap, allowlist, System) | validator sin visión de bytes: no aporta límite adicional | M | validator decodifica bytes y cruza lamports↔trade_value (G-V1) | `ValidationServiceTest.refusesWhenBytesDoNotMatchFacts` |
| DB tampering | estado/receipts | editar `action_proposal` (status, policy, input) | receipts firmados Ed25519 (`ReceiptService`), verify endpoint (`ReceiptsController.java:73`); hash de policy re-chequeado | attestation payload no persistido; `messageHash` no persistido; audit no append-only por DB | M | persistir `attestation_payload` + `message_hash`; trigger append-only en `audit_event` | `ReceiptVerifyTest.tamperedExecutionReceiptFails`; test de "attestation re-verificable desde DB" |
| RPC miente / no disponible | verdad de chain | getSignatureStatuses falso | `searchTransactionHistory=true`; dos lecturas (status + blockHeight); nunca re-send ciego | un solo RPC provider; sin quorum de RPC | M | segundo RPC para reconciliación (Helius/Triton) | chaos ya existe (`ChaosSolanaRpcClient`) |
| Token/programa malicioso | fondos | — | signer sólo System transfer (`SigningPolicy.java:288-297`) | n/a hoy (sin SPL/swap) | L | cuando entre SPL: allowlist de mints en signer | `SigningPolicyTest.refusesNonTransferPrograms…` (existe) |
| Slippage / manipulación de precio | valor | oracle | quorum multi-fuente, deviation, breaker, drift (`PolicyEngine.priceViolations:342-411`); re-lectura al ejecutar (`ExecutionService.java:146-153`) | slippage es un valor de config, no de quote de DEX | L (sin swap) | al introducir swap: `max_slippage_bps` desde la quote real | `PolicyEngineTest` price rules (existe) |
| MEV | valor | — | n/a (transfer a vault propio) | — | L | — | — |
| Dependency compromise | todo | supply chain Maven | Ed25519 del JDK sin libs cripto; pocas deps | sin SBOM/`dependency-check` en CI (grep `dependency-check|sbom` en `ci.yml` → 0) | M | `mvn dependency-check` o `trivy` en CI | job CI |
| Leak de secretos/logs | claves/tokens | logs, env | sin `log.*` de secretos; `include-message: never` | clave signer en env (UAT); `.env.uat` plano | M | ver signer comprometido | `smoke-uat` |
| Policy mal desplegada | autorización | cambiar límites | `H_R` en veredicto; `executionPreconditions:518-520` rechaza si cambió; validator pin | **UAT sin `VALIDATOR_POLICY_HASH`** (`compose:1020`, `.env.uat`) | M | setear `CRYPTOBOT_POLICY_HASH` en `.env.uat`; smoke verifica `pinned=true` en `/api/validator/identity` | `smoke-cryptobot-devnet.sh` |
| Bypass de approval | autorización | `/execute` sin approve | `executionPreconditions:507-522` (APPROVED + approval record) | approval no está dentro de la attestation ni del recibo EXECUTION (sólo hash en inputs `Receipts.java:366-368`) | L | incluir `approval_hash` en el payload del validator | `ValidationServiceTest.attestsApprovalHash` |
| Simulación stale | ejecución fallida | bytes distintos a los simulados | re-simulación sobre bytes exactos al ejecutar (`ExecutionService.java:256`) | resultado de re-simulación no persistido; `replaceRecentBlockhash=true` | L | persistir `simulation_hash` de ejecución | `ExecutionServiceTest.persistsExecutionSimulation` |
| TOCTOU validación→firma→envío | integridad | cambiar bytes entre etapas | cadena de hashes §3.3 + `verifySigned` (`:288-305`) | `messageHash` no persistido | L | columna `message_hash` (PR #27) | `ExecutionServiceValidatorTest.attestationTravelsToTheSigner` (existe) |
| Mainnet habilitado por accidente | fondos reales | flags | 3 procesos × flags default false + `execution-cluster` hardcodeado + smoke (§4.6) | sin genesis hash: etiqueta declarativa | M | signer: `getGenesisHash` opcional al boot y comparar con el `recentBlockhash`… no es posible por bytes; alternativa: pin del **genesis hash esperado** en config del service y check en `getLatestBlockhash`/`sendTransaction` contra `getGenesisHash` del RPC configurado | `SolanaRpcClientTest.refusesRpcWhoseGenesisIsNotDevnet` |
| Bytes mainnet etiquetados devnet (service comprometido) | ≤ cap × N al vault | §1.7 | allowlist + cap | genesis/velocity | M | idem + velocity en signer | idem |

---

## 7. PRIMITIVAS SOLANA (§21) — clasificación

Fuentes oficiales consultadas: solana.com/docs (Token Extensions, Durable Nonces, Compute Budget, Fee Structure, Agentic Payments x402/MPP, Solana Pay), docs.anza.xyz (durable nonces), solana-program.com (Confidential Balances), github.com/solana-foundation/mpp-specs.

| Primitiva | Clasificación | Justificación (una línea) | Lo que ya existe en el código |
|---|---|---|---|
| **simulateTransaction** | USE NOW (ya) | Es el paso 2 de propuesta y de ejecución; falta persistir el resultado de ejecución | `SolanaRpcClient.java:185-204`; `SimulationService`; `ExecutionService.java:256` |
| **Memo program (ancla / correlación)** | USE NOW (ya) | Ancla Merkle ticket-394 funciona en devnet; para el golden path, agregar un memo `intentId/receiptId` en la tx de transfer daría correlación on-chain (Solana Pay lo exige como penúltima instrucción) — pero rompe `instruction_count == 1` del signer: cambio pequeño y controlado (2 instrucciones: memo sin cuentas + transfer) | `MemoProgram.java`; `SigningPolicy.evaluateAnchor` |
| **Compute budget / priority fees** | USE NOW (bajo costo, mejora fiabilidad demo) | `SetComputeUnitLimit` + `SetComputeUnitPrice` reducen "pending" en devnet congestionada; el signer debe permitir exactamente esas 2 instrucciones extra del programa ComputeBudget (`ComputeBudget111…`) con valores acotados (cap de micro-lamports) | NO EXISTE; docs: solana.com/docs/core/fees/compute-budget, /fee-structure |
| **Durable nonce** | USE LATER | Resuelve "attestation/approval de 30 min vs blockhash de 90 s" (firmar en approve, emitir tras timelock) y da anti-replay nativo, pero Anza advierte "may be deprecated in a future release" y exige `AdvanceNonceAccount` como 1ª instrucción + nonce authority = signer; hoy el retry idempotente ticket-571 ya cubre el caso | NO EXISTE; docs: solana.com/docs/core/transactions/durable-nonces, docs.anza.xyz/implemented-proposals/durable-tx-nonces |
| **SPL Token transfer (USDC)** | USE LATER (fase 7/9: "USDC supplier payment") | Necesario para la historia "Buy 50 USDC of SOL" y para Capital/escrow; requiere `transferChecked` con mint allowlist en signer y validator; hoy sólo SOL | lectura de balances Token/Token-2022 (`SolanaRpcClient.java:109-118`) |
| **Swap (Jupiter)** | USE LATER | El mandato pide "Buy 50 USDC of SOL": un swap real implica tx versionada + ALT + N instrucciones: el signer byte-level actual no puede acotarlo; requiere policy por simulación de balances (como Fordefi) | precio Jupiter sólo lectura (`SolanaPublicClient.java:83`) |
| **Token-2022 Transfer Hooks** | USE LATER / NOT APPLICABLE ahora | Permite que un programa nuestro vete transfers de un mint propio (allowlist on-chain), pero sólo aplica a mints que controlemos (no USDC/SOL); útil para "Capital" con stablecoin propia | nada; docs: solana.com/docs/tokens/extensions/transfer-hook |
| **Confidential Balances** | NOT APPLICABLE | Oculta montos; el mandato pide receipts verificables y montos auditables; incompatible con transfer hooks; SOL nativo no es Token-2022 | nada; docs: solana.com/docs/tokens/extensions/confidential-transfer |
| **Wallet policies / allowlists / limits / velocity / approval on-chain (Squads v4 smart account)** | USE LATER (evaluar en fase 4/9) | Squads v4 da spending limits, time locks y roles **enforced on-chain** — la propiedad que hoy sólo el signer off-chain garantiza; migrar la wallet demo a un vault Squads con el signer como "spending-limit member" agregaría una capa que un signer comprometido no puede saltar | nada; squads.xyz/blog/update-spending-limits, github.com/Squads-Protocol/v4 |
| **Programa propio intent-authority (ticket-437)** | USE LATER | Implementado y testeado, no desplegado; su valor (nonce/receipt/policy-hash on-chain) duplica parcialmente lo que Squads/Turnkey dan sin mantener un programa; desplegarlo antes del 12/10 exige Solana CLI en host/CI | `programs/intent-authority`; Obsidian OnChain-Authority |
| **x402** | USE LATER (Agent economy, fase 9) | Es HTTP 402 con firma de pago SPL USDC; encaja como **intent source** "agent-to-agent payment" del core, no como primitiva de ejecución; Solana lo documenta con V2 y `PAYMENT-SIGNATURE`/`PAYMENT-RESPONSE` | nada; solana.com/docs/payments/agentic-payments/x402, x402.org |
| **MPP (Machine Payments Protocol, Stripe/Tempo, `@solana/mpp`)** | USE LATER | Mismo rol que x402 (`WWW-Authenticate: Payment`, `charge`/`session`, `exact`/`upto`); útil para "agent pays API"; el core sólo necesita un `PaymentIntent` adapter | nada; github.com/solana-foundation/mpp-specs, stripe.com/blog/machine-payments-protocol |
| **Solana Pay (transfer request + reference)** | USE LATER | `reference` account indexada por validators permite localizar el pago por `intentId` con `getSignaturesForAddress` sin memo; barato para reconciliación de pagos entrantes (escrow/invoice) | nada; docs.solanapay.com/spec |
| **Versioned tx / Address Lookup Tables** | NOT APPLICABLE ahora | Sólo necesario con swaps; el decoder legacy del signer no las entiende | `LegacyTransaction` |

---

## 8. Competencia / diferenciación (§32)

| Proyecto | Qué hace (con URL) | Qué NO hace respecto a la composición §31/§32 |
|---|---|---|
| **Turnkey Solana Policy Engine** — turnkey.com/blog/introducing-solana-policy-engine, docs.turnkey.com/concepts/policies/examples/solana | Policies sobre `SIGN_TRANSACTION` parseando Transfer/TransferChecked de Token y Token-2022; MPC en enclaves; scope por address/contract/spend limit para agentes | SaaS custodial-MPC; no hay validator independiente con policy pinneada por hash ni attestation, ni receipts firmados/DAG, ni reconciliación/DLQ; policy y firma en el mismo proveedor |
| **Privy Agentic Wallets** — privy.io/agent-wallets, docs.privy.io/recipes/agent-integrations/agentic-wallets | Server wallets en TEE, policies: transfer limits, contract allowlists, recipient restrictions, time windows; Solana soportado | Igual: proveedor único, sin simulación obligatoria, sin recibos verificables ni reconciliación; no open-source |
| **Crossmint Agent Wallets** — crossmint.com/solutions/agentic-payments, crossmint.com/learn/agent-wallets-compared | Dual-key smart wallet (agent key en TEE), spending limits on-chain; en Solana usa **Squads**; cards + stablecoins | No separa decisión/ejecución con validator; sin receipts/DAG; su blog compara con Privy/Turnkey/Coinbase — evidencia de que "policy wallet para agentes" es categoría poblada |
| **Coinbase CDP Agentic Wallets / AgentKit** — coinbase.com/developer-platform/discover/launches/agentic-wallets, github.com/coinbase/agentkit | Policy engine: per-token allowances, tx limits, session caps, counterparty allowlists, session keys revocables; TEE | Base/EVM-first (Solana vía AgentKit limitado); mismo proveedor decide y firma; sin attestation externa ni reconciliación explícita |
| **Squads v4** — squads.xyz/blog/update-spending-limits, github.com/Squads-Protocol/v4 | Smart account formalmente verificado: spending limits, time locks 24 h, roles, sub-accounts, **enforcement on-chain** | Es infraestructura, no un motor intent→policy→simulación→receipt; complementario (ver §7) |
| **Fordefi** — fordefi.com/policy-engine, docs.fordefi.com/developers/simulate-transactions | Policy engine institucional que **simula cada tx** y evalúa efectos (tokens que salen/entran) antes de firmar; MPC | Custodial SaaS; no orientado a agentes ni open-source; sin DAG de procedencia ni ancla |
| **Lit Protocol Vincent** — github.com/LIT-Protocol/Vincent, spark.litprotocol.com/meet-vincent… | Abilities + policies (spend caps, allowlists, time windows) evaluadas antes de firmar en red descentralizada; delegación on-chain auditable; EVM y no-EVM | Requiere red Lit y token; sin receipts firmados por etapa ni reconciliación; Solana no es first-class |
| **Solana Agent Kit (SendAI)** — github.com/sendaifun/solana-agent-kit, docs.sendai.fun/docs/v2/introduction | 100+ tools para que un LLM opere Solana; v2 integra Turnkey/Privy y human-in-the-loop | **Antipatrón del mandato**: v1 metía la private key base58 en el agente; sin policy engine propio, sin validator, sin receipts — es lo que CryptoBot debe contrastar en el pitch |

**Diferenciación defendible (con evidencia, sin "nadie lo hace")**: la composición completa y auto-hosteable — (1) validator en proceso separado con policy **pinneada por hash** y attestation Ed25519 sobre los bytes (`ValidationService`, `AttestationVerifier`), (2) signer byte-level con un solo shape y mainnet fail-closed (`SigningPolicy`), (3) timelock + cancel humano, (4) recibos firmados por etapa con DAG de procedencia y **ancla Merkle en devnet** (`Receipts`, `AnchorService`), (5) reconciliación con outcomes nombrados + DLQ + retry idempotente (`ReconciliationService`), (6) demo de fallas (chaos). Turnkey/Fordefi cubren (2) mejor (MPC/HSM, SPL parsing); Squads cubre (2) on-chain; ninguno de los ocho publica (1)+(4)+(5) juntos. Lo que CryptoBot **no** tiene y ellos sí: custodia MPC/TEE, SPL/Token-2022 parsing, swaps, velocity limits.

---

## 9. Tests corridos (punto 9)

```
cd signer    && mvn -q test   → EXIT=0  (log: scratchpad/logs/auditB-signer-test.log)
  AttestationRequirementGuardTest 4 · AttestationVerifierTest 9 · JsonLogLineTest 2 · SignerControllerTest 7 · SigningPolicyTest 11
  TOTAL 33 run, 0 failures, 0 errors, 0 skipped
cd validator && mvn -q test   → EXIT=0  (log: scratchpad/logs/auditB-validator-test.log)
  JsonLogLineTest 2 · IndependentPolicyTableVectorsTest 11 · PolicyStoreTest 4 · ValidationServiceTest 8 · ValidatorControllerTest 3
  TOTAL 28 run, 0 failures, 0 errors, 0 skipped
```
Observación: `SignerControllerTest` arranca con perfil `prod` y `signer_no_validator_key` WARN (tests con `require-attestation` según caso). Tests del service relevantes a este alcance (no corridos por mí, CI verde en `2b69b37`): `ExecutionServiceValidatorTest` 4, `ExecutionServiceMainnetGateTest` 5, `ExecutionRecoveryTest`, `ExecutionServiceReliabilityTest`, `ValidatorClientTest`, `DefaultPolicyHashParityTest`, `IntentAuthorityProgramTest`, `E2EDevnetIT` (IT, requiere devnet). **E2E con validator y signer reales: en PR abierta #27 (ticket-500, "Revisar")**, no en main.

---

## 10. Gaps (mi alcance) — tabla

| ID | Requisito del mandato | Estado actual | Evidencia | Gap | Sev |
|---|---|---|---|---|---|
| G-S1 | §8 signer: no keys in API/logs; clave protegida | Clave por archivo `:ro` en demo; **por env var en UAT** | `docker-compose.uat.yml:1063`; `.env.uat` plano 0600 | Mover a archivo/secret montado; SOPS/Bitwarden para `.env.uat` (ticket-574 lo promete, no existe) | H |
| G-S2 | §8 verifica scope, reuse, expiry | Expiry y cluster sí; reuse no; velocity no | `AttestationVerifier.java:136-154`; grep velocity → 0 | Velocity/daily cap en signer; opcional caché anti-replay por `verdict_hash` | M |
| G-S3 | §8 audit event por intento + métricas | Logs estructurados por intento; sin contador propio | `SignerController.java:77-98`; sin `Counter` | `signer_sign_total{result,reason}` en Micrometer | L |
| G-S4 | §25 mainnet defensa en profundidad | 3 procesos independientes + smoke; etiqueta `cluster` declarativa | §4.6 | Verificar `getGenesisHash` del RPC contra el cluster esperado en el service (y opcionalmente que el signer reciba el genesis firmado por el validator) | M |
| G-S5 | §8 "accepts only validated artifacts" | `sign-anchor` sin attestation ni rate limit | `SignerController.java:104-126` | Rate limit o attestation "anchor" del validator | L |
| G-V1 | §9 validator verifica monto, mint, destino, programas, mensaje | No decodifica bytes; atesta un hash dicho por el caller | `ValidationService.java:202-204,236` | Recibir `unsignedTransactionBase64`, decodificar (reusar `LegacyMessageDecoder`), cruzar lamports/destino/programa con `(I,S)` y con su propia allowlist | H |
| G-V2 | §9 verifica simulación, nonce/idempotencia, expiry con estado propio | Hechos `(I,S)` del caller, congelados al proponer; `current_slot` = epoch second de la propuesta | `ValidatorClient.java:299-301`; `PolicyEngine.java:463-485` | Validator lee reloj/slot propio (`getSlot`) y, para nonce, consulta DB/chain o recibe firma del approval; al menos usar `now` propio para `NOT_EXPIRED` | H |
| G-V3 | §6/§35 policy revision pinneada | UAT sin `VALIDATOR_POLICY_HASH` | `docker-compose.uat.yml:1020`; `.env.uat` | Setear `CRYPTOBOT_POLICY_HASH` y hacer que el smoke falle si `pinned=false` | M |
| G-V4 | §15 attestation en el receipt, verificable | Payload no persistido; recibo EXECUTION sin validator/attestation/messageHash | `ExecutionService.java:328-331`; `Receipts.java:378-388` | Persistir `attestation_payload`, `attestation_signature`, `message_hash` en execution; incluir `attestationHash`, `validatorIdentity`, `transactionMessageHash`, `policyHash` en `execution/1` → `execution/2` | H |
| G-I1 | §11/§12 TOCTOU persistido | Cadena de hashes en memoria correcta; `messageHash` no en DB | §3.3 | PR #27 (`intent_hash`, `execution_signature`) + `message_hash` | M |
| G-I2 | §10 simulación de ejecución correlacionable | Re-simulación no persistida; `replaceRecentBlockhash=true` | `ExecutionService.java:256`; `SolanaRpcClient.java:189` | Persistir `simulation_hash` de ejecución; simular con `sigVerify=false, replaceRecentBlockhash=false` sobre el blockhash real | L |
| G-A1 | §35 separación de funciones / ESCALATE | Mismo OPERATOR propone-aprueba-ejecuta; ESCALATE = sólo timelock | `CryptobotSecurityConfig.java:77-78`; `PolicyEngine.java:56` | `approver ≠ requestedBy` para tier ≥ SECOND_AGENT; authority distinta para `approve` (p.ej. `CRYPTOBOT_APPROVER`) | M |
| G-A2 | §2 LLM nunca ejecuta | Cumplido por diseño; **pregunta abierta P-1**: role del token S2S `service:cryptobot` | `application.yml:46-56`; `CryptobotJwtService.java:193-210` | Verificar en life-engine-auth que el token S2S no derive `OPERATOR`; si sí, denegar `/proposals/**` y `/wallets/*/proposals` a `sub=service:*` | M (hasta verificar) |
| G-C1 | §21 primitivas | Sin priority fees, sin memo de correlación en el transfer, sin SPL | §7 | ComputeBudget + memo `intentId` (cambio acotado en signer: whitelist de 2 programas más) | M (demo reliability) |
| G-C2 | §14/§16 programa on-chain | intent-authority no desplegado ni cableado | §4.7 | Decisión explícita en el ADR: desplegar (necesita Solana CLI) o parquear a favor de Squads | L |
| G-C3 | §24 RPC lying | Un solo proveedor RPC público | `SolanaRpcProperties.java:81-82` | Segundo RPC para reconciliación | M |
| G-D1 | §24 dependency compromise | Sin SBOM/scan en CI | `.github/workflows/ci.yml` (grep → 0) | `dependency-check`/`trivy` | M |
| G-D2 | §34 estado Jira vs código | ticket-493 y ticket-571 "Revisar" aunque mergeados y desplegados en UAT; ticket-500 en PR abierta | `git log`, `gh pr list` | Cerrar ticket-493; decidir merge de #27 antes de la fase 1 | L |

### Preguntas abiertas para el lead
- **P-1**: ¿qué `role`/authorities lleva el token S2S `sub=service:cryptobot` que el Runtime usa contra `/api/cryptobot/**`? (repo life-engine-auth, fuera de mi alcance). Determina si un Runtime comprometido podría llamar `/execute`.
- **P-2**: ¿la keypair devnet de UAT (`CRYPTOBOT_DEVNET_WALLET_KEY`) existe también con fondos en mainnet? Si no, T-mainnet-mislabel es L.
- **P-3**: ¿se quiere desplegar `intent-authority` antes del 12/10 (requiere Solana CLI en host o le-ci) o se documenta como "post-hackathon" en el ADR?
