(function() {

  let monHours = 24;
  let monTimer = null;
  let monTrafficChart = null;
  let monBlockRateChart = null;
  let monRuleChart = null;
  let monSceneChart = null;
  let monDegradationChart = null;
  let monAllRules = [];

  function destroyChart(ch) { if (ch) { ch.destroy(); ch = null; } return null; }

  function ensureChart(canvasId) {
    const el = document.getElementById(canvasId);
    if (!el) return null;
    const parent = el.parentElement;
    if (parent) parent.style.minHeight = '220px';
    return el;
  }

  function monFetch(path) {
    return fetch('/api/v1/admin/tenants/' + tenant() + path).then(r => {
      if (!r.ok) throw new Error(r.status + ' ' + r.statusText);
      return r.json().then(j => { if (j.code !== 0) throw new Error(j.message); return j.data; });
    });
  }

  function fmtPct(v) {
    if (v == null || isNaN(v)) return '—';
    return (v * 100).toFixed(2) + '%';
  }

  function fmtNum(n) {
    if (n == null) return '0';
    if (n >= 100000000) return (n / 100000000).toFixed(1) + '亿';
    if (n >= 10000) return (n / 10000).toFixed(1) + '万';
    return n.toLocaleString();
  }

  function parseUtc(s) {
    if (!s) return null;
    let iso = s.includes('T') ? s : s.replace(' ', 'T');
    if (!/[zZ]|[+-]\d{2}:?\d{2}$/.test(iso)) {
      iso += 'Z';
    }
    const t = Date.parse(iso);
    return Number.isNaN(t) ? null : new Date(t);
  }

  function fmtTime(s) {
    const d = parseUtc(s);
    if (!d) return '—';
    return d.toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  function esc(s) {
    if (s == null) return '';
    const d = document.createElement('div');
    d.textContent = String(s);
    return d.innerHTML;
  }

  function loadMonitorPage() {
    document.getElementById('monKpiCards').innerHTML = '<div class="hint">' + __('monitor.refreshing') + '…</div>';
    document.getElementById('monRuleSelect').innerHTML = '<option value="">' + __('monitor.select-rule') + '</option>';
    document.getElementById('monRankingBody').innerHTML = '';
    document.getElementById('monSceneBody').innerHTML = '';
    document.getElementById('monEventBody').innerHTML = '';
    document.getElementById('monIngestHealth').innerHTML = '';
    monTrafficChart = destroyChart(monTrafficChart);
    monBlockRateChart = destroyChart(monBlockRateChart);
    monRuleChart = destroyChart(monRuleChart);
    monSceneChart = destroyChart(monSceneChart);
    monDegradationChart = destroyChart(monDegradationChart);

    if (!tenant()) return;

    const hours = monHours;
    Promise.all([
      monFetch('/deploy-rollout/metrics?hours=' + hours).catch(() => null),
      monFetch('/monitor/rule-ranking?hours=' + hours + '&limit=20').catch(() => null),
      monFetch('/monitor/scene-traffic?hours=' + hours).catch(() => null),
      monFetch('/monitor/degradation?hours=' + hours).catch(() => null),
      monFetch('/monitor/event-timeline?hours=' + (hours > 48 ? hours : 48) + '&limit=20').catch(() => null),
      monFetch('/audit/ingest-status').catch(() => null),
    ]).then(([metrics, ranking, sceneTraffic, degradation, events, ingestHealth]) => {
      renderKpiCards(metrics);
      renderTrafficChart(metrics);
      renderBlockRateChart(metrics);
      renderRuleChart(metrics);
      renderRuleRanking(ranking);
      renderSceneTraffic(sceneTraffic);
      renderDegradationChart(degradation);
      renderEventTimeline(events);
      renderIngestHealth(ingestHealth);
    }).catch(e => log(e.message, 'err'));
  }

  function renderKpiCards(metrics) {
    const container = document.getElementById('monKpiCards');
    if (!metrics) {
      container.innerHTML = '<div class="hint">' + __('monitor.no-data') + '</div>';
      return;
    }
    const totals = metrics.totals || {};
    const totalReq = totals.total_requests || 0;
    const block = totals.block || 0;
    const review = totals.review || 0;
    const degraded = totals.cnt_degraded || 0;
    const blockRate = totalReq > 0 ? block / totalReq : 0;
    const reviewRate = totalReq > 0 ? review / totalReq : 0;
    const degRate = totalReq > 0 ? degraded / totalReq : 0;
    const series = metrics.series || [];
    const activeRules = new Set(series.filter(s => (s.total_requests || 0) > 0).map(s => s.rule_id)).size;

    container.innerHTML =
      '<div class="kpi-card"><div class="label">' + __('monitor.kpi-total-requests') + '</div><div class="value">' + fmtNum(totalReq) + '</div></div>' +
      '<div class="kpi-card"><div class="label">' + __('monitor.kpi-block-rate') + '</div><div class="value">' + fmtPct(blockRate) + '</div></div>' +
      '<div class="kpi-card"><div class="label">' + __('monitor.kpi-review-rate') + '</div><div class="value">' + fmtPct(reviewRate) + '</div></div>' +
      '<div class="kpi-card"><div class="label">' + __('monitor.kpi-degraded-rate') + '</div><div class="value">' + fmtPct(degRate) + '</div></div>' +
      '<div class="kpi-card"><div class="label">' + __('monitor.kpi-active-rules') + '</div><div class="value">' + activeRules + '</div></div>';
  }

  function renderTrafficChart(metrics) {
    const canvas = ensureChart('monTrafficChart');
    if (!canvas) return;
    const series = (metrics && metrics.series) || [];
    const cutoff = Date.now() - 60000;
    const filtered = series.filter(p => {
      const d = parseUtc(p.bucket);
      return d && d.getTime() < cutoff;
    });
    if (!filtered.length) {
      monTrafficChart = destroyChart(monTrafficChart);
      return;
    }
    const labels = filtered.map(p => {
      const d = parseUtc(p.bucket);
      return d ? d.toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '';
    });
    const datasets = [
      { label: 'allow', data: filtered.map(p => p.allow || 0), backgroundColor: 'rgba(34,197,94,0.7)', borderColor: '#22c55e', borderWidth: 1 },
      { label: 'review', data: filtered.map(p => p.review || 0), backgroundColor: 'rgba(251,191,36,0.7)', borderColor: '#fbbf24', borderWidth: 1 },
      { label: 'block', data: filtered.map(p => p.block || 0), backgroundColor: 'rgba(239,68,68,0.7)', borderColor: '#ef4444', borderWidth: 1 },
      { label: 'captcha', data: filtered.map(p => p.captcha || 0), backgroundColor: 'rgba(168,85,247,0.7)', borderColor: '#a855f7', borderWidth: 1 },
    ];
    if (monTrafficChart) {
      monTrafficChart.data.labels = labels;
      monTrafficChart.data.datasets.forEach((ds, i) => { ds.data = datasets[i].data; });
      monTrafficChart.update('none');
    } else {
      monTrafficChart = new Chart(canvas, {
        type: 'bar',
        data: { labels, datasets },
        options: {
          responsive: true, maintainAspectRatio: false,
          scales: { x: { stacked: true }, y: { stacked: true, beginAtZero: true } },
          plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, padding: 10, font: { size: 10 } } } },
          interaction: { mode: 'index', intersect: false },
        }
      });
    }
  }

  function renderBlockRateChart(metrics) {
    const canvas = ensureChart('monBlockRateChart');
    if (!canvas) return;
    const series = (metrics && metrics.series) || [];
    const cutoff = Date.now() - 60000;
    const filtered = series.filter(p => {
      const d = parseUtc(p.bucket);
      return d && d.getTime() < cutoff;
    });
    if (!filtered.length) {
      monBlockRateChart = destroyChart(monBlockRateChart);
      return;
    }
    const labels = filtered.map(p => {
      const d = parseUtc(p.bucket);
      return d ? d.toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '';
    });
    const blockRate = filtered.map(p => {
      const total = (p.total_requests || 0);
      return total > 0 ? ((p.block || 0) / total) : 0;
    });
    if (monBlockRateChart) {
      monBlockRateChart.data.labels = labels;
      monBlockRateChart.data.datasets[0].data = blockRate;
      monBlockRateChart.update('none');
    } else {
      monBlockRateChart = new Chart(canvas, {
        type: 'line',
        data: {
          labels,
          datasets: [{ label: __('monitor.overall-block-rate'), data: blockRate, borderColor: '#ef4444', backgroundColor: 'rgba(239,68,68,0.1)', fill: true, tension: 0.3, pointRadius: 0 }]
        },
        options: {
          responsive: true, maintainAspectRatio: false,
          scales: { y: { beginAtZero: true, max: 1, ticks: { callback: v => fmtPct(v) } } },
          plugins: { tooltip: { callbacks: { label: ctx => fmtPct(ctx.parsed.y) } }, legend: { position: 'bottom', labels: { boxWidth: 12, font: { size: 10 } } } },
          interaction: { mode: 'index', intersect: false },
        }
      });
    }
  }

  function renderRuleChart(metrics) {
    const canvas = ensureChart('monRuleChart');
    if (!canvas) return;
    const select = document.getElementById('monRuleSelect');
    const series = (metrics && metrics.series) || [];
    const ruleMap = {};
    series.forEach(s => {
      if (!ruleMap[s.rule_id]) ruleMap[s.rule_id] = [];
      ruleMap[s.rule_id].push(s);
    });
    monAllRules = Object.keys(ruleMap).sort();
    const currentVal = select.value;
    select.innerHTML = '<option value="">' + __('monitor.select-rule') + '</option>';
    monAllRules.forEach(id => {
      const opt = document.createElement('option');
      opt.value = id; opt.textContent = id;
      if (id === currentVal) opt.selected = true;
      select.appendChild(opt);
    });

    const selected = select.value || monAllRules[0];
    if (!selected) { monRuleChart = destroyChart(monRuleChart); return; }

    const ruleSeries = (ruleMap[selected] || []).filter(p => {
      const d = parseUtc(p.bucket);
      return d && d.getTime() < Date.now() - 60000;
    }).sort((a, b) => (parseUtc(a.bucket) || 0) - (parseUtc(b.bucket) || 0));

    if (!ruleSeries.length) { monRuleChart = destroyChart(monRuleChart); return; }

    const labels = ruleSeries.map(p => {
      const d = parseUtc(p.bucket);
      return d ? d.toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '';
    });
    const blockRates = ruleSeries.map(p => {
      const total = (p.total_requests || 0);
      return total > 0 ? ((p.block || 0) / total) : 0;
    });
    const totalRequests = ruleSeries.map(p => p.total_requests || 0);

    if (monRuleChart) {
      monRuleChart.data.labels = labels;
      monRuleChart.data.datasets[0].data = blockRates;
      monRuleChart.data.datasets[1].data = totalRequests;
      monRuleChart.update('none');
    } else {
      monRuleChart = new Chart(canvas, {
        type: 'line',
        data: {
          labels,
          datasets: [
            { label: __('monitor.rule-block-rate'), data: blockRates, yAxisID: 'y', borderColor: '#ef4444', tension: 0.3, pointRadius: 0 },
            { label: __('monitor.kpi-total-requests'), data: totalRequests, yAxisID: 'y1', borderColor: '#3b82f6', backgroundColor: 'rgba(59,130,246,0.1)', fill: true, tension: 0.3, pointRadius: 0 },
          ]
        },
        options: {
          responsive: true, maintainAspectRatio: false,
          scales: {
            y: { beginAtZero: true, max: 1, position: 'left', ticks: { callback: v => fmtPct(v) } },
            y1: { beginAtZero: true, position: 'right', grid: { drawOnChartArea: false } }
          },
          plugins: { tooltip: { callbacks: { label: ctx => ctx.datasetIndex === 0 ? fmtPct(ctx.parsed.y) : String(ctx.parsed.y) } }, legend: { position: 'bottom', labels: { boxWidth: 12, font: { size: 10 } } } },
          interaction: { mode: 'index', intersect: false },
        }
      });
    }
  }

  function renderRuleRanking(ranking) {
    const tbody = document.getElementById('monRankingBody');
    if (!ranking || !ranking.ranking || !ranking.ranking.length) {
      tbody.innerHTML = '<tr><td colspan="8" class="hint">' + __('monitor.no-data') + '</td></tr>';
      return;
    }
    tbody.innerHTML = ranking.ranking.map(r =>
      '<tr>' +
        '<td>' + esc(r.rule_id) + '</td>' +
        '<td>' + fmtNum(r.total_hits) + '</td>' +
        '<td>' + fmtNum(r.block) + '</td>' +
        '<td>' + fmtNum(r.review) + '</td>' +
        '<td>' + fmtNum(r.captcha) + '</td>' +
        '<td>' + fmtPct(r.hit_rate) + '</td>' +
        '<td>' + fmtPct(r.block_rate) + '</td>' +
        '<td>' + fmtNum(r.total_requests) + '</td>' +
      '</tr>'
    ).join('');
  }

  function renderSceneTraffic(data) {
    const tbody = document.getElementById('monSceneBody');
    const canvas = ensureChart('monSceneChart');
    if (!canvas) { monSceneChart = destroyChart(monSceneChart); return; }
    if (!data || !data.scenes || !data.scenes.length) {
      tbody.innerHTML = '<tr><td colspan="3" class="hint">' + __('monitor.no-data') + '</td></tr>';
      monSceneChart = destroyChart(monSceneChart);
      return;
    }
    const scenes = data.scenes;
    tbody.innerHTML = scenes.map(s =>
      '<tr><td>' + esc(s.scene) + '</td><td>' + esc(s.layer) + '</td><td>' + fmtNum(s.total_requests) + '</td></tr>'
    ).join('');

    const byScene = {};
    scenes.forEach(s => {
      if (!byScene[s.scene]) byScene[s.scene] = 0;
      byScene[s.scene] += s.total_requests;
    });
    const labels = Object.keys(byScene);
    const values = labels.map(l => byScene[l]);
    const colors = ['#3b82f6','#22c55e','#f59e0b','#ef4444','#a855f7','#06b6d4','#ec4899','#14b8a6'];

    if (monSceneChart) {
      monSceneChart.data.labels = labels;
      monSceneChart.data.datasets[0].data = values;
      monSceneChart.update('none');
    } else {
      monSceneChart = new Chart(canvas, {
        type: 'doughnut',
        data: { labels, datasets: [{ data: values, backgroundColor: colors.slice(0, labels.length) }] },
        options: {
          responsive: true, maintainAspectRatio: false,
          plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, padding: 10, font: { size: 10 } } } }
        }
      });
    }
  }

  function renderDegradationChart(data) {
    const canvas = ensureChart('monDegradationChart');
    if (!canvas) return;
    if (!data || !data.series || !data.series.length) {
      monDegradationChart = destroyChart(monDegradationChart);
      return;
    }
    const series = data.series;
    const labels = series.map(s => fmtTime(s.bucket));
    const rates = series.map(s => s.degraded_rate || 0);
    if (monDegradationChart) {
      monDegradationChart.data.labels = labels;
      monDegradationChart.data.datasets[0].data = rates;
      monDegradationChart.update('none');
    } else {
      monDegradationChart = new Chart(canvas, {
        type: 'line',
        data: {
          labels,
          datasets: [{ label: __('monitor.degradation-title'), data: rates, borderColor: '#f59e0b', backgroundColor: 'rgba(245,158,11,0.1)', fill: true, tension: 0.3, pointRadius: 0 }]
        },
        options: {
          responsive: true, maintainAspectRatio: false,
          scales: { y: { beginAtZero: true, ticks: { callback: v => fmtPct(v) } } },
          plugins: { tooltip: { callbacks: { label: ctx => fmtPct(ctx.parsed.y) } }, legend: { position: 'bottom', labels: { boxWidth: 12, font: { size: 10 } } } },
          interaction: { mode: 'index', intersect: false },
        }
      });
    }
  }

  function renderEventTimeline(data) {
    const tbody = document.getElementById('monEventBody');
    if (!data || !data.events || !data.events.length) {
      tbody.innerHTML = '<tr><td colspan="6" class="hint">' + __('monitor.no-data') + '</td></tr>';
      return;
    }
    tbody.innerHTML = data.events.map(ev =>
      '<tr>' +
        '<td>' + esc(fmtTime(ev.effective_at)) + '</td>' +
        '<td>' + esc(ev.rule_id) + '</td>' +
        '<td>' + esc(ev.rollout_state) + '</td>' +
        '<td>' + esc(ev.rule_revision) + '</td>' +
        '<td>' + esc(ev.trigger) + '</td>' +
        '<td>' + esc(ev.operator || '—') + '</td>' +
      '</tr>'
    ).join('');
  }

  function renderIngestHealth(data) {
    const container = document.getElementById('monIngestHealth');
    if (!data) {
      container.innerHTML = '<span class="hint">' + __('monitor.no-data') + '</span>';
      return;
    }
    let html = '<div style="font-size:0.85rem;line-height:1.8">';
    if (data.enabled !== undefined) {
      html += '<span class="tag ' + (data.enabled ? 'allow' : 'deny') + '">' + (data.enabled ? __('monitor.ingest-ok') : 'disabled') + '</span> ';
      html += '<span style="color:var(--text-dim)">stream:</span> ' + esc(data.stream_key || '—') + '<br>';
      html += '<span style="color:var(--text-dim)">Redis:</span> ' + (data.redis_ok ? '<span class="tag allow">OK</span>' : '<span class="tag deny">ERR</span>') + '<br>';
      html += '<span style="color:var(--text-dim)">DB events (24h):</span> ' + fmtNum(data.db_events_24h || 0) + '<br>';
      html += '<span style="color:var(--text-dim)">DB events (total):</span> ' + fmtNum(data.db_events_total || 0) + '<br>';
      if (data.lag_estimate != null) {
        html += '<span style="color:var(--text-dim)">Lag:</span> ' + fmtNum(data.lag_estimate) + '<br>';
      }
      html += '<span style="color:var(--text-dim)">Last poll:</span> ' + esc(data.last_poll_at || '—');
    } else {
      html += '<span style="color:var(--text-dim)">Layer:</span> ' + esc(data.layer || '—') + '<br>';
      html += '<span style="color:var(--text-dim)">Audit events:</span> ' + fmtNum(data.audit_events || 0) + '<br>';
      html += '<span style="color:var(--text-dim)">Estimated requests:</span> ' + fmtNum(data.estimated_requests || 0) + '<br>';
      html += '<span style="color:var(--text-dim)">Hours:</span> ' + esc(data.hours || '—');
    }
    html += '</div>';
    container.innerHTML = html;
  }

  function startMonitorRefresh() {
    stopMonitorRefresh();
    monTimer = setInterval(() => {
      loadMonitorPage();
    }, 30000);
  }

  function stopMonitorRefresh() {
    if (monTimer) { clearInterval(monTimer); monTimer = null; }
  }

  function exportDashboard() {
    const hours = monHours;
    Promise.all([
      monFetch('/deploy-rollout/metrics?hours=' + hours).catch(() => null),
      monFetch('/monitor/rule-ranking?hours=' + hours + '&limit=20').catch(() => null),
      monFetch('/monitor/scene-traffic?hours=' + hours).catch(() => null),
      monFetch('/monitor/degradation?hours=' + hours).catch(() => null),
      monFetch('/monitor/event-timeline?hours=48&limit=20').catch(() => null),
    ]).then(([metrics, ranking, sceneTraffic, degradation, events]) => {
      const dump = JSON.stringify({ exportedAt: new Date().toISOString(), metrics, ranking, sceneTraffic, degradation, events }, null, 2);
      navigator.clipboard.writeText(dump).then(() => {
        log(__('monitor.export-success'), 'ok');
      }).catch(() => {
        prompt(__('monitor.export-fail').replace('{0}', ''), dump);
      });
    }).catch(e => log(__('monitor.export-fail').replace('{0}', e.message), 'err'));
  }

  function setMonitorHours(h) {
    monHours = h;
    document.querySelectorAll('.mon-time-btn').forEach(b => b.classList.toggle('active', parseInt(b.dataset.hours) === h));
    loadMonitorPage();
  }

  document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('monRuleSelect').addEventListener('change', () => {
      const metrics = null;
      monFetch('/deploy-rollout/metrics?hours=' + monHours).then(m => renderRuleChart(m)).catch(() => {});
    });
    document.getElementById('monExportBtn').onclick = exportDashboard;
  });

  window.loadMonitorPage = loadMonitorPage;
  window.startMonitorRefresh = startMonitorRefresh;
  window.stopMonitorRefresh = stopMonitorRefresh;
  window.setMonitorHours = setMonitorHours;

})();
