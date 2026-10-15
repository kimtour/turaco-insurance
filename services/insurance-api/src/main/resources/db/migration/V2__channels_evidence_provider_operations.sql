ALTER TABLE claim ADD COLUMN provider_kind text NOT NULL DEFAULT 'STANDALONE' CHECK(provider_kind IN ('STANDALONE','LOCAL_SIMULATOR'));
ALTER TABLE claim ADD COLUMN provider_revision bigint NOT NULL DEFAULT 0;
CREATE TABLE claim_evidence (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, claim_id uuid NOT NULL,
 media_type text NOT NULL CHECK(media_type='text/plain'), ciphertext bytea NOT NULL,
 digest text NOT NULL, verification_status text NOT NULL CHECK(verification_status IN ('MATCH','CONFLICT','UNVERIFIED','QUARANTINED')),
 retention_until timestamptz NOT NULL, actor text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,country_code,id), FOREIGN KEY(tenant_id,country_code,claim_id) REFERENCES claim(tenant_id,country_code,id)
);
CREATE TABLE channel_receipt (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL,
 message_id text NOT NULL, digest text NOT NULL, ciphertext bytea NOT NULL,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PROCESSED','DEAD')),
 claim_id uuid, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,country_code,message_id),
 FOREIGN KEY(tenant_id,country_code) REFERENCES country_config,
 FOREIGN KEY(tenant_id,country_code,claim_id) REFERENCES claim(tenant_id,country_code,id)
);
CREATE TABLE evidence_resolution (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, evidence_id uuid NOT NULL,
 replacement_id uuid NOT NULL, actor text NOT NULL, evidence_reference text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id,country_code,evidence_id),
 FOREIGN KEY(tenant_id,country_code,evidence_id) REFERENCES claim_evidence(tenant_id,country_code,id),
 FOREIGN KEY(tenant_id,country_code,replacement_id) REFERENCES claim_evidence(tenant_id,country_code,id)
);
CREATE TABLE provider_receipt (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, claim_id uuid NOT NULL,
 event_id uuid NOT NULL, revision bigint NOT NULL, status text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,country_code,claim_id) REFERENCES claim(tenant_id,country_code,id)
);
CREATE TABLE provider_applied_event (
 tenant_id uuid NOT NULL, country_code char(2) NOT NULL, event_id uuid NOT NULL,
 PRIMARY KEY(tenant_id,country_code,event_id), FOREIGN KEY(tenant_id,country_code) REFERENCES country_config
);
CREATE TABLE mock_payment_receipt (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, obligation_id uuid NOT NULL,
 outcome text NOT NULL CHECK(outcome IN ('CONFIRMED','FAILED')), evidence_reference text NOT NULL, actor text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id,country_code,obligation_id),
 FOREIGN KEY(tenant_id,country_code,obligation_id) REFERENCES payment_obligation(tenant_id,country_code,id)
);
CREATE TABLE queue_delivery (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL,
 event_id uuid NOT NULL UNIQUE REFERENCES outbox_event(id), attempts integer NOT NULL DEFAULT 0,
 status text NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','DONE','DEAD')),
 available_at timestamptz NOT NULL DEFAULT now(), created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,country_code) REFERENCES country_config
);
CREATE TABLE processed_event (
 tenant_id uuid NOT NULL, country_code char(2) NOT NULL, event_id uuid NOT NULL,
 processed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,country_code,event_id),
 FOREIGN KEY(tenant_id,country_code) REFERENCES country_config
);
CREATE INDEX queue_ready ON queue_delivery(tenant_id,country_code,status,available_at);
CREATE INDEX channel_pending ON channel_receipt(tenant_id,country_code,status,created_at);
CREATE TRIGGER immutable_evidence BEFORE UPDATE OR DELETE ON claim_evidence FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_provider_receipt BEFORE UPDATE OR DELETE ON provider_receipt FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_mock_payment_receipt BEFORE UPDATE OR DELETE ON mock_payment_receipt FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_evidence_resolution BEFORE UPDATE OR DELETE ON evidence_resolution FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
