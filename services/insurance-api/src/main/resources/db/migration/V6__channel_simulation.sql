CREATE TABLE notification_simulation (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, claim_id uuid NOT NULL,
 client_key text NOT NULL, request_digest text NOT NULL, status text NOT NULL,
 template_key text NOT NULL, language text NOT NULL, actor text NOT NULL,
 evidence_reference text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,country_code,client_key),
 FOREIGN KEY(tenant_id,country_code,claim_id) REFERENCES claim(tenant_id,country_code,id)
);
CREATE TRIGGER immutable_notification_simulation BEFORE UPDATE OR DELETE ON notification_simulation FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
