# Payment attempts reserve stock

**Status:** accepted

**Supersedes:** the "does not decrement stock" decision in ADR-0004.

## Context

ADR-0004 made Checkout create a PENDING Order without moving stock and deferred
the decrement to payment, which would "re-check and commit the decrement
atomically when it charges". That sequence has no safe failure branch once
refunds are out of scope: if the charge succeeds and the stock re-check then
fails, the money is taken with nothing to fulfil and no way to give it back.
The payment slice therefore has to secure stock before the charge.

## Decision

- Creating a Payment Attempt reserves stock: in one transaction it locks the
  Order and its Product rows, re-checks Active and stock, decrements, inserts
  the PENDING Payment and commits. The provider call happens after the commit;
  a provider error restores the stock and marks the attempt FAILED.
- An open PENDING attempt is the reservation. There is no separate reservation
  column or table.
- At most one attempt per Order is open, enforced by a partial unique index. A
  second create is `409`.
- A `succeeded` event makes the decrement permanent. `failed`, `canceled`,
  explicit attempt cancellation and expiry restore it exactly once, guarded by
  the attempt's state under a row lock.
- A reservation expires after 30 minutes. A scheduled sweeper cancels the
  intent, restores stock and marks the attempt FAILED; the Order stays PENDING
  so the shopper can try again. If Stripe reports the intent already succeeded,
  the sweeper finalises the Order as PAID instead of releasing.
- A zero-total Order reserves stock like any other, then settles with a
  providerless SUCCEEDED Payment (`provider = 'none'`, amount `0`) and moves to
  PAID.
- Order cancellation first cancels any open attempt. A PAID Order still cannot
  be cancelled.

## Considered options

- **Check at creation, decrement in the success webhook** — rejected. The charge
  can succeed after the stock is gone; without refunds there is no honest
  response.
- **Charge, then allow negative stock** — rejected. It removes
  `products_stock_non_negative` and passes an inventory debt to the admin slice.
- **Reserve, but reopen refunds for the failure edge** — rejected. It reverses
  the slice's explicit "no refunds" scope and adds refund machinery for a race
  the reservation removes.
- **Reserve for the Order rather than the Attempt** — rejected. It needs a new
  Order column or table and a separate release path; tying the hold to the
  attempt gives expiry and cancellation a natural owner.

## Consequences

- Payment failure and abandonment cannot oversell. The cost is a sweeper and a
  30-minute hold on stock.
- The provider call cannot sit inside the reservation transaction, so a
  provider failure needs a compensating release. That release is idempotent.
- An abandoned attempt holds stock until the sweeper runs. Clients can release
  it immediately by cancelling the attempt.
- Multi-instance deployments need `FOR UPDATE SKIP LOCKED` in the sweeper; the
  single-instance assumption is documented as a known gap.
- Stock can disappear between Checkout and payment. The Order exists but cannot
  be paid until stock returns or the shopper cancels it.

## Spring notes

- The reservation runs in a `@Transactional` service method that locks Product
  rows in id order (to avoid deadlocks), inserts the Payment and commits before
  calling the gateway.
- A gateway failure is compensated in a second transaction that marks the
  attempt FAILED and restores stock.
- The sweeper is a `@Scheduled` component gated by
  `app.payments.sweeper.enabled`; `@EnableScheduling` lives on the application.
- `Order.markPaid()` accepts only PENDING and is idempotent; `Payment` state
  transitions reject anything but PENDING.
