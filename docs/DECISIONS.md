# Architecture decisions

ADR-style, one decision per entry. Format: **Decision** - rationale.

## Persistence and data model

**D1. Plain JDBC (`JdbcClient`) instead of JPA/Hibernate.**
Correctness here lives in SQL: `SELECT ... FOR UPDATE`, `ON CONFLICT`, `SKIP LOCKED`, advisory locks,
`SERIALIZABLE` retries. An ORM would hide exactly the statements that must be reviewed, add a
first-level cache that can serve stale balances after the row lock, and make N+1 queries easy.
The brief lists "JPA repos" under infrastructure; explicit SQL repositories fill the same role and keep the
domain free of persistence annotations.

**D2. Money is `long` minor units end to end; `BigDecimal`/`double` appear nowhere in money code.**
Arithmetic goes through `Amounts` (`Math.addExact`, overflow-safe fee). Remainders are computed by
*subtraction from the authorization* (`authorized - captured - voided`), so no sum of two counters can
overflow even at `Long.MAX_VALUE`. The only `BigInteger` is in a test oracle.

**D3. The payment state is derived, never stored independently.**
`PaymentStateMachine.derive(authorized, captured, refunded, voided)` is the single source of truth; the
`state` column is a cache of it. A property test checks every valid counter combination. This removes the
"state and amounts disagree" bug class.

**D4. Added a `voided_minor` counter and a `fee_minor` column.**
"Void after partial capture" and "void the remainder after a partial refund" need to record how much
authorization was released. `fee_minor` is stored per capture so reconciliation can recompute expected
ledger positions without depending on the current fee configuration.

**D5. State rules.** Capture: AUTHORIZED, PARTIALLY_CAPTURED. Refund: PARTIALLY_CAPTURED, CAPTURED,
PARTIALLY_REFUNDED. Void: AUTHORIZED, PARTIALLY_CAPTURED, PARTIALLY_REFUNDED (releases the uncaptured
remainder; `NOTHING_TO_VOID` if there is none). Once any refund exists no further capture is accepted.
This is the conservative reading of the brief's happy path (authorize -> partial capture -> partial
refund -> void remainder) and makes refund-before-capture, capture-after-void and double-void impossible.

**D6. Ledger postings.** Capture A with fee F: `DR PSP_CLEARING A | CR MERCHANT_PAYABLE A-F | CR FEE_REVENUE F`.
Refund R: `DR MERCHANT_RECEIVABLE R | CR REFUND_LIABILITY R`. Authorizations and voids move no money and post
nothing. Fees are not refunded (documented limitation). Balances are computed from entries, never stored,
so there is no balance row to contend on or to drift.

**D7. Ledger integrity is enforced in PostgreSQL.**
A *deferred* constraint trigger checks `sum(debits) = sum(credits)` (and >= 2 entries) at COMMIT, on both
`ledger_entries` and `ledger_transactions` (so an empty header is caught too). `BEFORE UPDATE/DELETE/TRUNCATE`
triggers make the ledger append-only. Sums are `NUMERIC` so the check itself cannot overflow.

**D8. UUID v7 generated in the application** (`Uuid7`) for payments, operations and ledger transactions:
time-ordered keys keep B-tree inserts local and double as a coarse creation order.

## Concurrency and idempotency

**D9. Programmatic transactions, no `@Transactional`.**
`RetryingTransactionRunner` and `TransactionTemplate` make the boundary explicit at the call site. There is
no proxy, so the "`@Transactional` on a self-invoked method" bug class cannot exist. Money-moving service
methods assert they are inside a read-write transaction and refuse to run otherwise.

**D10. SERIALIZABLE + row lock + bounded jittered retry.**
Money-moving methods `SELECT ... FOR UPDATE` the payment row *before* reading any balance, inside a
SERIALIZABLE transaction wrapped by a retry loop on SQLState `40001`/`40P01` (and `TransientConflictException`).
Retries are bounded (default 8), use full-jitter exponential backoff, and exhaustion is a `503` with
`Retry-After`, never a `500`. The row lock is what serializes writers; SERIALIZABLE is the safety net for
anything that is not covered by that lock (e.g. the idempotency table, account creation).

**D11. Idempotency claim lives in the work transaction; in-flight detection uses an advisory lock.**
The `IN_FLIGHT` row is inserted in the same transaction as the work, so a rollback (5xx, crash, lost
connection) removes it and the key is never poisoned. Because an uncommitted row is invisible to other
transactions, concurrent duplicates are detected with `pg_try_advisory_xact_lock(hash(merchant/key))`: a
request that cannot take the lock gets `409` + `Retry-After`. A request arriving after commit sees a
`COMPLETED` row and replays. A unique-violation against a row committed after our snapshot is converted to a
retry, which then becomes a replay.

**D12. 4xx are stored and replayed; 5xx are not.**
The work runs in a savepoint (`PROPAGATION_NESTED`). A deterministic client error rolls the savepoint back
(no partial writes) and the problem+json response is stored against the key. Anything else aborts the whole
transaction. Validation failures (400) are stored too, because parsing happens inside the idempotent unit.

**D13. Request fingerprint = SHA-256 of the canonical JSON body** (keys sorted recursively, whitespace
removed), compared together with `METHOD + path`. Same key + different payload or endpoint -> `422
IDEMPOTENCY_KEY_REUSED`. Non-JSON bodies fall back to hashing the raw bytes.

**D14. Key TTL is 24 hours** (`payflow.idempotency.ttl`): long enough for client retry windows, short enough to
bound table growth. Expired rows are treated as absent and purged in batches (`SKIP LOCKED`) by a scheduled job.

**D15. Merchant identity is the `X-Merchant-Id` header.** Authentication is out of scope for the MVP; the
header models what a trusted API gateway would inject. Every payment query is scoped by merchant, so a
payment of another merchant is a `404`. Operator endpoints use a separate admin token.

**D16. The PSP is a port (`PspGateway`) with a deterministic simulator.** Tokens `tok_decline` and
`tok_psp_down` select outcomes so decline (4xx, stored) and outage (5xx, retryable) are testable end to end.
The call happens inside the transaction; this is acceptable only because the simulator is in-process. A real
adapter would use the operation id as the PSP-side idempotency key (the retry wrapper can re-run the call).

## API

**D17. Strict request parsing.** Unknown fields, duplicate keys, quoted numbers, fractional numbers for
integer fields and numbers-as-strings are all rejected, so `"amount_minor": 10.5` can never become `10`.
Bodies above 64 KiB are refused with `413` before any handler runs.

**D18. Error contract.** RFC 9457 `application/problem+json` with a stable `code` and a `urn:payflow:problem:*`
type. A global `@RestControllerAdvice` maps every exception; unexpected errors return a generic body and the
stack trace goes only to the log.

**D19. Capture/refund/void are `POST` sub-resources returning `201`** with the created operation and the
resulting payment. A conflict (over-capture, wrong state, ...) is `409`; currency mismatch and key reuse are `422`;
malformed input is `400`.

## Webhooks and reconciliation

**D20. Inbound webhooks are authenticated, not idempotency-keyed.** HMAC-SHA256 over `"<t>." + raw bytes`
(captured in a filter before deserialization), 5-minute tolerance on the signed timestamp, constant-time
compare (`MessageDigest.isEqual`). Dedup is a unique constraint on the PSP event id with `ON CONFLICT DO NOTHING`,
so concurrent duplicates apply once. Failures are `401` with no state change and a warn log naming only the
reason class.

**D21. Outbound deliveries use a transactional outbox** written in the payment transaction (an event exists iff
the change committed). The dispatcher claims rows with `FOR UPDATE SKIP LOCKED` and a lease, calls the receiver
*outside* any transaction, then records the result. Delivery is at-least-once and receivers must be idempotent
on `PayFlow-Event-Id`. Backoff is exponential with full jitter (30s base, 1h cap), 8 attempts, then the
dead-letter table. Targets are validated against SSRF (https only, no private/loopback/link-local/ULA/CGNAT
addresses) unless explicitly relaxed.

**D22. Reconciliation runs in one REPEATABLE READ snapshot** so a payment being modified mid-run is never seen
half-written and falsely reported. It compares the payment counters with its operations and the net position of
each ledger account with the postings those operations imply. Operations the PSP later reported as failed are
excluded from the expectation. Operator endpoints are protected by an admin token compared in constant time.

## Operations and build

**D23. Pool of 20 connections** (`PAYFLOW_DB_POOL_SIZE`). Rule of thumb for a small Postgres: ~2 x cores +
effective spindles. Transactions hold a connection for a few milliseconds; a larger pool only increases lock
and SSI-conflict contention on hot payment rows. `minimum-idle` equals the maximum so the pool never resizes
under load.

**D24. Config via environment variables; no secrets in the repo.** The app refuses to start without
`PAYFLOW_DB_PASSWORD`, `PAYFLOW_ADMIN_TOKEN` and `PAYFLOW_PSP_WEBHOOK_SECRET` (the latter two >= 24 chars).
`.env.example` documents them; `.env` is git-ignored.

**D25. Quality gates.** `-Xlint:all -Werror`, Spotless (google-java-format), SpotBugs at effort Max / threshold
Medium, and a JaCoCo gate of 85 % line coverage all run in `mvn verify`. SpotBugs `EI_EXPOSE_REP2` is suppressed
only for constructor-injected Spring singletons (see `spotbugs-exclude.xml`); value records are defensively
copied instead.

**D26. OWASP dependency-check is a Maven profile (`-Psecurity`)** rather than part of the default build: it needs
the multi-hundred-MB NVD database, which makes an offline or first-time `mvn verify` impractical. CI runs it
with an `NVD_API_KEY`.

**D27. Tests share one PostgreSQL container and isolate by merchant id**, never by truncating tables: the ledger
is append-only by design, and truncation would also mask cross-test interference bugs.

**D28. Test runner: Surefire only (no Failsafe).** All tests, including Testcontainers ones, run in `test` so the
JaCoCo agent sees them and one command reports one result.

**D29. Reconciliation is exposed read-only.** A manual trigger endpoint would itself be a mutating endpoint
requiring idempotency semantics and an operator identity model; it is listed under "what I'd do next".

**D30. Per-payment in-process queue (`HotKeyGate`) before the transaction.** Added after the first CI run
failed: with 500 concurrent captures of one payment, SERIALIZABLE aborted contenders against each other (every
`SELECT ... FOR UPDATE` also reads the row, so contenders form read/write cycles) and some exhausted the retry
budget, giving `503`s. Rejected alternatives: more retries (hides the herd, still probabilistic), switching to
READ COMMITTED (would remove the SERIALIZABLE safety net described in D10). The gate is a fixed array of 1024 fair
locks keyed by payment id; it only reorders work inside one JVM and never affects correctness, which still rests on
the row lock, SERIALIZABLE and the bounded retry. Limitation: it does nothing across instances. Cost: requests for
one payment are processed one at a time (see the README benchmark trade-off).

**D31. The hot-key gate admits at most 50 requests per stripe (running + waiting); the 51st gets an immediate
`503` + `Retry-After: 1`.** Without a cap, one hot payment could park up to all 200 Tomcat request threads for up to
the 20 s wait, stalling unrelated payments and health checks. Why 50:
- *Drain time.* A capture holds the gate for roughly 4 ms in the benchmark (500 serialised requests in about 2 s), so
  a full queue of 50 drains in about 200 ms, far below the 20 s wait limit and below typical client timeouts. Every
  admitted request therefore completes quickly.
- *Thread budget.* 50 is a quarter of the 200-thread pool, so one payment can never take more than 25 % of the request
  threads (a few unlucky payments sharing a stripe still count as one stripe), leaving at least 150 for everything else.
- *Not tuned to a benchmark.* N was fixed from the two points above before measuring. The benchmark has 100 client
  threads hitting one payment, so with N = 50 about half the in-flight requests are rejected on arrival, and since
  rejections return immediately and the load generator does not back off, most of the 499 losing requests become `503`
  rather than `409` (about 425 of 499 in the measured runs). I kept N and changed the benchmark's expectation instead
  (README, "Measured").
The counter is an `AtomicInteger` per stripe: incremented first, and decremented in exactly one `finally` (or
immediately on rejection), so no exit path (work exception, timeout, interrupt, rejection) can leak a slot. This is
still a per-process mechanism; it does not coordinate several instances.

**D32. Inbound PSP events that match no operation are retried by a sweeper, then abandoned visibly.** The PSP is
called inside our payment transaction, so its event can arrive before that transaction commits. We answer `200`
(`unmatched`), so a well-behaved PSP never re-sends, and the stored event used to be dead weight. A scheduled
`UnmatchedEventSweepJob` (every 30 s) now re-applies stored `UNMATCHED` events from their saved payload.
- *Why a separate sweeper, not part of the nightly reconciliation run:* reconciliation is a read-only, once-a-day
  comparison inside one REPEATABLE READ snapshot; making it write `psp_status` would make it mutate the data it is
  judging, and a 24 h latency would leave a failed capture undetected all day. The sweep is a few indexed rows
  (`webhook_events_unmatched_idx`) and runs on the same scheduler as the dispatcher and purge job.
- *Race-safety:* candidates are taken with `FOR UPDATE SKIP LOCKED`, the same row lock a redelivery takes
  (`lockUnmatchedEvent`). Whoever holds it applies the event and sets `PROCESSED`; the other skips (sweeper) or sees a
  non-`UNMATCHED` row and answers `duplicate` (redelivery). Several instances can sweep concurrently.
- *Bounded:* after `payflow.webhook.unmatched-retention` (24 h) the event becomes `ABANDONED`: kept with its payload,
  logged at ERROR, counted in `payflow.webhook.inbound.abandoned`, and never retried or resurrected by a later
  redelivery. Limitation: a sweep handles at most 500 events, oldest first, so more than 500 permanently unmatched
  young events would delay newer ones until the old ones age out.
