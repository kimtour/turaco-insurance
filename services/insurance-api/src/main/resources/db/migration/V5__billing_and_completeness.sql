CREATE TABLE partner_batch (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL,
 client_key text NOT NULL, request_digest text NOT NULL, partner_code text NOT NULL,
 billing_period char(7) NOT NULL, filename text NOT NULL, ciphertext bytea NOT NULL,
 stage text NOT NULL CHECK(stage IN ('RECEIVED','PROFILING','MAPPING_PROPOSED','MAPPING_REVIEW','TRANSFORMED','VALIDATED','EXCEPTIONS_REVIEW','APPROVED','INVOICE_PREPARED','COMPLETED','FAILED_RETRYABLE','FAILED_FINAL','CANCELLED')),
 profile jsonb NOT NULL DEFAULT '{}', mapping jsonb NOT NULL DEFAULT '{}',
 validation jsonb NOT NULL DEFAULT '{}', excluded_rows jsonb NOT NULL DEFAULT '[]',
 model_trace jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,country_code,id), UNIQUE(tenant_id,country_code,client_key),
 FOREIGN KEY(tenant_id,country_code) REFERENCES country_config
);
CREATE TABLE billing_transition (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, batch_id uuid NOT NULL,
 stage text NOT NULL, actor text NOT NULL, evidence_reference text, created_at timestamptz NOT NULL DEFAULT now(),
 sequence bigserial UNIQUE,
 FOREIGN KEY(tenant_id,country_code,batch_id) REFERENCES partner_batch(tenant_id,country_code,id)
);
CREATE TABLE simulated_invoice (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, batch_id uuid NOT NULL,
 partner_code text NOT NULL, billing_period char(7) NOT NULL, amount numeric(14,2) NOT NULL CHECK(amount>0),
 currency char(3) NOT NULL, record_count integer NOT NULL CHECK(record_count>0), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,country_code,batch_id), UNIQUE(tenant_id,country_code,partner_code,billing_period),
 FOREIGN KEY(tenant_id,country_code,batch_id) REFERENCES partner_batch(tenant_id,country_code,id)
);
CREATE TRIGGER immutable_billing_transition BEFORE UPDATE OR DELETE ON billing_transition FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_simulated_invoice BEFORE UPDATE OR DELETE ON simulated_invoice FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE INDEX partner_batch_queue ON partner_batch(tenant_id,country_code,created_at DESC);

CREATE TABLE document_requirement (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, product_id uuid NOT NULL,
 incident_type_code text NOT NULL, version integer NOT NULL CHECK(version>0), document_type text NOT NULL,
 label text NOT NULL, effective_from date NOT NULL, effective_until date,
 CHECK(effective_until IS NULL OR effective_until>effective_from),
 UNIQUE(tenant_id,country_code,product_id,incident_type_code,version,document_type),
 FOREIGN KEY(tenant_id,country_code,product_id) REFERENCES product(tenant_id,country_code,id)
);
ALTER TABLE claim_evidence ADD COLUMN document_type text;
ALTER TABLE claim_evidence ADD COLUMN customer_id uuid;
ALTER TABLE claim_evidence ADD COLUMN extraction_confidence numeric CHECK(extraction_confidence BETWEEN 0 AND 1);
ALTER TABLE claim_evidence ADD CONSTRAINT evidence_customer_scope FOREIGN KEY(tenant_id,country_code,customer_id) REFERENCES customer(tenant_id,country_code,id);
CREATE TABLE document_review (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, country_code char(2) NOT NULL, claim_id uuid NOT NULL,
 requirement_id uuid NOT NULL REFERENCES document_requirement(id), evidence_id uuid,
 extraction_digest text NOT NULL, decision text NOT NULL CHECK(decision IN ('VERIFIED','CONFLICT','WAIVED_BY_AUTHORIZED_REVIEWER')),
 actor text NOT NULL, evidence_reference text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), sequence bigserial UNIQUE,
 FOREIGN KEY(tenant_id,country_code,claim_id) REFERENCES claim(tenant_id,country_code,id),
 FOREIGN KEY(tenant_id,country_code,evidence_id) REFERENCES claim_evidence(tenant_id,country_code,id),
 CHECK((decision='WAIVED_BY_AUTHORIZED_REVIEWER') OR evidence_id IS NOT NULL)
);
CREATE TRIGGER immutable_document_review BEFORE UPDATE OR DELETE ON document_review FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

-- Effective dates preserve historical demo incidents. Requirements are synthetic.
INSERT INTO document_requirement(id,tenant_id,country_code,product_id,incident_type_code,version,document_type,label,effective_from)
SELECT gen_random_uuid(),p.tenant_id,p.country_code,p.id,'HOSPITALIZATION',1,d.kind,d.label,'2026-10-01'
FROM product p CROSS JOIN (VALUES ('ADMISSION_RECORD','Admission record'),('DISCHARGE_SUMMARY','Discharge summary'),('IDENTITY_DOCUMENT','Identity document')) AS d(kind,label)
WHERE p.code='HOSPITAL_CASH';
