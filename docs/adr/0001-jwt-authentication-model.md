# JWT authentication: stateless access tokens with claims trusted for authorization

**Status:** accepted

## Context

Registration and login already issue HS256 JWTs, but `SecurityConfig` permits
every request, so nothing ever verifies a token. This decision covers the rest
of the project: how the API authenticates a caller, where it gets the caller's
identity from, and what happens when authentication fails.

## Decision

- `POST /api/auth/login` issues a 15-minute HS256 **access token**.
- The API runs as an OAuth2 **resource server**. A `JwtDecoder` verifies the
  signature, `exp`/`nbf` (Spring's default 60-second clock skew), `iss`
  (`https://ecom.local`), the HS256 algorithm, a `role` that maps to a known
  `Role`, and a numeric `sub`.
- The `role` claim becomes a `ROLE_<role>` authority, so `hasRole("ADMIN")`
  works when the admin endpoints arrive.
- Authorization trusts the token's claims. `GET /api/auth/me` is the one
  exception: it reads the `User` row by `sub` so the profile is current, and
  answers `401` if that user no longer exists.
- Missing or invalid tokens get `401` problem+json with a `WWW-Authenticate:
  Bearer` header; a denied request gets `403` problem+json. Same body shape as
  every other error in the API.
- Public routes: registration, login, health, API docs. Everything else is
  authenticated.

## Considered options

- **Server-side sessions** — rejected. A second state store, a session cookie,
  CSRF protection, and no clean story for the mobile client the API targets.
- **Opaque tokens with introspection** — rejected. An extra network hop and a
  server-side lookup on every request buys nothing here.
- **Refresh tokens** — deferred. Fifteen minutes is short enough to keep the
  blast radius small and long enough to learn with.
- **Reading the database on every request** — rejected. It defeats statelessness
  for authorization. The price is that a role change takes up to one token
  lifetime to apply.

## Consequences

- **No logout or revocation.** A token is valid until `exp`; the server keeps no
  session. `jti` is already in the token, so a denylist can be added later
  without changing the format.
- **Role changes lag up to 15 minutes.** A demoted admin keeps the old role
  until their token expires.
- **A deleted user is rejected at `/me`**, but a route that trusts claims alone
  would still accept their token until it expires. Acceptable while `/me` is the
  only protected route; revisit when orders exist.
- **The signing secret is the whole security boundary.** `JWT_SECRET` must be
  real and secret anywhere but a laptop.
- **Tokens are signed, not encrypted.** Anyone holding one can read its claims.
- **HS256 means every verifier can also mint tokens.** Fine for one deployable;
  asymmetric keys (RS256/ES256) are the fix if that ever changes.

## Spring notes

- `JwtEncoder` (`NimbusJwtEncoder`) signs; `JwtDecoder`
  (`NimbusJwtDecoder.withSecretKey`) verifies. They share one `SecretKey` in
  `JwtConfig`, so the two directions cannot drift.
- `JwtValidators.createDefaultWithIssuer` combines the timestamp validator with
  an issuer check. A custom `OAuth2TokenValidator` adds the claim checks; Spring
  composes validators rather than asking you to write one big check.
- `http.oauth2ResourceServer(o -> o.jwt(...))` installs the bearer-token filter
  and populates the `SecurityContext`. `JwtAuthenticationConverter` is what maps
  claims to authorities.
- `@AuthenticationPrincipal Jwt` hands the verified token to a controller.
  Nothing parses the `Authorization` header by hand.
