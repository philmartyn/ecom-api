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

**Catalog**:
The public, read-only view of what is for sale: categories and products. It is
not an admin surface and not the whole product table.
_Avoid_: storefront, shop, inventory

**Product**:
A sellable item with a unique Sku and Slug, a price, an image, a stock quantity
and an Active flag. Only active products are visible in the Catalog.
_Avoid_: item, article, SKU (that is the identifier, not the thing)

**Category**:
A named grouping of Products, addressed by Slug. Every Product belongs to
exactly one Category.
_Avoid_: collection, tag, department

**Slug**:
The URL-safe, unique, human-readable identifier used in public URLs
(`/api/products/{slug}`) and for category filters. Stable across renames in a
way an id is not readable and a name is not safe.
_Avoid_: path, handle, key

**Sku**:
The warehouse identifier for a Product. Unique, internal, and never exposed by
the public Catalog; shoppers identify products by Slug.
_Avoid_: product code, part number, slug (the Sku is not the public identifier)

**Active**:
The visibility flag on a Product. Active products appear in the Catalog;
inactive ones are treated as if they do not exist, including returning `404`
for their Slug.
_Avoid_: published, enabled, visible, deleted (deactivation is not deletion)

**In stock**:
Whether a Product currently has a positive stock quantity, exposed to shoppers
as the boolean `inStock`. The exact quantity is internal.
_Avoid_: available, on hand, quantity (that is the internal number)

**Browse**:
Reading the Catalog without a search term: list categories, page through
products, open one product by Slug. Public, needs no token.
_Avoid_: shop, surf, scan

**Search**:
Narrowing the product list with `q`, a case-insensitive contains match on the
Product's name only — not on descriptions, categories or Sku. `%`, `_` and `\`
are literal characters, not wildcards.
_Avoid_: query, filter (a category filter is not a search)
