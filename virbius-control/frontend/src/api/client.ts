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
  const j = await res.json();
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
  return (await res.json()) as T;
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
