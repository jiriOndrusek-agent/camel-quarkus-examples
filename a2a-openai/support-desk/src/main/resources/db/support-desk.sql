--
-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--      http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

-- Schema and demo data of the support desk. Dev Services run it when they start PostgreSQL in dev and test mode.
-- Dates are relative to now, so that the 30 day return window always applies to the same orders.

CREATE TABLE customers (
    -- the user name the customer logs in with
    id    VARCHAR(50) PRIMARY KEY,
    name  VARCHAR(100) NOT NULL,
    email VARCHAR(100) NOT NULL
);

CREATE TABLE orders (
    id              INTEGER PRIMARY KEY,
    customer_id     VARCHAR(50) NOT NULL REFERENCES customers (id),
    -- PROCESSING, SHIPPED or DELIVERED
    status          VARCHAR(20) NOT NULL,
    ordered_at      TIMESTAMP NOT NULL,
    delivered_at    TIMESTAMP,
    tracking_number VARCHAR(50)
);

CREATE TABLE order_items (
    order_id INTEGER NOT NULL REFERENCES orders (id),
    sku      VARCHAR(50) NOT NULL,
    name     VARCHAR(100) NOT NULL,
    quantity INTEGER NOT NULL,
    -- unit price in EUR
    price    NUMERIC(10, 2) NOT NULL,
    PRIMARY KEY (order_id, sku)
);

CREATE TABLE returns (
    id          SERIAL PRIMARY KEY,
    -- one return per order: a repeated request_return call cannot create a second refund
    order_id    INTEGER NOT NULL UNIQUE REFERENCES orders (id),
    customer_id VARCHAR(50) NOT NULL REFERENCES customers (id),
    amount      NUMERIC(10, 2) NOT NULL,
    reason      VARCHAR(500),
    -- APPROVED -> REFUNDED, or PENDING_APPROVAL -> APPROVED -> REFUNDED, or PENDING_APPROVAL -> REJECTED
    status      VARCHAR(20) NOT NULL,
    note        VARCHAR(500),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE tickets (
    id          SERIAL PRIMARY KEY,
    customer_id VARCHAR(50) NOT NULL REFERENCES customers (id),
    summary     VARCHAR(1000) NOT NULL,
    -- the priorities of the triage agent
    priority    VARCHAR(10) NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

-- Who called which tool and how it ended. The arguments are not stored, they may contain personal data.
CREATE TABLE tool_audit (
    id        SERIAL PRIMARY KEY,
    tool      VARCHAR(50) NOT NULL,
    caller    VARCHAR(50) NOT NULL,
    -- OK or FAILED
    outcome   VARCHAR(10) NOT NULL,
    called_at TIMESTAMP NOT NULL DEFAULT now()
);

INSERT INTO customers (id, name, email) VALUES
    ('alice', 'Alice Smith', 'alice@example.com'),
    ('bob', 'Bob Jones', 'bob@example.com');

INSERT INTO orders (id, customer_id, status, ordered_at, delivered_at, tracking_number) VALUES
    (1001, 'alice', 'DELIVERED', now() - INTERVAL '8 days', now() - INTERVAL '5 days', 'ACME-7741'),
    (1002, 'alice', 'DELIVERED', now() - INTERVAL '14 days', now() - INTERVAL '10 days', 'ACME-7702'),
    (1003, 'alice', 'SHIPPED', now() - INTERVAL '2 days', NULL, 'ACME-7790'),
    (1004, 'alice', 'DELIVERED', now() - INTERVAL '65 days', now() - INTERVAL '60 days', 'ACME-6120'),
    (2001, 'bob', 'DELIVERED', now() - INTERVAL '6 days', now() - INTERVAL '3 days', 'ACME-7755'),
    (2002, 'bob', 'DELIVERED', now() - INTERVAL '9 days', now() - INTERVAL '7 days', 'ACME-7731'),
    (2003, 'bob', 'DELIVERED', now() - INTERVAL '4 days', now() - INTERVAL '2 days', 'ACME-7768');

INSERT INTO order_items (order_id, sku, name, quantity, price) VALUES
    (1001, 'CABLE-USBC', 'USB-C cable, 2 m', 2, 12.50),
    (1002, 'SPEAKER-S1', 'Smart speaker S1', 1, 129.00),
    (1003, 'MOUSE-M5', 'Wireless mouse M5', 1, 39.90),
    (1004, 'LAMP-L2', 'Desk lamp L2', 1, 45.00),
    (2001, 'HEADPHONES-H2', 'Headphones H2', 1, 89.00),
    (2002, 'KEYBOARD-K3', 'Keyboard K3', 1, 59.00),
    (2003, 'CHARGER-C1', 'USB charger C1', 1, 24.90);

-- Two returns waiting for a decision of the support staff
INSERT INTO returns (order_id, customer_id, amount, reason, status) VALUES
    (2001, 'bob', 89.00, 'The sound cuts out', 'PENDING_APPROVAL'),
    (2002, 'bob', 59.00, 'Some keys stick', 'PENDING_APPROVAL');
