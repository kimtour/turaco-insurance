export function evidenceReferenceError(value) {
  return typeof value === 'string' && value.trim()
    ? null
    : 'Enter an evidence reference before recording this action.';
}
