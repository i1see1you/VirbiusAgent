import { admin, adminDownload, adminRoot } from '@/api/client';

// 控制面「评估」页 API（P2-c）：经 control 代理到 virbius-evaluation REST。
// 权限沿用 ApiKeyRoutePolicy（GET=TENANT_VIEWER / POST=TENANT_ADMIN）。

export interface EvaluationSummary {
  critical: number;
  high: number;
  medium: number;
  low: number;
  info: number;
  risk_score: number;
  grade: string;
}

export interface ReportMeta {
  report_id: string;
  agent_name: string;
  target_path: string;
  scanned_at: string;
  summary: EvaluationSummary;
  target_fingerprint?: string | null;
  target_type?: string | null;
  suppressed_count?: number | null;
  tenant?: string | null;
}

/** 列表统一分页信封（v3.1）。 */
export interface ReportPage {
  items: ReportMeta[];
  total: number;
  limit: number;
  offset: number;
}

export interface Evidence {
  type: string;
  ref: string;
  snippet?: string | null;
}

export interface Finding {
  risk_id: string;
  check_id: string;
  risk_name: string;
  severity: string;
  owasp?: string[];
  evidence?: Evidence[];
  virbius_config?: string[];
  commands?: string[];
  status?: string;
  first_seen?: string;
  last_seen?: string;
  confidence?: string | null;
  suppressed?: boolean | null;
  suppression?: { id?: string; reason?: string } | null;
}

export interface RescanResult {
  baseline_report_id: string;
  fixed: string[];
  regressed: string[];
  new_findings: string[];
  external_drift: string[];
  baseline_version_drift?: boolean | null;
}

export interface ScanReport {
  report_id: string;
  agent_name: string;
  target_path: string;
  scanned_at: string;
  summary: EvaluationSummary;
  findings: Finding[];
  rescan?: RescanResult | null;
  meta?: Record<string, unknown> | null;
}

export interface ScanRequest {
  target_path: string;
  agent_name?: string;
  baseline_report_id?: string;
}

export interface ProbeRequest {
  target_type: 'http' | 'mcp';
  url?: string;
  model?: string;
  identity?: string;
  mcp_cmd?: string[];
  agent_name?: string;
  baseline_report_id?: string;
  allow_dangerous?: boolean;
  retain_raw?: boolean;
}

/** 探针提交结果（202；轮询 job）。 */
export interface JobSubmit {
  job_id: string;
  status: string;
  poll_after_ms: number;
  deduplicated?: boolean;
}

export interface JobProgress {
  done: number;
  total: number;
  current?: string | null;
}

export interface JobView {
  job_id: string;
  kind: string;
  status: 'queued' | 'running' | 'canceling' | 'canceled' | 'succeeded' | 'failed';
  phase: string;
  progress?: JobProgress | null;
  report_id?: string | null;
  error?: { code: number; message: string } | null;
  created_at?: string | null;
  started_at?: string | null;
  finished_at?: string | null;
}

export interface JobPage {
  items: JobView[];
  total: number;
  limit: number;
  offset: number;
}

export interface ReplayRequest {
  tenant: string;
  app: string;
  since?: string;
  until?: string;
  baseline_report_id?: string;
}

export interface EvaluationLicense {
  mode: string;                 // trial / valid / grace / expired / invalid / unknown
  edition?: string | null;
  features?: string[];
  expires_at?: string | null;
  days_left?: number | null;
  active?: boolean | null;
  clock_frozen?: boolean | null;
  instance_id?: string | null;
  detail?: string | null;
}

export interface EvaluationStatus {
  enabled: boolean;             // false → 前端隐藏入口（运维显式关闭集成）
  configured: boolean;          // base-url 是否配置
  reachable: boolean;           // 健康探测是否可达
  license?: EvaluationLicense | null;
  promoEnabled: boolean;        // 商业引导开关（企业可关）
  promoUrl?: string | null;
}

export interface EvaluationSettingsView {
  enabled: boolean;
  baseUrl: string;
  apiTokenSet: boolean;
  promoEnabled: boolean;
  promoUrl: string;
  probeTenants: string;
  updatedBy?: string | null;
}

export interface EvaluationSettingsUpdate {
  enabled: boolean;
  baseUrl: string;
  apiToken?: string | null;   // null/省略 = 保持不变；"" = 清除
  promoEnabled: boolean;
  promoUrl: string;
  probeTenants: string;
}

export interface DiagStep {
  step: string;
  ok: boolean;
  detail: string;
  ms: number;
}

export interface DiagResult {
  ok: boolean;
  steps: DiagStep[];
}

const SETTINGS_BASE = '/platform/evaluation';

export function getSettings(): Promise<EvaluationSettingsView> {
  return adminRoot(SETTINGS_BASE + '/settings');
}

export function updateSettings(body: EvaluationSettingsUpdate): Promise<EvaluationSettingsView> {
  return adminRoot(SETTINGS_BASE + '/settings', { method: 'PUT', body: JSON.stringify(body) });
}

export function testSettings(): Promise<DiagResult> {
  return adminRoot(SETTINGS_BASE + '/settings/test', { method: 'POST', body: '{}' });
}

export function uploadLicense(jwt: string): Promise<unknown> {
  return adminRoot(SETTINGS_BASE + '/license', { method: 'POST', body: JSON.stringify({ jwt }) });
}

export function getStatus(refresh = false): Promise<EvaluationStatus> {
  return admin('/evaluation/status' + (refresh ? '?refresh=true' : ''));
}

export interface ReportQuery {
  targetFingerprint?: string;
  agentName?: string;
  grade?: string;
  targetType?: string;
  limit?: number;
  offset?: number;
}

export function listReports(q: ReportQuery = {}): Promise<ReportPage> {
  const params = new URLSearchParams();
  if (q.targetFingerprint) params.set('target_fingerprint', q.targetFingerprint);
  if (q.agentName) params.set('agent_name', q.agentName);
  if (q.grade) params.set('grade', q.grade);
  if (q.targetType) params.set('target_type', q.targetType);
  if (q.limit != null) params.set('limit', String(q.limit));
  if (q.offset != null) params.set('offset', String(q.offset));
  const qs = params.toString();
  return admin('/evaluation/reports' + (qs ? '?' + qs : ''));
}

export function getReport(reportId: string): Promise<ScanReport> {
  return admin('/evaluation/reports/' + encodeURIComponent(reportId));
}

export function createScan(body: ScanRequest): Promise<ScanReport> {
  return admin('/evaluation/scans', { method: 'POST', body: JSON.stringify(body) });
}

export function createProbe(body: ProbeRequest): Promise<JobSubmit> {
  return admin('/evaluation/probes', { method: 'POST', body: JSON.stringify(body) });
}

export function getJob(jobId: string): Promise<JobView> {
  return admin('/evaluation/jobs/' + encodeURIComponent(jobId));
}

export function listJobs(status?: string, limit = 20, offset = 0): Promise<JobPage> {
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (status) params.set('status', status);
  return admin('/evaluation/jobs?' + params.toString());
}

export function cancelJob(jobId: string): Promise<JobView> {
  return admin('/evaluation/jobs/' + encodeURIComponent(jobId) + '/cancel', { method: 'POST' });
}

/** HTML/SARIF/JSON 导出（经控制面代理，携带会话鉴权）。 */
export function downloadReport(reportId: string, format: 'html' | 'sarif' | 'json'): Promise<void> {
  const ext = format === 'json' ? 'json' : format;
  const path = format === 'json'
    ? '/evaluation/reports/' + encodeURIComponent(reportId)
    : '/evaluation/reports/' + encodeURIComponent(reportId) + '/' + format;
  return adminDownload(path, reportId + '.' + ext);
}

export function createReplay(body: ReplayRequest): Promise<ScanReport> {
  return admin('/evaluation/replays', { method: 'POST', body: JSON.stringify(body) });
}

export interface SkillRequest {
  target_path: string;
  agent_name?: string;
  baseline_report_id?: string;
}

export function createSkillScan(body: SkillRequest): Promise<ScanReport> {
  return admin('/evaluation/skills', { method: 'POST', body: JSON.stringify(body) });
}

export interface ReplayImportRequest {
  format: 'jsonl';
  tenant: string;
  app: string;
  events: string;
  coverage?: string;
  baseline_report_id?: string;
  allow_empty?: boolean;
}

export function importReplay(body: ReplayImportRequest): Promise<ScanReport> {
  return admin('/evaluation/replays/import', { method: 'POST', body: JSON.stringify(body) });
}
