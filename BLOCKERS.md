# Blockers

## 1. OWASP dependency-check did not complete (NVD download failed)

**Status: not run to completion. No vulnerability result exists; none is claimed.**

Command used (no `NVD_API_KEY` was available):

    mvn -B -ntp -Psecurity org.owasp:dependency-check-maven:check

- Attempt 1 (2026-11-12): downloaded 400,000 of 424,635 NVD records (94%) in 37 minutes, then the NVD API
  answered `Status Code: 524` (gateway timeout). Result: `UpdateException: Error updating the NVD Data` /
  `NoDataException: No documents exist`, build failed.
- Attempt 2 (immediately after): restarted from 0, got to 10,000 records, failed again with the same 524 after 3.5 minutes.
- Cause: unauthenticated NVD API access is heavily rate-limited and the service was returning 524s.
  The gate is **not** dropped: it stays configured (`-Psecurity`, fail on CVSS >= 7) and in CI as the
  `dependency-check` job, which runs once the repo variable `NVD_ENABLED=true` and secret `NVD_API_KEY` are set.

To finish it later (free key: https://nvd.nist.gov/developers/request-an-api-key):

    export NVD_API_KEY=<your key>
    mvn -B -ntp -Psecurity org.owasp:dependency-check-maven:check
    # report: target/dependency-check-report.html

## 2. The `dependency-check` CI job is skipped on purpose

The job in `.github/workflows/ci.yml` has `if: vars.NVD_ENABLED == 'true'` and shows as *skipped* in every run.
Reason: without an `NVD_API_KEY` secret the NVD download is rate-limited and failed twice locally (see 1). It is not
a hidden pass; there is no vulnerability result until it runs. To enable it: add the repository secret `NVD_API_KEY`
and the repository variable `NVD_ENABLED=true`.

## 3. `.github/workflows/ci.yml` was not modified in the final session

The available credential could not update workflow files (no `workflow` scope) and nothing required it.
