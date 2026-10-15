CREATE TABLE risk_assessment (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, claim_id uuid NOT NULL,
 rule_version text NOT NULL, flags jsonb NOT NULL, route text NOT NULL CHECK(route='HUMAN_REVIEW'),
 actor text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,country_code,claim_id) REFERENCES claim(tenant_id,country_code,id)
);
CREATE TRIGGER immutable_risk_assessment BEFORE UPDATE OR DELETE ON risk_assessment FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
