-- Audit trail: who did what, to which resource, from where.
-- Rows can only be inserted. The database itself rejects UPDATE and DELETE,
-- so not even a bug in the application can rewrite history.

CREATE TABLE audit_logs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor       VARCHAR(100) NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    target      VARCHAR(100),
    ip_address  VARCHAR(45),
    details     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_actor_created ON audit_logs (actor, created_at);

CREATE FUNCTION reject_audit_log_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is insert-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_logs_insert_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION reject_audit_log_change();

CREATE TRIGGER audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_log_change();
