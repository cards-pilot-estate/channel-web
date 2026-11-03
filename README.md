# channel-web

The web self-service channel of the card onboarding estate, owned by the web channel team: a Spring Boot **backend-for-frontend (BFF)** that serves the customer's single-page app at `web.<env>.localtest.me` (ADR-0020 in card-onboarding-platform).

Design: `card-onboarding-platform/docs/channels/web-channel.md` (screens S01 to S26, the BFF, storage, polling), README rulings R1 to R22.

## How a customer applies

```
browser ─► Traefik ─► channel-web BFF ─► API gateway ─► otp-service (sign-in code, proof)
                         │                    └────────► onboarding-service (submit, next-action)
                         ├─ Redis: session (15 min idle)
                         └─ Postgres: drafts (encrypted), consents, who owns which application
```

1. **Sign in** with a mobile number and a 6-digit code (`/bff/auth/otp/send`, `/verify`).
   - The code comes from otp-service through the gateway.
   - The BFF checks otp-service's signed proof (audience, subject, purpose, challenge, binding, expiry; each proof is accepted once).
   - Then it starts a new session for the customer. The principal is `customerKey`, an HMAC of the number.
2. **Consent first** (`POST /bff/drafts`): processing and the bureau check are required; marketing is optional and defaults to No. Nothing is stored before this (R3).
3. **One section per page** (`PUT /bff/drafts/current/sections/{about-you|address|work|card}` with `If-Match`):
   - answers are validated server-side, and errors come back as field codes;
   - the draft is encrypted with AES-GCM and purged 30 days after the last change.
4. **Submit** (`POST /bff/drafts/current/submission`). The BFF builds the platform's v1 body itself and stores the idempotency key before the call. A gateway failure answers 503 + `Retry-After`, and the retry sends the same key.
5. **Track** (`GET /bff/applications/{id}/status`): the platform's customer vocabulary from `next-action`. The BFF serves an application only to its owner (404 otherwise, with no upstream call).
6. **Withdraw or ask for erasure** after a fresh step-up code (5 minutes).

Every upstream call carries:
- the channel's client-credentials token (`channel-web-<env>`);
- `x-customer-key`, `X-Correlation-ID`, `X-Channel-ID: WEB` and an `Idempotency-Key` for writes;
- the environment's `Host` header.

No browser header is forwarded.

## Security baseline

- **Session cookie:** `WEB_SESSION`, which becomes `__Host-WEB_SESSION` + Secure behind TLS (WG.06). It is HttpOnly and SameSite=Strict, with a 15-minute idle timeout.
- **CSRF:** cookie-to-header for every write.
- **CSP:** `default-src 'self'`, `frame-ancestors 'none'`, `form-action 'self'`. Referrer-Policy `no-referrer`.
- **Network:** Traefik in; out only to the gateway, Keycloak, its own stores and DNS (NetworkPolicy).
- **Logs:** ids only, with masked numbers.

## Use

```bash
make verify               # BFF tests: Testcontainers Postgres and Redis, WireMock for the gateway
make load                 # image into kind
make deploy ENV=dev       # secrets if missing (client secret from iam/iam-secrets), then helm into channels-dev
bash scripts/journey-test.sh dev   # the live journey through Traefik, the BFF, the gateway and both backends
```

The SPA (the customer journey pages) is WG.09; until then `/` serves a placeholder page.
