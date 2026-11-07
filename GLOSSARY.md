# eCom API

A learning project built from the roadmap.sh eCommerce API brief, used to get
more comfortable with Java and Spring Boot. This glossary fixes the vocabulary
shared by the code, the docs and the issue tracker; it is not a spec.

## Language

**User**:
A person with an account in the store, identified by a case-insensitive unique
email, with one Role.
_Avoid_: account, member, buyer, client, customer

**Role**:
A User's permission tier, one of `CUSTOMER` or `ADMIN`.
_Avoid_: user type, permission level

**Customer**:
The Role every User receives at registration — not a separate entity from User.
_Avoid_: client, buyer, shopper
