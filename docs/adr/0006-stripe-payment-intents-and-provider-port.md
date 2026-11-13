# Stripe PaymentIntents behind a provider port, with the webhook authoritative

**Status:** accepted

## Context

The roadmap's next slice is Stripe payment. The project is a JSON API with no
frontend, card data must never touch the server, and a payment confirmed by a
client is only really known once Stripe tells us. The slice also has to stay
testable without network access or Stripe keys in CI.

## Decision

- Use Stripe **PaymentIntents**:
  `POST /api/orders/{id}/payments` creates an attempt and returns
  `clientSecret` (plus `publishableKey`) so a Stripe.js or mobile client can
  confirm; the server never sees card data.
- All Stripe calls sit behind a `PaymentGateway` port. `StripePaymentGateway` is
  the only class that imports `com.stripe.*`; `FakePaymentGateway` is selected in
  tests and by default locally.
- The **webhook is the primary writer** of `PAID`.
  `POST /api/payments/webhook` is public, verifies `Stripe-Signature` from the
  raw body, handles `payment_intent.succeeded`,
  `payment_intent.payment_failed` and `payment_intent.canceled`, and is
  idempotent by attempt state. Unknown refs answer `200` with an error log.
- The reservation sweeper is the reconciliation backstop: a `succeeded` intent
  whose webhook was lost still reaches `PAID`.
- `providerRef` never leaves the server. `clientSecret` appears only in the
  create response. `POST /api/orders/{id}/payments/{paymentId}/cancel` cancels
  an open attempt explicitly.
- There is no `Idempotency-Key` on our API this slice; a lost create response is
  recovered from the attempts list, and the one-open-attempt rule prevents a
  duplicate hold. The Stripe call carries
  `Idempotency-Key: payment-{paymentId}`.
- The Stripe API version is pinned in the adapter. `app.payments.provider`
  defaults to `fake`; `stripe` fails fast when the secret key or webhook secret
  is blank.
- Payment Status is separate from Order Status: only a `SUCCEEDED` attempt moves
  a PENDING Order to PAID. Refunds are out of scope; `REFUNDED` stays unused.

## Considered options

- **Stripe Checkout Session** — rejected. Least client work, but it outsources
  the payment state machine and duplicates the Order's line items in Stripe.
- **Server-side test charge** — rejected. Confirming with a test PaymentMethod
  normalises a flow that must never ship.
- **Fake provider only** — rejected. It delivers architecture, not the roadmap
  item.
- **Trusting the client's confirmation result** — rejected. It makes the client
  the source of truth for money.
- **An `Idempotency-Key` header now** — deferred. The one-open-attempt rule
  covers the dangerous duplicate; the remaining lost-response case is a read.

## Consequences

- The slice is testable without Stripe: the fake gateway covers service and
  integration tests, and webhook signatures are generated locally.
- A client must exist to confirm a PaymentIntent; until then the Stripe CLI is
  the manual end-to-end path.
- The webhook is a public, unauthenticated route whose only gate is the
  signature; it must stay out of the JWT perimeter deliberately.
- `failure_code` stores the decline code so a client can explain a failed
  attempt.
- Refunds, disputes, partial payments, saved payment methods and multi-currency
  remain out of scope.

## Spring notes

- `stripe-java` is a new dependency. A `StripeClient` bean is configured from
  properties; `StripePaymentGateway` implements the port and owns all SDK types.
- The webhook controller takes `@RequestBody String` and
  `@RequestHeader("Stripe-Signature")` so verification sees the raw bytes.
- `SecurityConfig` permits `POST /api/payments/webhook` before the catch-all.
- `PaymentResponse` exposes `failureCode` but never `providerRef`.
- Tests use `FakePaymentGateway`; webhook tests build a real signature with a
  local `whsec_` secret.
