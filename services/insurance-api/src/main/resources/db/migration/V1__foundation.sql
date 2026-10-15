CREATE TABLE tenant (id uuid PRIMARY KEY, name text NOT NULL);
CREATE TABLE country_config (
 tenant_id uuid NOT NULL REFERENCES tenant(id), country_code char(2) NOT NULL,
 residency text NOT NULL, retention_days integer NOT NULL CHECK (retention_days > 0),
 PRIMARY KEY (tenant_id,country_code), CHECK(country_code IN ('KE','UG','NG','GH','ZM'))
);
CREATE TABLE customer (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL,
 full_name text NOT NULL, mobile_number text NOT NULL,
 UNIQUE(tenant_id,country_code,id), FOREIGN KEY(tenant_id,country_code) REFERENCES country_config
);
CREATE TABLE product (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL,
 code text NOT NULL CHECK(code='HOSPITAL_CASH'), name text NOT NULL,
 UNIQUE(tenant_id,country_code,id), UNIQUE(tenant_id,country_code,code),
 FOREIGN KEY(tenant_id,country_code) REFERENCES country_config
);
CREATE TABLE coverage (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL,
 product_id uuid NOT NULL, benefit_code text NOT NULL, incident_type_code text NOT NULL,
 daily_amount numeric(12,2) NOT NULL CHECK(daily_amount>0), currency char(3) NOT NULL,
 max_nights integer NOT NULL CHECK(max_nights>0), waiting_days integer NOT NULL CHECK(waiting_days>=0),
 rule_version text NOT NULL,
 UNIQUE(tenant_id,country_code,product_id,incident_type_code),
 FOREIGN KEY(tenant_id,country_code,product_id) REFERENCES product(tenant_id,country_code,id)
);
CREATE TABLE policy (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL,
 customer_id uuid NOT NULL, product_id uuid NOT NULL, policy_number text NOT NULL,
 start_date date NOT NULL, end_date date NOT NULL CHECK(end_date>start_date),
 premium numeric(12,2) NOT NULL CHECK(premium>0), currency char(3) NOT NULL,
 status text NOT NULL CHECK(status IN ('ACTIVE','CANCELLED','EXPIRED')), version integer NOT NULL DEFAULT 1,
 UNIQUE(tenant_id,country_code,id), UNIQUE(tenant_id,country_code,policy_number),
 FOREIGN KEY(tenant_id,country_code,customer_id) REFERENCES customer(tenant_id,country_code,id),
 FOREIGN KEY(tenant_id,country_code,product_id) REFERENCES product(tenant_id,country_code,id)
);
CREATE TABLE claim (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, policy_id uuid NOT NULL,
 client_key text NOT NULL, request_digest text NOT NULL, extraction jsonb NOT NULL,
 intake_status text NOT NULL CHECK(intake_status IN ('OPEN','NEEDS_CONFIRMATION','VERIFIED','READY_TO_SUBMIT','SUBMITTED','CANCELLED')),
 provider_claim_status text NOT NULL CHECK(provider_claim_status IN ('NOT_SUBMITTED','SUBMISSION_PENDING','RECEIVED','PROCESSING','APPROVED','REJECTED','CLOSED','SYNC_UNKNOWN')),
 payout_status text NOT NULL CHECK(payout_status IN ('NOT_AUTHORIZED','RESERVED','SUBMITTED','CONFIRMED','FAILED','UNKNOWN')),
 claimant_verified boolean NOT NULL DEFAULT false, payee_verified boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,country_code,id), UNIQUE(tenant_id,country_code,client_key),
 FOREIGN KEY(tenant_id,country_code,policy_id) REFERENCES policy(tenant_id,country_code,id)
);
CREATE TABLE review (
 sequence bigserial UNIQUE, id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, claim_id uuid NOT NULL,
 actor text NOT NULL, decision text NOT NULL CHECK(decision IN ('RECOMMEND_APPROVAL','REQUEST_INFORMATION','RECOMMEND_REJECTION')),
 reason_code text NOT NULL, evidence_reference text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,country_code,claim_id) REFERENCES claim(tenant_id,country_code,id)
);
CREATE TABLE payment_obligation (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, claim_id uuid NOT NULL,
 benefit_code text NOT NULL, payout_version integer NOT NULL CHECK(payout_version>0),
 amount numeric(12,2) NOT NULL CHECK(amount>0), currency char(3) NOT NULL,
 status text NOT NULL CHECK(status IN ('RESERVED','SUBMITTED','CONFIRMED','FAILED','UNKNOWN')),
 idempotency_reference text NOT NULL UNIQUE, rule_version text NOT NULL, policy_version integer NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,country_code,id), UNIQUE(tenant_id,claim_id,benefit_code,payout_version),
 FOREIGN KEY(tenant_id,country_code,claim_id) REFERENCES claim(tenant_id,country_code,id)
);
CREATE TABLE payout_attempt (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, obligation_id uuid NOT NULL,
 idempotency_reference text NOT NULL UNIQUE, provider text NOT NULL CHECK(provider='LOCAL_FAKE'),
 outcome text NOT NULL CHECK(outcome IN ('CONFIRMED','UNKNOWN')), created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,country_code,obligation_id) REFERENCES payment_obligation(tenant_id,country_code,id)
);
CREATE TABLE audit_event (
 sequence bigserial PRIMARY KEY, id uuid NOT NULL UNIQUE, tenant_id uuid NOT NULL, country_code char(2) NOT NULL,
 aggregate_id uuid NOT NULL, actor text NOT NULL, event_type text NOT NULL, detail jsonb NOT NULL,
 correlation_id uuid NOT NULL, source text NOT NULL, policy_version integer,
 created_at timestamptz NOT NULL DEFAULT now(), FOREIGN KEY(tenant_id,country_code) REFERENCES country_config
);
CREATE TABLE claim_state_transition (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, claim_id uuid NOT NULL,
 machine text NOT NULL CHECK(machine IN ('intake','provider','payout')), from_state text NOT NULL, to_state text NOT NULL,
 actor text NOT NULL, source text NOT NULL, policy_version integer NOT NULL,
 correlation_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,country_code,claim_id) REFERENCES claim(tenant_id,country_code,id)
);
CREATE TABLE extraction_version (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, claim_id uuid NOT NULL,
 extraction jsonb NOT NULL, actor text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,country_code,claim_id) REFERENCES claim(tenant_id,country_code,id)
);
CREATE TABLE outbox_event (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, aggregate_id uuid NOT NULL,
 event_type text NOT NULL, payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
 FOREIGN KEY(tenant_id,country_code) REFERENCES country_config
);
CREATE TABLE external_ref (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL,
 provider text NOT NULL, resource_type text NOT NULL, internal_id uuid NOT NULL, external_id text NOT NULL,
 UNIQUE(tenant_id,country_code,provider,resource_type,external_id),
 UNIQUE(tenant_id,country_code,provider,resource_type,internal_id),
 FOREIGN KEY(tenant_id,country_code) REFERENCES country_config
);
CREATE INDEX claim_queue ON claim(tenant_id,country_code,created_at DESC);
CREATE INDEX audit_aggregate ON audit_event(tenant_id,country_code,aggregate_id,sequence);
CREATE FUNCTION reject_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'History is append-only'; END $$;
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_transitions BEFORE UPDATE OR DELETE ON claim_state_transition FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_extraction BEFORE UPDATE OR DELETE ON extraction_version FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_review BEFORE UPDATE OR DELETE ON review FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_attempt BEFORE UPDATE OR DELETE ON payout_attempt FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
