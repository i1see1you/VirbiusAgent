// API client mirroring the original ops-common.js helpers.
// Envelope: { code, message, data }; code !== 0 throws.

let ctx: { tenant: string; apiKey: string } = { tenant: 'default', apiKey: '' };

export function configureApi(next: Partial<{ tenant: string; apiKey: string }>) {
  ctx = { ...ctx, ...next };
}

export function currentTenant(): string {
  return ctx.tenant || 'default';
}

function authHeaders(extra?: Record<string, string>, isFormData = false): Record<string, string> {
  const h: Record<string, string> = { ...(isFormData ? {} : { 'Content-Type': 'application/json' }), ...(extra || {}) };
  if (ctx.apiKey) h['Authorization'] = 'Bearer ' + ctx.apiKey;
  return h;
}

function redirectToLoginIfUnauthorized(res: Response): void {
  if (res.status !== 401 || typeof window === 'undefined') return;
  window.location.assign('/ui/login');
}

// Parse a response defensively: never call res.json() on an empty or non-JSON
// body (a 5xx with Content-Length: 0 used to surface as "Unexpected end of JSON
// input"). On !ok, extract the server's error message when present.
async function readJson(res: Response, label: string): Promise<any> {
  if (res.status === 204) return undefined;
  const text = await res.text();
  if (!res.ok) {
    let detail = text.trim();
    if (detail) {
      try {
        const j = JSON.parse(detail);
        detail = j?.error || j?.message || detail;
      } catch {
        // keep raw text
      }
    }
    throw new Error(`${label}: HTTP ${res.status}${detail ? ' — ' + String(detail).slice(0, 200) : ''}`);
  }
  if (!text.trim()) throw new Error(`${label}: empty response (HTTP ${res.status})`);
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`${label}: invalid JSON (HTTP ${res.status})`);
  }
}

export interface AdminOpts {
  method?: string;
  body?: string | FormData;
  headers?: Record<string, string>;
  raw?: boolean;
}

// Unwraps {code,message,data}; returns data. Throws on code !== 0.
export async function adminFetch<T = any>(url: string, opts: AdminOpts = {}): Promise<T> {
  const isFormData = typeof FormData !== 'undefined' && opts.body instanceof FormData;
  const res = await fetch(url, {
    method: opts.method,
    body: opts.body,
    credentials: 'same-origin',
    headers: authHeaders(opts.headers, isFormData)
  });
  redirectToLoginIfUnauthorized(res);
  const j = await readJson(res, 'request failed');
  if (j && typeof j === 'object' && 'code' in j) {
    if (j.code !== 0) throw new Error(j.message || ('HTTP ' + res.status));
    return j.data as T;
  }
  return j as T;
}

// Global admin (no tenant segment): /api/v1/admin{path}
export function adminRoot<T = any>(path: string, opts: AdminOpts = {}): Promise<T> {
  return adminFetch<T>('/api/v1/admin' + path, opts);
}

// Tenant-scoped admin: /api/v1/admin/tenants/{tenant}{path}
export function admin<T = any>(path: string, opts: AdminOpts = {}): Promise<T> {
  const url = '/api/v1/admin/tenants/' + encodeURIComponent(ctx.tenant || 'default') + path;
  return adminFetch<T>(url, opts);
}

// Raw JSON fetch (no envelope unwrap), e.g. /api/v1/challenges
export async function rawJson<T = any>(url: string, opts: AdminOpts = {}): Promise<T> {
  const res = await fetch(url, {
    method: opts.method,
    body: opts.body,
    credentials: 'same-origin',
    headers: authHeaders(opts.headers)
  });
  redirectToLoginIfUnauthorized(res);
  return (await readJson(res, 'request failed')) as T;
}

// Tenant-scoped multipart upload (browser sets the boundary): returns unwrapped data.
export async function adminUpload<T = any>(path: string, formData: FormData): Promise<T> {
  const url = '/api/v1/admin/tenants/' + encodeURIComponent(ctx.tenant || 'default') + path;
  const headers: Record<string, string> = {};
  if (ctx.apiKey) headers['Authorization'] = 'Bearer ' + ctx.apiKey;
  return adminFetch<T>(url, { method: 'POST', body: formData, headers });
}

export function jsonBody(obj: any): string {
  return JSON.stringify(obj);
}

/** Tenant-scoped download (HTML/SARIF), carrying the session/API-key headers. */
export async function adminDownload(path: string, filename: string): Promise<void> {
  const url = '/api/v1/admin/tenants/' + encodeURIComponent(ctx.tenant || 'default') + path;
  const res = await fetch(url, { credentials: 'same-origin', headers: authHeaders(undefined, true) });
  redirectToLoginIfUnauthorized(res);
  if (!res.ok) throw new Error('download failed: HTTP ' + res.status);
  const blob = await res.blob();
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(href);
}
