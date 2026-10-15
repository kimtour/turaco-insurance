-- Retain the original extraction fingerprint while caching text request identity.
ALTER TABLE claim ADD COLUMN text_request_digest text
 CHECK(text_request_digest IS NULL OR text_request_digest ~ '^[a-f0-9]{64}$');

-- Existing evidence has no proven snapshot binding and must be checked again.
ALTER TABLE claim_evidence ADD COLUMN extraction_digest text
 CHECK(extraction_digest IS NULL OR extraction_digest ~ '^[a-f0-9]{64}$');

-- Corrections may require a new replacement without rewriting prior resolutions.
ALTER TABLE evidence_resolution DROP CONSTRAINT evidence_resolution_tenant_id_country_code_evidence_id_key;
ALTER TABLE evidence_resolution ADD CONSTRAINT unique_evidence_replacement
 UNIQUE(tenant_id,country_code,evidence_id,replacement_id);
