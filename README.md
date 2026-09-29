# Transaction Currency Converter

A REST application for the **Corporate Payments Product Brief**. It stores purchase transactions in USD and converts them into a country's currency using historical U.S. Treasury exchange rates.

**Stack:** Java 21, Spring Boot 3.5.16, Spring MVC, Bean Validation, Spring Data JPA, PostgreSQL 17, Liquibase, Spring RestClient, Actuator, springdoc-openapi 2.8.17 / Swagger UI, Lombok, Maven, JUnit 5, Mockito, and Testcontainers.

## Quick start

Prerequisites: Docker Engine or Docker Desktop with Linux containers and Docker Compose v2. Java and Maven are not required on the host.

```bash
docker compose up --build -d --wait
```

The application is available at http://localhost:8080. Check readiness at http://localhost:8080/actuator/health/readiness.

Compose waits for PostgreSQL to become healthy. Liquibase applies the initial migration at application startup, then Hibernate validates the schema. The image build compiles the application and test sources but skips execution of the controller tests, which need a Docker engine for Testcontainers. Run the test suite separately before deploying.

```bash
docker compose logs -f app
docker compose down
```

The `postgres-data` volume preserves transactions across restarts. **`docker compose down -v` deletes the database volume.**

Copy `.env.example` to `.env` to customize credentials and host ports. Defaults are `transactions` for the database name, username, and password; they are intended for local development. The `.env` file is excluded from Git. Access to the Treasury API over HTTPS is required for conversions; no API key is needed.

## API

| Method | Path | Result |
|---|---|---|
| `POST` | `/api/v1/transactions` | Store a transaction; `201 Created` with a transaction DTO |
| `GET` | `/api/v1/transactions/{id}` | Retrieve the stored USD transaction |
| `GET` | `/api/v1/transactions/{id}/conversion?currency=CANADA_DOLLAR` | Retrieve the transaction with its exchange rate and converted amount |
| `GET` | `/actuator/health/readiness` | Application and database readiness |
| `GET` | `/actuator/health/liveness` | Process liveness |

The controller declares `/api/v1/transactions` at class level and resource-specific suffixes on its mapping annotations. The POST method returns `TransactionResponse` directly and sets HTTP 201 with `@ResponseStatus`; it does not return a `ResponseEntity` or a `Location` header.

### Swagger / OpenAPI

Open [Swagger UI](http://localhost:8080/swagger-ui/index.html) after starting the application. It documents all three transaction endpoints listed above, including request validation, both accepted currency formats, response schemas, and error responses. Use **Try it out** to create a transaction, copy the returned ID, then retrieve or convert it. Creating a transaction persists it; conversion calls Treasury.

| Method | Path | Result |
|---|---|---|
| `GET` | `/swagger-ui.html` | Redirect to Swagger UI |
| `GET` | `/swagger-ui/index.html` | Interactive API documentation |
| `GET` | `/v3/api-docs` | OpenAPI document in JSON |
| `GET` | `/v3/api-docs.yaml` | OpenAPI document in YAML |
| `GET` | `/v3/api-docs/swagger-config` | Configuration loaded by Swagger UI |

The [OpenAPI contract](docs/openapi.yaml) is packaged into the application and loaded by `OpenApiConfiguration`. Springdoc publishes it together with the controller mappings. Its relative server URL uses the current host and port, including custom Docker port mappings. The contract can also be imported into Postman or Swagger Editor. Documentation is available in both profiles; Actuator endpoints are listed in this README separately from the transaction API.

### Create a transaction

```bash
curl -i http://localhost:8080/api/v1/transactions \
  -H 'Content-Type: application/json' \
  -d '{"description":"Business lunch","transactionDate":"2024-06-30","amountUsd":10.005}'
```

Example response (the server generates the UUID):

```json
{
  "id": "6b494e4a-9d3d-4af5-8f70-c114cbfabf64",
  "description": "Business lunch",
  "transactionDate": "2024-06-30",
  "amountUsd": 10.01
}
```

### Retrieve and convert

Replace the example UUID with the one returned by POST:

```bash
curl http://localhost:8080/api/v1/transactions/6b494e4a-9d3d-4af5-8f70-c114cbfabf64

curl --get \
  http://localhost:8080/api/v1/transactions/6b494e4a-9d3d-4af5-8f70-c114cbfabf64/conversion \
  --data-urlencode 'currency=CANADA_DOLLAR'
```

The exact Treasury name, such as `currency=Canada-Dollar`, is also accepted. Responses always use the Treasury name:

```json
{
  "id": "6b494e4a-9d3d-4af5-8f70-c114cbfabf64",
  "description": "Business lunch",
  "transactionDate": "2024-06-30",
  "amountUsd": 10.01,
  "currency": "Canada-Dollar",
  "exchangeRate": 1.37,
  "exchangeRateDate": "2024-06-30",
  "exchangeRateEffectiveDate": "2024-06-30",
  "convertedAmount": 13.71
}
```

PowerShell example:

```powershell
$transaction = Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/v1/transactions' `
  -ContentType 'application/json' `
  -Body '{"description":"Business lunch","transactionDate":"2024-06-30","amountUsd":10.005}'

Invoke-RestMethod -Uri "http://localhost:8080/api/v1/transactions/$($transaction.id)/conversion?currency=CANADA_DOLLAR"
```

## Validation and conversion rules

- `description` is required, must contain a non-whitespace character, and is limited to 50 characters. U+0000 is rejected because PostgreSQL text columns cannot store it. The submitted text is preserved.
- `transactionDate` must be a JSON string containing a real calendar date in `yyyy-MM-dd` format, from `0001-01-01` through `9999-12-31`, without a time component or surrounding whitespace. Arrays, timestamps, extended years, and invalid dates such as `2024-02-30` are rejected. Future dates are allowed because the brief does not prohibit them. Hibernate uses JDBC 4.2 `LocalDate` binding to preserve historical dates without a Julian/Gregorian calendar conversion.
- `amountUsd` is required and is rounded to cents using `HALF_UP` before persistence: `10.005` becomes `10.01`. It must remain positive after rounding, so input below `0.005` is rejected. The maximum input is `99999999999999999.99`, matching `NUMERIC(19,2)` storage.
- All monetary calculations use `BigDecimal`. The exchange rate is not rounded before multiplication.
- Each transaction receives a UUID. Repeated POST requests create distinct transactions; business-key idempotency is outside the brief.
- `currency` is represented by the shared [Currency enum](src/main/java/com/example/currencyconverter/model/Currency.java) throughout the controller, service, and client contracts. Query parameters accept enum constants such as `CANADA_DOLLAR` or exact Treasury names such as `Canada-Dollar`. ISO codes such as `CAD` are not accepted because the data source identifies country-currency pairs.
- The enum includes the 265 distinct country-currency names in the Treasury catalog snapshot retrieved on September 22, 2026, including historical currencies. To support a newly published currency, add its enum entry and update the OpenAPI catalog. Runtime requests never load or mutate this catalog.
- An unknown currency is a `400` validation error. A recognized currency without an eligible rate is a `422` conversion error.
- The eligible interval is **`[transactionDate.minusMonths(6), transactionDate]`**, inclusive. This means six calendar months, not 180 days. For `2024-08-31`, the lower bound is `2024-02-29`.
- Treasury `record_date` is the exchange-rate date. The client filters by the interval, sorts by descending `record_date` then `effective_date`, and requests the first result. It also requires `effective_date <= transactionDate` to exclude later amendments. Both dates are returned.
- The conversion formula is `storedAmountUsd × exchangeRate`, rounded to two decimal places using `HALF_UP`. A converted amount rounded to zero is allowed.
- Unknown or duplicate JSON fields, trailing JSON values, and invalid data types are rejected. Descriptions must be JSON strings and amounts must be JSON numbers; numeric strings are not accepted as amounts. These strict rules apply to `CreateTransactionRequest`, while the Treasury client still accepts the upstream API's string-encoded rates. Only supported enum values reach the Treasury client, preventing callers from injecting filter conditions.

Sources: [Treasury Reporting Rates of Exchange](https://fiscaldata.treasury.gov/datasets/treasury-reporting-rates-exchange/treasury-reporting-rates-of-exchange) and [Fiscal Data API documentation](https://fiscaldata.treasury.gov/api-documentation/).

## Error responses

Errors use `application/problem+json`, with `type`, `title`, `status`, `detail`, `instance`, and a machine-readable `code`. All codes are defined in the shared [ErrorCode enum](src/main/java/com/example/currencyconverter/exception/ErrorCode.java); `ApiExceptionHandler` uses enum values rather than string literals, including for framework validation errors. Request-body validation errors also include an `errors` array with field names and messages.

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `VALIDATION_ERROR` / `INVALID_REQUEST` | Invalid body, date, amount, UUID, or currency |
| 404 | `TRANSACTION_NOT_FOUND` | No transaction with the requested ID |
| 422 | `EXCHANGE_RATE_NOT_FOUND` | No eligible rate for the requested currency and date |
| 502 | `TREASURY_INVALID_RESPONSE` | Malformed data or an unexpected rejection from Treasury |
| 503 | `TREASURY_UNAVAILABLE` | Timeout, connection failure, or Treasury HTTP 429/5xx |
| 500 | `INTERNAL_ERROR` | Unexpected server failure; internal details are logged, not exposed |

## Testing

### Full test suite in Docker

```bash
docker compose --profile test run --build --rm tests
```

This runs the controller integration suite with the `local` profile only. Testcontainers creates and removes its own PostgreSQL 17 container; the application service and its database are not used. The test runner accesses the host Docker engine through `/var/run/docker.sock`, with `host.docker.internal` used for mapped container ports. This Compose runner requires a Linux-container Docker engine with that socket available; running the Maven Wrapper directly is also supported on Windows with Docker Desktop.

### Local tests with Java 21

The Maven Wrapper downloads Maven on its first invocation:

```bash
# All controller tests with the local profile; Docker must be running
./mvnw test

# The same tests, followed by packaging and the remaining build checks
./mvnw verify
```

On Windows, use `.\mvnw.cmd test` and `.\mvnw.cmd verify`.

All tests use `@SpringBootTest(RANDOM_PORT)`, `@Testcontainers`, `@Container`, and Spring Boot `@ServiceConnection`. They cannot be redirected to an external database using environment variables. Docker must be running; both `test` and `verify` fail rather than silently skipping tests when Docker is unavailable. To compile and package without executing tests, use `./mvnw -DskipTests package`.

Every scenario sends HTTP requests through `TestRestTemplate` to the application. Business scenarios exercise `TransactionController`; documentation scenarios exercise Swagger UI and OpenAPI endpoints. Transactions are created through POST and retrieved through GET; tests do not invoke the service or Treasury client directly, execute SQL, or inspect configuration objects. All scenarios live in [TransactionControllerTest](src/test/java/com/example/currencyconverter/controller/TransactionControllerTest.java), which selects the `local` profile with `@ActiveProfiles("local")` and runs with a Spring context and Testcontainers database. Tests use independent transaction IDs, so they do not need database cleanup queries.

The HTTP requests exercise the real controller, service, repository, PostgreSQL, and Liquibase. The sole repository spy arranges a database failure for the HTTP 500 scenario; the request and assertions still go through the controller. Treasury requests are redirected by test configuration to [TreasuryApiStub](src/test/java/com/example/currencyconverter/support/TreasuryApiStub.java), a JUnit extension bound to `127.0.0.1` on a random port. It owns its server lifecycle, resets responses between tests, and simulates rates, empty results, HTTP errors, and timeouts. It never forwards requests. No test queries the live Treasury API. Initial Maven dependency and Docker image downloads may require internet access; application requests during tests stay within the local test environment.

The suite covers monetary rounding, maximum-amount precision, the supported date boundaries and historical date persistence, the inclusive six-month window, currency URL encoding, and the complete create/read/convert flow. It also checks strict JSON types and structure, null characters, enum binding, missing transactions/rates, upstream HTTP errors and timeouts, malformed JSON, invalid rate records, independent record/effective dates, generic internal errors, and every public error code. All scenarios run once with the `local` profile. Maven Surefire executes the suite during the `test` phase and writes reports to `target/surefire-reports`.

Swagger checks verify the UI, its configuration, JSON/YAML documents, transaction paths, validation constraints, error responses, and currency catalogs against the Java enum.

Coverage of the Corporate Payments Product Brief:

| Requirement | Automated verification |
|---|---|
| Persist description, date, USD amount, and a unique ID | Create/read round trip through PostgreSQL; repeated POSTs produce distinct UUIDs; packaged smoke test reads the same transaction after restart |
| Description at most 50 characters; valid date; positive amount rounded to cents | Boundary and invalid-input scenarios; calendar validation; `HALF_UP` rounding and amount precision |
| Return original purchase fields, exchange rate, and converted amount | Complete create/read/convert response assertions |
| Rate on or before purchase date, within six months | Both inclusive interval boundaries, out-of-window dates, and Treasury query parameters |
| Explain that conversion is impossible when no rate exists | HTTP 422 with `EXCHANGE_RATE_NOT_FOUND` and a conversion error message |
| Converted amount rounded to two decimal places | Multiplication and rounding cases, including fractional-cent results |

The brief leaves some validation details to the implementation. This application requires non-blank descriptions and an amount that stays positive after rounding, limits amounts to database precision, and interprets six months as calendar months. These choices are described above.

The [CI workflow](.github/workflows/ci.yml) runs `verify` with Java 21 and Testcontainers, then builds and starts the Docker image with Compose. A smoke test checks readiness, transaction creation/readback, rounding, and strict input validation against the packaged application. It repeats the readback after restarting the application to verify persistence. This catches missing runtime dependencies that can be masked by the test classpath. CI saves test reports and application logs, then removes its isolated Compose environment.

To run the same smoke test against an already running application, use PowerShell:

```powershell
$id = ./scripts/smoke-test.ps1 -BaseUrl http://localhost:8080
# After restarting the application:
./scripts/smoke-test.ps1 -BaseUrl http://localhost:8080 -TransactionId $id
```

The smoke test creates one transaction and never calls the live Treasury API.

If Windows blocks local PowerShell scripts, launch the smoke test with a process-scoped policy:

```powershell
powershell -NoProfile -ExecutionPolicy RemoteSigned -File ./scripts/smoke-test.ps1
```

## Service logging

`TransactionService` uses Lombok `@Slf4j` and parameterized `log` calls:

- `INFO`: a transaction was persisted or converted successfully; includes its ID and, for conversion, the currency and rate dates.
- `DEBUG`: operation starts and successful retrieval by ID.
- `WARN`: a transaction was not found or no eligible exchange rate exists.

Purchase descriptions, amounts, and full request/response bodies are not logged. Unexpected failures and Treasury diagnostics remain in the existing exception handler, so the service does not duplicate their stack traces. A creation success message is emitted after the repository save completes.

Enable service debug logs for a Java run with `--logging.level.com.example.currencyconverter.service=DEBUG`, or set `LOGGING_LEVEL_COM_EXAMPLE_CURRENCYCONVERTER_SERVICE=DEBUG` in the application process environment. With Compose, read logs using `docker compose logs -f app`.

## Local development

```bash
docker compose up -d --wait postgres
./mvnw spring-boot:run
```

In IntelliJ IDEA, open `pom.xml` as a Maven project, select JDK 21, and run `CurrencyConverterApplication`. For a non-default database host, port, or name, set the complete `DB_URL`.

## Environments

Shared settings live in `application.yml`; environment-specific database settings live in `application-local.yml` and `application-prod.yml`.

| Profile | Activation | Database settings |
|---|---|---|
| `local` | Default for local Java runs; explicitly selected by Compose | Development URL and credentials, overridable through environment variables |
| `prod` | `SPRING_PROFILES_ACTIVE=prod`; default in the runtime Docker image | Required `DB_URL`, `DB_USER`, and `DB_PASSWORD`, with no development fallbacks |

The Compose file is a local development environment. For production, supply the deployment's database settings and start the packaged application with the `prod` profile:

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=jdbc:postgresql://database:5432/transactions
export DB_USER=transaction_service
export DB_PASSWORD='<deployment-secret>'
java -jar target/transaction-currency-converter.jar
```

Both profiles apply Liquibase migrations, validate the JPA schema, disable Open Session in View, and hide health details. The production connection pool defaults to 20 connections and can be adjusted with `DB_POOL_SIZE`.

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` for Java; `prod` in the runtime image | Selects the environment; Compose explicitly selects `local` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/transactions` in `local` | Required in `prod`; Compose sets the container address |
| `DB_NAME` | `transactions` | Database name used by Compose |
| `DB_USER` / `DB_PASSWORD` | `transactions` / `transactions` in `local` | Required in `prod` |
| `DB_POOL_SIZE` | `20` | Maximum pool size in `prod` |
| `APP_PORT` | `8080` | Application host port in Compose |
| `DB_PORT` | `5432` | PostgreSQL host port in Compose |
| `TREASURY_BASE_URL` | `https://api.fiscaldata.treasury.gov/services/api/fiscal_service` | Upstream base URL |
| `TREASURY_CONNECT_TIMEOUT` | `2s` | Connection timeout |
| `TREASURY_READ_TIMEOUT` | `5s` | Read timeout |

### Database schema

The schema starts with a single YAML migration, `db/changelog/changes/001-create-transactions.yaml`. Its `createTable` change defines the columns, UUID primary key, and required fields. A PostgreSQL SQL change inside the same YAML changeset adds the non-blank description and positive amount checks without requiring the commercial Liquibase `addCheckConstraint` extension. An explicit rollback drops the table. Liquibase applies the migration to a fresh database and records its execution; subsequent starts do not recreate the table.

## Structure and responsibilities

```text
src/
├── main/
│   ├── java/com/example/currencyconverter/
│   │   ├── CurrencyConverterApplication.java
│   │   ├── controller/
│   │   │   ├── TransactionController.java
│   │   │   ├── advice/ApiExceptionHandler.java
│   │   │   ├── config/JsonRequestConfiguration.java
│   │   │   ├── config/OpenApiConfiguration.java
│   │   │   ├── converter/CurrencyConverter.java
│   │   │   └── json/TransactionDateDeserializer.java
│   │   ├── service/TransactionService.java
│   │   ├── repository/TransactionRepository.java
│   │   ├── client/
│   │   │   ├── ExchangeRateClient.java
│   │   │   ├── TreasuryExchangeRateClient.java
│   │   │   ├── config/
│   │   │   ├── dto/
│   │   │   └── exception/TreasuryException.java
│   │   ├── model/
│   │   │   ├── Transaction.java
│   │   │   └── Currency.java
│   │   ├── dto/
│   │   └── exception/
│   └── resources/
│       ├── application.yml
│       ├── application-local.yml
│       ├── application-prod.yml
│       └── db/changelog/
└── test/java/com/example/currencyconverter/
Dockerfile
docker-compose.yml
pom.xml
README.md
docs/openapi.yaml
scripts/smoke-test.ps1
mvnw / mvnw.cmd
```

The controller owns HTTP mappings, parameter conversion, and exception-to-response translation. The service owns transaction and conversion rules. The repository owns persistence. The client layer owns all Treasury-specific HTTP configuration, response DTOs, exchange-rate results, and exceptions. The shared `Currency` enum belongs to the model because it is part of the application contract across layers; API request/response DTOs remain separate from JPA entities and Treasury DTOs.

Lombok generates dependency-injection constructors, the currency enum constructor, entity getters, the protected JPA no-argument constructor, and the exception handler logger. Custom exception constructors remain explicit because they construct messages and call `super`. The entity's creation constructor also remains explicit to exclude the generated identifier. `@Builder` is used for `ConvertedTransactionResponse` and `CreateTransactionRequest`, where named arguments make amounts and dates easier to distinguish. Controller tests build their request DTO with `CreateTransactionRequest.builder()` and derive variations with `toBuilder()`. Raw JSON is used for invalid dates, malformed JSON, and missing-field checks. DTOs remain records.

Creation performs one database write through Spring Data JPA's transactional `repository.save`. The service does not need an additional `@Transactional` boundary for this single operation; DTO mapping reads ordinary fields after the repository call completes. If creation later involves several database operations that must succeed or fail together, the service should define their transaction boundary. For conversion, the repository read completes before the upstream HTTP request, so no database transaction is held open while waiting for Treasury. Open Session in View is disabled. Liquibase exclusively manages schema changes, and Hibernate uses `validate`.

The runtime container uses an unprivileged user. The application has bounded HTTP timeouts, graceful shutdown, and separate readiness/liveness checks. Compose allows 40 seconds for shutdown, exceeding Spring's configured 30-second shutdown phase. Only the health actuator endpoint is exposed; Spring Boot's default settings hide health details, error messages, and stack traces. Treasury outages do not prevent transaction creation or retrieval. Invalid Treasury responses are logged with the specific validation or decoding reason, while HTTP responses retain generic public messages. Automatic retries and caching are omitted: each conversion queries the source, and temporary failures return `503`.

Authentication is outside the brief because no users or access rules are specified. Compose binds host ports to `127.0.0.1`. A public deployment needs organizational TLS, authentication/authorization, secret management, and database backups. Historical corrections made by Treasury may change a later conversion result; exchange-rate snapshots are not persisted because the brief requires persistence of the original transaction.
