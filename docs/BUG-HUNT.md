# Adversarial review pass

After the build was green I re-read the whole codebase as a hostile reviewer, category by category,
backing the reading with greps (`@Transactional`, `FOR UPDATE`, `catch (`, floating point, secret
comparisons) and with SpotBugs at effort Max. Each finding below was reproduced or demonstrated, fixed, and
covered by a test. Categories with no finding say so and say why.

## Findings

### 1. The PSP call was not idempotent across transaction retries *(double-write, high)*
- **Where:** `PaymentService` called the PSP with the *operation id* as its idempotency key.
- **Failure scenario:** a capture hits a serialization failure (`40001`) after the PSP call but before commit.
  `RetryingTransactionRunner` re-runs the whole transaction, which generates a **new** operation id, so the
  retry sends the PSP a second capture under a different key. With a real PSP that is a duplicate capture even
  though our database stays correct. The simulator hid it because it never saw two calls.
- **Fix:** `IdempotentExecutor` publishes a request-scoped key (`IdempotencyContext`); the PSP key is
  `sha256(sha256(merchant/key) : operation type)`, identical on every attempt of the same logical request and
  different for different requests. `PspGateway` documents the contract.
- **Tests:** `IdempotencyFailureTest.exhaustedSerializationRetries...` (all 12 attempts use one key) and
  `theSamePspKeyIsUsedForTheSameRequestAndADifferentOneForADifferentRequest` (replays never reach the PSP).

### 2. No bound on lock, statement or idle-in-transaction waits *(resource exhaustion, high)*
- **Where:** connection setup. A transaction holding a payment row lock (for example stuck in the PSP call)
  would make every other capture/refund/void on that payment wait forever; each waiter pins one of the 20
  pooled connections, so one stuck payment can drain the pool and take the whole service down.
- **Fix:** Hikari `connection-init-sql` sets `lock_timeout=10s`, `statement_timeout=30s` and
  `idle_in_transaction_session_timeout=60s`. A lock timeout (`55P03`) is a `ConcurrencyFailureException` so it is
  retried and then surfaces as `503`; statement timeouts and pool exhaustion
  (`TransientDataAccessException`/`DataAccessResourceFailureException`) are mapped to `503 SERVICE_BUSY` +
  `Retry-After` instead of a generic `500`.
- **Tests:** `OperationalTest.everyConnectionBoundsLockStatementAndIdleTransactionWaits`,
  `IdempotencyFailureTest.aStatementTimeoutIsA503NotA500AndLeavesNoTrace`.

### 3. `TransactionTemplate.execute` can return `null` and two call sites dereferenced it *(null handling, medium)*
- **Where:** `IdempotentExecutor.executeOnce` (the work's response) and `ReconciliationService.run` (the run
  summary). SpotBugs `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`.
- **Fix:** an explicit `requireNonNull` with a message in the executor; reconciliation body extracted to a method
  and the null case raises an `IllegalStateException` that marks the run `FAILED`.

### 4. Value records leaked mutable collections *(aliasing, low)*
- **Where:** `Problem`, `LedgerView`, `LedgerTransactionView`, `RunDetail`, two response DTOs and
  `PayflowProperties.supportedCurrencies` stored and returned caller-owned `List`/`Set` instances (SpotBugs
  `EI_EXPOSE_REP/REP2`). A mutation after construction would have changed a stored idempotent response or the
  set of supported currencies at runtime.
- **Fix:** compact constructors copy with `List.copyOf`/`Set.copyOf`. `LedgerRepository` was constructing a
  view with a `null` list and patching it later; it now builds each view once.

### 5. Webhook dispatcher queried the endpoint once per delivery *(N+1, low)*
- **Fix:** one lookup per merchant per batch (`Map<merchant, Optional<Endpoint>>`).

### 6. `HttpClient` in the webhook dispatcher was never closed *(resource leak, low)*
- **Fix:** `@PreDestroy` closes it (JDK 21 `HttpClient` is `AutoCloseable`).

### 7. Two flaky-by-construction webhook tests *(race in tests)*
- **Where:** `OutboundWebhookTest` left `PENDING` deliveries behind. A later test's `dispatcher.processDue()`
  could claim another test's rows, and one assertion ("nothing is due") depended on a full-jitter draw that is
  `~0` with probability 1/30000 per run.
- **Fix:** each test parks its leftovers (`next_attempt_at = +30 days`) in `@AfterEach`; the assertion now pins the
  schedule explicitly. A lease test claims through the repository so it is deterministic.

### Found by the test suite while building (fixed before the review pass, listed for honesty)
- The advisory-lock scope string contained a NUL byte, which PostgreSQL rejects (`0x00` in a text parameter); every
  mutating request returned `500` until the first end-to-end run. The scope is now `merchant/key` (merchant ids
  cannot contain `/`).
- Jackson silently coerced `{"reason": 7}` to the string `"7"`; strict coercion rules now reject it
  (`EdgeCaseTest.voidAcceptsAnEmptyBody...`).

## Categories reviewed with no finding

| Category | Result and why |
|---|---|
| TOCTOU between read and write | None found. Capture, refund and void read balances only from the row returned by `SELECT ... FOR UPDATE`; the update also carries `WHERE version = ?`. 500-way, 60-way and 40-way races in `ConcurrencyTest`/`CaptureBenchmarkTest` end with counters, operations and ledger in exact agreement. |
| Missing `FOR UPDATE` | None. `grep FOR UPDATE` shows the payment lock plus two `SKIP LOCKED` queue claims. No money path reads a balance any other way. |
| Transaction boundaries | None. The idempotency claim, the work, the response storage, the ledger postings and the outbox row are one transaction; verified by rollback tests (crash, 503, 5xx all leave no key, operation, ledger or event). |
| `@Transactional` self-invocation | Not applicable: there is no `@Transactional` anywhere (`grep` is empty); transactions are programmatic (D9). |
| Lost updates | None: row lock plus version check; `PaymentRepository.update` throws if it ever touches 0 rows. |
| Money arithmetic | None. No `double`/`float`/`BigDecimal` in main code. Sums use `Math.addExact`; remainders use subtraction; fee computation is proven equal to a `BigInteger` oracle across 5,000 random and edge inputs including `Long.MAX_VALUE`; DB checks widen to `NUMERIC`. |
| Unbounded retries | None: transaction retries bounded (8, 12 in tests) and tested; webhook attempts capped at 8 then dead-lettered; reconciliation and purge loops terminate on short pages. |
| Non-constant-time secret comparison | None: webhook HMAC uses `MessageDigest.isEqual`; the admin token is hashed and then compared with `isEqual`; `grep` finds no `equals` on secrets. |
| Exception swallowing | None harmful. Every `catch` either rethrows a typed error, records the failure (delivery attempt, discrepancy, FAILED run) or is a documented fallback (`RequestFingerprint` for non-JSON bodies). |
| Duplicate request double-writes | None found after finding 1: client retries are absorbed by the idempotency table, in-flight duplicates by the advisory lock, concurrent identical webhooks by the unique event id (`concurrentDuplicatesOfOneEventApplyExactlyOnce`), concurrent dispatchers by `SKIP LOCKED` + lease. |
| N+1 queries | Only finding 5. Ledger and reconciliation reads are single joined/`ANY(?)` queries. |
| Flaky time-dependent assertions | Only finding 7. Other tests use fixed clocks, DB-side timestamps in the past, or wide windows (`between(now+23h, now+25h)`). |

## Known weaknesses I chose not to fix (also in the README)
- `X-Merchant-Id` is unauthenticated; anyone who can reach the API can create idempotency rows for arbitrary
  merchant ids.
- The advisory lock hashes `merchant/key` to 64 bits; a collision would produce a spurious `409 REQUEST_IN_PROGRESS`
  (safe: the client retries), never a wrong replay.
- The SSRF check resolves the host once; DNS rebinding between validation and the connection is not prevented.
- A reconciliation run whose process dies stays `RUNNING` until someone notices; there is no stale-run reaper.
- Merchant webhook signing secrets are stored in plaintext (needed for HMAC); they should be envelope-encrypted.
