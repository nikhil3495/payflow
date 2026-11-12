-- Inbound PSP events that matched no operation are retried by a sweeper until they age out.
-- ABANDONED marks the ones that aged out: kept (with their payload) for an operator, never retried.

ALTER TABLE webhook_events DROP CONSTRAINT webhook_events_processing_state_check;
ALTER TABLE webhook_events ADD CONSTRAINT webhook_events_processing_state_check
    CHECK (processing_state IN ('PROCESSED', 'UNMATCHED', 'IGNORED', 'ABANDONED'));

CREATE INDEX webhook_events_unmatched_idx ON webhook_events (received_at)
    WHERE processing_state = 'UNMATCHED';
