import { admin } from '@/api/client';

export interface IntegrateApp {
  app_id: string;
  last_seen: string | null;
}

export interface ObservationEvent {
  event_id: string;
  call_id: string;
  phase: 'started' | 'completed' | 'error';
  device_id?: string;
  session_id?: string;
  trace_id?: string;
  tool_name?: string;
  duration_ms?: number | null;
  args_repr?: string | null;
  result_repr?: string | null;
  error?: string | null;
  source?: string;
  package_version?: string;
  observed_at?: string;
}

export interface InventoryTool {
  tool_name: string;
  mcp_name?: string | null;
  risk_class?: string;
  matched_rules?: string;
  confidence?: string;
  recommended_mode?: string;
  rule_table_version?: string;
  package_version?: string;
  bound_mode?: string;
  first_seen?: string;
  last_seen?: string;
}

export interface LivenessDevice {
  device_id: string;
  last_seen?: string | null;
  last_event_ts?: string | null;
  dropped_total?: number;
  package_version?: string;
  mode?: string;
  state?: 'live' | 'stale' | 'never-seen' | string;
  last_seen_age_seconds?: number | null;
  last_event_age_seconds?: number | null;
}

export function listApps(): Promise<{ apps: IntegrateApp[]; count: number }> {
  return admin('/integrate/apps');
}

export function listObservations(
  appId: string,
  params: Record<string, string> = {}
): Promise<{ events: ObservationEvent[]; count: number; note?: string }> {
  const qs = new URLSearchParams(params).toString();
  const suffix = qs ? '?' + qs : '';
  return admin('/integrate/apps/' + encodeURIComponent(appId) + '/observations' + suffix);
}

export function getCoverage(
  appId: string
): Promise<{ tools: InventoryTool[]; warnings: InventoryTool[]; note?: string }> {
  return admin('/integrate/apps/' + encodeURIComponent(appId) + '/coverage');
}

export function getLiveness(
  appId: string
): Promise<{ devices: LivenessDevice[]; count: number }> {
  return admin('/integrate/apps/' + encodeURIComponent(appId) + '/liveness');
}

export function recentAudit(limit = 100): Promise<any> {
  return admin('/audit/recent?limit=' + limit);
}
