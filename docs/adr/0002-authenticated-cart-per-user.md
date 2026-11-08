# Cart ownership: one authenticated cart per user

**Status:** accepted

## Context

The `carts` and `cart_items` tables already exist. `carts.user_id` is `UNIQUE`,
so the database already assumes one live cart per user. The next slice adds the
cart endpoints, so the ownership model has to be settled before the API shape:
who owns a cart, how a request finds it, and whether a shopper can have one
before signing in.

The roadmap brief permits guest carts, but the schema and the JWT model both
point at authenticated users.

## Decision

- Cart endpoints require a valid access token; there is no public cart route.
- A User has at most one Cart, and a Cart belongs to exactly one User. The
  database enforces this with `carts.user_id UNIQUE` and `ON DELETE CASCADE`.
- The Cart is implicit in the route (`/api/cart`), not addressed by a cart id.
  The access token's `sub` identifies the User.
- There are no guest carts, cookie carts, or merge-on-login. A shopper must be
  signed in to add, change or remove items.
- Cart operations re-check that the User row still exists and answer `401`
  when it does not, matching `GET /api/auth/me`. A token naming a deleted user
  is not an authenticated caller.
- The `carts` row is created lazily on the first add. `GET /api/cart` never
  writes; a user with no cart row gets an empty cart. Emptying the cart leaves
  the row in place.

## Considered options

- **Guest cart with a cookie or session id, merged at login** — rejected for
  now. It improves conversion but adds a second identity, merge rules,
  expiry/cleanup and schema changes. Revisit only with a browser client that
  needs it.
- **Cart id in the URL (`/api/carts/{id}`)** — rejected. It invites ownership
  checks (IDOR) on every route and buys nothing when a user has exactly one
  cart.
- **Create a cart at registration** — rejected. It adds a write to every
  registration and a row for users who never shop; lazy creation keeps `GET`
  read-only.
- **Trust the token's `sub` without checking the User row** — rejected.
  `carts.user_id` is a foreign key; a deleted user would turn a cart write into
  a database error rather than an honest `401`.

## Consequences

- `/api/cart/**` is authenticated by the existing catch-all; no public
  allowlist entry is added.
- There is no merge conflict logic and no cart expiry.
- A deleted user's token is rejected by cart operations even though other
  claim-trusting routes would still accept it. This narrows, but does not
  close, the known deleted-user gap.
- Supporting guest carts later is an API and schema change, not a
  configuration flag.
- Lazy creation introduces a first-write race for concurrent adds; the cart
  slice serialises mutations with a pessimistic write lock on the User row,
  which also proves the user still exists. Reads do not take the lock.

## Spring notes

- Controllers receive the verified token with `@AuthenticationPrincipal Jwt`;
  `TokenClaimsValidator` has already guaranteed a numeric `sub`.
- The cart service takes a `long userId`; HTTP concerns stay in the controller.
- `carts.user_id UNIQUE` is the ownership guarantee. The service can rely on it
  rather than checking "does this user already have a cart?" in two places.
