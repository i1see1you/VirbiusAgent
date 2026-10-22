import { admin, adminRoot } from '@/api/client';

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
  updatedBy?: string | null;
}

export interface EvaluationSettingsUpdate {
  enabled: boolean;
  baseUrl: string;
  apiToken?: string | null;   // null/省略 = 保持不变；"" = 清除
  promoEnabled: boolean;
  promoUrl: string;
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

export function listReports(targetFingerprint?: string): Promise<ReportMeta[]> {
  const qs = targetFingerprint
    ? '?target_fingerprint=' + encodeURIComponent(targetFingerprint)
    : '';
  return admin('/evaluation/reports' + qs);
}

export function getReport(reportId: string): Promise<ScanReport> {
  return admin('/evaluation/reports/' + encodeURIComponent(reportId));
}

export function createScan(body: ScanRequest): Promise<ScanReport> {
  return admin('/evaluation/scans', { method: 'POST', body: JSON.stringify(body) });
}

export function createProbe(body: ProbeRequest): Promise<ScanReport> {
  return admin('/evaluation/probes', { method: 'POST', body: JSON.stringify(body) });
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
