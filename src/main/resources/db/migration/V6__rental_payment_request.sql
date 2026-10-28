ALTER TABLE rentals ADD COLUMN customer_id UUID;
ALTER TABLE rentals ADD COLUMN payment_requested_at TIMESTAMPTZ;
