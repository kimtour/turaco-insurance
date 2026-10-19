CREATE TABLE ai_task_trace (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, claim_id uuid NOT NULL,
 metadata jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,country_code,claim_id) REFERENCES claim(tenant_id,country_code,id)
);
CREATE TRIGGER immutable_ai_task_trace BEFORE UPDATE OR DELETE ON ai_task_trace FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
