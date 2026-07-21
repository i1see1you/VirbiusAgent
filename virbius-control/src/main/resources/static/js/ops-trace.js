    // ── Agent decision chain trace page ──

    let traceTimelineData = [];

    async function loadTraceSearch() {
      const toolName = document.getElementById('traceSearchTool')?.value.trim() || '';
      const stepType = document.getElementById('traceSearchType')?.value || '';
      const decision = document.getElementById('traceSearchDecision')?.value || '';
      const limit = parseInt(document.getElementById('traceSearchLimit')?.value || '50', 10);
      try {
        const params = new URLSearchParams();
        if (toolName) params.set('tool_name', toolName);
        if (stepType) params.set('step_type', stepType);
        if (decision) params.set('tool_decision', decision);
        params.set('limit', String(limit));
        const data = await admin('/trace/search?' + params.toString());
        renderTraceSearchResults(data || []);
      } catch (e) {
        log(e.message, 'err');
      }
    }

    function renderTraceSearchResults(rows) {
      const body = document.getElementById('traceSearchBody');
      if (!body) return;
      if (!rows.length) {
        body.innerHTML = '<tr><td colspan="8" class="empty-state">暂无数据</td></tr>';
        return;
      }
      body.innerHTML = rows.map(r => {
        const decisionTag = r.tool_decision
          ? `<span class="tag ${r.tool_decision === 'allow' ? 'allow' : 'deny'}">${esc(r.tool_decision)}</span>`
          : '—';
        const stepTypeTag = r.step_type
          ? `<span class="tag">${esc(r.step_type)}</span>`
          : '—';
        return `<tr class="trace-row" data-session="${escAttr(r.session_id)}" data-trace="${escAttr(r.trace_id)}">
          <td>${esc(r.trace_id || '').slice(0, 12)}…</td>
          <td>${esc(r.session_id || '').slice(0, 12)}…</td>
          <td>${stepTypeTag}</td>
          <td>${esc(r.tool_name || '—')}</td>
          <td>${decisionTag}</td>
          <td>${r.risk_score ?? '—'}</td>
          <td>${r.tool_duration_ms != null ? r.tool_duration_ms + 'ms' : '—'}</td>
          <td>${fmtTime(r.occurred_at)}</td>
        </tr>`;
      }).join('');

      // Click to view session timeline
      body.querySelectorAll('.trace-row').forEach(tr => {
        tr.onclick = () => {
          const sid = tr.dataset.session;
          if (sid) loadTraceTimeline(sid);
        };
        tr.style.cursor = 'pointer';
      });
    }

    async function loadTraceTimeline(sessionId) {
      if (!sessionId) return;
      document.getElementById('traceTimelineSession').textContent = sessionId;
      try {
        const data = await admin('/trace/session/' + encodeURIComponent(sessionId) + '/timeline');
        traceTimelineData = data || [];
        renderTraceTimeline(traceTimelineData);
        log('Loaded ' + traceTimelineData.length + ' steps for session ' + sessionId.slice(0, 12), 'ok');
      } catch (e) {
        log(e.message, 'err');
      }
    }

    function renderTraceTimeline(steps) {
      const container = document.getElementById('traceTimelineContainer');
      if (!container) return;
      if (!steps.length) {
        container.innerHTML = '<p class="hint">暂无链路数据</p>';
        return;
      }

      // Group by trace_id
      const byTrace = {};
      steps.forEach(s => {
        const key = s.trace_id || 'unknown';
        if (!byTrace[key]) byTrace[key] = [];
        byTrace[key].push(s);
      });

      let html = '';
      for (const [traceId, traceSteps] of Object.entries(byTrace)) {
        html += `<div class="trace-chain-group">`;
        html += `<h4 style="margin:0 0 0.5rem;font-size:0.85rem;color:#334155">Trace: ${esc(traceId).slice(0, 16)}… (${traceSteps.length} steps)</h4>`;
        html += `<div class="trace-chain">`;
        traceSteps.forEach((s, idx) => {
          const stepIcon = stepIconFor(s.step_type);
          const decisionBadge = s.tool_decision
            ? `<span class="trace-decision ${s.tool_decision}">${esc(s.tool_decision)}</span>`
            : '';
          const riskBadge = s.risk_score > 0
            ? `<span class="trace-risk risk-${riskLevel(s.risk_score)}">风险 ${s.risk_score}</span>`
            : '';
          const statusBadge = s.tool_status
            ? `<span class="trace-status ${s.tool_status}">${esc(s.tool_status)}</span>`
            : '';
          const toolInfo = s.tool_name ? ` · ${esc(s.tool_name)}` : '';
          const durationInfo = s.tool_duration_ms != null ? ` · ${s.tool_duration_ms}ms` : '';
          const ruleInfo = s.rule_id ? ` · rule: ${esc(s.rule_id)}` : '';

          html += `<div class="trace-step-card ${s.step_type}">`;
          html += `<div class="trace-step-header">`;
          html += `<span class="trace-step-icon">${stepIcon}</span>`;
          html += `<span class="trace-step-type">${esc(s.step_type)}</span>`;
          html += `<span class="trace-step-seq">#${s.step_seq}</span>`;
          html += `</div>`;
          html += `<div class="trace-step-body">`;
          html += `<div class="trace-step-meta">${toolInfo}${durationInfo}${ruleInfo}</div>`;
          if (decisionBadge || riskBadge || statusBadge) {
            html += `<div class="trace-step-badges">${decisionBadge}${riskBadge}${statusBadge}</div>`;
          }
          if (s.tool_args) {
            html += `<div class="trace-step-args"><pre>${esc(JSON.stringify(JSON.parse(s.tool_args), null, 2))}</pre></div>`;
          } else if (s.tool_args_hash) {
            html += `<div class="trace-step-hash">args-hash: ${esc(s.tool_args_hash).slice(0, 24)}…</div>`;
          }
          html += `<div class="trace-step-time">${fmtTime(s.occurred_at)}</div>`;
          html += `</div>`;
          html += `</div>`;
          if (idx < traceSteps.length - 1) {
            html += `<div class="trace-arrow-down">↓</div>`;
          }
        });
        html += `</div>`;
        html += `</div>`;
      }
      container.innerHTML = html;
    }

    function stepIconFor(stepType) {
      const icons = {
        input: '📥',
        reasoning: '🧠',
        tool_call: '🔧',
        tool_result: '📤',
        output: '🏁'
      };
      return icons[stepType] || '❓';
    }

    function riskLevel(score) {
      if (score >= 70) return 'high';
      if (score >= 30) return 'mid';
      return 'low';
    }

    async function loadTraceIngestStatus() {
      try {
        const data = await admin('/trace/ingest-status');
        const el = document.getElementById('traceIngestStatus');
        if (!el) return;
        if (!data || data.length === 0) {
          el.innerHTML = '<span class="hint">无状态数据</span>';
          return;
        }
        let html = '<div class="kpi-grid">';
        html += kpiCard('启用', data.enabled ? '✅ 是' : '❌ 否');
        html += kpiCard('Redis', data.redis_ok ? '✅ 已连接' : '❌ 未连接');
        html += kpiCard('Stream', esc(data.stream_key || '—'));
        html += kpiCard('积压', data.stream_length != null ? data.stream_length : '—');
        html += kpiCard('最近轮询', data.last_poll_at ? fmtTime(data.last_poll_at) : '—');
        html += kpiCard('最近批次', data.last_batch_ingested != null ? data.last_batch_ingested : '—');
        html += kpiCard('检查点', data.checkpoint ? esc(data.checkpoint) : '—');
        html += '</div>';
        el.innerHTML = html;
      } catch (e) {
        log(e.message, 'err');
      }
    }

    function kpiCard(label, value) {
      return `<div class="kpi-card"><div class="label">${label}</div><div class="value">${value}</div></div>`;
    }
