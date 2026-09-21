#!/usr/bin/env node
// Round-trip regression for the frontend arg_transform builder: the UI must
// never rewrite rule semantics on edit-save, and its PATH grammar must stay
// equivalent to virbius-core::arg_transform::parse_path / ArgTransformValidator.
// Run: node scripts/test-arg-transform-roundtrip.mjs
import assert from 'node:assert';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const frontend = path.join(root, 'virbius-control', 'frontend');
const src = path.join(frontend, 'src', 'utils', 'argTransform.ts');
const tsc = path.join(frontend, 'node_modules', '.bin', 'tsc');

if (!existsSync(tsc)) {
  console.log('SKIP: frontend/node_modules missing (run npm install in virbius-control/frontend)');
  process.exit(0);
}
const outDir = mkdtempSync(path.join(tmpdir(), 'argtf-'));
const build = spawnSync(tsc, [
  '--module', 'commonjs', '--target', 'es2020', '--outDir', outDir, src,
], { stdio: 'inherit' });
if (build.status !== 0) {
  console.error('tsc failed');
  process.exit(1);
}
const { buildConfig, rowsFromConfig, newRow } = createRequire(import.meta.url)(
  path.join(outDir, 'argTransform.js'));

const cfg = (...mutations) => ({ phase: 'pre_tool_call', mutations });
const restrict = (path_, to) => cfg({ path: path_, op: 'restrict', to, on_violation: 'clamp' });
const roundTrip = (config) => buildConfig(rowsFromConfig(config));

// #2: min must survive an edit round-trip (was silently rewritten to {max:0})
assert.deepEqual(
  roundTrip(restrict('$.a', { min: 5 })).config.mutations[0].to,
  { min: 5 }, 'min-only range corrupted by round-trip');
assert.deepEqual(
  roundTrip(restrict('$.a', { min: 5, max: 10 })).config.mutations[0].to,
  { min: 5, max: 10 }, 'both bounds corrupted by round-trip');
assert.deepEqual(
  roundTrip(restrict('$.a', { max: 500 })).config.mutations[0].to,
  { max: 500 }, 'max-only regression');

// buildConfig rejects impossible ranges and half-empty bounds
assert.ok(buildConfig([{ ...newRow('max'), path: '$.a', min: 10, max: 5 }]).errors.length,
  'min>max accepted');
assert.ok(buildConfig([{ ...newRow('max'), path: '$.a', min: undefined, max: undefined }]).errors.length,
  'empty range accepted');

// #3: grammar parity — stacked indices legal, leading zeros illegal
assert.equal(
  roundTrip(restrict('$.matrix[0][1]', { max: 9 })).config?.mutations[0].path,
  '$.matrix[0][1]', 'stacked-index path rejected');
assert.deepEqual(
  buildConfig([{ ...newRow('max'), path: '$.a[1]', max: 5 }]).errors, [],
  'plain index rejected');
assert.ok(buildConfig([{ ...newRow('max'), path: '$.a[01]', max: 5 }]).errors.length,
  'leading-zero index accepted');

console.log('arg-transform round-trip: all assertions passed');
