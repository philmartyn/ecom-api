# eCom API

[![Tests](https://github.com/philmartyn/ecom-api/actions/workflows/tests.yml/badge.svg)](https://github.com/philmartyn/ecom-api/actions/workflows/tests.yml)

A small eCommerce REST API built as a learning project from the
[roadmap.sh eCommerce API](https://roadmap.sh/projects/ecommerce-api) brief:
signup and login, a product catalog, a shopping cart, checkout with Stripe, and
an admin panel.

The goal is understanding, not speed, so the code is organised by feature and
each slice is finished — with tests — before the next one starts.

## Status

| Area | State |
|---|---|
| Infrastructure (Docker, Postgres, Flyway) | done |
| Schema: 8 tables | done |
| Catalog entities + repositories | done |
| Dev seed data | done |
| Registration (`POST /api/auth/register`) | done |
| Login issuing HS256 JWTs (`POST /api/auth/login`) | done |
| Token validation on incoming requests | **not started** |
| Product, cart, order and payment endpoints | **not started** |

The API is currently **open**: `SecurityConfig` permits every request. Login
issues tokens that nothing verifies yet. That changes in the next slice.

## Stack

| | |
|---|---|
| Java | 27 (Corretto) — enforced by the Gradle toolchain |
| Spring Boot | 4.1.2 |
| Build | Gradle 9.8.2 (wrapper committed) |
| Database | PostgreSQL 17 (Docker) |
| Migrations | Flyway — Hibernate runs `ddl-auto: validate` |
| Auth | Spring Security, OAuth2 resource server, Nimbus JOSE (HS256) |
| Docs | springdoc-openapi 3.1.0 |
| Tests | JUnit 5, AssertJ, Mockito, Testcontainers |

Boot 4 and Java 27 are newer than almost every tutorial you will find. Boot 4
split the test starters and moved annotations (`@WebMvcTest` now lives in
`org.springframework.boot.webmvc.test.autoconfigure`, `@MockBean` became
`@MockitoBean`). Where a version difference matters, the code comments say so.

## Running it

Requires JDK 27 and Docker.

```bash
cp .env.example .env          # local only; .env is gitignored
docker compose up -d          # Postgres on localhost:5432
./gradlew bootRun
```

Then:

```bash
curl localhost:8080/actuator/health          # {"status":"UP",...}
open http://localhost:8080/swagger-ui.html   # interactive API docs
```

Start with the `local` profile to seed four categories and ten products and to
log SQL:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

`DevDataSeeder` is idempotent — it skips seeding when categories already exist.

To reset the database (drops the volume, Flyway re-runs from scratch):

```bash
docker compose down -v && docker compose up -d
```

In IntelliJ, run `EComApplication` directly; the defaults already point at the
container.

## Configuration

| Variable | Default | Used by |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `ecom` | the container's first boot |
| `DB_URL` | `jdbc:postgresql://localhost:5432/ecom` | the application |
| `DB_USERNAME` / `DB_PASSWORD` | `ecom` | the application |
| `JWT_SECRET` | `dev-only-secret-change-me-32-bytes-min` | token signing |

`POSTGRES_*` and `DB_*` are deliberately separate: one initialises the database,
the other connects to it, and in a real deployment they diverge.

`JWT_SECRET` must be at least 32 bytes of UTF-8 for HS256. The app refuses to
start otherwise. The committed default exists so the project runs out of the
box; **set a real secret anywhere but a laptop.**

## Endpoints

### `POST /api/auth/register`

```json
{ "email": "Bob@Example.com", "password": "correct-horse-battery",
  "firstName": "Bob", "lastName": "Smith" }
```

`201 Created` — the email is stored lower-cased and the password is BCrypt-hashed.

```json
{ "id": 1, "email": "bob@example.com", "firstName": "Bob", "lastName": "Smith",
  "role": "CUSTOMER", "createdAt": "2026-10-25T17:15:47.783115Z" }
```

| Failure | Response |
|---|---|
| invalid or missing fields | `400` with an `errors` map |
| email already registered (any casing) | `409` |
| password over 72 bytes when UTF-8 encoded | `400` |

### `POST /api/auth/login`

```json
{ "email": "bob@example.com", "password": "correct-horse-battery" }
```

`200 OK` with `Cache-Control: no-store`.

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "tokenType": "Bearer", "expiresIn": 899 }
```

`expiresIn` is seconds, truncated, so it reads `899` rather than `900`. The
token's claims are:

| Claim | Value |
|---|---|
| `sub` | user id — stable if the email changes |
| `email` | the address, for display without a database hit |
| `role` | `CUSTOMER` or `ADMIN` |
| `iss` | `https://ecom.local` |
| `iat`, `exp` | issued at, issued at + 15 minutes |
| `jti` | random id, unused today, ready for a denylist |

A wrong password and an unknown email return the same `401`, the same body and
take about the same time. Anything else is an account-enumeration oracle:

```json
{ "title": "Invalid credentials", "status": 401,
  "detail": "Email or password is incorrect", "instance": "/api/auth/login" }
```

### Errors

All errors are RFC 9457 `application/problem+json`:

```json
{ "title": "Validation failed", "status": 400,
  "detail": "One or more fields are invalid", "instance": "/api/auth/register",
  "errors": { "password": "size must be between 8 and 72" } }
```

A JWT is signed, **not encrypted** — anyone holding it can read the claims. Never
put a secret in one, and only send it over TLS.

## Data model

Flyway owns the schema; `V1__initial_schema.sql` is the source of truth and
Hibernate only validates against it. Applied migrations are never edited.

| Table | Purpose |
|---|---|
| `users` | accounts; case-insensitive unique email, BCrypt hash, role |
| `categories` | product grouping, unique slug |
| `products` | price `NUMERIC(12,2)`, stock, active flag |
| `carts` | one live cart per user |
| `cart_items` | product, quantity, unique per cart and product |
| `orders` | totals, status, shipping address |
| `order_items` | **snapshots** of name and price at purchase time |
| `payments` | provider reference, amount, status |

`order_items` copies rather than references because order history must not
change when a product is renamed, repriced or deleted.

Money is `NUMERIC(12,2)` in the database and `BigDecimal` in Java — never
`double`. Enums are stored as `VARCHAR` with a `CHECK` constraint and mapped
with `@Enumerated(STRING)`, so the database rejects nonsense while the Java side
stays readable.

## Project layout

Package by feature, not by layer:

```
com.example.ecom
├── auth/            registration, login, JWT issuing
├── catalog/         Category, Product, repositories
├── common/
│   ├── config/      security, JPA auditing, OpenAPI
│   ├── domain/      AuditableEntity (created_at / updated_at)
│   └── exception/   domain exceptions + RFC 9457 advice
├── dev/             local-only data seeder
└── user/            User, Role, repository
```

DTOs are records. Entities have no setters — state changes go through
intention-revealing methods such as `Product.deactivate()` — and no Lombok, so
what you read is what the compiler sees.

## Tests

```bash
./gradlew test          # 49 tests, ~20s, needs Docker
```

The same command runs on GitHub Actions for every push and pull request
(`.github/workflows/tests.yml`); the badge above reports the latest result.

| Layer | Class | What it proves |
|---|---|---|
| unit | `UserTest`, `ProductTest` | normalisation, role mapping, invariants |
| unit | `AuthServiceTest` | hashing, duplicate handling, 72-byte rule, credential failures |
| unit | `TokenServiceTest` | claims, 15-minute lifetime, signature actually verified |
| slice | `AuthControllerTest` | JSON shape, status codes, problem-detail bodies, no hash leaks |
| repository | `UserRepositoryTest`, `CatalogRepositoryTest`, `DevDataSeederTest` | constraints, idempotent seeding |
| integration | `AuthRegistrationIntegrationTest`, `AuthLoginIntegrationTest` | the whole chain against real PostgreSQL |

Database tests use Testcontainers with a **singleton container**: one PostgreSQL
starts for the whole run and is shared through `AbstractIntegrationTest`, rather
than one per class. Migrations run against it exactly as they do in production.

## Roadmap

- [x] Docker Compose, env files, profiles
- [x] Schema and Flyway
- [x] Catalog entities, repositories, seed data
- [x] Registration with validation and problem-detail errors
- [x] Login issuing JWTs
- [ ] **Next:** validate incoming tokens, lock down routes, `GET /api/auth/me`
- [ ] Product browsing and search endpoints
- [ ] Cart: add, update quantity, remove
- [ ] Checkout and orders
- [ ] Stripe payment integration
- [ ] Admin: products, pricing, inventory
- [ ] Observability stack (Compose profile is written but commented out)

## Known gaps

Deliberate for now, listed so nobody mistakes them for oversights:

- **Every route is public.** Token validation and authorisation rules arrive
  with the next slice.
- **No logout or revocation.** A token is valid until `exp`; the server keeps no
  session. `jti` is there for the day a denylist is worth building.
- **No refresh tokens.** After 15 minutes the client logs in again.
- **No rate limiting.** Login runs BCrypt on demand, which is a brute-force and
  CPU-exhaustion target in the open.
- **No CORS configuration.** Irrelevant until a browser client exists.
- **No README before this one** — the project grew faster than its docs.
