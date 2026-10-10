# Contributing

Thanks for taking a look. I welcome changes that fix a real problem or make the existing behavior easier to understand.

Use Java 21 and the Maven Wrapper. Run `sh ./mvnw verify` before opening a pull request; on Windows use `.\mvnw.cmd verify`. Tests use isolated PostgreSQL and do not require Docker. The demo is a useful check when changing the HTTP delivery path.

Keep changes focused. Explain the behavior before and after the change, why the approach is appropriate, and which checks you ran. Add a meaningful test when changing a transaction, security boundary, queue transition, or public API contract. Avoid tests that only repeat a trivial implementation.

Java formatting is checked automatically during `verify`. Run `sh ./mvnw spotless:apply` to apply the configured four-space Google Java format before submitting changes; use `.\mvnw.cmd spotless:apply` on Windows.

Database changes need a new Flyway migration once a schema version has been released. Review how a migration affects active jobs and how the application will behave during a rolling update. Update the API guide and operational notes when their contracts change.

Use parameterised SQL, keep tenant scope explicit, and keep HTTP calls outside database transactions. Never commit credentials, event payloads from a real customer, or logs containing secrets. For suspected vulnerabilities, follow [SECURITY.md](SECURITY.md) instead of posting exploit details publicly.

The pull request template asks for operational impact as well as code changes. “None” is a useful answer when there is none.
