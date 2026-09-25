// Builder for tool-catalog arg_transforms (restrict / redact / truncate).
// After allow: replace with a definite value. Lists may filter to []. Scalar mismatch fails the call.

export type TransformKind = 'max' | 'values' | 'prefixes' | 'fixed' | 'match' | 'truncate' | 'redact';

export interface TransformRow {
  kind: TransformKind;
  path: string;
  name?: string;
  min?: number;
  max?: number;
  max_len?: number;
  listText?: string;
  detectors?: string[];
}

export const DETECTORS = ['phone_cn', 'idcard_cn', 'email', 'bank_card_cn'] as const;
// Grammar mirrors arg_transform::parse_path (virbius-core): `.key` with 0+
// `[n]` suffixes per segment, or leading `[n]`; `$..*string` is the only `..`
// form.  Leading zeros are banned so `$.a[01]` cannot alias `$.a[1]`.
const PATH_RE = /^\$(?:\.[A-Za-z0-9_@ -]+(?:\[(?:0|[1-9]\d*)])*|\[(?:0|[1-9]\d*)])+$|^\$\.\.\*string$/;
const MAX = 64;
const MAX_MATCH = 256;

export function newRow(kind: TransformKind = 'max'): TransformRow {
  return { kind, path: '', name: '', max: 500, max_len: 512, listText: '', detectors: ['phone_cn'] };
}

export function ensurePath(path: string): string {
  const p = path.trim();
  if (!p) return '';
  if (p.startsWith('$')) return p;
  return '$.' + p.replace(/^\.+/, '');
}

export function buildConfig(rows: TransformRow[]): { config: any | null; errors: string[] } {
  const errors: string[] = [];
  if (!rows.length) return { config: null, errors };
  if (rows.length > MAX) errors.push('too_many');
  const mutations: any[] = [];
  const paths = new Set<string>();
  rows.forEach((r, i) => {
    const at = `#${i + 1}`;
    const path = ensurePath(r.path);
    if (!path || !PATH_RE.test(path)) errors.push(`${at} path`);
    if (paths.has(path)) errors.push(`${at} dup_path`);
    paths.add(path);
    const name = (r.name || '').trim();
    if (name.length > 64) errors.push(`${at} name`);
    const m: any = { path, op: 'restrict' };
    if (name && name.length <= 64) m.name = name;
    switch (r.kind) {
      case 'max': {
        const hasMin = typeof r.min === 'number' && !Number.isNaN(r.min);
        const hasMax = typeof r.max === 'number' && !Number.isNaN(r.max);
        if (!hasMin && !hasMax) errors.push(`${at} max`);
        else if (hasMin && hasMax && r.min! > r.max!) errors.push(`${at} range`);
        else {
          m.to = {};
          if (hasMin) m.to.min = r.min;
          if (hasMax) m.to.max = r.max;
          m.on_violation = 'clamp';
        }
        if (path === '$..*string') errors.push(`${at} scan_op`);
        break;
      }
      case 'match': {
        if (path === '$..*string') errors.push(`${at} scan_op`);
        const pat = (r.listText || '').trim();
        if (!pat || pat.length > MAX_MATCH) errors.push(`${at} match`);
        else {
          try { new RegExp(pat); } catch { errors.push(`${at} match`); break; }
          m.to = { match: pat };
          m.on_violation = 'clamp';
        }
        break;
      }
      case 'values':
      case 'prefixes':
      case 'fixed': {
        if (path === '$..*string') errors.push(`${at} scan_op`);
        let parsed: any;
        try { parsed = JSON.parse(r.listText || (r.kind === 'fixed' ? '""' : '[]')); }
        catch { errors.push(`${at} json`); break; }
        if (r.kind === 'fixed') { m.to = [parsed]; }
        else if (r.kind === 'values') {
          m.to = Array.isArray(parsed) ? parsed : [parsed];
        } else {
          m.to = { prefixes: Array.isArray(parsed) ? parsed : [parsed] };
        }
        m.on_violation = 'clamp';
        break;
      }
      case 'truncate':
        m.op = 'truncate';
        if (!r.max_len || r.max_len <= 0) errors.push(`${at} max_len`);
        else m.max_len = r.max_len;
        break;
      case 'redact':
        m.op = 'redact';
        if (!r.detectors?.length) errors.push(`${at} detector`);
        else m.detectors = r.detectors;
        break;
    }
    mutations.push(m);
  });
  return errors.length ? { config: null, errors } : { config: { phase: 'pre_tool_call', mutations }, errors };
}

export function rowsFromConfig(raw?: any): TransformRow[] {
  if (!raw) return [];
  try {
    const cfg = typeof raw === 'string' ? JSON.parse(raw) : raw;
    const mutations = Array.isArray(cfg) ? cfg : (Array.isArray(cfg?.mutations) ? cfg.mutations : []);
    return mutations.map((m: any) => {
      const path = m.path || '';
      const name = typeof m.name === 'string' ? m.name : '';
      if (m.op === 'truncate') return { ...newRow('truncate'), path, name, max_len: m.max_len || 512 };
      if (m.op === 'redact') {
        const detectors = Array.isArray(m.detectors) ? m.detectors : (m.detector ? [m.detector] : ['phone_cn']);
        return { ...newRow('redact'), path, name, detectors };
      }
      const to = m.to;
      if (to && typeof to === 'object' && !Array.isArray(to) && typeof to.match === 'string') {
        return { ...newRow('match'), path, name, listText: to.match };
      }
      if (to && typeof to === 'object' && !Array.isArray(to) && ('max' in to || 'min' in to)) {
        // Preserve real bounds exactly; absent bound stays undefined so the
        // editor never invents a `max: 0` for a min-only rule.
        return {
          ...newRow('max'),
          path,
          name,
          min: typeof to.min === 'number' ? to.min : undefined,
          max: typeof to.max === 'number' ? to.max : undefined,
        };
      }
      if (to && typeof to === 'object' && Array.isArray(to.prefixes)) {
        return { ...newRow('prefixes'), path, name, listText: JSON.stringify(to.prefixes) };
      }
      const vals = Array.isArray(to) ? to : (to?.values || []);
      if (vals.length === 1) return { ...newRow('fixed'), path, name, listText: JSON.stringify(vals[0]) };
      return { ...newRow('values'), path, name, listText: JSON.stringify(vals) };
    });
  } catch {
    return [];
  }
}
