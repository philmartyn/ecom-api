# Checkout creates a PENDING order without reserving stock

**Status:** accepted

## Context

The Cart slice is finished. The schema already has `orders`, `order_items` and
`payments`, and the README's next roadmap item is checkout and orders. The Cart
holds current prices and never reserves stock, so checkout has to decide four
things at once: when an Order begins to exist, whether checkout moves stock,
what happens to the Cart, and how an unpaid Order ends.

Stripe is deliberately out of scope. This decision covers the order model and
the API contract; the payment slice will consume it.

## Decision

- `POST /api/orders` turns the caller's Cart into a **PENDING Order**. No
  `payments` row is written and no provider is called.
- `order_items` snapshot the Product's name and unit price; the Quantity is
  fixed. `subtotal` is the sum of the line totals, `total` equals `subtotal`
  (there is no tax or shipping rule), and `currency` is `USD`.
- The Shipping Address is required at checkout and copied onto the Order. The
  Cart's Products, not the request body, decide the lines.
- Checkout **validates** availability (Product Active, stock at least the
  Quantity) but **does not decrement stock**. The payment slice re-checks and
  commits the decrement atomically when it charges.
- The Cart Items are deleted in the same transaction; the `carts` row survives,
  as it does after any emptying. A failed checkout rolls everything back.
- Checkout locks the User row with `SELECT … FOR UPDATE` (ADR-0003), then reads
  the Cart and Products, creates the Order and clears the Cart. It does not lock
  Product rows.
- A Cart that is empty, or has a line that is inactive or short on stock, is
  rejected as a whole with `409`. The body lists `{productId, slug, reason}`
  for each offending line and never exposes a stock number.
- Orders are read by their owner only: `GET /api/orders` returns a paginated
  summary list, newest first; `GET /api/orders/{id}` returns the full Order.
  Another user's id is `404`, not `403`, so ids cannot be enumerated.
- Every order route re-checks that the User row still exists and answers `401`
  when it does not, matching `/me` and the cart. Checkout gets the check from
  the User-row lock; reads and cancellation check before touching orders.
- `POST /api/orders/{id}/cancel` moves the caller's own PENDING Order to
  CANCELLED. It locks the Order row, is idempotent on an already-CANCELLED
  Order, and answers `409` for PAID, SHIPPED or DELIVERED. It does not restore
  the Cart and does not touch stock.
- There is no idempotency key and no expiry. A retried checkout after a lost
  response gets `409 "Cart is empty"`; the client recovers with
  `GET /api/orders`. PENDING Orders live until paid or cancelled.

## Considered options

- **Decrement stock at checkout** — rejected. It makes an unpaid PENDING Order
  hold inventory with no expiry, so an abandoned checkout strands stock until a
  cancellation path or sweeper exists. Payment is the commit point.
- **A reservation table, or decrement plus automatic expiry** — rejected for
  this slice. It is the correct model for a busy store, but it adds a sweeper,
  a cancellation policy and more tests than the slice needs. Revisit when
  abandoned orders matter.
- **Clear the Cart when payment succeeds** — rejected. The Order and the Cart
  would duplicate the same lines, and a second checkout could create a second
  Order from one Cart. Clearing at creation makes the Order the single record.
- **Restore the Cart on cancellation** — rejected. It silently re-adds items the
  shopper may no longer want, needs merge rules, and can fail after the
  cancellation already succeeded.
- **An `Idempotency-Key` header** — deferred. The User-row lock prevents the
  dangerous concurrent duplicate; the remaining lost-response case is solved by
  a read. A key needs a table, a TTL and response replay.
- **Expire PENDING Orders** — deferred. Without a reservation there is no
  correctness reason; "how long is an unpaid Order valid?" belongs with
  payments.
- **A public order number** — rejected for now. The numeric id is enough; a
  separate number is another unique value to generate and expose.

## Consequences

- An Order can exist for stock that is gone by payment time. The payment slice
  must re-check and fail (or refund) rather than assume the checkout check still
  holds.
- The Cart is empty after checkout even if payment never succeeds. The Order
  holds the lines, so the client can retry payment or cancel; there is no
  "restore cart" button on the server.
- A retried checkout is a `409`, not a replay. Clients that need retry safety
  must read `GET /api/orders`.
- PENDING Orders do not expire and do not hold stock. The cost is history
  clutter, not inventory.
- `total` is only a copy of `subtotal` until shipping or tax exists. Adding
  either means new columns, a new calculation and a new ADR.
- Cancellation is cheap and idempotent: no stock to put back, no Cart to
  rebuild. PAID and later states belong to the payment and admin slices.
- Owner-only reads and cancellation keep the authorization surface small; admin
  order access arrives with the admin slice.
- A deleted user's token is rejected by every order route with `401`, narrowing
  the deleted-user gap to the routes that still trust claims alone.

## Spring notes

- `Order` is the aggregate root; `OrderItem` is cascaded and ordered by id.
  `ShippingAddress` is an `@Embeddable`, not an entity.
- `OrderStatus` mirrors the schema's `CHECK` and is mapped with
  `@Enumerated(STRING)`.
- Checkout uses `UserRepository.findByIdForUpdate`, already used by cart
  mutations. Cancellation uses an `OrderRepository` method with
  `@Lock(PESSIMISTIC_WRITE)` scoped to both `id` and `user_id`, so a foreign id
  is a `404` rather than a lock.
- Order reads use `@EntityGraph` to load items in one query and the existing
  `PageResponse` envelope for the list.
- All order responses carry `Cache-Control: no-store`, matching `/me` and the
  Cart.
