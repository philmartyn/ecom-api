# Cart mutation locking: pessimistic write lock on the user row

**Status:** accepted

## Context

Cart mutations are read-modify-write operations. Two adds of the same Product can
both read quantity 1 and both write 2, losing an increment. Two simultaneous
first adds can race on `carts.user_id UNIQUE`. The Cart is created lazily, so
there is no Cart row to lock before the first add. The strategy has to serialise
edits per user without adding a schema migration or a retry loop.

## Decision

- Every cart mutation (`POST`, `PATCH`, `DELETE`) starts its transaction by
  locking the User row with `SELECT … FOR UPDATE`
  (`@Lock(PESSIMISTIC_WRITE)`).
- The lock serialises all cart mutations for that User. It also proves the User
  still exists, so a token naming a deleted User gets the same `401` as
  `GET /api/auth/me`.
- Once the lock is held, the service finds or creates the Cart, loads the
  Product, checks Active/stock, and mutates the Cart Item.
- Reads (`GET /api/cart`) do not lock. They use a normal existence check inside
  a read-only transaction.
- No `@Version` column, no migration, no retry loop.

## Considered options

- **Last-write-wins** — rejected. Simple, but lost increments and a first-add
  unique violation surfacing as a `409` are poor behaviour for a double-click.
- **Optimistic locking (`@Version` on Cart)** — rejected for this slice. It
  detects conflicts but still needs a retry or client handling, and it requires
  a `V2` migration.
- **Atomic SQL upserts** — rejected. It avoids locks but moves cart arithmetic
  into PostgreSQL-specific queries and makes the service harder to follow.
- **Lock the Cart row instead** — rejected as the only lock. It cannot protect
  the first add because the row does not exist yet; locking the User row covers
  both the existing-cart and first-add cases.
- **Create the Cart at registration** — rejected in ADR 0002; lazy creation is
  the chosen ownership model.

## Consequences

- Cart mutations for one User are serialised. The transactions are short, and a
  cart is not a high-throughput resource, so contention is low.
- Other writers that lock the same User row, such as profile or role changes,
  can contend with cart writes. Acceptable at this scale.
- Reads stay lock-free and see the last committed cart.
- The mutation path gets its deleted-User check from the lock itself, so it
  does not need a separate `existsById`.
- The lock serialises **cart writes**, not stock changes. The stock check is
  best-effort at write time; checkout still has to re-validate because there is
  no reservation.
- A future move to optimistic locking or event-sourced carts would replace this
  decision, not extend it.

## Spring notes

- `UserRepository.findByIdForUpdate(long id)` uses
  `@Lock(LockModeType.PESSIMISTIC_WRITE)` plus an explicit
  `@Query("select u from User u where u.id = :id")`.
- The lock only applies inside a transaction; the service mutation methods are
  `@Transactional`, and `GET` is `@Transactional(readOnly = true)`.
- PostgreSQL implements this as `SELECT … FOR UPDATE`. Lock the User first, then
  touch the Cart and Product, so two cart transactions cannot acquire the same
  rows in opposite orders.
- The lock is per User, so two different users never block each other.
