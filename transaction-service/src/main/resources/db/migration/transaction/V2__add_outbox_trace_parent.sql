-- Contexto de trace (W3C traceparent) gravado junto ao evento, para que a publicação assíncrona
-- continue o mesmo trace da requisição HTTP (ADR 0010).
ALTER TABLE outbox_event ADD COLUMN trace_parent VARCHAR(80) NULL;
