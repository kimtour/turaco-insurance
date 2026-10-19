# ADR-003: selected model, region and hosting defaults

Date: 19 October 2026. Status: selected at the user's request; live deployment awaits account access and evaluation. Synthetic data only.

The user delegated the choice of infrastructure and providers. These decisions remove configuration ambiguity; they do not create an AWS account, accept a provider contract or establish production readiness.

| Decision | Selection | Reason |
|---|---|---|
| Default execution | Deterministic mock adapters | Offline demos and CI work without credentials or inference charges |
| First live model candidate | Bedrock `openai.gpt-oss-20b-1:0` | An open-weight text model suitable for an initial bounded mapping/extraction evaluation; task quality remains unverified |
| Pilot region | `eu-west-1` (Ireland), in-region inference | AWS documents this model in-region there; keep application and inference in one selected region |
| Cross-region inference | Disabled | The IAM example names only the regional foundation model, not inference profiles |
| Current demonstration hosting | Existing Cloudflare hub and existing Codespace | Preserve the user's existing presentation and free-allowance operating choice |
| Managed application target | ECS Fargate, separate Java/AI/dashboard services; managed PostgreSQL on RDS | A future managed pilot avoids introducing EKS operations for this small application |
| Identity | Retain Keycloak and the existing signed-scope contract for the synthetic demo | A production identity deployment must replace dev-mode/password-grant fixtures and enroll real users |
| Secrets | AWS Secrets Manager and ECS workload roles | Avoid credentials in files or browser sessions; exact account identifiers await the user's account |
| WhatsApp | Direct Meta WhatsApp Cloud API after authorized enrollment | Matches the existing signature boundary; no live account or template approval is assumed |
| Turaco integration | Official partner API through the existing ports | Public reference documentation informs design; authenticated contracts and sandbox permissions are still needed |
| Payments | Retain the simulator until an authorized disbursement contract exists | Premium notification APIs are not assumed to execute claim payouts |
| Voice | Typed-transcript rehearsal and human handoff | Choose real ASR/TTS only after representative language/audio evaluation |

AWS's [model card](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-openai-gpt-oss-20b.html) lists the selected runtime model ID, Converse and structured-output support, and in-region availability in Ireland as checked on this date. This documents service availability, not access in a particular AWS account. Confirm it again before live deployment. Consult [current pricing](https://aws.amazon.com/bedrock/pricing/) before enabling inference.

`infrastructure/aws/bedrock.synthetic.env.example` records the selected configuration with live access disabled. Its $1 input / $3 output per million tokens are deliberately conservative budgeting parameters, not advertised prices. The application rejects a request whose bounded token ceiling exceeds $0.15 under those assumptions. Account-level budgets, quotas and alerts are still necessary; this is not a spending guarantee.

`infrastructure/aws/bedrock-inference-policy.json` is a least-privilege policy example for the selected model and region. It is not attached to any principal. Do not expand it to wildcard models or regions to bypass access failures.

Ireland is a synthetic pilot selection. It is not a determination that real Kenyan or other market insurance/medical data may be transferred there. Production region, retention, encryption and cross-border handling require the applicable owner and legal/privacy approval.

No AWS CLI configuration or credentials directory was present in this environment during the check. Cloud resources and provider agreements cannot be completed from a model/region choice alone. No AWS resources were provisioned and no live model calls were made.
