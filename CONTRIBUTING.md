# Contributing to Life Engine

## Conventional Commits

Todos los commits siguen el formato:

```
{type}({scope}): {description}
```

### Types

| Type | Cuándo usarlo |
|------|--------------|
| `feat` | Nueva funcionalidad visible al usuario o API |
| `fix` | Corrección de bug |
| `refactor` | Cambio interno sin efecto en comportamiento |
| `test` | Solo tests — sin cambio de código productivo |
| `docs` | Solo documentación |
| `ci` | Cambios en pipelines, workflows, configs de CI |
| `chore` | Deps, configs menores, tareas de mantenimiento |
| `perf` | Mejora de performance sin cambio de comportamiento |

### Scopes por servicio

| Servicio | Scope |
|----------|-------|
| Auth | `auth` |
| Runtime | `runtime` |
| Business Chat | `bc` |
| RAG | `rag` |
| ATP | `atp` |
| CryptoBot | `crypto` |
| Dev Agent | `dev-agent` |
| Deploy | `deploy` |
| Infra | `infra` |
| Security | `security` |

### Ejemplos válidos

```
feat(bc): add WhatsApp webhook retry with exponential backoff
fix(rag): add @MockBean JwksPublicKeyProvider to WebFluxTest slices
refactor(runtime): extract workflow executor into separate class
test(auth): add RS256 JWT validation integration tests
ci(deploy): push SHA tag on GHCR alongside :latest
chore(auth): upgrade spring-boot to 3.3.2
```

### Breaking changes

Agregar `!` después del scope y footer `BREAKING CHANGE:`:

```
feat(bc)!: rename SendMessageResponse.metadata.conversationId to threadId

BREAKING CHANGE: metadata key renamed — update all consumers before deploying
```

## Branch naming

```
KAN-{number}-{short-description}
```

Ejemplos: `KAN-200-branch-protection`, `KAN-RAG-CI-fix`

## Flujo de trabajo

1. Crear branch desde `main`
2. Commits con Conventional Commits
3. Abrir PR con template completo
4. CI debe estar verde
5. 1 aprobación (o merge directo si el autor es el EM y CI pasa)
6. Squash merge a `main`

## Contratos congelados — NO modificar

- `workflowId: business-chat.reply.v1`
- Keys de metadata en `SendMessageResponse`
- Surfaces: `business-chat-v1.0.0`, `tenancy-v1.0.0`, `whatsapp-v1.0.0`, `inbox-v1.0.0`
- Tag `e2e-demo-v1`

Cualquier cambio a estos contratos requiere aprobación explícita del Engineering Manager.
