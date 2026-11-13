# eCom API

A learning project built from the roadmap.sh eCommerce API brief, used to get
more comfortable with Java and Spring Boot. This glossary fixes the vocabulary
shared by the code, the docs and the issue tracker; it is not a spec.

## Language

### Accounts

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

### Catalog

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

### Cart

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
The number of units of a Product in a Cart Item or an Order Item. Always a
positive whole number. A Cart Item's Quantity is replaced outright when changed,
never accumulated; an Order Item's Quantity is fixed at Checkout.
_Avoid_: count, amount (that is money)

**Subtotal**:
The sum of the Line totals in a Cart or an Order, before any order-level costs
such as shipping or tax.
_Avoid_: total (that is the Order total), basket total, cart value

**Available**:
Whether a Cart Item's Quantity can currently be fulfilled: the Product is Active
and its stock is at least the Quantity. Distinct from **In stock**, which only
says the Product has some units.
_Avoid_: in stock, buyable, on hand

**Line total**:
The unit price of a Product multiplied by the Quantity in one Cart Item or
Order Item.
_Avoid_: item total, subtotal (that is the whole Cart or Order)

**Total quantity**:
The sum of the Quantities across all Cart Items in a Cart or Order Items in an
Order — units, not the number of distinct items.
_Avoid_: item count (that is the number of distinct items)

### Orders

**Order**:
The record of a Checkout: the Cart's Products with their names and unit prices
snapshotted as Order Items, a Shipping Address, and an Order Status. It exists
before payment.
_Avoid_: purchase, transaction, basket, cart

**Order Item**:
One Product in an Order, with the Product's name and unit price snapshotted at
Checkout and a fixed Quantity. It is not a Cart Item: later Product changes do
not alter it.
_Avoid_: line item, order line, cart item

**Checkout**:
The act of turning the signed-in User's Cart into a PENDING Order. The Order
snapshots the lines; the Cart is emptied.
_Avoid_: place order, purchase, pay (payment is a later step)

**Order Status**:
The lifecycle state of an Order, one of `PENDING`, `PAID`, `SHIPPED`,
`DELIVERED` or `CANCELLED`.
_Avoid_: state, phase, payment status (that is the Payment's status)

**Shipping Address**:
The name and postal address an Order is shipped to, captured at Checkout. It is
part of the Order, not a saved address-book entry.
_Avoid_: delivery address, mailing address

**Order total**:
The amount the shopper pays for an Order: its Subtotal plus any order-level
costs. Until such costs exist, it equals the Subtotal.
_Avoid_: total (unqualified), grand total, amount

**Cancellation**:
Ending a PENDING Order before payment, moving it to CANCELLED. It leaves the
Cart empty and does not change the Order Items.
_Avoid_: delete, removal, refund (a refund belongs to a Payment)

### Payments

**Payment**:
The act of settling a PENDING Order through a payment provider. A Payment
belongs to exactly one Order; it is not Checkout, which only creates the Order.
_Avoid_: checkout, purchase, transaction

**Payment Attempt**:
One try at paying an Order through the provider, recorded with its own Payment
Status. Failed Attempts are retained, so an Order's payment history is complete
and the shopper can try again.
_Avoid_: payment, charge, retry

**Payment Status**:
The lifecycle state of a Payment Attempt, one of `PENDING`, `SUCCEEDED`,
`FAILED` or `REFUNDED`. It is not the Order Status.
_Avoid_: order status, payment state

**Reservation**:
The stock a pending Payment Attempt holds. Creating an Attempt decrements the
Product's stock; success makes the decrement permanent, while failure,
cancellation or expiry restores it.
_Avoid_: lock, hold, allocation
