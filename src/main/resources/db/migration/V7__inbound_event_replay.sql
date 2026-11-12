-- When an operator replayed an ABANDONED inbound event (NULL: never replayed).
ALTER TABLE webhook_events ADD COLUMN replayed_at TIMESTAMPTZ;
