CREATE TABLE rentals (
                         id                UUID PRIMARY KEY,
                         channel           VARCHAR(10)   NOT NULL,
                         customer_nit      VARCHAR(20)   NOT NULL,
                         customer_user_id  UUID,
                         branch_id         UUID          NOT NULL,
                         origin_text       VARCHAR(200)  NOT NULL,
                         destination_text  VARCHAR(200)  NOT NULL,
                         departure_date    DATE          NOT NULL,
                         return_date       DATE,
                         passengers        INTEGER       NOT NULL,
                         distance_km       NUMERIC(8,2),
                         estimated_price   NUMERIC(10,2) NOT NULL,
                         confirmed_price   NUMERIC(10,2),
                         status            VARCHAR(20)   NOT NULL,
                         bus_id            UUID,
                         driver_id         UUID,
                         payment_date      DATE,
                         created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
                         updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE rental_settings (
                                 branch_id            UUID PRIMARY KEY,
                                 price_per_km         NUMERIC(10,2) NOT NULL,
                                 passenger_surcharge  NUMERIC(10,2) NOT NULL,
                                 updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
                               id              UUID PRIMARY KEY,
                               aggregate_type  VARCHAR(50)  NOT NULL,
                               aggregate_id    UUID         NOT NULL,
                               event_type      VARCHAR(80)  NOT NULL,
                               topic           VARCHAR(80)  NOT NULL,
                               payload         JSONB        NOT NULL,
                               created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
                               published_at    TIMESTAMPTZ,
                               attempts        INTEGER      NOT NULL DEFAULT 0,
                               last_error      TEXT
);

CREATE TABLE processed_events (
                                  event_id      UUID PRIMARY KEY,
                                  event_type    VARCHAR(80) NOT NULL,
                                  processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
