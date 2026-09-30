# Auditoría C — Operación, compatibilidad, Jira, docs, demo (TAE Phase 0)

> Redacted for publication (2026-09-30): `ticket-NNN` references point to the private issue tracker; local paths are
> shown as `<workspace>/…` and lab hostnames/IPs as placeholders. The findings themselves are unchanged.

- Fecha: 2026-09-21 · Auditor C (read-only) · sesión lead 4c3b37fb
- Código auditado: worktree detached `<workspace>/active/.worktrees/tae-audit-cryptobot` = origin/main `2b69b37` (Merge PR #29 ticket-573). `git status --short | wc -l` = 0.
- UI auditada: worktree detached `<workspace>/active/.worktrees/tae-audit-cryptobot-ui` = origin/main `b439995` (Merge PR #7 ticket-576). Creado por esta auditoría (única escritura fuera del scratchpad).
- Todas las rutas relativas son al worktree del service salvo que se indique `deploy/`, `gitops/`, `ui/`, `vault/`.
- Convención de estado: **implementado** · **implementado pero no cableado** · **sólo en tests** · **sólo en docs** · **en PR abierta** · **NO EXISTE (buscado con …)**.

---

## 1. Repos, ramas, worktrees, CI

### 1.1 PRs abiertas

| Repo | PR | Rama | Última act. | Qué trae | Estado / riesgo |
|---|---|---|---|---|---|
| cryptobot-service | **#27** | `ticket-500-e2e-cadena-completa` (base 0dba856) | 2026-09-20 | `ChainE2EIT` + `ChainStack` + `Relay` (E2E con validator y signer reales en JVM), `intent_hash`/`execution_signature` como columnas, cambios en `ci.yml`, `pom.xml`, `README.md` (18 archivos, +1472) | **CONFLICTO DE MIGRACIÓN**: la rama agrega `V10__intent_hash_and_execution_signature.sql`; main ya tiene `V10__inference_engine_version_width.sql` (llegó con ticket-572 `da490d4`). Flyway falla con dos V10. Hay que renumerar a V11 antes de mergear. `gh pr view 27 --json mergeable` → UNKNOWN. |
| cryptobot-service | **#26** | `ticket-353-glosario-metricas` (base 0dba856) | 2026-09-20 | `POST /api/cryptobot/glossary/events`, `cryptobot_glossary_term_total{term,action}`, `cryptobot_glossary_search_total{hit}`, dashboard `life-engine-cryptobot-glosario.json` (+1373) | Fuera del demo path (label `post-v1` en ticket-353). Toca `CryptobotMetrics.java` y `CryptobotMetricsTest.java` → conflicto probable con ticket-582 (ver 1.3). |
| cryptobot-ui | **#8** | `ticket-353-glosario-metricas` | 2026-09-20 | eventos open/search/copy → service | Depende de service #26. |
| runtime | **#33** | `colosseum-cryptobot-advisor` | 2026-09-14 | workflow `crypto.portfolio-advisor.v1` | OPEN desde 2026-09-14; **no existe en runtime main** (ver §5). Issue ticket-326 (own-platform, post-v1). |
| deploy | **#220** | `ticket-388-cryptobot-platform-catalog` | — | `deployments/scripts/prod-readiness.sh`, `scripts/platform-verify.sh` conocen a cryptobot | ticket-388: el catálogo YA tiene cryptobot (merged en ticket-574 #216); esta PR es el residuo (verify/readiness). |

PRs mergeadas relevantes (todas `sebdev89/life-engine-cryptobot-service`, por `gh pr list --state merged`): #8 ticket-403 (squash 0eb05c7) · #9 ticket-435 · #10 ticket-436 · #12 ticket-440 · #13 ticket-437 · #14 ticket-391 · #15 ticket-392 · #16 ticket-393 (2026-09-21) · #17 ticket-394 · #18 ticket-438 · #19 ticket-488 · #20 ticket-493 · #21 ticket-69 · #22 ticket-570 · #23 ticket-571 · #24 ticket-579 · #25 ticket-575 · #28 ticket-572 · #29 ticket-573.

### 1.2 Ramas remotas sin mergear (service, `git branch -r --no-merged origin/main`)

| Rama | Último commit | KAN | Estado |
|---|---|---|---|
| `origin/ticket-500-e2e-cadena-completa` | 63f2e59 2026-09-20 | ticket-500 | PR #27 abierta (ver arriba) |
| `origin/ticket-353-glosario-metricas` | 9cbdd14 2026-09-20 | ticket-353 | PR #26 abierta |
| `origin/ticket-403-ejecucion-fiable` | be48526 2026-09-16 | ticket-403 | **stale**: PR #8 se mergeó por squash (0eb05c7); el commit de la rama quedó huérfano. Borrable. |
| `origin/ticket-199-cryptobot-build-identity` | c6083c7 2026-07-16 | ticket-199 | **stale**: PR #1 mergeada 2026-07-16; 3 commits residuales (target/ y .gitignore). Borrable. |

Las otras 27 ramas remotas están contenidas en main. UI: sólo `origin/ticket-353-glosario-metricas` (fbb0b6a) sin mergear; `origin/feat/glosario-cryptobot` y `ticket-259/325/393/394/576` contenidas.

### 1.3 Worktrees (sólo listado; `git status --short | wc -l`)

`<workspace>/active/.worktrees/*cryptobot*`:

| Worktree | Rama | HEAD | Dirty |
|---|---|---|---|
| `cryptobot-colosseum` | ticket-324-colosseum-mvp | 4ada2f5 | 0 |
| `cryptobot-ui-colosseum` | colosseum-cryptobot-mvp | 8753cc2 | 1 |
| `ticket-352-cryptobot` | ticket-352-cryptobot-uat | e410bf2 | 0 |
| `ticket-353-cryptobot-ui` | ticket-353-glosario-metricas | fbb0b6a | 0 |
| `ticket-355-cryptobot` / `ticket-355-cryptobot-ui` | ticket-355-cotizaciones-ars | 07f95c0 / 37daed1 | 0 / 0 |
| `ticket-439-cryptobot` | ticket-439-oraculo-multifuente | b79518b (ancestro de main) | **62 staged** — snapshot viejo del trabajo ticket-438/439 ya en main (`git diff --cached origin/main --stat` = 168 files, +343/−12632). Sin valor; revisar y descartar. |
| `ticket-572-cryptobot` | ticket-572-risk-antifraude-demo | da490d4 | 0 |
| `ticket-582-cryptobot` | ticket-582-metricas-finas-demo | 2b69b37 | **8 dirty** — trabajo EN CURSO (ticket-582 claimed por verticals 2026-09-21 05:49): modifica `signer/…/SignerController.java`, `ExecutionService.java`, `PolicyEngine.java`, `ReconciliationService.java`, `CryptobotMetrics.java`, `validator/…/ValidatorController.java`; nuevos `SignerMetrics.java`, `ValidatorMetrics.java`, `ExecutionServiceRefusalMetricsTest.java` (+245/−41). **Toca exactamente los archivos que la fase 1 del mandato va a mover.** |
| `tae-audit-cryptobot` | (detached) | 2b69b37 | 0 |

`<workspace>/active/cryptobot/cryptobot-service/.worktrees/*` (8, todos dirty=0): `demo-main` (detached 4cd0104), `ticket-393` (35812d8), `ticket-488` (50afcd6), `ticket-493` (600091b), `ticket-570` (1c68130), `ticket-571` (cfc0623), `ticket-573` (bfc4256), `ticket-69` (69dbe22). Todas mergeadas → podables. Además `/tmp/cryptobot-main-audit` (detached 50176f5) registrado en `git worktree list`.

Checkout principal `<workspace>/active/cryptobot/cryptobot-service`: rama `ci/mvnw-self-hosted` @ 6719848, **43 archivos dirty** (por eso AUDIT-COMMON prohíbe usarlo). UI `cryptobot/cryptobot-ui`: main @ e03713b (origin/main es b439995 → checkout local atrasado), 2 untracked; worktrees `.worktrees/ticket-576` (4e152f7, 0 dirty) y `.claude/worktrees/glosario` (ce80ce8).

### 1.4 CI (`.github/workflows/ci.yml`, único workflow; la UI NO tiene `.github/workflows` en origin/main)

| Job | runner | Qué hace | Líneas |
|---|---|---|---|
| `build-and-test` | `[self-hosted, le-ci]`, 20 min | `mvnw test-compile`, `mvnw test` (service), `mvnw -f signer/pom.xml test`, `mvnw -f validator/pom.xml test`, `scripts/demo/tests/test-run.sh` (ticket-575, offline), surefire on failure | ci.yml:17-62 |
| `program-test` | le-ci, 40 min, Rust stable + cache | `cargo test --locked` en `programs/intent-authority` (ticket-437) | ci.yml:70-97 |
| `docker-publish` (matrix service/signer/validator) | le-ci, `needs: build-and-test`, sólo push a main | ticket-579: publica `ghcr.io/sebdev89/life-engine-cryptobot-{service,signer,validator}` tag `sha-<7>`, labels OCI + `life-engine.semver/build.source/module`, cache gha por módulo, DOCKER_CONFIG aislado (ticket-488), digest al run summary | ci.yml:126-260 |

Últimos 5 runs de main (`gh run list --branch main`):

| Run | SHA | Conclusión |
|---|---|---|
| 35576296231 | 2b69b37 (#29 ticket-573) | success — 5 jobs verdes: program-test, build+test, publish validator/signer/service (08:08→08:21Z) |
| 35575828637 | d60fc53 (#28 ticket-572) | cancelled (concurrency `cancel-in-progress` por el push de #29 cinco minutos después) |
| 35551401417 | 4cd0104 (#16 ticket-393) | success |
| 35537577429 | 7fa31ed (#25 ticket-575) | success |
| 35537095821 | 9d68b84 (#24 ticket-579) | cancelled (mismo motivo) |

Observación: `concurrency.cancel-in-progress: true` sobre `main` (ci.yml:12-14) cancela el publish del commit anterior cuando dos merges van seguidos; el digest de d60fc53 nunca se publicó. No es un defecto para deploy (se despliega el último), pero deja huecos en GHCR por commit.

---

## 2. DEPLOY (`<workspace>/active/deploy/deploy`, main @ e55f20e; 6 untracked: `.worktrees/`, backups `.env.*.bak-*`, 2 PDFs)

### 2.1 uat-compose (`deploy/uat/docker-compose.uat.yml`)

Redes (líneas 49-68): `le-uat-cryptobot` (`internal: true`, sólo service↔signer↔validator) y `le-uat-egress` (única salida a Internet declarada; sólo cryptobot). Comentario en :60-66 documenta un **drift** vivo: `le-uat-services` está declarada `internal: true` pero la red real se creó sin ese flag (2026-09-20).

| Servicio | Imagen | Puerto | Redes | depends_on | healthcheck | Env (nombres) |
|---|---|---|---|---|---|---|
| `cryptobot` (:889-963) | `${CRYPTOBOT_IMAGE}` (render-compose-env desde manifiesto) | expose 8091 | services, data, cryptobot, egress | postgres, auth, cryptobot-validator, cryptobot-signer (todos `service_healthy`) | `wget /actuator/health` 15s/5 retries/start 90s | APP_ENV=uat, CRYPTOBOT_SERVICE_PORT, JWT_SECRET, AUTH_JWKS_URI, CRYPTOBOT_SECURITY_ENABLED=true, CRYPTOBOT_R2DBC_URL/JDBC_URL (`life_engine_cryptobot`), CRYPTOBOT_DB_USER/PASSWORD, CRYPTOBOT_RUNTIME_BASE_URL, CRYPTOBOT_RUNTIME_AUTH_MODE=service, AUTH_INTERNAL_BASE_URL, CRYPTOBOT_S2S_CLIENT_ID/SECRET, **CRYPTOBOT_ALLOW_MAINNET=false**, CRYPTOBOT_SNAPSHOT_PROVIDER=deterministic-local, CRYPTOBOT_MONITORING_ENABLED=false, CRYPTOBOT_ADVISOR_LOCALE, **CRYPTOBOT_EXECUTION_ENABLED=true**, CRYPTOBOT_SOLANA_DEVNET_RPC (default api.devnet.solana.com), CRYPTOBOT_REBALANCE_VAULT, CRYPTOBOT_SIGNER_ENABLED/BASE_URL/TOKEN, CRYPTOBOT_VALIDATOR_ENABLED/BASE_URL/TOKEN, CRYPTOBOT_TIMELOCK_ESCALATED (30m) |
| `cryptobot-validator` (:979-1023) | `${CRYPTOBOT_VALIDATOR_IMAGE}` | expose 8097 | sólo `le-uat-cryptobot` | — | wget health 15s/start 60s | VALIDATOR_PORT, VALIDATOR_KEYPAIR_JSON (=CRYPTOBOT_VALIDATOR_ATTESTATION_KEY), VALIDATOR_TOKEN, **VALIDATOR_POLICY_HASH=`${CRYPTOBOT_POLICY_HASH:-}`**, VALIDATOR_ENABLED=true |
| `cryptobot-signer` (:1033-1081) | `${CRYPTOBOT_SIGNER_IMAGE}` | expose 8096 | sólo `le-uat-cryptobot` | — | wget health | SIGNER_PORT, SIGNER_KEYPAIR_JSON (=CRYPTOBOT_DEVNET_WALLET_KEY), SIGNER_TOKEN, SIGNER_CLUSTER=devnet, SIGNER_ALLOWED_DESTINATIONS (=vault), SIGNER_MAX_LAMPORTS (2 SOL), SIGNER_VALIDATOR_PUBLIC_KEY, SIGNER_REQUIRE_ATTESTATION=true, **SIGNER_ALLOW_MAINNET=false**, SIGNER_ENABLED=true |

Sin `life-engine.scrape` en signer/validator (:986-988): Prometheus de UAT no los scrapea aunque desde ticket-573 exponen `/actuator/prometheus` (`signer/src/main/resources/application.yml:61`, `validator/…/application.yml:67`). Las métricas propias de ticket-582 no van a tener target hasta cambiar el compose.

**`CRYPTOBOT_POLICY_HASH` no está fijado en UAT**: `grep -cE '^CRYPTOBOT_POLICY_HASH=.+' uat/.env.uat` = 0 (no aparece ni en `.env.uat.example`). El validator arranca con WARN y sin pin de `H_R` (comentario :1004-1007). Mismo estado en k8s (§3). Variables `CRYPTOBOT_*` presentes en `.env.uat` (sólo nombres): DEVNET_WALLET_KEY, REBALANCE_VAULT, SIGNER_TOKEN, VALIDATOR_ATTESTATION_KEY, VALIDATOR_ATTESTATION_PUBKEY, VALIDATOR_TOKEN, S2S_CLIENT_SECRET.

### 2.2 Manifiestos

`deploy/deployments/uat-current.yml:83-118` (los 3, todos `buildSource: github-actions`, `sourceCiRun …/runs/35576296231`, operador sebas):

| Servicio | tag | digest | deployedAt | previousRelease |
|---|---|---|---|---|
| cryptobot | sha-2b69b37 | `sha256:ec067f0d…ad628d` | 2026-09-21T08:25:04Z | uat/cryptobot/20260920T212812Z/7fa31ed |
| cryptobot-validator | sha-2b69b37 | `sha256:de50563a…3383e0` | 08:22:12Z | …/7fa31ed |
| cryptobot-signer | sha-2b69b37 | `sha256:eddc16c5…cec583` | 08:23:06Z | …/7fa31ed |

`platform-catalog.yml:105-143`: **ticket-388 está** (cryptobot port 8091 dependsOn [postgres, auth, cryptobot-validator, cryptobot-signer]; validator 8097 y signer 8096 con `exposesActuatorInfo: false`). Ambientes: `prod` services = [auth, runtime, rag, business-chat, atp] (:160-168, sin cryptobot); `uat-compose` y `uat-k8s` incluyen los 3 (:181, :195); `local` los 3 como procesos del host (:209).

**PROD**: `grep -i cryptobot deployments/prod-current.yml` → NO EXISTE. `prod/docker-compose.prod.yml` sólo lo nombra en `RUNTIME_S2S_ALLOWED_SUBJECTS/CLIENT_IDS` (:400-401, allowlist del Runtime) y en comentarios (:284, :395-399: "ATP/CryptoBot no se declaran en PROD"). Confirmado: cryptobot no corre en prod.

### 2.3 Smoke `deploy/uat/smoke-cryptobot-devnet.sh` (176 líneas; lo invoca `smoke-uat.sh` §F :735-775)

Todo por `docker exec` dentro de `le-uat-cryptobot` (misma red/DNS que el servicio; secretos por `-e`, nunca en argv). Códigos: 0 PASS · 1 FAIL · 2 SKIP.

| Paso | Qué hace | Endpoint | Líneas |
|---|---|---|---|
| 0 | `CRYPTOBOT_EXECUTION_ENABLED=true` en el contenedor; los 3 healthy; AUTH_BOOTSTRAP_* y CRYPTOBOT_SIGNER_TOKEN presentes (si no → SKIP) | — | :51-65 |
| 1 | login usuario bootstrap → JWT | `POST http://auth:8081/api/auth/login` | :91-97 |
| 2 | pubkey de la wallet del signer | `GET http://cryptobot-signer:8096/api/signer/identity` (X-Signer-Token) | :99-103 |
| 3 | registrar wallet (idempotente), leer SOL/USD; < 0.05 SOL → SKIP | `POST /api/cryptobot/wallets {address,cluster:devnet,label}` | :105-115 |
| 4 | propuesta REBALANCE SOL→(100−SELL_PCT)% (default 25 %; 2 % cae en ASSET_CONCENTRATION); COOLDOWN → espera y reintenta 1 vez; exige AWAITING_APPROVAL | `POST /api/cryptobot/wallets/{id}/proposals` | :117-141 |
| 5 | aprobar; timelock > 60 s → FAIL (ESCALATE) | `POST /api/cryptobot/proposals/{id}/approve` | :143-152 |
| 6 | ejecutar con `Idempotency-Key: <uuid>`; poll hasta EXECUTED/FAILED (180 s); imprime firma, confirmationStatus, explorerUrl | `POST …/proposals/{id}/execute`, `GET …/proposals/{id}` | :154-176 |

`smoke-uat.sh` §F además verifica `CRYPTOBOT_ALLOW_MAINNET=false`, `SIGNER_ALLOW_MAINNET=false`, `SIGNER_CLUSTER=devnet` leyendo el env del contenedor (:750-763) y que nginx NO alcance al signer (:393-396). El smoke depende de que los veredictos caigan en ALLOW (≤ autonomous-up-to) — con una wallet > 5000 USD el ESCALATE lo rompe (:27-30).

**Gap de evidencia**: la salida 0/2 del smoke devnet NO queda registrada en `ops.d/evidence/*.md` (sólo "smoke OK"); la firma devnet del smoke no se persiste en el repo deploy. La única traza escrita es el comentario de ticket-577 ("smoke UAT OK 08:26:57Z con 1 tx devnet") y ticket-323 ("3 tx devnet desde el smoke").

### 2.4 `deploy/uat/scripts/cryptobot-devnet-credentials.sh` (187 líneas; test en `test-cryptobot-devnet-credentials.sh`)

Escribe en `uat/.env.uat` (chmod 600) sin imprimir secretos: `CRYPTOBOT_SIGNER_TOKEN`, `CRYPTOBOT_VALIDATOR_TOKEN` (openssl rand), `CRYPTOBOT_DEVNET_WALLET_KEY`, `CRYPTOBOT_VALIDATOR_ATTESTATION_KEY` (Ed25519 formato solana-keygen, generados con `cryptography` de Python), `CRYPTOBOT_VALIDATOR_ATTESTATION_PUBKEY`, `CRYPTOBOT_REBALANCE_VAULT` (pubkeys, no secretas; la privada del vault se descarta). Modos `--generate | --wallet-file/--validator-file/--vault-file | --print-only | --force`. Por stdout sólo las 3 pubkeys. No escribe `CRYPTOBOT_POLICY_HASH`.

### 2.5 Evidencia `deploy/ops.d/evidence/*cryptobot*` (12 archivos; últimos 3)

| Archivo | Servicio | Digest | Resultado |
|---|---|---|---|
| `20260921T082209Z-uat-deploy-cryptobot-validator.md` | validator | `sha256:de50563a…` (sin reconstruir, procedencia github-actions) | exit 0, smoke OK 08:23:01Z, 1 advertencia |
| `20260921T082304Z-uat-deploy-cryptobot-signer.md` | signer | `sha256:eddc16c5…` | exit 0, smoke OK 08:24:59Z, working tree sucio |
| `20260921T082501Z-uat-deploy-cryptobot.md` | service | `sha256:ec067f0d…` | exit 0, smoke OK 08:26:57Z |

Anteriores: 2026-09-16 (×2), 09-18 (×2), 09-19, 09-20 (×4 = primer ciclo con signer/validator 7fa31ed). Ciclos UAT en PRs deploy #221 (2026-09-20, "1 tx devnet en el smoke") y #222 (2026-09-21).

---

## 3. GITOPS (`<workspace>/k8s-lab/plataforma-v1/gitops`, main @ e10ed1d = merge PR #44 "promoción k8s-uat 2026-09-21"; 1 untracked `.worktrees/`)

Archivos: `base/cryptobot/{deployment,service,serviceaccount,kustomization}.yaml`, ídem `base/cryptobot-signer/`, `base/cryptobot-validator/`; `overlays/uat/cryptobot/{kustomization,ingress,cryptobot.env,cryptobot-signer.env,cryptobot-validator.env,secret.enc.yaml,cryptobot-signer-secret.enc.yaml,cryptobot-validator-secret.enc.yaml,secret-generator.yaml}`; `bootstrap/children/app-cryptobot.yaml`; `scripts/cryptobot-devnet-secrets.sh` + `scripts/tests/test-cryptobot-devnet-secrets.sh`. (Los `.worktrees/KAN-*` son copias viejas.)

- **Application** `cryptobot` (app-cryptobot.yaml): project `plataforma-uat`, path `overlays/uat/cryptobot`, `git://<gitops-host>/plataforma.git` main, `automated.prune: true`, selfHeal deliberadamente no escrito (drill 2026-09-14). **Una sola Application para los 3 Deployments** (kustomization.yaml:4-9: la ejecución se enciende en el mismo sync que trae validator+signer).
- **Digests en base** (`base/*/deployment.yaml`): service `@sha256:ec067f0d…` (:40), signer `@sha256:eddc16c5…` (:31), validator `@sha256:de50563a…` (:37) = los de `uat-current.yml`. Los mueve `gitops-bump.sh` ("exactamente una línea image@digest por deployment").
- **Secrets SOPS** (nombres de keys, sin valores): `cryptobot-secret` {CRYPTOBOT_SIGNER_TOKEN, CRYPTOBOT_VALIDATOR_TOKEN} (+ `CRYPTOBOT_S2S_CLIENT_SECRET` según kustomization :16, escrito por `s2s-secret.sh`; la key no aparece en el grep de `secret.enc.yaml` — verificar con `sops -d` fuera de esta auditoría); `cryptobot-validator-secret` {VALIDATOR_KEYPAIR_JSON, VALIDATOR_TOKEN}; `cryptobot-signer-secret` {SIGNER_KEYPAIR_JSON, SIGNER_TOKEN}. Inyectados por `secretKeyRef` sin `optional` (kustomization :46-92). Contraseña de Postgres desde `postgres-secret`.
- **ConfigMaps con hash** (`cryptobot.env`, `cryptobot-signer.env`, `cryptobot-validator.env`): traducción 1:1 del compose. Diferencias: `APP_ENV=k8s-uat`, Runtime en `:8080`, JWT_SECRET ausente (sólo JWKS RS256), `CRYPTOBOT_REBALANCE_VAULT=FrBNyfCU…6BmX` (pubkey), `SIGNER_VALIDATOR_PUBLIC_KEY=DQ1R2tc1…N2xp` (misma pubkey de atestación que la evidencia local del 2026-09-20 → **la wallet/clave del hackathon se reutiliza en k8s-uat**), **`VALIDATOR_POLICY_HASH=` vacío** (validator.env:19).
- **Ingress**: `<uat-host>`, sólo `/api/cryptobot`.
- **NetworkPolicies** (`base/plataforma/networkpolicies.yaml`): `allow-cryptobot` (:210, ingress Traefik + collector), `allow-cryptobot-validator` (:233, sólo `app: cryptobot` → 8097), `allow-cryptobot-signer` (:249, sólo `app: cryptobot` → 8096), `egress-cryptobot` (:397, DNS, auth:8081, runtime:8080, postgres:5432, validator, signer, Internet TCP/443), `egress-cryptobot-validator`/`-signer` (:434+, egress nulo).
- **Scripts**: `cryptobot-devnet-secrets.sh` (`--generate | --from-env <.env.uat> | --check`; escribe los 3 Secrets con `sops set --value-stdin` y las 2 pubkeys en los `.env`; no imprime secretos); `esperar-digest.sh <svc> <digest> [--rev]` (ticket-473, sólo get/port-forward: 6 hitos hasta readiness UP).

**Estado vivo** (`KUBECONFIG=…/vm/kubeconfig`, contexto `k8s-uat`, sólo get):

```
deploy cryptobot            READY 1  image …cryptobot-service@sha256:ec067f0d…   (= manifiesto)
deploy cryptobot-signer     READY 1  image …cryptobot-signer@sha256:eddc16c5…
deploy cryptobot-validator  READY 1  image …cryptobot-validator@sha256:de50563a…
pods: cryptobot-647666f59d-j8k77, cryptobot-signer-fc76bffd9-lfwdm, cryptobot-validator-6b5657684b-dzbt7 — Running 1/1, 0 restarts, 26 min
svc ClusterIP: cryptobot 8091, cryptobot-signer 8096, cryptobot-validator 8097
netpol: allow-cryptobot, allow-cryptobot-signer, allow-cryptobot-validator, egress-cryptobot, egress-cryptobot-signer, egress-cryptobot-validator
application/cryptobot: Synced Healthy rev=e10ed1d (11 Applications, todas Synced/Healthy)
```

**Gap**: `deploy/uat/smoke-uat-k8s.sh` no menciona cryptobot (`grep -c cryptobot` = 0); `ops.d/lib/services.sh:216` `OPS_ENV_K8S_SERVICES=( [uat]="runtime" )` → en k8s-uat la ejecución devnet de CryptoBot **no se smokea** (smoke-uat.sh:745-746 la delega al k8s smoke, que no la tiene). Los 3 pods están Healthy, pero "healthy" no prueba que firmen (smoke-cryptobot-devnet.sh:4-7).

---

## 4. Observabilidad — inventario de compatibilidad (§34: qué NO se puede renombrar)

### 4.1 Dashboards (`deploy/observability/grafana/dashboards/`)

- `life-engine-cryptobot-demo.json` — "Life Engine — CryptoBot · Demo path", **51 paneles** (ticket-573): funnel 1·Intents → 8·CONFIRMED, embudo, conversión, pendientes, duplicados, retries, fallidos por etapa; controles (mainnet 409, política, DENY/ESCALATE, validador rechazó, mismatches, predicados fallidos, `trade_failed_total` por etapa, Loki); reconciliación/retries/DLQ (ticket-571); latencias (timers summary, sin histogramas); recibos/anclas (ticket-391/392/394); texto "cómo leer".
- `life-engine-cryptobot-negocio.json` — "Life Engine — CryptoBot (negocio y trade funnel)", **31 paneles** (ticket-425): embudo requested→approved→submitted→confirmed, duplicadas (SLO=0), antes del trade (market/risk/strategies), release, Solana RPC, outbox/DLQ/reconciliación/recibos.

Labels usados en queries: `environment` (120 usos, `$environment`), `service_name` (Loki), `alertstate` (`ALERTS`), `proposalId`/`id`/`hash` (literales de `uri`). Agrupaciones: `by (result)` ×11, `by (uri)`, `by (stage)`, `by (reason)`, `by (result, asset)`, `by (predicate)`, `by (outcome)`, `by (method, kind, cluster)`, `by (decision, escalation)`.

Valores de label que las queries filtran literalmente (romper uno rompe un panel): `result` ∈ {approved, blocked_by_policy, expired, failed, cancelled, start_failed, high, invalid, issued, pending, proposed, refused, rejected, verified}; `stage` ∈ {preflight, sign, broadcast, onchain, rpc, other}; `decision` ∈ {allow, deny, escalate}; `outcome` ∈ {dead_lettered, retried}; `reason` =~ `ambiguous|retries_exhausted|inconsistent|outbox` y `resolved|requeued`; `status` = "409" y `2..`; `method` = POST; `uri` ∈ {`/api/cryptobot/proposals/{proposalId}/execute`, `/api/cryptobot/wallets/{walletId}/proposals`, `/api/cryptobot/proposals/{proposalId}/(approve|execute)`, `/api/cryptobot/dead-letters/{id}/(resolve|requeue)`, `/api/cryptobot/receipts/{hash}/verify`, `/api/cryptobot/anchors`} → **las rutas HTTP son parte del contrato de observabilidad** (`http_server_requests_seconds_count{uri=…}`).

### 4.2 Reglas Prometheus (`deploy/observability/prometheus/rules/cryptobot.yml`, grupo `cryptobot-demo-path`; tests en `rules-tests/cryptobot_test.yml`, 26 `alertname`/`eval_time`)

| Alerta | expr (resumida) | for | severity |
|---|---|---|---|
| `CryptoBotDlqNotEmpty` | `max by (environment,service,instance)(cryptobot_dead_letter_open{service=~"cryptobot\|cryptobot-service", environment!="local"}) > 0` | 5m | warning |
| `CryptoBotReconciliationFailing` | `sum … increase(cryptobot_reconciliation_total{…, outcome="dead_lettered"}[15m]) >= 2` | 5m | warning |
| `CryptoBotMainnetAttempt` | `sum … increase(http_server_requests_seconds_count{…, uri="/api/cryptobot/proposals/{proposalId}/execute", status="409"}[5m]) > 0` | — | info |

Scrape en UAT: `docker_sd` con `life-engine.service` → label `service` (prometheus.yml:75-76). En local: target estático `host.docker.internal:8091` (:138). Nota: `CryptoBotMainnetAttempt` cuenta **cualquier** 409 en execute (timelock, estado, cooldown) — es el gap que ticket-582 quiere cerrar con `execution_refused_total{reason}`.

### 4.3 Cruce métrica ↔ código (`src/main/java/io/lifeengine/cryptobot/observability/CryptobotMetrics.java:82-113` nombres; javadoc :29-68 mapeo; `registerPlaceholders()` :344-400; `PrometheusMeterNamesTest.java` fija 13 nombres)

| Métrica Prometheus | Micrometer (código) | Labels | Dashboard/alerta | Estado |
|---|---|---|---|---|
| `market_analysis_total` | `market.analysis` | result, asset | negocio | ambas |
| `risk_analysis_total` | `risk.analysis` | result | negocio | ambas |
| `strategies_total` | `strategies` | result, asset | negocio | ambas |
| `approvals_total` | `approvals` | result | demo, negocio | ambas (test) |
| `trade_requested_total` | `trade.requested` | result, asset | demo, negocio | ambas (test) |
| `trade_submitted_total` | `trade.submitted` | asset | demo, negocio | ambas (test) |
| `trade_confirmed_total` | `trade.confirmed` | result, asset | demo, negocio | ambas (test) |
| `trade_failed_total` | `trade.failed` | stage, asset | demo (×12), negocio | ambas (test) |
| `trade_reconciled_total` | `trade.reconciled` | result | negocio | ambas |
| `reconciliation_mismatch_total` | `reconciliation.mismatch` | — | demo, negocio | ambas (test) |
| `duplicate_trade_suppressed_total` | `duplicate.trade.suppressed` | — | demo, negocio | ambas (test); emisor `ExecutionService.java:222` |
| `outbox_pending`, `outbox_failed`, `dlq_size` (gauges) | `outbox.pending/failed`, `dlq.size` | — | demo, negocio | ambas (test dlq_size) |
| `cryptobot_reconciliation_total` | `cryptobot.reconciliation` | outcome (matched/corrected/retried/dead_lettered/skipped) | demo + alerta | ambas (test) |
| `cryptobot_dead_letter_total` | `cryptobot.dead.letter` | reason (ambiguous/retries_exhausted/inconsistent/outbox/resolved/requeued) | demo | ambas (test) |
| `cryptobot_dead_letter_open` (gauge) | `cryptobot.dead.letter.open` | — | demo + alerta | ambas (test) |
| `policy_verdicts_total` | `policy.verdicts` | decision, escalation | demo | ambas (test) |
| `policy_predicate_failed_total` | `policy.predicate.failed` | predicate | demo | ambas |
| `solana_rpc_errors_total` | `solana.rpc.errors` | method, cluster, kind | negocio | ambas |
| `solana_confirmation_latency_seconds{_sum,_count,_max}` | `solana.confirmation.latency` (Timer) | result, cluster | demo, negocio | ambas |
| `intelligence_receipts_total` | `intelligence.receipts` | result (issued/verified/invalid/failed) | demo ×6, negocio | ambas |
| `deterministic_inference_total`, `deterministic_mismatch_total` | `deterministic.inference/mismatch` | — | demo, negocio | ambas |
| `validator_attestations_total` | `validator.attestations` | result (issued/refused) | demo ×6 | ambas (test) |
| `receipt_anchors_total` | `receipt.anchors` | result (submitted/finalized/failed/abandoned) | demo | ambas |
| `anchored_receipts_total`, `anchor_pending`, `anchor_finality_latency_seconds*` | `anchored.receipts`, `anchor.pending`, `anchor.finality.latency` | — | demo | ambas |
| `http_server_requests_seconds_{count,sum}` | Spring Boot auto | uri, status, method | demo ×5 + alerta | ambas |
| `oracle_execution_refused_total` | `oracle.execution.refused` (:214-216, pre-registrada :399) | — | — | **huérfana** (código sí, dashboard no) |
| `artifact_reuse_total{external}`, `provenance_depth` (summary) | `artifact.reuse`, `provenance.depth` | — | — | **huérfanas** |
| `cryptobot_quotes_fetch*`, `cryptobot_quotes_fetch_latency_seconds{exchange}` | `CachedArsQuotesService.java:38-39` | exchange | — | **huérfanas** (ticket-355) |
| `cryptobot_oracle_source_fetch*`, `cryptobot_oracle_source_latency_seconds{source}`, `cryptobot_oracle_consensus*` | `PriceOracleService.java:61-63` | source | — | **huérfanas** (ticket-439) |
| Métricas del signer / validator | NO EXISTEN en main (`grep -rl "Counter.builder\|MeterRegistry" signer/src/main validator/src/main` → sólo `BuildIdentityCommonTags`) | — | panel "6 · Firmados*" es derivado | **en curso ticket-582** (worktree dirty: `SignerMetrics.java`, `ValidatorMetrics.java`) |

Ninguna métrica del dashboard falta en el código (0 huérfanas del lado dashboard). Común tags: `environment`, `service`, `version`, `commit` (`BuildIdentityConfig`). `asset` acotado a allow-list + `other`/`none` (:74-77).

**Lista "NO renombrar"** (consumidores: 2 dashboards, 3 alertas, 26 tests de reglas, `PrometheusMeterNamesTest`, Control Tower ticket-423): las 27 series de la tabla marcadas "ambas" + sus valores de label + las 6 `uri` literales + los common tags.

---

## 5. Consumidores de API — qué contratos son breaking

### 5.1 Endpoints expuestos hoy (`grep @…Mapping`, 3 módulos)

Service `/api/cryptobot/**` (todos Bearer JWT, scoped por `sub`): wallets (`POST /wallets`, `GET /wallets`, `GET /{id}/portfolio`, `POST /{id}/refresh`, `GET /{id}/activity`, `POST /{id}/ask`, `GET /{id}/messages`, `POST /{id}/proposals`, `GET /{id}/proposals`) · proposals (`GET /proposals`, `GET /{id}`, `GET /{id}/audit`, `GET /{id}/events`, `POST /{id}/approve|reject|cancel|execute`) · receipts (`GET /receipts/signing-key`, `GET /receipts/{hash}`, `POST /receipts/{hash}/verify`, `GET /proposals/{id}/receipts`, `GET /wallets/{id}/receipts`) · lineage (`GET /receipts/{hash}/lineage|ancestors|descendants|parents|children|reused-by`, `GET /proposals/{id}/lineage`) · anchors (`POST /anchors`, `GET /anchors`, `GET /anchors/{root}`, `POST /anchors/{root}/verify`) · dead-letters (`GET`, `GET /{id}`, `POST /{id}/resolve|requeue`) · demo (`GET|PUT|DELETE /demo/chaos`, `GET|PUT|DELETE /demo/price`, sólo `cryptobot.chaos.enabled`) · legacy (`/market-review`, `/market-reviews[/latest]`, `/monitoring/run-once`, `/indicators`, `/observations`, `/zones`, `/journal`, `/watchlist`, `/snapshots/{symbol}`, `/quotes/{asset}`) · `GET /health`. Signer `/api/signer/{identity, sign, sign-anchor}` (X-Signer-Token). Validator `/api/validator/{identity, validate}` (X-Validator-Token).

### 5.2 Tabla endpoint → consumidores

| Endpoint | cryptobot-ui (`ui/src/app/*.ts`) | smoke UAT (`deploy/uat/smoke-cryptobot-devnet.sh`) | `scripts/smoke-cryptobot.sh` (workspace) | `scripts/demo/run.sh` / `e2e-devnet.sh` | Runtime | Dashboards/alertas |
|---|---|---|---|---|---|---|
| `POST /wallets` | control-plane-api.ts:345 | :106 | — | e2e:479 | — | — |
| `GET /wallets`, `/{id}/portfolio`, `/{id}/refresh`, `/{id}/activity`, `/{id}/messages` | :341,353,357,361,373 | — | — | — | — | — |
| `POST /wallets/{id}/ask` (+ SSE del Runtime vía `runtimeSseUrl`, cryptobot-api.ts:230) | :365; dashboard.ts:481 EventSource | — | — | — | requiere `crypto.portfolio-advisor.v1` (**no existe en runtime main**) | — |
| `POST /wallets/{id}/proposals` | :382 | :120 | — | run.sh:292,376; e2e:482 | — | uri en demo dashboard |
| `GET /proposals`, `GET /wallets/{id}/proposals` | :391,395 | — | — | — | — | — |
| `GET /proposals/{id}` | :399 | :163,170 | — | e2e:459,465 | — | — |
| `GET /proposals/{id}/events` | :433 | — | — | e2e (outbox) | — | — |
| `GET /proposals/{id}/audit` | — (usa `/{id}` que trae `audit[]`) | — | — | — | — | — |
| `POST /proposals/{id}/approve|reject` | :403 (`decideProposal`) | :144 | — | run.sh:302,427; e2e | — | uri approve |
| `POST /proposals/{id}/execute` (`Idempotency-Key` header **o** body `{operationId}`) | :420 — **usa el body** porque el header no está en el CORS allow-list (:411-414) | :156 header | — | run.sh:306 header; e2e:452,488 header | — | uri execute + alerta MainnetAttempt (409) |
| `POST /proposals/{id}/cancel` | :425 | — | — | — | — | — |
| `GET /proposals/{id}/receipts`, `GET /wallets/{id}/receipts` | receipts-api.ts:88,92 | — | — | run.sh:310,398; e2e:465 | — | — |
| `POST /receipts/{hash}/verify` | receipts-api.ts:96; lineage-api.ts:145 | — | — | run.sh:315,408,461; e2e:469 | — | uri verify |
| `GET /receipts/{hash}` | lineage-api.ts:141 | — | — | run.sh:436,441 | — | — |
| `GET /receipts/{hash}/lineage|parents|children|reused-by`, `GET /proposals/{id}/lineage` | lineage-api.ts:119-137 | — | — | run.sh:417 | — | — |
| `GET /anchors`, `POST /anchors`, `POST /anchors/{root}/verify` | receipts-api.ts:100 (GET) | — | — | run.sh:450,466,477 | — | uri anchors |
| `GET /dead-letters`, `POST /dead-letters/{id}/resolve|requeue` | reliability-api.ts:58,62,68 | — | — | e2e:313 (requeue) | — | uri resolve/requeue |
| `GET|PUT|DELETE /demo/chaos` | reliability-api.ts:80,92,96 (sólo si GET → 200) | — | — | run.sh:529; e2e:256 | — | — |
| `GET|PUT|DELETE /demo/price` | — (NO EXISTE en la UI: `grep demo/price ui/src` = 0) | — | — | run.sh:343-369 | — | — |
| `GET /market-reviews[/latest]`, `POST /monitoring/run-once` | cryptobot-api.ts:183,195,206 (legacy dashboard) | — | — | — | — | — |
| `GET /health` | cryptobot-api.ts:213 | — | :52 | run.sh:278; e2e:117 | — | — |
| `GET /snapshots/{symbol}`, `/watchlist`, `/indicators`, `/zones`, `/observations`, `/journal` | — | — | :63,104-108 | — | **Runtime → cryptobot** (`ext/cryptomarketreview/tools/GetCrypto*Tool.java`: `/api/cryptobot/{snapshots/,watchlist,zones,observations,journal,indicators}` — workflow `crypto.market-review.v1`) | — |
| `GET /api/signer/identity` | — | :100 | — | wallet-devnet.sh / run.sh setup | — | — |
| `POST /api/signer/sign`, `/sign-anchor`; `POST /api/validator/validate`, `GET /api/validator/identity` | — | — | — | — | sólo `integration/signer/SignerClient.java:68,102,128` y `integration/validator/ValidatorClient.java:95,144` | — |
| `POST /api/auth/login` (Auth) | auth-api.ts:36 | :94 | vía smoke-auth.sh | run.sh:270 (uat) | — | — |

Otros consumidores del nombre "cryptobot" (no de la API): auth-ui `launcher-apps.catalog.ts:27-31` (launcher a `environment.cryptobotAppUrl`), `life-engine-api.paths.ts:364` (`/api/agents/crypto-bot-agent/exchange-status`: agente legacy de Auth, no este service), dev-agent test (`serviceToken("service:cryptobot")`), Runtime allowlist S2S `service:cryptobot` (compose uat :298-299, prod :400-401), business-chat docs/scripts (menciones).

Dirección Runtime: **CryptoBot → Runtime** (`integration/lifeengine/AdvisorRuntimeClient.java:48` start run `crypto.portfolio-advisor.v1`, `/runs/{id}`; `RuntimeClient` para `crypto.market-review.v1`) y **Runtime → CryptoBot** (tools del market-review). `grep -rl portfolio-advisor runtime/life-engine-runtime/src` → **NO EXISTE** en main (sólo PR #33 abierta desde 2026-09-14). En UAT `POST /wallets/{id}/ask` falla por workflow desconocido; la UI y el pitch lo tratan como opcional (`vault/Products/CryptoBot-Hackathon-Pitch-2026-09-20.md:431`).

### 5.3 Contratos breaking si cambian (resumen)

1. Rutas y verbos de `/api/cryptobot/wallets/**`, `/proposals/**`, `/receipts/**`, `/anchors/**`, `/dead-letters/**`, `/demo/chaos` — UI + scripts + dashboards (`uri` literal) + alerta.
2. Forma de respuesta consumida por scripts: `wallet.id`, `snapshot.positions[].symbol/amount`, `snapshot.totalUsd`, `snapshot.priceSource`, `proposal.id/status/policy.violations[].rule|message/policy.authorization.decision|policyHash|tier/plan.summary/execution.signature|explorerUrl|confirmationStatus|error`, `approval.executableAt`, `audit[].eventType`, `receiptHash`, `body.kind`, `valid`, `signatureValid`, `reproduced`, dead letter `outcome/resolvedBy/resolvedAt` (smoke :108-174, e2e, run.sh, UI `control-plane-api.ts` records).
3. Idempotencia: `Idempotency-Key` header **y** body `{operationId}` (la UI sólo puede el body).
4. Códigos: 409 en execute antes de APPROVED / timelock / mainnet / blocked; 409 en requeue repetido; 401 sin token (smoke-uat :335); 403 para operador en `/dead-letters`.
5. `ProposalStatus` persistidos (`domain/transactions/ProposalStatus.java:15-25`): PROPOSED, SIMULATED, BLOCKED_BY_POLICY, AWAITING_APPROVAL, APPROVED, REJECTED, EXECUTING, SUBMITTED, EXECUTED, FAILED, EXPIRED — la UI (`live-model.ts`) deriva la timeline de 12 pasos de los `audit.eventType`: PROPOSAL_CREATED, SIMULATED, POLICY_EVALUATED, AWAITING_APPROVAL, BLOCKED_BY_POLICY, APPROVED, REJECTED, CANCELLED, EXPIRED, EXECUTION_STARTED, EXECUTION_VALIDATED, EXECUTION_SIGNED, EXECUTION_SUBMITTED, EXECUTION_BROADCAST_UNCERTAIN, EXECUTION_CONFIRMATION_PENDING, EXECUTION_DUPLICATE_SUPPRESSED, EXECUTION_RETRIED, EXECUTION_FAILED, EXECUTED, RECONCILED, RECONCILIATION_AMBIGUOUS, RECONCILIATION_RETRIES_EXHAUSTED, DEAD_LETTER_RESOLVED, DEAD_LETTER_REQUEUED (24 `EV_*` en `src/main`).
6. Eventos outbox: `trade.requested|approved|rejected|cancelled|expired|submitted|confirmed|failed|reconciled`, `receipt.created`, `receipt.anchors` (11; sólo `LoggingOutboxSink` los consume hoy).
7. Receipt kinds (`domain/receipt/ReceiptKind.java`): WALLET_SNAPSHOT, HUMAN_IDEA, MARKET_ANALYSIS, RISK_DECISION, STRATEGY, SIMULATION, EXECUTION; dominio del hash `"life-engine.cryptobot.receipt"` (README:390) — cambiarlo invalida todo recibo emitido y anclado.
8. Wire signer/validator: `X-Signer-Token`/`X-Validator-Token`, `cluster` obligatorio en el payload (ticket-493), `SIGNER_VALIDATOR_PUBLIC_KEY` pineado.
9. Migraciones Flyway V1..V10 (`src/main/resources/db/migration/`), base `life_engine_cryptobot` en `le-uat-postgres` y en `postgres.uat.svc`.
10. Nombres de paquete GHCR `life-engine-cryptobot-{service,signer,validator}` y los ids `cryptobot`, `cryptobot-signer`, `cryptobot-validator` (ci.yml:104-112: "cambiarlos acá obliga a cambiarlos en ops.d/lib/services.sh, resolve-artifact.sh y platform-catalog.yml"; gitops-bump.sh espera una línea image@digest por `base/<svc>/deployment.yaml`).

---

## 6. Demo path vs. mandato §42 y §30

### 6.1 Qué hay (`scripts/demo/`, 1386 líneas: `run.sh` 552, `e2e-devnet.sh` 523, `wallet-devnet.sh` 198, `lib.sh` 113; `tests/test-run.sh` en CI)

`run.sh [--target local|uat] [--rpc auto|devnet|local] [--sell-sol N] [--no-recovery] [--no-anchor] [--keep] [--dry-run]` (:1-38):

| Acto | Qué hace | Endpoints | Evidencia |
|---|---|---|---|
| 0 setup | tools, claves + `.env.demo` (una vez, gitignored), elige RPC (devnet si la wallet tiene ≥ 0.6 SOL, si no `solana-test-validator` del compose), `docker-compose.demo.yml` up (service+validator+signer+Postgres propio, **sin Auth/Runtime**; JWT minteado desde JWT_SECRET), health, SOL | `/health`, `/api/signer/identity` | `out/run-<ts>/` |
| 1 execute (= `e2e-devnet.sh`) | registrar wallet → intent SOL→N % → plan → simulación → 13 reglas + R_v + validador → execute antes de aprobar 409 → approve → timelock → execute (`Idempotency-Key`) → validador atesta → signer firma → sendTransaction → SUBMITTED → confirm → EXECUTED → replay misma key = misma firma → outbox → receipt EXECUTION verify → intent mainnet en el mismo run → 409 | wallets, proposals, approve, execute, receipts, verify | `out/evidence-<ts>.md`: proposal id, operationId, signature, slot, explorer, receipt hash, `getSignatureStatuses` directo al RPC |
| 2 risk (ticket-572) | intent adversario SOL→5 % → BLOCKED_BY_POLICY (reglas nombradas) → approve 409 → execute 409 → RISK_DECISION verify; cooldown 60 s visible; `PUT /demo/price` −90 % en una fuente → PRICE_DEVIATION; todas a 900 s → PRICE_STALE; cada una con recibo verificado (L1 `reproduced=true`) y en el linaje | proposals, approve, execute, receipts, verify, demo/price | EV2 en el informe |
| 3 recovery (`e2e-devnet.sh --chaos rpc-down`) | RPC caído en broadcast → EXECUTING+SIGNED, firma persistida antes → reconciler sin veredicto ×3 → dead letter `ambiguous` → RPC vuelve → `POST /dead-letters/{id}/requeue` → RETRIED (mismo operationId, firma nueva) → EXECUTED; requeue repetido 409; cadena consultada directo: 1 transferencia (+1 en el vault) | dead-letters, demo/chaos | evidencia con ambas firmas y conteo de transfers del vault |
| 4 evidence | DAG del EXECUTION (parents), `POST /anchors?wait=true` → memo Merkle finalized → `anchors/{root}/verify` → inclusion proof → métricas | receipts, lineage, anchors | EV4 |
| 5 report | `out/demo-report-<ts>.md` + log; grep de cada secreto del env → 0 hits o exit 3 | — | informe |

Modos de chaos disponibles (`e2e-devnet.sh:20-28`): `uncertain`, `confirm-timeout`, `rpc-down`. `ChaosController` sólo con `CRYPTOBOT_CHAOS_ENABLED=true` (`docker-compose.demo.yml:130`; **no** en UAT ni k8s → acto 3 y precios SKIPPED con `--target uat`, run.sh:31-35).

Composes: `docker-compose.demo.yml` (demo-postgres, validator/signer/service por `build:`, `solana-local` agave v2.2.14 en profile `local-validator`, `SIGNER_REQUIRE_ATTESTATION=true`, `*_ALLOW_MAINNET=false`) y `docker-compose.hackathon.yml` (validator+signer+service+**cryptobot-ui**, con Auth/Runtime/Postgres del host — anterior a ticket-570; sigue en el repo; **dos composes para el mismo stack**).

Evidencias en el vault (`Products/evidencia/`): `cryptobot-e2e-devnet-20260920-happy.md` — wallet `G4bCRqj3…4exS`, proposal `4c422b00…`, operationId `ef13435b…`, **signature `4qeaKhgU4Yob4biUsRBW61wJ7ar2HKDkHD5vQUsMiPgzvukSKC8d8vPLkgH3hiWHLuvy7uAYcnPHDRsCo4s12sbV`, slot 501489009**, validator `DQ1R2tc1…`, verdict ALLOW bajo `sha256:1882cd0a…`, receipt EXECUTION `sha256:a4d257a2…` valid=True, mainnet 409 (EXECUTION_CLUSTER + ONCHAIN_SIMULATION_PASSED + SIGNER_CONTROLS_WALLET); `…-chaos-rpc-down.md` — firma #1 `5tvrRJUA…` never-seen, dead letter `3a88532c…` ambiguous, requeue → **firma #2 `2mU1LTY3…`, slot 501489762**, vault transfers 1→2, DLQ open=0. Ambas sobre commit `0dba856` (anterior a ticket-572/573). ticket-577 registra: 12 transfers wallet→vault confirmados en devnet, 1 ancla Memo n=25.

### 6.2 Golden path §42 ("Buy 50 USDC of SOL" → … → Grafana)

| Paso del mandato | Hoy | Evidencia |
|---|---|---|
| Intent "Buy 50 USDC of SOL" | **Parcial**: el intent de la API es `{kind: REBALANCE, targetWeights:{SOL: N}, counterAsset}` (`ControlPlaneDtos.java:48`); no existe BUY/SELL por monto ni swap USDC→SOL. La única operación ejecutable es `SystemProgram.transfer` SOL → vault (compose uat :937-940; README "leg ejecutable"). `TradingIntent` (ticket-435) tiene vocabulario BUY/SELL/SWAP/… pero no es la entrada HTTP. | §5 tabla |
| policy → simulation → validator → signer → devnet → confirmed | **Existe** (acto 1) | evidencia happy |
| reconciled | **Existe** (reconciler cada 30 s / demo chaos) — pero el happy path termina en EXECUTED por `confirm` inline; la reconciliación sólo actúa si queda SUBMITTED/EXECUTING | e2e:365-380 |
| receipt | **Existe** (EXECUTION receipt, verify, anchor) | e2e:464-472 |
| Grafana | **Existe** (dashboard demo 51 paneles) — pero sólo se llena en UAT (docker_sd) o local con Prometheus del host; el compose demo NO trae Prometheus/Grafana (run.sh acto 4 lee `/actuator/prometheus` a mano) | §4 |

### 6.3 Los 10 failure demos §30

| # | Demo | Estado | Cómo hoy / evidencia |
|---|---|---|---|
| 1 | valid → CONFIRMED | **existe** | `run.sh` acto 1; `smoke-cryptobot-devnet.sh`; evidencia happy |
| 2 | mainnet → DENIED | **existe** | `e2e-devnet.sh:477-491` (wallet mainnet read-only → paper trade → execute 409); smoke-uat §F flags; tests `ExecutionServiceMainnetGateTest`; 3 capas ticket-493 |
| 3 | excessive amount → DENIED | **existe (por %)** | acto 2: SOL→5 % ⇒ BLOCKED_BY_POLICY (MAX_TRADE_PCT_OF_PORTFOLIO / ASSET_CONCENTRATION); MAX_TRADE_USD y TRADE_WITHIN_MAX existen (`PolicyEngine.java:68`, `PolicyPredicate.java:17`) pero no se disparan en el script (wallet chica) |
| 4 | excessive slippage → DENIED | **sólo en tests** | `SLIPPAGE_WITHIN_MAX` (`DeterministicPolicyEngine.java:115`) pero `maxSlippageBps` sale de `AuthorizationProperties.java:35` (config, default 100), no del caller: **no demoable por API**. Tests: `DeterministicPolicyEngineTest`, `PolicyEngineTest`, `vectors-v1.json`, `ValidatorControllerTest` |
| 5 | unauthorized mint → DENIED | **parcial** | `ASSET_ALLOWLIST`/`ASSET_ALLOWED` (allowed-assets SOL,USDC,USDT `application.yml:206`) — demoable con `targetWeights:{BONK:…}` pero no está en ningún script; tests `PolicyEngineTest`, `AdversarialBenchmarkTest` |
| 6 | duplicate request → one execution | **existe** | replay de `Idempotency-Key` (e2e:451-455) + `duplicate_trade_suppressed_total`; índice único `operation_id` (V5) |
| 7 | signer down → retry/safe | **parcial** | Safe sí: stage SIGN → `fail()` → FAILED, nada firmado/broadcast (`ExecutionService.java:277-283,454; errorCodeOf → SIGNER_UNAVAILABLE :482`). **Retry no**: la propuesta queda FAILED terminal; sin chaos mode para signer (`ChaosController` sólo `rpc-down|uncertain|confirm-timeout`). Sólo en tests: `ChaosTest.signerUnavailable_pauses` (benchmark in-memory :183-191) |
| 8 | RPC lost after submit → reconciliation finds tx | **existe** | `--chaos uncertain` / `confirm-timeout` (e2e:365-387); reconciler `EXECUTED by reconciliation` |
| 9 | permanent failure → DLQ | **existe** | `--chaos rpc-down` → `ambiguous` (evidencia); `retries_exhausted` tras max-retries=2 (ticket-571) |
| 10 | tampered receipt → verify fails | **sólo en tests** | `ReceiptServiceTest.java:111-132` (body/hash/firma alterados fallan en el check exacto); el demo sólo verifica recibos válidos; no hay endpoint que reciba un recibo externo (verify toma el `hash` de la DB) → "verify(receipt) sin confiar en la UI" (§15) requiere `POST /receipts/verify` con body |

### 6.4 Docs

`docs/` del service contiene sólo `runbooks/dead-letter.md`. `docs/architecture/` **NO EXISTE** (`ls docs`) — los entregables §36-37 (`TRUSTED-AGENT-EXECUTION-AUDIT.md`, `-ADR.md`) se crean de cero. README.md (544 líneas): "What it does today" (:14-41), mainnet fail-closed (:43), run local 4 procesos (:71), demo (:114), API (:136-153), security model (:192-448), observability (:450), tests (:492-502: service 467 / signer 33 / validator 28 / program 29 / E2EDevnetIT 2 / UI 24), pre-existente vs hackathon (:504-517).

---

## 7. JIRA (leído por REST vía `jira.py._call`, con description y comentarios; dump en `scratchpad/jira-dump-C.txt`)

### 7.1 Épicas

| KAN | Estado | Qué es | Relación con el mandato |
|---|---|---|---|
| **ticket-323** | En curso | CryptoBot — AI control plane para Solana (Colosseum MVP), own-verticals. Hijos HK-1..9 (ticket-569..577), ticket-582. Último comentario lead 2026-09-21 02:29: HK-1/2/3/6/7/8/9/5 ✓, HK-4 PR #28, HK-5 PR #29 → ambas mergeadas después (2b69b37). Pendiente: videos, submission ≤ 10/10. | Fase 8 del mandato (hackathon demo). Sigue viva hasta el 12/10; **freeze**: nada fuera del demo path. |
| **ticket-390** | Backlog | CryptoBot Endgame — Decision Receipts (recibos firmados, DAG, risk L1, ancla devnet). Hijos P0-0..P0-6 + niveles 4/5 + oráculo + benchmark: 403, 435, 436, 437, 438, 439, 440, 391, 392, 393, 394, 395, 402, 493, 500, 501. | Es la base ya construida del "execution core". Fases 2 (intent), 4 (policy/validator/signer), 5 (receipt) del mandato **ya tienen entregas acá**. |
| **ticket-523** | Backlog | Engine v1 (own-engine): E1 Jobs/Runs/Events (ticket-524, Revisar), E2 Agent contracts (ticket-525, Revisar), E5 Governance/Policy Engine (ticket-528), E7 Event Backbone (ticket-530), E8 ATP eval (ticket-531). DoD: 3 agentes (DevAgent, CryptoAgent, MarketingAgent) sobre la misma infra. | Fase 9 (integración reutilizable): el core del mandato es lo que E5/E7 esperan extraer. **Riesgo de duplicación**: ticket-528 pide un Policy Engine central de Platform con `policy.evaluate.v1`; el mandato §6 pide un policy engine en el core. |
| **ticket-583** | Backlog (creada 2026-09-21 05:58) | **Trusted Agent Execution** — épica del mandato, label `trusted-execution`. Describe módulos, fases 0..9, entregables de fase 0, relación con ticket-390/323/523/530/450/464. | **YA EXISTE la épica del mandato §38.** No crear otra. |
| **ticket-584** | En curso (claimed lead 05:59, rama `ticket-584-tae-audit`) | TAE · Fase 0 — auditoría: AUDIT.md (30 secciones), ADR, `trusted-agent-execution-trust-boundaries.md`, stories 1..9 en ticket-583 "buscando duplicados en ticket-390/323/523 antes". | Este informe es la evidencia C de esa story. |

### 7.2 Hijos y relacionados — KAN → estado → entregó → abierto → fase §33 → ¿story reutilizable? ¿duplicaría?

| KAN | Estado | Entregó (PR / evidencia) | Qué sigue abierto | Fase §33 | Reutilizar / duplicar |
|---|---|---|---|---|---|
| ticket-403 | Finalizada | PR #8 (squash 0eb05c7): `operationId` idempotente, máquina de estados durable con versión, `ReconciliationJob`, outbox `SKIP LOCKED`, `dead_letter` (V5). Evidencia UAT deploy#188, métricas dlq_size/outbox_* = 0. | "prueba funcional con wallet devnet" — cubierta después por ticket-570. | 1/6 (idempotencia, reconciliación) | Cerrado; la story de fase 6 (invariantes) debe **citar** sus tests, no repetirlos. |
| ticket-435 | Finalizada | PR #9: `TradingIntent` (BUY/SELL/SWAP/CANCEL/HOLD/REBALANCE), `JsonCanonicalizer` RFC 8785 + NFC, `IntentHash`, `vectors-v1.json`. | `TradingIntent` **no es la entrada HTTP** (`CreateProposalRequest` es targetWeights): "implementado pero no cableado" al pipeline. | 2 (intent canónico) | La story fase 2 "ExecutionIntent" **duplicaría** ticket-435 si no parte de `TradingIntent`; y **ticket-457** (Backlog) ya pide exactamente el cableado intent→policy (un canonicalizador, `IntentFacts` desde `TradingIntent`, `input_hash ⊇ H_I`). → reutilizar ticket-457 como story de fase 2. |
| ticket-436 | Finalizada | PR #10: `DeterministicPolicyEngine` (11 predicados, ALLOW/DENY/ESCALATE, `R_v`, `H_R`), vectores, 2 implementaciones. Desplegado UAT a6dbf4f. | Tramos ESCALATE decorativos (→ ticket-466). `maxSlippageBps` viene de config. | 4 (policy) | Reutilizar. **ticket-466** (Backlog): segundo validador para REQUIRE_SECOND_AGENT + ruta ALLOW con timelock + "firma humana" — solapa con §6/§9 del mandato (APPROVAL_REQUIRED). |
| ticket-437 | Finalizada (`post-v1`) | PR #13: programa `intent-authority` (PDA policy/nonce/receipt, I1-I4), 29 tests cargo, job CI. | **NO desplegado en devnet, NO cableado en `ExecutionService`** (comentario lead 2026-09-17; README:355). | §21 (primitivas USE LATER) | No abrir story de fase; queda como "USE LATER" con proposal `cryptobot-authority-devnet-deploy-y-wiring`. |
| ticket-438 | Finalizada | PR #18: `validator/` (tabla independiente, `VALIDATOR_POLICY_HASH`, atestación Ed25519 90 s), `signer/` limitado (caps, allowlist, atestación obligatoria), fail-closed, timelock cancelable. | multisig/HSM = post-MVP; validator no lee la cadena ni verifica firma humana (Pitch :425). | 4 (validator/signer) | Reutilizar. Story fase 4 = **endurecer el contrato** (§8/§9), no reconstruir. |
| ticket-439 | Backlog (`post-v1`) | Rama entró **mergeada dentro de PR #28 (ticket-572)**: `PriceOracle` (mediana, freshness, desviación, breaker, quorum), fuentes Jupiter/Pyth/CoinGecko/Coinbase, reglas PRICE_*, `ORACLE_READING` en recibos. | No implementado: persistencia del último consenso (breaker por proceso), `MarketDataPort` genérico (ticket-383). **Issue sigue en Backlog aunque el código está en main** → cerrar con evidencia. | 3/7 (market data untrusted, §19 MarketDataPort) | La story "MarketDataPort {price, quote, liquidity, volume, historical}" de fase 3 **continúa** ticket-439/ticket-383; no duplicar. |
| ticket-440 | Finalizada | PR #12: benchmark 10 000 intents (7 000/3 000), 0 violaciones, I1-I7 como tests, `ChaosTest`. Vault `CryptoBot-Benchmark-Adversarial-2026-09-16.md`. | Proposals derivadas: ticket-455 (panel), ticket-468 (parser), ticket-457, ticket-466. | 6 (invariantes) | Los invariantes §29 (DENIED never SIGNED, etc.) **ya existen como I1-I7** en `benchmark/*Test`; la story fase 6 debe mapear §29 ↔ I1-I7 y agregar sólo los que falten (tampered receipt, expired approval, mainnet disabled ⇒ no execute está en `ExecutionServiceMainnetGateTest`). |
| ticket-450 | Backlog (own-platform) | Proposal: canonicalización RFC 8785 + hash como primitiva de Platform. | Sin trabajo. | 9 (reuso) | Story fase 9 la referencia; no duplicar. |
| ticket-457 | Backlog (own-verticals) | Proposal: unir intent canónico con Policy Engine. | Sin trabajo. | **2** | **Es la story de fase 2** (ver ticket-435). |
| ticket-464 | Backlog (own-platform) | Proposal: outbox+DLQ+reconciliación como lib `life-engine-reliability`. | Continuada por ticket-530. | 9 | No duplicar; el core del mandato exporta el contrato que ticket-530 consume. |
| ticket-493 | **Revisar** | PR #20 mergeada (2026-09-19): `CRYPTOBOT_ALLOW_MAINNET`/`SIGNER_ALLOW_MAINNET` default false, 3 capas, `AttestationRequirementGuard`, test sin stub. Verificación #20+#21 483/483, 0 violaciones. | AC "UAT: curl execute con wallet mainnet → 409" — cubierto por e2e-devnet.sh paso 12 en local; en UAT lo verifica sólo el flag del smoke, no un execute. **Issue abierto en Revisar con código en UAT.** | 4 (§25 mainnet DENY) | Cerrar con evidencia. Story §25 "readiness gate" (CB-13) es nueva, no duplica. |
| ticket-500 | **Revisar** | PR #27 **abierta**: `ChainE2EIT` (validator+signer reales en JVM, RPC mock HTTP), `intent_hash`/`execution_signature` columnas, README conteo. | **Bloqueada por colisión V10** (§1.1). Absorbida parcialmente por ticket-570 (E2E real por script). | 6 (E2E) | Es la story fase 6 "integration E2E en JVM"; hay que resolver V10→V11 y decidir si convive con `E2EDevnetIT`. |
| ticket-501 | **Revisar** | Absorbido por ticket-571 (PR #23 mergeada): DLQ resolve/requeue, V9, runbook `docs/runbooks/dead-letter.md`. | AC "ensayado una vez en UAT con una entrada real (salida en el issue)" — sólo ensayado en local (evidencia chaos). | 1/6 | Cerrar con evidencia; la story de fase 6 negativa #9 (permanent failure → DLQ en UAT) puede reusar este AC. |
| ticket-530 | Backlog (E7) | — | Decisión broker (Kafka/RabbitMQ/NATS) con ADR **después del 12/10** (comentario Sebastián 2026-09-21). | 9 / §27 | El mandato §27 dice "no Kafka just because events exist"; Sebastián prefiere broker real para CryptoBot. **Contradicción a resolver en el ADR de E7, no en la fase 0.** |
| ticket-569 | Revisar | Doc demo path (vault). | AC: aprobación de Sebastián. | 8 | — |
| ticket-570 | Revisar | PR #22: compose demo, wallet script, `e2e-devnet.sh`, `E2EDevnetIT`, 3 corridas local-validator. | Cerrar. | 6/8 | — |
| ticket-571 | Revisar | PR #23: chaos, retry idempotente, DLQ API, runbook. | Cerrar. | 1/6/8 | — |
| ticket-572 | Revisar | PR #28: oráculo (ticket-439), PRICE_*, RISK_DECISION L1, run.sh acto 2, 508 tests. | Cerrar. | 3/8 | — |
| ticket-573 | Revisar | PR #29 (main 2b69b37): logs JSON+MDC+ErrorCode, dashboard demo, alertas, prometheus en signer/validator. deploy #218. | Cerrar. | 8 / §23 | — |
| ticket-574 | Revisar | deploy #216/#221/#222, gitops #41/#43/#44: signer+validator en uat-compose y k8s-uat, smoke 1 tx devnet. | AC k8s "verify UAT-K8S PASS" — k8s no smokea cryptobot (§3). | 8 | — |
| ticket-575 | Revisar | PR #25: `run.sh` local/UAT con informe. | AC "corrido 3 veces seguidas sin intervención" sin evidencia en el issue. | 8 | — |
| ticket-576 | Revisar | ui PR #7 (main b439995): `/live`, DLQ, chaos, 61 tests. | Cerrar. | 8 | — |
| ticket-577 | Revisar | Pitch reencuadrado (vault 391fff5, sin push): "Safe Execution Protocol for AI Agents". | Videos, submission ≤ 10/10. | 8 | — |
| ticket-582 | **En curso** (claimed 05:49) | Worktree con 8 archivos dirty (§1.3): `execution_refused_total{reason}`, `SignerMetrics`, `ValidatorMetrics`, histogramas. | Sin PR. | 8 / §23 | **Conflicto con fase 1**: toca `ExecutionService`, `PolicyEngine`, `ReconciliationService`, `SignerController`, `ValidatorController`. Coordinar lease. |
| ticket-326 | Backlog (own-platform, `post-v1`) | — | runtime PR #33 (`crypto.portfolio-advisor.v1`) abierta desde 2026-09-14; decisión antes del 03/10. | fuera (Engine) | No es del core. |
| ticket-402 | Backlog (`post-v1`) | — | NATS → se compara contra Kafka/RabbitMQ en E7 (ticket-530). | 9 | Ver ticket-530. |
| ticket-455 | Backlog (own-infra) | Proposal: panel "capa de autoridad". | **Ya cubierto** por el dashboard demo (paneles "Veredictos DENY/ESCALATE", "Predicados que dijeron NO", "Veredictos por decisión y escalación", ticket-573). Cerrar como duplicado. | 8 | Cerrar. |
| ticket-528 (E5) | Backlog (own-engine) | — | Policy Engine central de Platform (`policy.evaluate.v1`), enforcement en Tool Runtime y budgets. | 9 | **Solapa** con §6 del mandato (policy engine determinista del core). El ADR debe decir: el core TIENE su policy de ejecución (dominio económico); E5 es governance de agentes/tools; no fusionar (§18). |
| ticket-253 | Backlog (`post-v1`) | Runtime V3 — Execution Engine durable (motor delgado, ADR-RT-013). | — | — | **Colisión de nombre**: "Execution Engine" en Runtime = durabilidad de runs; "Trusted Agent Execution Engine" = ejecución económica. Nombrar distinto en Jira/docs. |

Búsqueda de duplicados (`summary ~ Trusted|intent|execution engine|receipt|signer|validator|policy|recibo|escrow|idempot`, 41 resultados): además de los de arriba, ticket-16 (secrets), ticket-214 (video), ticket-395 (Runtime expone model.ref/promptHash — Finalizada), ticket-417/471/474 (infra), ticket-511 (Auth S2S tenants), ticket-520/536/566 (Media), ticket-524/525/531/554 (Engine). **Ninguno con "Trusted" salvo ticket-583/584.** Palabras que no aparecen en ningún summary: `escrow`, `receipt verify`, `ExecutionReceipt`, `Chain Adapter`, `ports`.

---

## 8. VAULT — decisiones ya tomadas que el mandato debe respetar o que lo contradicen

(Rutas relativas a `<workspace>/obsidian/life-engine/`; extracción con `archivo:línea`.)

### 8.1 Decisiones vigentes que el mandato DEBE respetar

| Decisión | Fuente | Implicación para TAE |
|---|---|---|
| Tres capas; cada componente en una; **Platform nunca depende de una vertical; Engine nunca conoce una vertical**; toda capability nueva se clasifica antes de implementarse (ADR si no está claro). Status **accepted**. | `Decisions/ADR-PLAT-017-Frontera-Platform-Engine.md:20-30`; `Architecture/Life-Engine-Ecosystem-v1.md:10-23` | `trusted-execution-core` tiene que clasificarse (Vertical-lib, Engine o Platform) **antes** de la fase 1. Hoy `cryptobot-service (+signer, validator, ui)` = VERTICAL (`ADR-PLAT-017:26`, `Ecosystem-v1:89`). |
| No se mueve código por ADR-PLAT-017; extracciones (outbox/DLQ de CryptoBot, `ext/*`) son issues de Engine v1. | `ADR-PLAT-017:34` | La fase 1 "boundaries inside current CryptoBot" es compatible (no extrae); la fase 9 choca con E7/E5 si se hace sin ADR de Engine. |
| Policy Engine central es de **Platform**; governance de agentes (`Agent → action request → Policy Engine → ALLOW/DENY/REQUIRE_APPROVAL`) es de Engine E5; el `PolicyEngine` de CryptoBot es "de dominio". | `Ecosystem-v1:45,56`; ticket-528 | Tres policy engines nombrados: Platform (central), Engine (governance), core TAE (ejecución). El mandato §18 ya separa trading risk ≠ execution policy; falta separar **execution policy ≠ agent governance** en el ADR. |
| Event Backbone: normalizar outbox+DLQ+reconciliación de CryptoBot (V5) como capability; **no obligar broker donde no aporta**. | `Ecosystem-v1:60`; `Endgame:515-517` ("NATS todavía no") | Coincide con §27 del mandato. |
| Jobs/Runs/Events canónico en Runtime; **`ActionProposal` NO es un Run** (11 estados, no cambia); `AuditEvent` de CryptoBot se conserva; `operationId` misma semántica que `job.operationId`; `Receipts.runtimeRef` debe llevar `runId + producedBySeq + runtimeDigest`. Status **proposed**. | `Decisions/ADR-ENG-001-Modelo-Canonico-Job-Run-Event.md:25,72-74,143-148,180` | La máquina de estados del core (§5) parte de los 11 estados; sin `WAITING_APPROVAL` en Runtime → la espera humana vive en la vertical/core. |
| Runtime nunca ejecuta procesos/CLIs; permisos declarados en descriptor, decisión ALLOW/DENY/REQUIRE_APPROVAL es del Policy Engine (Governance). Status proposed. | `Decisions/ADR-ENG-002-Agent-Runtime-Contracts.md:149,152` | El core nunca corre en Runtime; Runtime sólo produce intents (LLM = L0). |
| Recibos, DAG y anclaje viven en `cryptobot-service`; criterio de extracción a `intelligence-ledger`: dos verticales escribiendo recibos o un consumidor externo. `receiptId` content-addressed; dominio `life-engine.cryptobot.receipt`; schema `ir/1`; kinds fijos; `reproducibility` L0..L3; anchor fuera del hash; RFC 8785; prompts/salidas nunca en recibo. | `Products/CryptoBot-Endgame-Verifiable-Intelligence-2026-09-15.md:86-90,144-197` | El `ExecutionReceipt` del mandato §15 **ya existe como `EXECUTION` receipt** (kind, hash, firma, anchor). Cambiar el dominio del hash invalida lo anclado en devnet. Lo que falta del §15: `policyHash/Version/Decision`, `attestationHash`, `validatorResult`, `signerIdentity`, `submittedAt/confirmedAt/reconciledAt`, `finalStatus` como campos explícitos del body (hoy parte está en `audit_event`/`action_proposal`, no en el recibo — verificar con Auditor A). |
| Claims prohibidos: "Proof of Inference", "LLM determinista", cómputo verificado por terceros, atribución económica funcionando, descentralización, "estándar" (sin spec pública ni segundo implementador). | `Endgame:440-446`; `Products/CryptoBot-Hackathon-Pitch-2026-09-20.md:397-411` | §31/§32 del mandato ("never nobody else does this without evidence") coincide. El ADR no puede llamar "protocolo/estándar" al core. |
| Fuera del hackathon: programa Anchor, USDC/escrow, reparto, zkML, mainnet; multisig/HSM; mTLS signer↔service. | `Endgame:397-398`; `Products/CryptoBot-Hackathon-Demo-Path-2026-09-20.md:§4`; `Pitch:413-435` | §22 (Capital/Caputronic/escrow/agent economy) es "validar sin implementar": consistente. |
| Mainnet fail-closed en 3 capas hasta un readiness gate independiente (CB-13); `execution-cluster: devnet` fijo en `application.yml`. | `Pitch:415-418`; ticket-493 | §25 del mandato. El gate CB-13 no tiene issue. |
| Hackathon: scope congelado, ningún feature fuera del demo path; código congelado 08/10; grabación 09/10; **submit ≤ 10/10**; decisión ticket-326 antes del 03/10. | `Demo-Path:§7`; `Pitch:441-456` | Fases 1-7 del mandato **no pueden tocar main antes del 10/10** salvo que no cambien comportamiento del demo (§33 "each phase deployable" + §40 prioridad 1 = demo). |
| Programa `intent-authority`: nativo sin Anchor; no desplegado (gate humano: Solana CLI); `ExecutionService` no lo llama; v1 de vectores se congela al primer deploy. | `Products/CryptoBot-OnChain-Authority-2026-09-16.md:7,39-73` | §21: USE LATER. |
| Benchmark: predicados, meters y I1-I7 a preservar; chaos → DENY/PAUSE nunca ejecución silenciosa. | `Products/CryptoBot-Benchmark-Adversarial-2026-09-16.md:72-84,126-166,182-204` | Lista de compatibilidad §10. |
| Capital: vertical congelada hasta Platform Baseline v1; orden Platform → CryptoBot → Capital → Caputronic → Factory. | `Products/Life-Engine-Capital.md:4,10-12`; `Roadmaps/Roadmap-Maestro-2026-09-20.md:22` | §22: validar fit, no abrir. |
| Kubernetes para prod: no ahora. | `Endgame:519-521` | cryptobot no va a prod (§2.2). |

### 8.2 Contradicciones o tensiones explícitas con el mandato

| # | Mandato | Vault / Jira | Qué decidir |
|---|---|---|---|
| C1 | §27 "no Kafka just because events exist; use current infra unless demonstrated need" | ticket-530 comentario Sebastián 2026-09-21: "para CryptoBot lo más sabio sería un broker real — Kafka o RabbitMQ"; decisión con ADR después del 12/10. Memoria `cryptobot-broker-kafka-o-rabbit.md`. | El core define el **contrato** de eventos (§27, IDs no payloads) y deja el transporte al ADR de E7. No elegir broker en fase 0-7. |
| C2 | §3 módulos `trusted-execution-core`, `solana-execution-adapter`, `cryptobot-trading` | `Ecosystem-v1:89` y `ADR-PLAT-017:26`: cryptobot = VERTICAL; `Clasificacion:137-138`: reliability = E-dup (Engine), receipts = P-cand (Platform ledger); `Pitch:142-146`: "protocolo = ENGINE E5 + PLATFORM policy engine central". | El core del mandato mezcla piezas que la clasificación 2026-09-20 asignó a 3 capas distintas. Fase 1 (fronteras dentro de CryptoBot) no viola nada; fase 9 requiere ADR-ENG (E5/E7) + ADR-PLAT (ledger). El ADR §37 debe declarar la capa del core explícitamente. |
| C3 | §6 policy engine del core con ALLOW/DENY/APPROVAL_REQUIRED | Código: ALLOW/DENY/**ESCALATE**(REQUIRE_SECOND_AGENT\|REQUIRE_HUMAN_SIGNATURE) (ticket-436); Pitch llama REVIEW; dashboards filtran `decision="escalate"`; ticket-528 usa REQUIRE_APPROVAL. | §5 del mandato prohíbe renombrar estados persistidos sin migración. Mantener `ESCALATE` como valor persistido/métrica y mapear APPROVAL_REQUIRED como alias en el contrato. |
| C4 | §42 golden path "Buy 50 USDC of SOL" (swap) | `Pitch:420-421`: "sólo `SystemProgram.transfer` a vault allowlisteado, **sin swap Jupiter**"; compose UAT: leg ejecutable SOL→vault; signer sólo acepta transfer (`SigningPolicy`). | El golden path del mandato **no es ejecutable hoy** ni está en el scope congelado. Elegir: (a) narrar "Buy 50 USDC of SOL" como intent con leg ejecutable transfer (lo que hay), o (b) abrir swap = feature nueva post-12/10. |
| C5 | §28 APIs `POST /intents`, `/intents/{id}/approve|execute`, `GET /executions/{id}`, `/receipts/verify` | API actual `/api/cryptobot/wallets/{id}/proposals`, `/proposals/{id}/*`, consumida por UI, 3 scripts, dashboards por `uri` literal y 1 alerta (§5). | Sólo aditivo con alias; renombrar rompe 6 `uri` de Grafana y la UI. |
| C6 | §36 "READY_TO_IMPLEMENT" y fases 1-7 | Freeze hackathon hasta 10/10 (`Demo-Path`, ticket-323 comentario 2026-09-20 17:48 labels `post-v1`). | READY_TO_IMPLEMENT=true sólo para cambios que no alteren el demo; el resto arranca 13/10. |
| C7 | §38 "epic Trusted Agent Execution + stories" | ticket-583/584 ya creadas 2026-09-21 05:58-05:59. | No duplicar; crear stories como hijas de ticket-583 y **enlazar** ticket-457, 466, 500, 501, 493, 439 en vez de re-escribirlas. |
| C8 | §23 "keep compatibility with existing Grafana… metric names" y §15 receipt con `signerIdentity` etc. | `Endgame:415-421` lista métricas `cryptobot_receipts_total{kind,level}`, `cryptobot_receipt_latency_seconds`, … que **NO existen** con ese nombre (el código emite `intelligence_receipts_total{result}`, `anchor_pending`, `artifact_reuse_total`, `provenance_depth`). | El vault del Endgame está desactualizado respecto al código; el inventario válido es §4.3 de este informe. |
| C9 | §17 "ONE reference strategy now" | Código: `RebalancePlanner` (targetWeights) es la única estrategia; `TradingIntent` tiene 6 verbos. Pitch: "rebalance SOL". | Consistente; la estrategia de referencia es REBALANCE. |

---

## 9. Flujos futuros §22 (Capital, Caputronic, Legal escrow, Agent economy)

`grep -ril "caputronic\|capital\b\|escrow\|agent economy\|agent-to-agent\|payroll\|invoice" vault` → 23 archivos; la mayoría de `invoice` es Commerce (Stripe) y `escrow` de M&A.

| Producto | Doc | Qué es | Intent que produciría |
|---|---|---|---|
| **Capital** | `Products/Life-Engine-Capital.md` (62 líneas; congelada, `:4,10-12`) | Portfolio personal consolidado (ETFs, acciones, crypto vía CryptoBot), look-through por emisor/sector, mismo motor de riesgo que CryptoBot; "nunca ejecuta sola" (`:44-45`). | **Rebalance sugerido** (propuesta → riesgo determinista → aprobación humana) = el mismo `REBALANCE` de hoy con otro `source`; el Pitch le asigna además "pay the other agent" (`Pitch:138`). Fit: sí, sin cambios en el core; necesita `source`/`tenant` en el intent (§4). |
| **Caputronic** | **NO EXISTE doc de producto** (`ls Products/ \| grep -i caputronic` = 0). Menciones: `ADR-PLAT-017:26`, `Ecosystem-v1:19,75`, `Roadmap-Maestro:34`, `ADR-PLAT-018-Commerce-Ownership.md:84`. | Vertical futura (semanas 7-8), debe consumir Commerce/Identity/Ledger de Platform. | **"Pay this invoice"** = transfer USDC (SPL) a proveedor allowlisteado con monto y referencia de factura (`Pitch:136`; Paper "Procurement: AI proposes purchase → budget/vendor rules → signed order" `Paper:1572-1578`). Fit: requiere `assetOut=USDC`, `destination` variable (hoy 1 vault fijo en signer `SIGNER_ALLOWED_DESTINATIONS`), instrucción SPL transfer (hoy sólo SystemProgram.transfer). |
| **Legal escrow** | **NO EXISTE doc** (`grep -rn -i escrow` → sólo Pitch/Endgame/Demo-Path/adquisición). Legal Tech ❌ en `Ecosystem-v1:74`. | Tesis: "release the escrow" (`Pitch:137`); Endgame fase 2: escrow USDC con liberación diferida para artefactos (`Endgame:296-298`). | **Escrow release** = invocar un programa (Anchor/nativo) que libera USDC a un beneficiario tras condición; requiere `allowedPrograms` + instrucción de programa custom → hoy fuera (signer sólo transfer, `intent-authority` no desplegado). Fit del intent: sí (`operation=ESCROW_RELEASE`, `destination`, `allowedPrograms`); fit del adapter: no hoy. |
| **Agent economy / agent-to-agent** | **NO EXISTE doc** (`payroll` 0 hits; `agent-to-agent` sólo `Pitch:138,221`). | Pagos entre agentes; x402 como capa de pago futura "no inventar otra" (`Endgame:438`); atribución en USDC diferida (`Endgame:259-265`). | **Pago agente-a-agente** = transfer USDC con `actor=agent`, `destination=agent wallet`, límites de velocidad/día por agente. Fit del intent: sí; requiere `actor` de tipo agente (hoy `tenantId = ownerUserId`, sin modelo de tenant — `Clasificacion:145`, ADR-ENG-001 D4). |

Conclusión §22: los 4 flujos caben en el **modelo de intent** sin cambios incompatibles (operation/asset/destination/source/actor), pero **3 de 4 no caben en el adapter/signer actual** (sólo `SystemProgram.transfer` a un único destino allowlisteado, sin SPL/Token-2022, sin programas). Eso es una extensión del `ChainExecutionPort` + `SigningPolicy` (allowlist de mints/programas/destinos por tenant), no un cambio del core.

---

## 10. Gaps de mi alcance y lista de "no romper"

### 10.1 Gaps (requisito → estado → evidencia → gap → severidad)

| # | Requisito | Estado | Evidencia | Gap | Sev |
|---|---|---|---|---|---|
| G1 | §34 no destruir UAT / PRs mergeables | PR #27 (ticket-500) **no mergeable**: dos `V10__*.sql` | `git ls-tree origin/ticket-500-… db/migration` vs main (`V10__inference_engine_version_width.sql` de da490d4) | Renumerar a V11 + rebase; Flyway fallaría al arrancar en UAT | **Alta** (bloquea la única E2E en JVM con signer+validator reales) |
| G2 | §8/§9 validator con policy pineada por hash | `VALIDATOR_POLICY_HASH` vacío en uat-compose (`CRYPTOBOT_POLICY_HASH` ausente de `.env.uat`) y en k8s (`cryptobot-validator.env:19`) | §2.1, §3 | El validator UAT acepta cualquier `R_v` que hashee (WARN); un cambio de política del servicio sin el del validator da POLICY_HASH_MISMATCH pero un cambio coordinado malicioso no se detecta. Local demo sí lo pinea (evidencia "policy pin sha256:1882cd0a…") | **Alta** (seguridad, config) |
| G3 | §35 DoD "evidencia" / §34 UAT | k8s-uat no smokea cryptobot (`OPS_ENV_K8S_SERVICES=[uat]="runtime"`, `smoke-uat-k8s.sh` 0 menciones) | §3 | 3 pods Healthy sin prueba de firma; `esperar-digest.sh` sólo mide readiness | Media |
| G4 | §44 "never DONE without evidence" | La evidencia `ops.d/evidence/*cryptobot*.md` no registra PASS/SKIP del smoke devnet ni la firma; sólo "smoke OK" | §2.5 | La única traza es un comentario de Jira (ticket-577) | Media |
| G5 | §30 failure demos 4, 5, 7, 10 | slippage sólo en tests (no controlable por API); mint no scripted; signer down = FAILED sin retry ni chaos mode; tampered receipt sólo en tests y sin endpoint que reciba un recibo externo | §6.3 | 4 de 10 demos no son ejecutables por script hoy | Media (demo) / Alta para #10 (§15 "verify(receipt) without trusting UI") |
| G6 | §42 golden path "Buy 50 USDC of SOL" | Sólo `REBALANCE` targetWeights → transfer SOL→vault; sin swap ni USDC | §6.2, C4 | El relato del mandato no coincide con la operación ejecutable; hay que elegir narrativa o feature | Alta (producto) |
| G7 | §28 APIs de negocio (`/intents`, `/executions`, `/receipts/verify` con body) | NO EXISTEN (`grep -rn '"/intents\|/executions' src/main` = 0) | §5.1 | Sólo alias aditivos; `POST /receipts/verify` con cuerpo no existe (verify toma hash de DB) | Media |
| G8 | §23 observabilidad de signer/validator | `/actuator/prometheus` expuesto (ticket-573) pero sin `life-engine.scrape` en compose UAT (:986-988) ni métricas propias en main; ticket-582 en curso | §2.1, §4.3 | El dashboard "Firmados*" es derivado | Media |
| G9 | §33 fases desplegables + freeze hackathon | ticket-582 (En curso) y fase 1 tocan los mismos 6 archivos; freeze hasta 10/10 | §1.3, C6 | Concurrencia de ramas sobre `ExecutionService`/`PolicyEngine`; leases por issue | Media |
| G10 | §38 Jira sin duplicados | ticket-583/584 existen; ticket-457/466/500/501/493/439/455 abiertas y solapadas con fases 2/4/6 | §7 | Cerrar 493/501/439/455 con evidencia y adoptar 457/466/500 como stories antes de crear nuevas | Media |
| G11 | §34 consumidor Runtime | `crypto.portfolio-advisor.v1` NO EXISTE en runtime main (PR #33 abierta 7 días); `POST /wallets/{id}/ask` falla en UAT; Runtime `ext/cryptomarketreview` llama 6 endpoints legacy de CryptoBot (violación E2 de la clasificación) | §5.2 | Dos acoplamientos Runtime↔CryptoBot fuera del core; decidir ticket-326 antes del 03/10 | Baja para el core |
| G12 | Higiene de repos | ramas `ticket-403`, `ticket-199` stale; worktree ticket-439 con 62 staged obsoletos; 8 worktrees mergeados en `cryptobot-service/.worktrees`; checkout principal en `ci/mvnw-self-hosted` con 43 dirty; UI local main atrasado; `docker-compose.hackathon.yml` duplicado del demo compose | §1 | Ruido para quien haga fase 1 | Baja |
| G13 | §36 entregables | `docs/architecture/` NO EXISTE en el service | §6.4 | Crear con la PR de ticket-584 | — |
| G14 | CI | `cancel-in-progress` en main deja commits sin imagen (d60fc53, 9d68b84); la UI no tiene workflow CI | §1.4 | Sin impacto en deploy (siempre último digest); UI sin gate | Baja |
| G15 | Drift de red UAT | `le-uat-services` declarada `internal: true` pero viva con Internal=false (comentario compose :60-66) | §2.1 | El aislamiento del signer depende sólo de `le-uat-cryptobot` (`internal: true`, verificado por smoke :393-396) | Baja |

### 10.2 Cosas que NO se pueden romper (compatibilidad §34) → consumidor

| Contrato | Consumidor(es) |
|---|---|
| Rutas/verbos `/api/cryptobot/{wallets,proposals,receipts,anchors,dead-letters,demo/chaos}/**` y sus shapes JSON (§5.3 #2) | cryptobot-ui (`control-plane-api.ts`, `receipts-api.ts`, `lineage-api.ts`, `reliability-api.ts`), `deploy/uat/smoke-cryptobot-devnet.sh`, `scripts/demo/{run,e2e-devnet}.sh`, Grafana `uri` literal ×6, alerta `CryptoBotMainnetAttempt` |
| `POST …/execute` con `Idempotency-Key` **y** body `{operationId}`; 409 para no-APPROVED/timelock/mainnet/blocked | UI (sólo body por CORS), smoke, demo, alerta 409 |
| Endpoints legacy `/snapshots/{symbol}`, `/watchlist`, `/zones`, `/observations`, `/journal`, `/indicators`, `/market-reviews*`, `/monitoring/run-once`, `/health` | Runtime `ext/cryptomarketreview/tools/*` (workflow `crypto.market-review.v1`), `scripts/smoke-cryptobot.sh`, UI dashboard legacy |
| `/api/signer/{identity,sign,sign-anchor}`, `/api/validator/{identity,validate}`, headers `X-Signer-Token`/`X-Validator-Token`, `cluster` en payload, `SIGNER_VALIDATOR_PUBLIC_KEY` | `SignerClient`, `ValidatorClient`, smoke (:100), `wallet-devnet.sh` |
| 27 series Prometheus + valores de label + common tags (§4.3), `ProposalStatus` ×11, `audit_event.eventType` ×24, outbox `trade.*`/`receipt.*` ×11, `ReceiptKind` ×7, dominio `life-engine.cryptobot.receipt`, `ir/1` | 2 dashboards, 3 alertas, 26 rule tests, `PrometheusMeterNamesTest`, Control Tower, UI `live-model.ts` (timeline por eventType), recibos anclados en devnet (1 ancla n=25) |
| Variables de entorno `CRYPTOBOT_*`, `SIGNER_*`, `VALIDATOR_*` listadas en §2.1 (compose) y §3 (ConfigMaps/Secrets SOPS) | `deploy/uat/docker-compose.uat.yml`, `gitops/overlays/uat/cryptobot/*.env`, 3 Secrets SOPS, `cryptobot-devnet-credentials.sh`, `cryptobot-devnet-secrets.sh`, `smoke-uat.sh` §F (lee `CRYPTOBOT_ALLOW_MAINNET`, `SIGNER_ALLOW_MAINNET`, `SIGNER_CLUSTER`, `CRYPTOBOT_EXECUTION_ENABLED` del contenedor) |
| Ids de servicio `cryptobot`, `cryptobot-signer`, `cryptobot-validator`; paquetes GHCR `life-engine-cryptobot-{service,signer,validator}`; puertos 8091/8096/8097; una línea `image@digest` por `base/<svc>/deployment.yaml`; Dockerfiles con `ARG GIT_*` | `platform-catalog.yml`, `uat-current.yml`, `ops.d/lib/services.sh`, `resolve-artifact.sh`, `gitops-bump.sh`, NetworkPolicies por `app:` label, Argo Application única |
| Migraciones Flyway V1..V10 y base `life_engine_cryptobot` | uat-compose Postgres, k8s `postgres.uat.svc`, PR #27 (V10 colisiona) |
| `docker-compose.demo.yml` + `.env.demo` + `~/.cryptobot-demo/` + `CRYPTOBOT_CHAOS_ENABLED` | `run.sh`, `e2e-devnet.sh`, `E2EDevnetIT`, UI (`/demo/chaos` opcional), `scripts/demo/tests/test-run.sh` en CI |
| Workflow ids `crypto.market-review.v1` (existe) y `crypto.portfolio-advisor.v1` (PR #33) | `AdvisorProperties.java:11`, `RuntimeClient`, UI `/ask` + SSE |
| Claims del pitch (permitidos/prohibidos) y "what we are NOT" | `Pitch:385-435`, `Endgame:440-446`; submission ≤ 10/10 |

---

## 11. Resumen — 10 hallazgos más importantes (Auditor C)

1. **PR #27 (ticket-500) no es mergeable**: agrega `V10__intent_hash_and_execution_signature.sql` y main ya tiene `V10__inference_engine_version_width.sql` (ticket-572, da490d4). Es la única E2E en JVM con signer+validator reales; renumerar a V11 y rebasear.
2. **`VALIDATOR_POLICY_HASH` no está pineado en ningún UAT** (`.env.uat` sin `CRYPTOBOT_POLICY_HASH`; `gitops/overlays/uat/cryptobot/cryptobot-validator.env:19` vacío). El validator corre con WARN; sólo el compose demo local lo pinea (evidencia `policy pin sha256:1882cd0a…`).
3. **La épica del mandato ya existe**: ticket-583 (Trusted Agent Execution) + ticket-584 (Fase 0, claimed por el lead). Stories de fase 2/4/6 tienen candidatos abiertos: ticket-457 (intent→policy), ticket-466 (tramos ESCALATE/segundo validador), ticket-500 (E2E JVM). Cerrar con evidencia ticket-493, 501, 439 (código en main, issue abierto) y ticket-455 (panel ya hecho en ticket-573).
4. **Golden path §42 "Buy 50 USDC of SOL" no existe**: la única operación ejecutable es `REBALANCE` targetWeights → `SystemProgram.transfer` SOL→vault único (signer `SIGNER_ALLOWED_DESTINATIONS`, sin swap, sin SPL). Pitch:420-421 lo declara. Decidir narrativa vs feature post-12/10.
5. **Failure demos §30: 6/10 ejecutables hoy** (valid, mainnet, amount por %, duplicate, RPC lost, permanent→DLQ). Slippage y tampered-receipt sólo en tests (`maxSlippageBps` es config; `verify` sólo acepta hashes de la DB); mint no scripted; signer down = FAILED terminal sin retry ni chaos mode.
6. **Inventario de compatibilidad**: 27 series Prometheus usadas por 2 dashboards (82 paneles) + 3 alertas + 26 rule tests; 6 `uri` HTTP literales en Grafana; 11 `ProposalStatus`, 24 `eventType`, 11 eventos outbox, 7 `ReceiptKind`, dominio `life-engine.cryptobot.receipt`. Huérfanas (código sin panel): `oracle_execution_refused_total`, `artifact_reuse_total`, `provenance_depth`, `cryptobot_quotes_*`, `cryptobot_oracle_*`. Sin métricas de signer/validator en main (ticket-582 en curso).
7. **Consumidores de API**: UI (4 clientes TS, usa body `{operationId}` por CORS), smoke UAT (7 llamadas), `run.sh`/`e2e-devnet.sh` (20+ rutas incl. `/demo/chaos`, `/demo/price`), Runtime `ext/cryptomarketreview` → 6 endpoints legacy (Engine llama a Vertical), `scripts/smoke-cryptobot.sh` legacy. `crypto.portfolio-advisor.v1` NO existe en runtime main (PR #33 abierta desde 14/09; `/ask` roto en UAT).
8. **UAT vivo y consistente**: uat-compose y k8s-uat corren los 3 módulos por digest `sha-2b69b37` (ec067f0d / de50563a / eddc16c5), Argo Synced/Healthy, 6 NetworkPolicies, secretos SOPS con las keys esperadas. PROD confirmado sin cryptobot (`prod-current.yml`, `prod/docker-compose.prod.yml`). Pero k8s-uat **no smokea** cryptobot (`OPS_ENV_K8S_SERVICES=[uat]="runtime"`) y la evidencia `ops.d/evidence` no registra PASS/SKIP ni firma del smoke devnet.
9. **Concurrencia con fase 1**: ticket-582 (En curso, worktree con 8 dirty) modifica `ExecutionService`, `PolicyEngine`, `ReconciliationService`, `SignerController`, `ValidatorController`, `CryptobotMetrics` — los mismos archivos que las fronteras del core. Freeze del hackathon hasta 10/10 (código congelado 08/10).
10. **Contradicciones documentadas con el mandato**: C1 broker (Sebastián prefiere Kafka/RabbitMQ para CryptoBot vs §27) → ADR E7 post-12/10; C2 capa del core (Vertical hoy; reliability=Engine E7, receipts=Platform ledger, policy=Platform/E5 según ADR-PLAT-017 y la clasificación) → el ADR §37 debe fijar la capa; C3 `ESCALATE` persistido vs `APPROVAL_REQUIRED`; C5 rutas `/intents` vs `/proposals` (sólo aditivo). El vault Endgame lista métricas `cryptobot_receipts_total{kind,level}` que nunca existieron con ese nombre.
