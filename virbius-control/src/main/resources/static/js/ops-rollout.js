    function rolloutRuleId() {
      return document.getElementById('roRuleSelect').value.trim();
    }

    function fmtPct(n) {
      if (n == null || Number.isNaN(n)) return 'N/A';
      return (n * 100).toFixed(2) + '%';
    }

    function rolloutStateLabel(st, pct) {
      if (st === 'canary' && pct != null) return `canary@${pct}%`;
      return st || 'draft';
    }

    function renderRolloutFlow(st, pct) {
      const el = document.getElementById('roFlowStrip');
      const order = { draft: 0, disabled: -1, dry_run: 1, canary: 2, full: 3 };
      const cur = st === 'disabled' ? 'draft' : (st || 'draft');
      const curIdx = order[cur] ?? 0;
      const parts = [];
      FLOW_STEPS.forEach((step, i) => {
        if (i > 0) parts.push('<span class="flow-arrow">→</span>');
        let cls = 'flow-step';
        if (step === cur) cls += ' active';
        else if (i < curIdx) cls += ' done';
        let label = step;
        if (step === 'canary' && cur === 'canary' && pct != null) label = `canary ${pct}%`;
        parts.push(`<span class="${cls}">${esc(label)}</span>`);
      });
      if (st === 'disabled') {
        parts.push('<span class="flow-arrow">|</span><span class="flow-step active">disabled</span>');
      }
      el.innerHTML = parts.join('');
    }

    function renderRolloutKpi(totals) {
      const t = totals || {};
      const cards = [
        ['review (24h)', t.review ?? 0],
        ['block (24h)', t.block ?? 0],
        ['captcha (24h)', t.captcha ?? 0],
        ['total requests', t.total_requests ?? 0],
        ['hit_rate', t.hit_rate != null ? fmtPct(t.hit_rate) : 'N/A'],
        ['review_rate', t.review_rate != null ? fmtPct(t.review_rate) : 'N/A']
      ];
      document.getElementById('roKpi').innerHTML = cards.map(([label, value]) =>
        `<div class="kpi-card"><div class="label">${esc(label)}</div><div class="value">${esc(String(value))}</div></div>`
      ).join('');
    }

    function renderMiniBar(review, block, captcha, allow) {
      const total = Math.max(review + block + captcha + allow, 1);
      const segs = [
        { n: review, c: '#fbbf24' },
        { n: block, c: '#ef4444' },
        { n: captcha, c: '#a855f7' },
        { n: allow, c: '#22c55e' }
      ];
      return '<div class="bar-cell">' + segs.map(s => {
        const h = Math.max(2, Math.round((s.n / total) * 32));
        return `<div class="bar-seg" style="height:${h}px;background:${s.c}" title="${s.n}"></div>`;
      }).join('') + '</div>';
    }

    function renderRolloutBlockRate(totals) {
      const el = document.getElementById('roBlockRateValue');
      const rate = totals && totals.block_rate != null ? totals.block_rate : null;
      if (rate == null) {
        el.textContent = '—';
        el.className = 'value';
        return;
      }
      el.textContent = fmtPct(rate);
      el.className = 'value ' + (rate < 0.01 ? 'rate-low' : rate < 0.05 ? 'rate-mid' : 'rate-high');
    }

    let roChart = null;

    function renderRolloutCombinedChart(series, series1m) {
      const cutoff = Date.now() - 2 * 3600 * 1000;
      const hourPoints = (series || []).filter(p => {
        const d = parseUtc(p.bucket);
        return d && d.getTime() < cutoff;
      });
      const minPoints = series1m || [];
      const merged = hourPoints.concat(minPoints).sort((a, b) =>
        (parseUtc(a.bucket) || 0) - (parseUtc(b.bucket) || 0)
      );
      if (!merged.length) {
        if (roChart) { roChart.destroy(); roChart = null; }
        return;
      }
      const labels = merged.map(p => {
        const d = parseUtc(p.bucket);
        return d.toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
      });
      const datasets = [
        { label: 'total_requests', data: merged.map(p => p.total_requests ?? 0), yAxisID: 'y', borderColor: '#3b82f6', backgroundColor: 'rgba(59,130,246,0.1)', fill: true, tension: 0.2, pointRadius: 0 },
        { label: 'review',  data: merged.map(p => p.review ?? 0),  yAxisID: 'y1', borderColor: '#fbbf24', tension: 0.2, pointRadius: 0 },
        { label: 'block',   data: merged.map(p => p.block ?? 0),   yAxisID: 'y1', borderColor: '#ef4444', tension: 0.2, pointRadius: 0 },
        { label: 'captcha', data: merged.map(p => p.captcha ?? 0), yAxisID: 'y1', borderColor: '#a855f7', tension: 0.2, pointRadius: 0 },
        { label: 'allow',   data: merged.map(p => p.allow ?? 0),   yAxisID: 'y1', borderColor: '#22c55e', tension: 0.2, pointRadius: 0 },
      ];
      if (roChart) {
        roChart.data.labels = labels;
        roChart.data.datasets.forEach((ds, i) => ds.data = datasets[i].data);
        roChart.update('none');
      } else {
        roChart = new Chart(document.getElementById('roCombinedChart'), {
          type: 'line',
          data: { labels, datasets },
          options: {
            responsive: true,
            maintainAspectRatio: false,
            spanGaps: true,
            interaction: { mode: 'index', intersect: false },
            scales: {
              y: { type: 'linear', position: 'left', title: { display: true, text: 'total_requests' } },
              y1: { type: 'linear', position: 'right', title: { display: true, text: 'action count' }, grid: { drawOnChartArea: false } }
            },
            plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, padding: 12, font: { size: 10 } } } }
          }
        });
      }
    }

    function renderRolloutTimeline(events) {
      const tbody = document.querySelector('#roTimelineTable tbody');
      tbody.innerHTML = '';
      (events || []).forEach(ev => {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td>${esc(fmtTime(ev.effective_at))}</td>
          <td>${ruleStatusTag(ev.rollout_state)}</td>
          <td>${ev.canary_percent ?? '—'}</td><td>${esc(ev.trigger || '')}</td>
          <td>${esc(ev.operator || '')}</td><td>${ev.rule_revision ?? ''}</td>`;
        tbody.appendChild(tr);
      });
      if (!events || !events.length) {
        tbody.innerHTML = '<tr><td colspan="6" class="hint">' + __('rollout.no-timeline') + '</td></tr>';
      }
    }

    function updateRolloutSuggestedHint(meta) {
      const hint = document.getElementById('roSuggestedHint');
      if (!meta) {
        hint.textContent = __('rollout.select-rule-hint');
        return;
      }
      const st = meta.rollout_state;
      if (st === 'draft') {
        hint.textContent = __('rollout.draft-hint');
        return;
      }
      if (st === 'disabled') {
        hint.textContent = __('rollout.disabled-hint');
        return;
      }
      if (st === 'full') {
        hint.textContent = __('rollout.full-hint');
        return;
      }
      const target = rolloutEvaluateTarget(meta);
      if (!target) {
        hint.textContent = __('rollout.no-target-hint');
        return;
      }
      hint.textContent = __('rollout.next-step-hint', rolloutStateLabel(target.target_state, target.canary_percent));
    }

    function renderRolloutSamples(samples) {
      const tbody = document.querySelector('#roSamplesTable tbody');
      tbody.innerHTML = '';
      (samples || []).forEach(s => {
        const tr = document.createElement('tr');
        const traceCell = s.trace_id
          ? `<a href="#" class="trace-link" data-trace="${escAttr(s.trace_id)}"><code>${esc(s.trace_id)}</code></a>`
          : '';
        tr.innerHTML = `<td>${esc(fmtTime(s.intercepted_at))}</td>
          <td>${traceCell}</td>
          <td>${esc(s.effective_action || '')}</td><td>${esc(s.reason_code || '')}</td>
          <td>${s.max_risk_score ?? ''}</td>
          <td>${esc(rolloutStateLabel(s.rollout_state, s.canary_percent))}</td>`;
        tbody.appendChild(tr);
      });
      tbody.querySelectorAll('.trace-link').forEach(a => {
        a.onclick = (e) => {
          e.preventDefault();
          openTraceModal(a.getAttribute('data-trace'));
        };
      });
      if (!samples || !samples.length) {
        tbody.innerHTML = '<tr><td colspan="6" class="hint">' + __('rollout.no-samples') + '</td></tr>';
      }
    }

    async function openTraceModal(traceId) {
      if (!traceId) return;
      const modal = document.getElementById('roTraceModal');
      const hint = document.getElementById('roTraceHint');
      const tbody = document.querySelector('#roTraceTable tbody');
      modal.style.display = '';
      hint.textContent = __('rollout.trace-hint', traceId);
      tbody.innerHTML = '<tr><td colspan="7" class="hint">' + __('rollout.loading-trace') + '</td></tr>';
      try {
        const detail = await admin('/audit/trace/' + encodeURIComponent(traceId));
        tbody.innerHTML = '';
        const rows = [];
        (detail.db_events || []).forEach(h => rows.push({ ...h, _source: 'db' }));
        rows.sort((a, b) => String(a.intercepted_at || '').localeCompare(String(b.intercepted_at || '')));
        rows.forEach(h => {
          const tr = document.createElement('tr');
          tr.innerHTML = `<td>${esc(fmtTime(h.intercepted_at))}</td>
            <td>${esc(h.layer || '')}</td><td>${esc(h.effective_action || '')}</td>
            <td>${esc(h.rule_id || '')}@${h.rule_revision ?? ''}</td>
            <td>${esc(rolloutStateLabel(h.rollout_state, h.canary_percent))}</td>
            <td>${h.max_risk_score ?? ''}</td>
            <td>${esc(h._source || '')}</td>`;
          tbody.appendChild(tr);
        });
        if (!rows.length) {
          tbody.innerHTML = '<tr><td colspan="7" class="hint">' + __('rollout.no-audit-records') + '</td></tr>';
        }
        if (detail.note) {
          hint.textContent = `trace_id: ${traceId} — ${detail.note}`;
        }
      } catch (e) {
        tbody.innerHTML = `<tr><td colspan="7" class="hint">${esc(e.message)}</td></tr>`;
      }
    }

    function updateRolloutActions(meta) {
      const st = meta ? meta.rollout_state : null;
      const isDraft = st === 'draft';
      const isDisabled = st === 'disabled';
      const exec = inExecutionPlane(st);
      document.getElementById('roPublish').style.display = isDraft ? '' : 'none';
      document.getElementById('roRecover').style.display = isDisabled ? '' : 'none';
      document.getElementById('roDisable').style.display = meta && !isDisabled ? '' : 'none';
      document.getElementById('roRollback').style.display = exec && st !== 'dry_run' ? '' : 'none';
      document.getElementById('roApply').style.display = meta && !isDraft && !isDisabled && st !== 'full' ? '' : 'none';
      document.getElementById('roLadderStart').style.display = exec && st !== 'full' ? '' : 'none';
      document.getElementById('roLadderPause').style.display = exec ? '' : 'none';
      updateRolloutSuggestedHint(meta);
      loadDeployStatus().catch(() => {});
    }

    function rolloutEvaluateTarget(meta) {
      if (!meta) return null;
      const st = meta.rollout_state;
      const ladder = rolloutCanaryLadder;
      if (st === 'dry_run') {
        return { target_state: 'canary', canary_percent: ladder[0] };
      }
      if (st === 'canary') {
        const pct = meta.canary_percent;
        const idx = ladder.indexOf(pct != null ? pct : ladder[0]);
        const nextIdx = idx < 0 ? 0 : idx + 1;
        if (nextIdx >= ladder.length) return null;
        const step = ladder[nextIdx];
        if (step >= 100) return { target_state: 'full', canary_percent: null };
        return { target_state: 'canary', canary_percent: step };
      }
      return null;
    }

    async function loadAllRulesForRollout() {
      const layers = ['cloud', 'gateway', 'edge'];
      const all = [];
      for (const layer of layers) {
        const rules = await admin('/rules?layer=' + encodeURIComponent(layer));
        rules.forEach(r => all.push(r));
      }
      rolloutAllRules = all.sort((a, b) => a.rule_id.localeCompare(b.rule_id));
      const sel = document.getElementById('roRuleSelect');
      const prev = sel.value;
      sel.innerHTML = '<option value="">' + __('rollout.select-rule') + '</option>' +
        rolloutAllRules.map(r => {
          const label = `${r.rule_id} [${r.layer}] ${rolloutStateLabel(r.rollout_state, r.canary_percent)}`;
          return `<option value="${escAttr(r.rule_id)}">${esc(label)}</option>`;
        }).join('');
      if (prev && rolloutAllRules.some(r => r.rule_id === prev)) sel.value = prev;
    }

    async function refreshRolloutDashboard(opts) {
      if (rolloutRefreshBusy) return;
      rolloutRefreshBusy = true;
      try {
        await loadAllRulesForRollout();
        try {
          const pol = await admin('/rollout-policy');
          if (pol && pol.canary_ladder && pol.canary_ladder.length) {
            rolloutCanaryLadder = pol.canary_ladder;
          }
        } catch (_) { /* 读 tb_tenant_rollout_policy；无 UI 配置 */ }
        const ruleId = rolloutRuleId();
        if (!ruleId) {
          rolloutRuleMeta = null;
          document.getElementById('roBadge').innerHTML = '';
          renderRolloutFlow('draft');
          renderRolloutKpi({});
          renderRolloutBlockRate(null);
          renderRolloutCombinedChart(null, null);
          renderRolloutTimeline([]);
          renderRolloutSamples([]);
          updateRolloutActions(null);
          return;
        }
        const meta = await admin('/rules/' + encodeURIComponent(ruleId));
        rolloutRuleMeta = meta;
        const st = meta.rollout_state || 'draft';
        const pct = meta.canary_percent;
        document.getElementById('roBadge').innerHTML = ruleStatusTag(st)
          + (pct != null ? ` <span class="tag">${pct}%</span>` : '')
          + ` <span class="hint">${esc(meta.layer)}/${esc(meta.runtime)}</span>`;
        if (meta.layer === 'edge') {
          try {
            const health = await admin('/rollout/ingest-health?layer=edge&hours=24');
            document.getElementById('roBadge').innerHTML += ` <span class="hint">edge audit 24h: ${health.audit_events ?? 0}</span>`;
          } catch (_) { /* optional */ }
        }
        renderRolloutFlow(st, pct);
        updateRolloutActions(meta);
        const [metrics, timeline, samples] = await Promise.all([
          admin('/rules/' + encodeURIComponent(ruleId) + '/metrics?hours=24').catch(() => ({ series: [], totals: {} })),
          admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/timeline').catch(() => []),
          admin('/rules/' + encodeURIComponent(ruleId) + '/audit-samples?limit=30').catch(() => [])
        ]);
        renderRolloutKpi(metrics.totals || {});
        renderRolloutBlockRate(metrics.totals || null);
        renderRolloutCombinedChart(metrics.series || [], metrics.series_1m || []);
        renderRolloutTimeline(timeline);
        renderRolloutSamples(samples);
      } finally {
        rolloutRefreshBusy = false;
        loadGatewayArtifactStatus().catch(() => {});
        loadDeployStatus().catch(() => {});
      }
    }

    async function loadDeployStatus() {
      const el = document.getElementById('deployStatusBar');
      if (!el) return;
      try {
        const data = await admin('/dashboard/overview');
        const ds = data.deploy_status || {};
        const cards = Object.entries(ds).map(([layer, st]) => {
          const hasUnpub = st.has_unpublished;
          const label = LAYER_LABELS[layer] || layer;
          const dot = hasUnpub
            ? '<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#ef4444;margin-right:4px"></span>'
            : '<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#22c55e;margin-right:4px"></span>';
          const rules = st.pending_rules || [];
          const count = rules.length;
          let statusText, pendingHtml;
          if (!st.deployed_at) {
            statusText = count > 0 ? __('rollout.not-deployed-count', count) : __('rollout.not-deployed');
          } else if (hasUnpub) {
            statusText = __('rollout.pending-deploy', count);
          } else {
            statusText = __('rollout.synced');
          }
          if (hasUnpub && count > 0) {
            const groups = { dry_run: [], canary: [], full: [], disabled: [] };
            rules.forEach(r => {
              const g = groups[r.rollout_state];
              if (g) g.push(r.rule_id);
            });
            const labels = { dry_run: __('ro-deploy.pending'), canary: __('ro-deploy.canary'), full: __('ro-deploy.full'), disabled: __('ro-deploy.disabled') };
            const parts = [];
            ['dry_run', 'canary', 'full', 'disabled'].forEach(st => {
              const ids = groups[st];
              if (!ids || !ids.length) return;
              const tag = labels[st] || st;
              const maxShow = 2;
              const shown = ids.slice(0, maxShow).map(id => `<code style="font-size:0.7rem">${esc(id)}</code>`).join(' · ');
              const extra = ids.length > maxShow ? __('rollout.et', ids.length) : '';
              parts.push(`<span style="font-size:0.68rem;color:#64748b;margin-right:0.25rem">[${esc(tag)}]</span>${shown}${extra}`);
            });
            pendingHtml = `<div style="font-size:0.72rem;color:#64748b;margin-top:2px">${parts.join('&nbsp;&nbsp;|&nbsp;&nbsp;')}</div>`;
          } else {
            pendingHtml = '';
          }
          const timeAgo = fmtTimeAgo(st.deployed_at);
          let gwNodeHtml = '';
          if (layer === 'gateway') {
            gwNodeHtml = '<div id="gwNodeStatus" style="font-size:0.72rem;color:#94a3b8;margin-top:2px">—</div>';
          }
          return `<div class="kpi-card" id="deployCard_${layer}"><div class="label">${dot}${esc(label)}</div><div class="value" style="font-size:0.82rem">${esc(statusText)}</div><div style="font-size:0.72rem;color:#94a3b8">${esc(timeAgo)}</div>${pendingHtml}${gwNodeHtml}</div>`;
        });
        el.innerHTML = cards.join('');
        loadGatewayArtifactStatus().catch(() => {});
      } catch (e) {
        el.innerHTML = '<div class="kpi-card"><div class="label">' + __('rollout.status-deploy') + '</div><div class="value" style="font-size:0.85rem;color:#dc2626">' + __('rollout.unavailable') + '</div></div>';
      }
    }

    async function doDeploy(layer) {
      try {
        const data = await admin('/deploy/' + encodeURIComponent(layer), { method: 'POST' });
        log(data, 'ok');
        await loadDeployStatus();
      } catch (e) {
        log(e.message, 'err');
      }
    }

    async function loadGatewayArtifactStatus() {
      const el = document.getElementById('gwNodeStatus');
      if (!el) return;
      try {
        const data = await admin('/gateway-artifacts/policy-version');
        const rev = data.artifact_revision ?? '?';
        el.innerHTML = `<span style="color:#22c55e">` + __('rollout.config-published', esc(String(rev))) + `</span>`;
      } catch (e) {
        el.textContent = __('rollout.config-unavailable');
      }
    }

    function startRolloutAutoRefresh() {
      stopRolloutAutoRefresh();
      rolloutRefreshTimer = setInterval(() => {
        if (document.getElementById('panel-rollout').classList.contains('active')) {
          refreshRolloutDashboard({ silent: true }).catch(() => {});
        }
      }, ROLLOUT_REFRESH_MS);
    }

    function stopRolloutAutoRefresh() {
      if (rolloutRefreshTimer) {
        clearInterval(rolloutRefreshTimer);
        rolloutRefreshTimer = null;
      }
    }

    document.getElementById('roRuleSelect').onchange = () =>
      refreshRolloutDashboard().catch(e => log(e.message, 'err'));
    document.getElementById('roRefresh').onclick = () =>
      refreshRolloutDashboard().then(() => log(__('rollout.dashboard-refreshed'), 'ok')).catch(e => log(e.message, 'err'));

    document.getElementById('roApply').onclick = async () => {
      const ruleId = rolloutRuleId();
      if (!ruleId || !rolloutRuleMeta) return;
      const target = rolloutEvaluateTarget(rolloutRuleMeta);
      if (!target || (!target.rollout_state && !target.target_state)) {
        log(__('rollout.no-next-target'), 'warn');
        return;
      }
      const force = document.getElementById('roForce').checked;
      const comment = document.getElementById('roForceComment').value.trim();
      if (force && !comment) {
        log(__('rollout.force-comment-required'), 'warn');
        return;
      }
      const rolloutState = target.rollout_state || target.target_state;
      let canaryPercent = target.canary_percent;
      if (rolloutState === 'full') canaryPercent = null;
      const body = { rollout_state: rolloutState, canary_percent: canaryPercent, force, comment: force ? comment : null };
      try {
        const data = await admin('/rules/' + encodeURIComponent(ruleId) + '/rollout', {
          method: 'PATCH',
          body: JSON.stringify(body)
        });
        log(data);
        await refreshRolloutDashboard();
        if (selectedRuleId === ruleId) await selectRule(ruleId);
      } catch (e) {
        log(e.message, 'err');
      }
    };

    document.getElementById('roPublish').onclick = () => {
      const ruleId = rolloutRuleId();
      if (!ruleId) return;
      admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/publish', { method: 'POST' })
        .then(log).then(() => refreshRolloutDashboard()).catch(e => log(e.message, 'err'));
    };
    document.getElementById('roRollback').onclick = () => {
      const ruleId = rolloutRuleId();
      if (!ruleId) return;
      admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/rollback', { method: 'POST' })
        .then(log).then(() => refreshRolloutDashboard()).catch(e => log(e.message, 'err'));
    };
    document.getElementById('roDisable').onclick = () => {
      const ruleId = rolloutRuleId();
      if (!ruleId) return;
      admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/disable', { method: 'POST' })
        .then(log).then(() => refreshRolloutDashboard()).catch(e => log(e.message, 'err'));
    };
    document.getElementById('roRecover').onclick = () => {
      const ruleId = rolloutRuleId();
      if (!ruleId) return;
      admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/recover', { method: 'POST' })
        .then(log).then(() => refreshRolloutDashboard()).catch(e => log(e.message, 'err'));
    };
    document.getElementById('roLadderStart').onclick = () => {
      const ruleId = rolloutRuleId();
      if (!ruleId) return;
      admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/ladder/start', { method: 'POST' })
        .then(log).catch(e => log(e.message, 'err'));
    };
    document.getElementById('roLadderPause').onclick = () => {
      const ruleId = rolloutRuleId();
      if (!ruleId) return;
      admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/ladder/pause', { method: 'POST' })
        .then(log).catch(e => log(e.message, 'err'));
    };
    document.querySelectorAll('[data-deploy]').forEach(btn => {
      btn.onclick = () => doDeploy(btn.dataset.deploy);
    });
    document.getElementById('roTraceClose').onclick = () => {
      document.getElementById('roTraceModal').style.display = 'none';
    };
    document.getElementById('btnGatewayArtifact').onclick = () =>
      loadGatewayArtifactStatus().then(() => log(__('rollout.artifact-refreshed'))).catch(e => log(e.message, 'err'));
