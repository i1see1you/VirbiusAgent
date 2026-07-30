import i18n from '@/locales';

export function field<T = any>(obj: any, ...keys: string[]): T | undefined {
  if (!obj) return undefined;
  for (const k of keys) {
    if (obj[k] !== undefined && obj[k] !== null) return obj[k];
  }
  return undefined;
}

export function esc(s: any): string {
  return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

export function parseUtc(s: any): Date | null {
  if (!s) return null;
  let iso = String(s).includes('T') ? String(s) : String(s).replace(' ', 'T');
  if (!/[zZ]|[+-]\d{2}:?\d{2}$/.test(iso)) iso += 'Z';
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : new Date(t);
}

export function fmtTime(s: any): string {
  if (!s) return '-';
  const d = parseUtc(s);
  if (!d) return String(s).replace('T', ' ').slice(0, 19);
  return d.toLocaleString(undefined, { hour12: false });
}

export function fmtTimeAgo(s: any): string {
  if (!s) return '-';
  const d = parseUtc(s);
  if (!d) return '-';
  const diff = Date.now() - d.getTime();
  const t = i18n.global.t;
  if (diff < 60000) return t('time.just-now');
  if (diff < 3600000) return t('time.minutes-ago', [Math.floor(diff / 60000)]);
  if (diff < 86400000) return t('time.hours-ago', [Math.floor(diff / 3600000)]);
  return t('time.days-ago', [Math.floor(diff / 86400000)]);
}

export function parseMethodsText(s: any): string[] {
  return String(s || 'POST').split(/[,，\s]+/).map(x => x.trim().toUpperCase()).filter(Boolean);
}

export function inferListStorage(dim: string, storage?: string): 'memory' | 'redis' {
  if (storage) return String(storage).toLowerCase() as any;
  const d = (dim || '').toLowerCase();
  if (d === 'keyword' || d === 'ip_cidr' || d === 'ip' || d === 'content') return 'memory';
  if (d === 'user_id' || d === 'device_id' || d === 'var' || d.startsWith('var:')) return 'redis';
  return 'memory';
}

export function formatListStorage(storage: string): string {
  const t = i18n.global.t;
  return storage === 'redis' ? 'Redis' : t('lists.storage-memory');
}

export function formatListDimension(dim: string): string {
  if (!dim) return '-';
  const t = i18n.global.t;
  if (String(dim).startsWith('var:')) return 'var(' + dim.slice(4) + ')';
  const map: Record<string, string> = {
    keyword: t('lists.dim-keyword'),
    user_id: t('lists.dim-user'),
    device_id: t('lists.dim-device'),
    ip_cidr: 'IP/CIDR'
  };
  return (map[dim] || dim) + ' `' + dim + '`';
}

export function isListEntryActive(expiresAt: any): boolean {
  if (!expiresAt) return true;
  const t = Date.parse(String(expiresAt));
  return !Number.isNaN(t) && t > Date.now();
}

export function countActiveListEntries(entries: any[]): number {
  if (!Array.isArray(entries)) return 0;
  return entries.filter(e => isListEntryActive(field(e, 'expires_at', 'expiresAt'))).length;
}

export function listEntryValue(e: any): string {
  if (typeof e === 'string') return e;
  return field(e, 'value') || '';
}

export function formatBindScope(scope: any): string {
  const s = scope || {};
  const bs = s.bind_scope || 'global';
  const ref = s.bind_ref || {};
  if (bs === 'tool') {
    const tools = Array.isArray(ref.tool_names) ? ref.tool_names.join(', ') : '';
    const ids = Array.isArray(ref.app_ids) ? ref.app_ids.join(', ') : '';
    return (tools || ids) ? `tool:${tools || '*'}` + (ids ? ` [${ids}]` : '') : 'tool';
  }
  if (bs === 'service') {
    const ids = Array.isArray(ref.app_ids) ? ref.app_ids.join(', ') : '';
    return ids ? `service:${ids}` : 'service';
  }
  return 'global';
}

export function ruleStatusTagClass(st: string): string {
  const s = st || 'draft';
  return s === 'disabled' ? 'disabled' : (s === 'draft' ? 'draft' : '');
}

export function inExecutionPlane(st: string): boolean {
  return st === 'dry_run' || st === 'canary' || st === 'full';
}

export function parseJsonSafe(text: string): any {
  return JSON.parse(text);
}

export function statusClass(st: string): string {
  if (st === 'active') return 'success';
  if (st === 'revoked') return 'danger';
  return 'warning';
}

export function rateClass(rate: number): string {
  if (rate < 1) return 'rate-low';
  if (rate < 5) return 'rate-mid';
  return 'rate-high';
}
