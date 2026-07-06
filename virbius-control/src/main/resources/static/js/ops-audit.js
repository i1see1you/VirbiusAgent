    function renderAuditEventRow(h) {
      return `<td>${esc(fmtTime(h.intercepted_at))}</td>
        <td>${esc(h.layer || '')}</td><td>${esc(h.scene || '')}</td>
        <td>${esc(h.effective_action || '')}</td>
        <td>${esc(h.rule_id || '')}@${h.rule_revision ?? ''}</td>
        <td>${esc(h.reason_code || '')}</td>
        <td>${h.max_risk_score ?? ''}</td>
        <td>${esc(rolloutStateLabel(h.rollout_state, h.canary_percent))}</td>
        <td>${esc(h.user_id || '')}</td>`;
    }

    async function loadAuditRecent(limit) {
      const dbTbody = document.querySelector('#acDbTable tbody');
      const summary = document.getElementById('acSummary');
      summary.textContent = __('common.loading');
      dbTbody.innerHTML = '<tr><td colspan="9" class="hint">' + __('common.loading') + '</td></tr>';
      try {
        const data = await admin('/audit/recent?limit=' + (limit || 100));
        document.getElementById('acDbCount').textContent = data.db_count ?? 0;
        summary.textContent = data.note || '';
        dbTbody.innerHTML = '';
        (data.db_events || []).forEach(h => {
          const tr = document.createElement('tr');
          tr.innerHTML = renderAuditEventRow(h);
          dbTbody.appendChild(tr);
        });
        if (!(data.db_events || []).length) {
          dbTbody.innerHTML = '<tr><td colspan="9" class="hint">' + __('ac.no-db-records') + '</td></tr>';
        }
      } catch (e) {
        summary.textContent = e.message;
        dbTbody.innerHTML = `<tr><td colspan="9" class="hint">${esc(e.message)}</td></tr>`;
      }
    }

    async function searchAuditCenter(traceId) {
      const tid = (traceId || document.getElementById('acTraceId').value || '').trim();
      if (!tid) {
        log(__('ac.input-trace-id'), 'warn');
        return;
      }
      document.getElementById('acTraceId').value = tid;
      const summary = document.getElementById('acSummary');
      const dbTbody = document.querySelector('#acDbTable tbody');
      summary.textContent = __('ac.searching');
      dbTbody.innerHTML = '<tr><td colspan="9" class="hint">' + __('common.loading') + '</td></tr>';
      try {
        const detail = await admin('/audit/trace/' + encodeURIComponent(tid));
        document.getElementById('acDbCount').textContent = detail.db_count ?? 0;
        summary.textContent = detail.note || '';
        dbTbody.innerHTML = '';
        (detail.db_events || []).forEach(h => {
          const tr = document.createElement('tr');
          tr.innerHTML = renderAuditEventRow(h);
          dbTbody.appendChild(tr);
        });
        if (!(detail.db_events || []).length) {
          dbTbody.innerHTML = '<tr><td colspan="9" class="hint">' + __('ac.no-db-records') + '</td></tr>';
        }
        log({ trace_id: tid, db_count: detail.db_count });
      } catch (e) {
        summary.textContent = e.message;
        dbTbody.innerHTML = `<tr><td colspan="9" class="hint">${esc(e.message)}</td></tr>`;
      }
    }

    document.getElementById('acSearch').onclick = () => {
      searchAuditCenter().catch(e => log(e.message, 'err'));
    };
    document.getElementById('acTraceId').addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        searchAuditCenter().catch(err => log(err.message, 'err'));
      }
    });
