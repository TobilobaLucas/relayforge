# Verification evidence

The build and original local demo ran on 2026-10-07; the Python demo ran on 2026-10-10. These are checks I actually ran, with their limits noted below.

## Environment

| Component | Version |
| --- | --- |
| Operating system | Windows, x86-64 |
| Java | Eclipse Temurin 21.0.12.1 |
| Maven | 3.9.16 |
| Spring Boot | 3.5.16 |
| Test database | Native PostgreSQL 17.10, isolated and started by Zonky |

The development commands used a workspace-local Maven cache at `.local/m2`. It is ignored by Git and is not required by the project.

## Build and automated tests

```powershell
mvn.cmd -U -B -ntp "-Dmaven.repo.local=.local/m2" verify
```

Result: **BUILD SUCCESS — 83 tests, zero failures, zero errors, zero skipped.** The suite includes 24 application integration tests and 59 unit or parameterised cases.

The build produced `target/relayforge-1.0.0.jar`, JUnit reports in `target/surefire-reports`, and a JaCoCo report in `target/site/jacoco`. Aggregate line coverage for this run was **89.9%**. The build also passed the Java formatting check.

The packaged jar was inspected: the disposable launcher, demo receiver, JUnit, and embedded PostgreSQL dependencies are absent. Production database access uses the normal PostgreSQL JDBC driver.

## Maven Wrapper

```powershell
.\mvnw.cmd -v
```

Result: the generated Maven Wrapper resolved Maven 3.9.16 and ran with Java 21. The Unix workflow invokes it with `sh` so its operation does not depend on executable file mode being preserved by a Windows upload.

## Live HTTP demonstration

The generated `.env` contained three distinct credential entries. The local launcher started PostgreSQL, applied Flyway’s initial migration, started Tomcat, and served readiness as `{"status":"UP"}`.

```powershell
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=.local/m2" -Plocal-demo test-compile exec:java
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo.ps1 -ReceiverEndpoint http://localhost:9000
```

Observed outcomes:

- A repeated submission returned the same event ID with `duplicate:true`.
- The flaky receiver returned two failures, then a success. Attempt history was `1:FAILED, 2:FAILED, 3:SUCCEEDED`.
- The rejecting receiver caused an immediate dead letter. After changing the receiver to accept work and replaying, history was `1:FAILED, 2:SUCCEEDED`, with one replay recorded.
- The receiver verified the delivery signatures over the raw bodies.
- The demo script exited successfully with “Demo passed.”

This demonstration used the Java-only local receiver and the PowerShell client.

On 2026-10-10, I also ran `scripts/demo.py` against the Java local launcher and the Python receiver from `examples/receiver.py`, listening on port 9001. The Python client exited with `Demo passed.` It observed the same idempotency, third-attempt success, dead-letter, and replay outcomes. The receiver logged `signatureVerified: true` for all five HTTP deliveries. This checked the Python demo path locally; it did not run the complete Compose stack.

## Repository checks

Application YAML, Compose YAML, Prometheus configuration, alert rules, Dependabot, workflows, and the issue template parsed successfully with SnakeYAML using duplicate-key rejection. Local Markdown links were checked for existing targets. These checks do not substitute for the container runtime or GitHub Actions schema validation.

## Checks still requiring their target environment

Docker was not installed locally, so the image build, complete Compose demo, and Prometheus scrape were not executed here. The CI workflow defines the Compose build and Python demo job for a Docker-capable GitHub runner. GitHub Actions, CodeQL, and Dependabot have not run before this repository is uploaded and those services are enabled.

No load benchmark, cloud deployment, database failover experiment, or independent security audit was performed. The project makes no measured throughput or availability claim.
