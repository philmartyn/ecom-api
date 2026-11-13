# eCom API

[![Tests](https://github.com/philmartyn/ecom-api/actions/workflows/tests.yml/badge.svg)](https://github.com/philmartyn/ecom-api/actions/workflows/tests.yml)

A small eCommerce REST API built as a learning project from the
[roadmap.sh eCommerce API](https://roadmap.sh/projects/ecommerce-api) brief:
signup and login, a product catalog, a shopping cart, orders, checkout with
Stripe, and an admin panel.

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
| Token validation on incoming requests | done |
| Product browsing and search (`GET /api/products`, `GET /api/categories`) | done |
| Cart (`GET /api/cart`, add/update/remove items) | done |
| Orders: checkout, list, detail, cancel | done |
| Payment (Stripe) | designed (ADR-0005, ADR-0006); not implemented |

Registration, login, health/info, the API docs and the catalog are public; every
other route requires `Authorization: Bearer <token>`. The public list and the
401/403 contract are under [Endpoints](#endpoints).

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
`@MockitoBean`), and it ships **Jackson 3** — the error handlers inject
`tools.jackson.databind.ObjectMapper`, not the Jackson 2 type. Where a version
difference matters, the code comments say so.

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

### `GET /api/auth/me`

Requires `Authorization: Bearer <accessToken>`.

`200 OK` with `Cache-Control: no-store` and the same profile body registration
returns:

```json
{ "id": 1, "email": "bob@example.com", "firstName": "Bob", "lastName": "Smith",
  "role": "CUSTOMER", "createdAt": "2026-10-25T17:15:47.783115Z" }
```

The token's `sub` identifies the user, but the row is read from the database, so
the profile is current rather than a stale copy of the claims. If that user no
longer exists the response is `401`, not `404` — a valid token naming nobody is
not an authenticated caller.

### `GET /api/categories`

Public. Every category, ordered by name:

```json
[ { "id": 1, "slug": "keyboards", "name": "Keyboards",
    "description": "Mechanical and membrane keyboards" } ]
```

There is no product count per category. It would be a second query on every
browse and a number that is stale the moment it is returned.

### `GET /api/products`

Public. A page of **active** products. Retired products are invisible: every
query starts with `active = true`, so no filter combination can surface one.

| Parameter | Default | Rules |
|---|---|---|
| `q` | none | case-insensitive contains match on `name` only |
| `category` | none | exact category slug; an unknown slug returns an empty page |
| `page` | `0` | zero-based, must be ≥ 0 |
| `size` | `20` | 1–100 |
| `sort` | `name,asc` | field one of `name`, `price`, `createdAt`, direction `asc` or `desc` |

An invalid `page`, `size` or `sort` is a `400`, not a `500` from the ORM. `%`,
`_` and `\` in `q` are literal, so searching for `%` does not return everything.

The response is an explicit envelope, not Spring's `Page` serialisation:

```json
{ "items": [ ... ], "page": 0, "size": 20, "totalElements": 10, "totalPages": 1 }
```

Each item is a **summary**, not the detail shape:

```json
{ "id": 1, "slug": "mechanical-keyboard", "name": "Mechanical Keyboard",
  "price": 89.99, "imageUrl": "https://example.com/keyboard.png",
  "category": { "slug": "keyboards", "name": "Keyboards" },
  "inStock": true }
```

The list omits `sku` and the exact `stockQuantity`: the storefront needs "can I
buy this", not an inventory count. The summary keeps the payload small by
leaving out descriptions and timestamps.

### `GET /api/products/{slug}`

Public. Lookup is by **slug**, not id — the slug is the public, URL-friendly
identifier, and `sku` is a warehouse detail. `200 OK` returns the summary fields
plus the long-form ones:

```json
{ "id": 1, "slug": "mechanical-keyboard", "name": "Mechanical Keyboard",
  "price": 89.99, "imageUrl": "https://example.com/keyboard.png",
  "category": { "slug": "keyboards", "name": "Keyboards" },
  "inStock": true, "description": "Hot-swappable switches.",
  "createdAt": "2026-10-25T17:15:47.783115Z",
  "updatedAt": "2026-10-25T17:15:47.783115Z" }
```

An unknown slug and an inactive product both return `404` with the same body.
That is deliberate: distinguishing them would tell the world which products were
retired.

### `GET /api/cart`

Requires a token. Returns the signed-in user's cart. The cart is implicit — the
route carries no cart id, and neither does the response:

```json
{ "items": [
    { "product": { "id": 1, "slug": "mechanical-keyboard",
                   "name": "Mechanical Keyboard", "price": 89.99,
                   "imageUrl": "https://example.com/keyboard.png" },
      "quantity": 2, "lineTotal": 179.98, "available": true } ],
  "totalQuantity": 2, "subtotal": 179.98 }
```

An empty cart is `200`, not `404`:

```json
{ "items": [], "totalQuantity": 0, "subtotal": 0.00 }
```

`GET` never writes. The cart row is created lazily by the first add, and it
survives emptying, so a shopper who removes every line still has a cart rather
than a stream of create/destroy cycles.

`available` is `false` when the product is no longer Active or its stock has
dropped below the line's quantity. The line stays visible with its price and
`lineTotal`; the client decides whether to warn or hide it. Nothing is reserved,
so a later checkout still has to re-check stock.

Prices are **current**, not snapshots — the same line can cost more tomorrow.
Only `order_items` snapshots name and price, because order history must not
change. Lines are ordered by when they were added (`createdAt`, then id), so two
identical `GET`s return the same list. Every success carries
`Cache-Control: no-store`.

### `POST /api/cart/items`

```json
{ "productId": 1, "quantity": 2 }
```

Adds to the cart, **accumulating**: adding 2 when 1 is already there leaves 3.
`200` with the full cart.

### `PATCH /api/cart/items/{productId}`

```json
{ "quantity": 3 }
```

Sets an **absolute** quantity — it does not add. `200` with the full cart.

### `DELETE /api/cart/items/{productId}`

Removes the line and returns `200` with the full cart. Idempotent: deleting a
product that is not in the cart is still a success, so a retried request is
harmless.

The item key is the immutable `productId`, not the slug. Slugs stay the public
Catalog identifier, but a rename cannot orphan a pending cart change.

| Failure | Response |
|---|---|
| missing field, quantity not positive, `productId` ≤ 0 | `400` with an `errors` map |
| non-numeric `productId` in the path | `400 "Invalid request"` |
| product unknown or inactive (POST, PATCH) | `404 "Product not found"` |
| PATCH for a product not in the cart | `404 "Cart item not found"` |
| resulting quantity over stock | `409 "Not enough stock"` |
| no token, or a token naming a deleted user | `401` |

The `409` never includes the stock number: that is an inventory detail, the same
reason `inStock` is a boolean. Nothing is reserved when a line is added or
changed, so the check only means "this quantity is possible right now".

The response deliberately omits the cart id, timestamps, currency, `sku` and
stock quantity. Totals are money without a currency until multi-currency is a
real requirement.

### Order endpoints

The contract below is fixed by
[ADR-0004](docs/adr/0004-checkout-pending-orders.md). Every order route
re-checks the User row, so a token naming a deleted user is `401`, not an empty
list or `404`.

#### `POST /api/orders`

Requires a token. Turns the caller's cart into a `PENDING` order, snapshots
each line's product name and unit price, and empties the cart. The `carts` row
survives. No payment is attempted and no `payments` row is written.

```json
{ "shipping": { "name": "Bob Smith", "line1": "1 Main St",
                "line2": "Apt 2", "city": "Springfield",
                "postalCode": "12345", "country": "US" } }
```

`line2` is optional; every other field is required. `country` is a two-letter
code, stored uppercase.

`201 Created` with `Location: /api/orders/{id}` and the full order:

```json
{ "id": 1, "status": "PENDING", "subtotal": 179.98, "total": 179.98,
  "currency": "USD", "totalQuantity": 2,
  "shipping": { "name": "Bob Smith", "line1": "1 Main St", "line2": "Apt 2",
                "city": "Springfield", "postalCode": "12345", "country": "US" },
  "items": [ { "productId": 1, "productName": "Mechanical Keyboard",
               "unitPrice": 89.99, "quantity": 2, "lineTotal": 179.98 } ],
  "createdAt": "2026-11-08T12:00:00Z", "updatedAt": "2026-11-08T12:00:00Z" }
```

Stock is checked, not reserved: the order is rejected if any line is inactive
or short on stock, but no quantity is decremented. Payment will reserve stock
when it opens an attempt (ADR-0005).

| Failure | Response |
|---|---|
| missing or invalid shipping fields | `400` with an `errors` map |
| empty cart | `409 "Cart is empty"` |
| one or more lines unavailable | `409` with `unavailableItems` |
| no token, or a token naming a deleted user | `401` |

An unavailable-line body names the lines and the reason, never the stock count:

```json
{ "title": "Cart cannot be checked out", "status": 409,
  "detail": "One or more items are unavailable", "instance": "/api/orders",
  "unavailableItems": [
    { "productId": 1, "slug": "mechanical-keyboard", "reason": "INSUFFICIENT_STOCK" }
  ] }
```

`reason` is `INACTIVE` or `INSUFFICIENT_STOCK`; `INACTIVE` wins when both apply.

#### `GET /api/orders`

Requires a token. The caller's orders, newest first, as a page of summaries:

```json
{ "items": [ { "id": 1, "status": "PENDING", "subtotal": 179.98,
               "total": 179.98, "currency": "USD", "totalQuantity": 2,
               "createdAt": "2026-11-08T12:00:00Z" } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
```

`page` defaults to `0` and `size` to `20` (1–100); an invalid value is `400`.
There is no status filter and no client-chosen sort yet. A token naming a
deleted user is `401`.

#### `GET /api/orders/{id}`

Requires a token. `200` with the full order shown under `POST /api/orders`.
Only the caller's own order is visible: an unknown id and another user's id both
return the same `404`, so order ids cannot be enumerated. A token naming a
deleted user is `401`, not `404`.

#### `POST /api/orders/{id}/cancel`

Requires a token. Moves the caller's own `PENDING` order to `CANCELLED` and
returns `200` with the full order. There is no request body.

| Failure | Response |
|---|---|
| unknown id, or another user's order | `404` |
| order is `PAID`, `SHIPPED` or `DELIVERED` | `409 "Order cannot be cancelled"` |
| no token, or a token naming a deleted user | `401` |

Cancelling an already-`CANCELLED` order is `200`, not `409`, so a retry is
harmless. Cancellation does not restore the cart and does not touch stock.

All order responses carry `Cache-Control: no-store`. A retried checkout after a
lost response gets `409 "Cart is empty"`; the client should read
`GET /api/orders` to find the order that was created.

### Protecting routes

Only these are public:

| Route | Why |
|---|---|
| `POST /api/auth/register` | there is no token yet |
| `POST /api/auth/login` | issues the token |
| `GET /actuator/health`, `GET /actuator/info` | operational probes |
| `GET /v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` | API docs |
| `GET /api/categories` | browsing must work before login |
| `GET /api/products`, `GET /api/products/{slug}` | public, read-only catalog |

The catalog rules are `GET`-scoped. A `POST /api/products` is not public; it
falls through to `authenticated()`, which keeps the public surface from growing
when admin writes arrive.

`/api/cart/**` is deliberately absent from the table above, so it falls through
to `authenticated()`. There is no per-route rule to forget when another cart
verb is added: anything not named as public is protected by default.

`/api/orders/**` is likewise absent, so checkout, order reads and cancellation
all fall through to `authenticated()`. There is no public order route.

Everything else is `authenticated()`. A missing or invalid token gets `401`
problem+json with a `WWW-Authenticate: Bearer` header; an authenticated caller
who is not allowed gets `403` problem+json. The two are deliberately distinct:
401 means *authenticate*, 403 means *you are authenticated but not allowed*.
Errors are generic so a caller cannot tell a forged token from an expired one.

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
| `orders` | one row per checkout; PENDING status, totals, currency, shipping address |
| `order_items` | **snapshots** of name, unit price and quantity at checkout |
| `payments` | provider reference, amount, status — unused until Stripe |

`order_items` copies rather than references because order history must not
change when a product is renamed, repriced or deleted. `cart_items` does the
opposite: it points at the live product, so the cart always shows the current
price. That is also why a deactivated product can still appear in a cart, marked
`available: false`.

An order is created `PENDING`; its `order_items` are fixed at that moment, and
the stock decrement waits for a payment attempt (ADR-0004, refined by
ADR-0005).

Money is `NUMERIC(12,2)` in the database and `BigDecimal` in Java — never
`double`. Enums are stored as `VARCHAR` with a `CHECK` constraint and mapped
with `@Enumerated(STRING)`, so the database rejects nonsense while the Java side
stays readable.

## Project layout

Package by feature, not by layer:

```
com.example.ecom
├── auth/            registration, login, JWT issuing, current-user lookup
├── cart/            Cart aggregate, cart service, authenticated cart routes
├── catalog/         Category, Product, public browsing and search
├── common/
│   ├── config/      security, JPA auditing, OpenAPI
│   ├── domain/      AuditableEntity (created_at / updated_at)
│   ├── exception/   domain exceptions + RFC 9457 advice
│   ├── security/    filter-chain entry point, access-denied handler, problem body
│   └── web/         PageResponse envelope, PageParams bounds, NoStore builder
├── dev/             local-only data seeder
├── order/           Order aggregate, checkout, order reads, cancellation
└── user/            User, Role, repository
```

DTOs are records. Entities have no setters — state changes go through
intention-revealing methods such as `Product.deactivate()` — and no Lombok, so
what you read is what the compiler sees.

## Tests

```bash
./gradlew test          # 225 tests, ~35s, needs Docker
```

The same command runs on GitHub Actions for every push and pull request
(`.github/workflows/tests.yml`); the badge above reports the latest result.

| Layer | Class | What it proves |
|---|---|---|
| unit | `UserTest`, `ProductTest` | normalisation, role mapping, invariants |
| unit | `AuthServiceTest` | hashing, duplicate handling, 72-byte rule, credential failures |
| unit | `TokenServiceTest` | claims, 15-minute lifetime, signature actually verified |
| unit | `ProductQueryTest` | defaults, bounds, sort whitelist, whitespace normalisation |
| unit | `CartTest` | accumulation, absolute change, removal, response totals, `available` |
| unit | `OrderTest` | line snapshots, totals, `totalQuantity`, cancel transitions, shipping normalisation |
| slice | `AuthControllerTest` | JSON shape, status codes, problem-detail bodies, no hash leaks |
| slice | `MeEndpointTest` | `/me` contract: `sub` → service id, `UserResponse` body, `no-store`, no hash |
| slice | `CatalogControllerTest` | envelope, summary vs detail shape, 400s for bad paging and sort |
| slice | `CartControllerTest` | the four routes, full-cart responses, 400/404/409 bodies, `no-store` |
| slice | `OrderControllerTest` | checkout/list/detail/cancel, `Location`, `no-store`, 400/404/409 bodies, no inventory leaks |
| slice | `SecurityPerimeterTest` | public allowlist (including catalog GETs), cart and order routes require a token, 401 vs 403, unknown routes never leak 404 |
| repository | `UserRepositoryTest`, `CatalogRepositoryTest`, `DevDataSeederTest` | constraints, idempotent seeding |
| repository | `CartRepositoryTest` | `NOT NULL` columns, cascade and orphan removal, entity graph, line order |
| repository | `PessimisticLockTest` | a cart mutation blocks a second `FOR UPDATE`; plain reads are not blocked |
| repository | `OrderRepositoryTest` | constraints, cascade, entity graph, owner scoping, `FOR UPDATE` lookup |
| integration | `AuthRegistrationIntegrationTest`, `AuthLoginIntegrationTest` | the whole chain against real PostgreSQL |
| integration | `TokenValidationIntegrationTest` | valid login token accepted; tampered, expired, wrong-secret, wrong-issuer, unknown-role, bad-subject and deleted-user tokens rejected |
| integration | `CatalogIntegrationTest` | search escaping, category filter, active-only visibility, paging past the end |
| integration | `CartIntegrationTest` | lazy creation, accumulate vs absolute, idempotent delete, stock `409`, deactivated lines stay visible, current prices, ordering |
| integration | `OrderIntegrationTest` | checkout, empty/unavailable `409`, cart clearing, owner-only reads, cancel transitions, deleted-user `401` |
| integration | `OrderConcurrencyTest` | one order from two concurrent checkouts; two concurrent cancels both succeed |

The order slice (ADR-0004) is covered by `OrderTest`, `OrderControllerTest`,
`OrderRepositoryTest`, `OrderIntegrationTest` and `OrderConcurrencyTest`, and
`SecurityPerimeterTest` now includes `/api/orders/**`.

Database tests use Testcontainers with a **singleton container**: one PostgreSQL
starts for the whole run and is shared through `AbstractIntegrationTest`, rather
than one per class. Migrations run against it exactly as they do in production.

## Roadmap

- [x] Docker Compose, env files, profiles
- [x] Schema and Flyway
- [x] Catalog entities, repositories, seed data
- [x] Registration with validation and problem-detail errors
- [x] Login issuing JWTs
- [x] Token validation, route lockdown, `GET /api/auth/me`
- [x] Product browsing and search (`GET /api/products`, `GET /api/categories`)
- [x] Cart: add, update quantity, remove
- [x] Checkout and orders: `PENDING` creation, list, detail, cancel (ADR-0004)
- [ ] **Next:** Stripe payment integration (designed: ADR-0005, ADR-0006)
- [ ] Admin: products, pricing, inventory
- [ ] Observability stack (Compose profile is written but commented out)

## Known gaps

Deliberate for now, listed so nobody mistakes them for oversights:

- **Role changes lag up to 15 minutes.** Authorization trusts the token's `role`
  claim, so a demoted admin keeps the old role until that token expires. `/me`
  reads the database, but ordinary protected routes do not.
- **A deleted user's token still works** on routes that trust claims alone.
  `/me`, every cart route and every order route re-check the database, so they
  return `401`; the rest do not. The admin slice should revisit this globally.
- **Cart and checkout stock are not reserved.** Adding a cart line only checks
  stock at that moment, and checkout validates again without decrementing. Two
  shoppers can both hold the last unit and both create `PENDING` orders; the
  payment slice reserves stock when it opens an attempt (ADR-0005), so the
  second shopper gets a `409` at payment time instead of an oversell.
- **A retried checkout is not idempotent.** There is no `Idempotency-Key`; a
  retry after a lost response gets `409 "Cart is empty"`. The client recovers
  with `GET /api/orders`. Concurrent duplicates are prevented by the User-row
  lock.
- **`PENDING` orders do not expire.** They hold no stock, so the cost is history
  clutter; an expiry rule belongs with payments.
- **Cancelling does not restore the cart.** The order keeps the lines; "buy
  again" is a client concern.
- **No admin order access.** Orders are owner-only until the admin slice.
- **Order totals have no shipping or tax.** `total` equals `subtotal`; adding
  either needs new columns and a calculation.
- **Order lines have no slug or image snapshot.** The detail returns `productId`
  and the snapshotted name and price only; a product link needs a current
  catalog lookup, and the slug may have changed since checkout.
- **Cart prices are current, not snapshotted.** Repricing a product changes every
  cart's `lineTotal` immediately. Snapshots belong to `order_items`, where the
  history must stay fixed.
- **Cart totals have no currency.** `subtotal` and `lineTotal` are bare
  decimals; an Order stores `USD`. Multi-currency is a real feature, not a
  formatting choice.
- **No clear-cart endpoint.** Checkout empties the cart, but removing everything
  without ordering is still one `DELETE` per line.
- **No guest cart or merge-on-login.** ADR 0002 keeps carts authenticated-only,
  so there is nothing to merge. Supporting guests means a cookie identity and a
  merge rule for two carts of the same product.
- **Carts never expire.** A cart row lives until its user is deleted. If
  abandoned carts ever cost storage or skew analytics, they need a sweeper.
- **No logout or revocation.** A token is valid until `exp`; the server keeps no
  session. `jti` is there for the day a denylist is worth building.
- **No refresh tokens.** After 15 minutes the client logs in again.
- **No rate limiting.** Login runs BCrypt on demand, which is a brute-force and
  CPU-exhaustion target in the open.
- **No full-text search.** `q` is a `LIKE '%…%'` on `name`, which cannot use a
  B-tree index and does not search descriptions. Fine at seed-data scale;
  PostgreSQL `pg_trgm` is the next step if the catalog grows.
- **No price, stock or attribute filters.** The contract supports `q` and
  `category` only; adding filters means new query parameters and tests.
- **No catalog caching.** Every browse hits the database. Adding a cache now
  would hide the query and N+1 behaviour this project is meant to teach.
- **No CORS configuration.** Irrelevant until a browser client exists.
- **No README before this one** — the project grew faster than its docs.
