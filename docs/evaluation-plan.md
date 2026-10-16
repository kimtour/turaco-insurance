# Extraction evaluation scope

Version 0.2 includes the runnable `app.evaluation` stratified synthetic regression report and durable conversation, document-conflict and Anthropic-adapter checks. These remain synthetic contracts, not field language/audio validation. The local simulator never converts fixture success into production capability status.

Four synthetic cases cover English, Swahili, code-switching and missing discharge date. They verify contracts and deterministic fixture behavior, not language proficiency or medical accuracy.

CI runs `python -m app.evaluation datasets/eval/hospital-cash.json --check` from an environment where the `app` package is available, using the appropriate relative dataset path. The gate fails if any deterministic fixture expectation differs or the dataset is empty. Its uploaded `synthetic-extraction-evaluation` report separates code-switching and records exact-match field accuracy/error rate, true positives, false positives, false negatives, precision and recall. A wrong non-null value counts as both a false positive and false negative; correctly absent fields contribute to accuracy but not true positives. Precision/recall with no denominator are null. These are regression measurements on four invented fixtures, not production language thresholds.

Tests check evidence quotes, confirmation, unknowns, provider failures, authentication and unsupported languages. The SDK adapter uses mocked Responses output; a live call requires explicit credentials/model. Real output needs separate representative annotated evaluation.

Before pilot, recruit native-speaker annotators and obtain consented, de-identified data stratified by country, language, dialect, code-switching and modality. Measure critical-field accuracy and unsupported-value rates separately. Compare provider/model versions, grounding, clarification, latency and cost. Calibrate confidence empirically; fixture values are not calibrated probabilities.

The registry sets production_enabled=false for both synthetic text pairs. Speech is disabled with authorized human assistance as fallback. An early ASR spike needs an owner, annotation budget, consent process, language-specific WER/CER and agreed critical-field thresholds after baseline measurement. No audio results or production thresholds are invented.
