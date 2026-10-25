-- V1: initial schema for the eCommerce API.
-- Flyway owns the schema; Hibernate runs with ddl-auto=validate and only checks.

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
                       id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                       email         VARCHAR(255) NOT NULL,
                       password_hash VARCHAR(255) NOT NULL,
                       first_name    VARCHAR(100) NOT NULL,
                       last_name     VARCHAR(100) NOT NULL,
                       role          VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER',
                       created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                       updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                       CONSTRAINT users_role_check CHECK (role IN ('CUSTOMER', 'ADMIN'))
);

-- Case-insensitive uniqueness: Bob@x.com and bob@x.com must be the same account.
CREATE UNIQUE INDEX users_email_unique_idx ON users (lower(email));

-- ---------------------------------------------------------------------------
-- categories
-- ---------------------------------------------------------------------------
CREATE TABLE categories (
                            id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            name        VARCHAR(100) NOT NULL UNIQUE,
                            slug        VARCHAR(120) NOT NULL UNIQUE,
                            description TEXT,
                            created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
                            updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- products
-- ---------------------------------------------------------------------------
CREATE TABLE products (
                          id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                          category_id    BIGINT NOT NULL REFERENCES categories (id) ON DELETE RESTRICT,
                          sku            VARCHAR(64)  NOT NULL UNIQUE,
                          name           VARCHAR(200) NOT NULL,
                          slug           VARCHAR(220) NOT NULL UNIQUE,
                          description    TEXT,
                          price          NUMERIC(12, 2) NOT NULL,
                          stock_quantity INTEGER NOT NULL DEFAULT 0,
                          image_url      VARCHAR(500),
                          active         BOOLEAN NOT NULL DEFAULT TRUE,
                          created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                          updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                          CONSTRAINT products_price_non_negative  CHECK (price >= 0),
                          CONSTRAINT products_stock_non_negative  CHECK (stock_quantity >= 0)
);

CREATE INDEX products_category_id_idx ON products (category_id);
CREATE INDEX products_active_idx      ON products (active);

-- ---------------------------------------------------------------------------
-- carts  (one live cart per user; checkout empties it)
-- ---------------------------------------------------------------------------
CREATE TABLE carts (
                       id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                       user_id    BIGINT NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
                       created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                       updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- cart_items
-- ---------------------------------------------------------------------------
CREATE TABLE cart_items (
                            id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            cart_id    BIGINT  NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
                            product_id BIGINT  NOT NULL REFERENCES products (id) ON DELETE CASCADE,
                            quantity   INTEGER NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT cart_items_quantity_positive CHECK (quantity > 0),
                            CONSTRAINT cart_items_cart_product_unique UNIQUE (cart_id, product_id)
);

CREATE INDEX cart_items_cart_id_idx    ON cart_items (cart_id);
CREATE INDEX cart_items_product_id_idx ON cart_items (product_id);

-- ---------------------------------------------------------------------------
-- orders
-- ---------------------------------------------------------------------------
CREATE TABLE orders (
                        id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        user_id              BIGINT NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
                        status               VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
                        subtotal             NUMERIC(12, 2) NOT NULL,
                        total                NUMERIC(12, 2) NOT NULL,
                        currency             VARCHAR(3)    NOT NULL DEFAULT 'USD',
                        shipping_name        VARCHAR(200)  NOT NULL,
                        shipping_line1       VARCHAR(255)  NOT NULL,
                        shipping_line2       VARCHAR(255),
                        shipping_city        VARCHAR(100)  NOT NULL,
                        shipping_postal_code VARCHAR(20)   NOT NULL,
                        shipping_country     VARCHAR(2)    NOT NULL,
                        created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
                        updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
                        CONSTRAINT orders_status_check CHECK (
                            status IN ('PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED')
                            ),
                        CONSTRAINT orders_subtotal_non_negative CHECK (subtotal >= 0),
                        CONSTRAINT orders_total_non_negative    CHECK (total >= 0),
                        CONSTRAINT orders_currency_format       CHECK (currency ~ '^[A-Z]{3}$')
    );

CREATE INDEX orders_user_id_created_at_idx ON orders (user_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- order_items  (snapshots: history must not change when a product changes)
-- ---------------------------------------------------------------------------
CREATE TABLE order_items (
                             id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                             order_id     BIGINT NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
                             product_id   BIGINT NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
                             product_name VARCHAR(200)   NOT NULL,
                             unit_price   NUMERIC(12, 2) NOT NULL,
                             quantity     INTEGER        NOT NULL,
                             created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
                             updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
                             CONSTRAINT order_items_quantity_positive    CHECK (quantity > 0),
                             CONSTRAINT order_items_unit_price_non_negative CHECK (unit_price >= 0)
);

CREATE INDEX order_items_order_id_idx   ON order_items (order_id);
CREATE INDEX order_items_product_id_idx ON order_items (product_id);

-- ---------------------------------------------------------------------------
-- payments
-- ---------------------------------------------------------------------------
CREATE TABLE payments (
                          id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                          order_id     BIGINT NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
                          provider     VARCHAR(30) NOT NULL,
                          provider_ref VARCHAR(255),
                          status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                          amount       NUMERIC(12, 2) NOT NULL,
                          currency     VARCHAR(3) NOT NULL DEFAULT 'USD',
                          created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                          updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                          CONSTRAINT payments_status_check CHECK (
                              status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REFUNDED')
                              ),
                          CONSTRAINT payments_amount_non_negative CHECK (amount >= 0),
                          CONSTRAINT payments_provider_ref_unique UNIQUE (provider, provider_ref)
);

CREATE INDEX payments_order_id_idx ON payments (order_id);