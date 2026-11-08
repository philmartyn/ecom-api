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
The URL-safe, unique, human-readable identifier used in public Catalog URLs
(`/api/products/{slug}`) and for category filters. It is the human-facing
identifier; a Cart Item is keyed by the Product's immutable id instead, so a
Slug rename cannot orphan a pending cart operation.
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

**Cart**:
The single live basket of Products belonging to a User. A User has at most one
Cart, and a Cart belongs to exactly one User. Carts exist only for signed-in
shoppers; there is no guest Cart.
_Avoid_: basket, bag, shopping list, order

**Cart Item**:
One Product in a Cart together with a Quantity. A Cart holds at most one Cart
Item per Product; adding the same Product again increases that item's Quantity
rather than creating a second row.
_Avoid_: line item, row, entry, basket item

**Quantity**:
The number of units of a Product in a Cart Item. Always a positive whole number:
`PATCH` with zero or a negative value is a validation error, and only `DELETE`
removes the Cart Item. A change via `PATCH` sets the new Quantity outright
rather than adding to it.
_Avoid_: count, amount (that is money)

**Subtotal**:
The sum of each Cart Item's unit price multiplied by its Quantity. It is a Cart
total before order-level costs, not an Order total.
_Avoid_: total (reserved for an Order), basket total, cart value

**Available**:
Whether a Cart Item's Quantity can currently be fulfilled: the Product is Active
and its stock is at least the Quantity. Distinct from **In stock**, which only
says the Product has some units.
_Avoid_: in stock, buyable, on hand

**Line total**:
The unit price of a Product multiplied by the Quantity in one Cart Item.
_Avoid_: item total, subtotal (reserved for the whole Cart)

**Total quantity**:
The sum of the Quantities across all Cart Items in a Cart — units, not the
number of distinct Cart Items.
_Avoid_: item count (that is the number of Cart Items)
