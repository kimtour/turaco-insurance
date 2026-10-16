import assert from 'node:assert/strict';
import {test} from 'node:test';
import {evidenceReferenceError} from '../src/action-validation.js';

test('evidence reference is required before recording an action', () => {
  assert.equal(evidenceReferenceError(''), 'Enter an evidence reference before recording this action.');
  assert.equal(evidenceReferenceError('   '), 'Enter an evidence reference before recording this action.');
  assert.equal(evidenceReferenceError('synthetic-review-001'), null);
});
