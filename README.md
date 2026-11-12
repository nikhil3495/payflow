# PayFlow

A payments backend (Java 21, Spring Boot, plain JDBC, PostgreSQL 16) that does the hard part of a
payment API correctly: **authorize → partial capture → partial refund → void the remainder**, with
idempotent requests, a double-entry ledger enforced by the database, and concurrency control you can read
in the SQL. The PSP is simulated in-process.

**Run it:** `cp .env.example .env && docker compose up --build --wait` → API on `http://localhost:8080`
(details and a scripted happy path below).

- **258 tests, 0 failures** (`mvn clean verify`, run this session), including a property-based ledger test
  and a 500-way concurrent-capture test against a real PostgreSQL (Testcontainers).
- **95.6 % line coverage** (1,645 of 1,721 lines, from `target/site/jacoco/jacoco.csv`); the build fails below 85 %.
- Quality gates in the build: `-Xlint:all -Werror`, Spotless, SpotBugs (max effort), JaCoCo.

## Architecture

```
                 X-Merchant-Id, Idempotency-Key
   client ───────────────────────────────────────▶  web (controllers, filters, problem+json)
                                                      │
                                                      ▼
                                      application (PaymentService, IdempotentExecutor,
                                       RetryingTransactionRunner, WebhookDispatcher,
                                       ReconciliationService)
                                         │                     │
                                         ▼                     ▼
                              domain (pure Java:        PspGateway (port)
                              Payment, state machine,   └─ SimulatedPspGateway
                              ledger postings, Amounts)
                                         │
                                         ▼
                      infrastructure (JdbcClient repositories, explicit SQL)
                                         │
                                         ▼
        PostgreSQL 16: payments · payment_operations · ledger_entries/transactions (append-only,
        balanced at COMMIT by a deferred trigger) · idempotency_keys · webhook outbox/dead letters
```

Packages: `domain` (no framework, no persistence annotations) → `application` → `infrastructure` / `web`.
Decision records with rationale: [`docs/DECISIONS.md`](docs/DECISIONS.md) (D1–D29). Review findings:
[`docs/BUG-HUNT.md`](docs/BUG-HUNT.md).

## Run it (one command)

```bash
cp .env.example .env      # then replace the values; the app refuses to start without them
docker compose up --build --wait
./scripts/smoke.sh        # optional: runs the happy path below against the container
```

API on `http://localhost:8080`; OpenAPI spec in [`docs/openapi.json`](docs/openapi.json).
Tests: `mvn clean verify` (needs Docker for Testcontainers).

## API

All mutating calls need `Idempotency-Key` and `X-Merchant-Id`. Money is integer minor units. Errors are RFC 9457
`application/problem+json` with a stable `code`.

| Method & path | Purpose |
|---|---|
| `POST /v1/payments` | authorize |
| `GET /v1/payments/{id}` | read a payment |
| `POST /v1/payments/{id}/captures` | capture (partial allowed) |
| `POST /v1/payments/{id}/refunds` | refund |
| `POST /v1/payments/{id}/voids` | void the uncaptured remainder |
| `GET /v1/payments/{id}/ledger` | double-entry postings |
| `PUT /v1/webhook-endpoint` | register the merchant's outbound webhook |
| `POST /v1/webhooks/psp` | inbound PSP events (HMAC-signed) |
| `GET /v1/reconciliation/runs[/{id}[/discrepancies]]` | operator endpoints (admin token) |

Real exchanges, captured from the container by `scripts/smoke.sh` ([`docs/smoke-output.txt`](docs/smoke-output.txt)):

```
POST /v1/payments   [Idempotency-Key: auth-…]
> {"amount_minor": 10000, "currency": "USD", "payment_method_token": "tok_visa", "reference": "order-1042"}
< 201 {"id":"01a1cc7e-bcda-7291-89b4-b4c81b436fe7","merchant_id":"acme","amount_minor":10000,"currency":"USD",
       "authorized_minor":10000,"captured_minor":0,"refunded_minor":0,"voided_minor":0,"state":"AUTHORIZED",…}

Same request again                       → 201  Idempotency-Replayed: true   (identical body, nothing re-executed)
Same key, {"amount_minor": 99, …}        → 422  IDEMPOTENCY_KEY_REUSED

POST /v1/payments/{id}/captures   > {"amount_minor": 4000, "currency": "USD"}
< 201 {"type":"CAPTURE","amount_minor":4000,"state":"SUCCEEDED","payment":{…,"captured_minor":4000,"state":"PARTIALLY_CAPTURED"}}

POST /v1/payments/{id}/refunds    > {"amount_minor": 1000, "currency": "USD", "reason": "customer request"}
< 201 {"type":"REFUND",…,"payment":{…,"refunded_minor":1000,"state":"PARTIALLY_REFUNDED"}}

POST /v1/payments/{id}/captures   > {"amount_minor": 7000, "currency": "USD"}
< 409 {"type":"urn:payflow:problem:invalid-state-transition","status":409,
       "detail":"CAPTURE is not allowed while the payment is PARTIALLY_REFUNDED","code":"INVALID_STATE_TRANSITION"}

POST /v1/payments/{id}/voids
< 201 {"type":"VOID","amount_minor":6000,…,"payment":{"authorized_minor":10000,"captured_minor":4000,
       "refunded_minor":1000,"voided_minor":6000,"state":"PARTIALLY_REFUNDED"}}

GET /v1/payments/{id}/ledger  (capture 4000 at the default 250 bps fee)
< capture: DEBIT PSP_CLEARING 4000 | CREDIT MERCHANT_PAYABLE 3900 | CREDIT FEE_REVENUE 100
  refund:  DEBIT MERCHANT_RECEIVABLE 1000 | CREDIT REFUND_LIABILITY 1000
```

## Idempotency design

Goal: a client may retry any mutating request any number of times, after any failure, and the effect happens once.

- **The key is claimed in the same transaction as the work** (D11). A rollback, crash or 5xx removes the claim, so a
  key can never be "poisoned" by a request that did nothing.
- **Concurrent duplicates:** an uncommitted row is invisible to other transactions, so in-flight detection uses
  `pg_try_advisory_xact_lock(hash(merchant/key))`; the loser gets `409` + `Retry-After`. After commit a retry sees
  `COMPLETED` and gets the stored response back with `Idempotency-Replayed: true`.
- **Same key, different request → 422.** The fingerprint is SHA-256 of the canonical JSON (keys sorted, whitespace
  removed) plus method and path (D13).
- **4xx are stored and replayed; 5xx are not** (D12). Work runs in a savepoint so a deterministic client error leaves
  no partial writes but its answer is still replayable.
- **The PSP call is idempotent too:** its key derives from `merchant/key + operation type`, not from the operation id,
  so a transaction retry cannot send the PSP a second capture (bug-hunt finding 1).
- Keys live 24 h and are purged in batches (D14).

## Concurrency design

Every money-moving operation does the following inside one transaction:

1. **`SELECT … FOR UPDATE` on the payment row, before reading any balance.** This is what serializes writers on one
   payment: the second capture waits, then sees the updated counters and is rejected. Without it, two transactions
   read the same balance and both succeed (lost update / over-capture). The update also carries `WHERE version = ?`.
2. **`SERIALIZABLE` isolation.** The row lock covers the payment; SERIALIZABLE is the safety net for anything the lock
   does not cover (the idempotency table, account creation) by aborting any anomaly instead of committing it.
3. **Bounded retry with jittered backoff** on SQLState `40001`/`40P01` (default 8 attempts, full-jitter exponential).
   SERIALIZABLE makes aborts a normal outcome, so they must be retried; the bound and jitter prevent retry storms, and
   exhaustion is a `503` + `Retry-After`, never a 500 (D10).
4. Lock, statement and idle-in-transaction timeouts bound every wait so one stuck payment cannot drain the 20-connection
   pool (bug-hunt finding 2).
5. **A per-payment in-process queue (`HotKeyGate`) in front of all of the above.** Found by CI, not by me: on a
   GitHub runner, 500 concurrent captures of one payment produced a `503` (retry budget exhausted), and a second
   contended test did too. Cause: under SERIALIZABLE, every contender that runs `FOR UPDATE` on the same row also
   *reads* it, so contenders form read/write cycles with each other and PostgreSQL aborts many of them with `40001`;
   with hundreds of waiters a request can lose that lottery more often than the retry budget allows (locally about
   250 retries per 500 requests). Queueing requests for the same payment *before* they open a transaction removes the
   self-inflicted herd (0 retries in the same test). It is only an optimisation: the row lock, SERIALIZABLE and the
   retry remain the correctness mechanism, and the only one across several application instances, where this gate
   does not help (D30).

The ledger is append-only and balanced at COMMIT by a deferred constraint trigger, so even a bug in the application
cannot persist an unbalanced transaction (D7).

### Measured: 500 concurrent captures of one authorization

100 client threads, real HTTP and PostgreSQL 16. Required outcome: exactly one `201` and 499 `409`s, one capture
operation, `captured_minor` = authorized amount, ledger balanced. Raw data:
[`docs/benchmark-results.txt`](docs/benchmark-results.txt).

**Before the hot-key gate** (runs 1–6) every local run was exactly 1×201 / 499×409, but the first CI run on a GitHub
runner produced 1×201 / 498×409 / 1×503, so "green locally" was luck, not a guarantee.

| Run | Context | Result | Wall | p50 | p95 |
|---|---|---|---|---|---|
| 1 | in full suite, OWASP NVD download running | 1×201 / 499×409 | 1176 ms | 132 ms | 456 ms |
| 2 | in full suite, OWASP NVD download running | 1×201 / 499×409 | 1312 ms | 165 ms | 588 ms |
| 3 | in full suite, OWASP NVD download running | 1×201 / 499×409 | 1206 ms | 146.5 ms | 540.3 ms |
| 4 | single test alone (cold JVM), idle machine | 1×201 / 499×409 | 3268 ms | 397.8 ms | 1798.6 ms |
| 5 | single test alone (cold JVM), idle machine | 1×201 / 499×409 | 3052 ms | 365.5 ms | 1596.3 ms |
| 6 | in `mvn clean verify`, idle machine | 1×201 / 499×409 | 1336 ms | 160.5 ms | 521.9 ms |
| CI | GitHub runner, first run | 1×201 / 498×409 / **1×503** | 3154 ms | 473.6 ms | 1396.0 ms |

**After the hot-key gate** (inside `mvn clean verify`, idle machine, 0 transaction retries each time):

| Run | Result | Wall | p50 | p95 | max |
|---|---|---|---|---|---|
| 7 | 1×201 / 499×409 | 1998 ms | 386.3 ms | 504.7 ms | 518.5 ms |
| 8 | 1×201 / 499×409 | 1893 ms | 373.8 ms | 424.8 ms | 433.8 ms |
| 9 | 1×201 / 499×409 | 1920 ms | 393.9 ms | 438.5 ms | 443.8 ms |
| CI re-run 1 | 1×201 / 499×409 | 3306 ms | 594.1 ms | 801.4 ms | 814.9 ms |
| CI re-run 2 | 1×201 / 499×409 | 1989 ms | 355.4 ms | 537.6 ms | 583.8 ms |

Trade-off, stated plainly: requests for one payment are now processed one at a time, so total wall time for this
worst case went *up* (1.2–1.3 s in runs 1–3 and 6 → 1.9–2.0 s) and median latency rose about 2.5× (about 150 ms →
about 390 ms), while the tail
(p95 and max) dropped and the 503s disappeared. Requests for different payments are not affected. Runs 1–3 ran
under CPU contention from a dependency download, but run 6 on an idle machine was no faster, so contention was not a
significant factor. Runs 1–2 were recorded in an earlier session and could not be re-verified. Client, server and
database share one machine; this is a correctness test with a latency printout, not a tuned load test.

## Limitations and what I'd do next

- **OWASP dependency-check has not completed** — the NVD API returned 524 twice without a key. See `BLOCKERS.md`;
  CI runs it when `NVD_ENABLED`/`NVD_API_KEY` are configured.
- `X-Merchant-Id` is unauthenticated (a gateway is assumed); real auth is the first thing to add.
- The PSP is a simulator, and the call happens inside the DB transaction. With a real PSP I'd use an
  authorize-then-confirm outbox pattern so no connection is held across network I/O.
- Fees are not refunded; no multi-currency FX; a refund after any refund blocks further capture (D5, conservative).
- Advisory-lock hash collisions give a spurious `409` (safe, retried); SSRF check is vulnerable to DNS rebinding;
  a reconciliation run killed mid-way stays `RUNNING`; webhook secrets are stored in plaintext (see `docs/BUG-HUNT.md`).
- The hot-key gate is per process. With several instances, contenders on one payment from *different* instances still
  collide in the database and can exhaust retries (a `503` + `Retry-After`, never a wrong result); the real fix there
  is routing a payment's writes to one instance (consistent hashing) or a distributed lock.
- Single Postgres node, no load test beyond the one-hot-payment case; I'd add a mixed-workload benchmark across many
  payments, partitioning for the ledger and idempotency tables, and a manual reconciliation trigger with operator identity.
