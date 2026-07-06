    async function loadTenantSelect() {
      const sel = document.getElementById('tenantSelect');
      const data = await adminRoot('/tenants');
      const cur = tenant();
      sel.innerHTML = (data || []).map(t =>
        `<option value="${escAttr(field(t, 'tenant_id', 'tenantId'))}">${esc(field(t, 'tenant_id', 'tenantId'))} · ${esc(field(t, 'name', 'name') || '')}</option>`
      ).join('');
      if (cur && [...sel.options].some(o => o.value === cur)) sel.value = cur;
      else if (sel.options.length) sel.value = sel.options[0].value;
      document.getElementById('tenantId').value = sel.value;
    }

    async function loadTenantsPage() {
      await loadTenantSelect();
      const data = await adminRoot('/tenants');
      const tbody = document.getElementById('tenantTableBody');
      tbody.innerHTML = (data || []).map(t => {
        const tid = field(t, 'tenant_id', 'tenantId');
        return `<tr>
          <td><code>${esc(tid)}</code></td>
          <td>${esc(field(t, 'name', 'name') || '')}</td>
          <td>${esc(String(field(t, 'rule_count', 'ruleCount') ?? 0))}</td>
          <td>${esc((field(t, 'created_at', 'createdAt') || '').slice(0, 19))}</td>
          <td><button type="button" class="btn-switch-tenant" data-tid="${escAttr(tid)}">${__('common.switch')}</button>
              <button type="button" class="btn-load-cred" data-tid="${escAttr(tid)}">${__('common.credential')}</button></td>
        </tr>`;
      }).join('');
      document.getElementById('credTenantLabel').textContent = tenant();
      await loadCredentialsFor(tenant());
    }

    async function loadCredentialsFor(tid) {
      document.getElementById('credTenantLabel').textContent = tid;
      const creds = await adminFetch('/api/v1/admin/tenants/' + encodeURIComponent(tid) + '/api-credentials');
      const platform = await adminFetch('/api/v1/admin/platform/api-credentials').catch(() => []);
      const all = [...(creds || []), ...(platform || [])];
      const tbody = document.getElementById('credTableBody');
      tbody.innerHTML = all.map(c => {
        const cid = field(c, 'credential_id', 'credentialId');
        const tid = field(c, 'tenant_id', 'tenantId');
        const st = field(c, 'status', 'status');
        return `<tr>
          <td><code>${esc(field(c, 'key_prefix', 'keyPrefix') || '')}****</code></td>
          <td>${esc(field(c, 'role', 'role') || '')}</td>
          <td>${esc(field(c, 'label', 'label') || '')}</td>
          <td>${esc(st)}</td>
          <td>${esc((field(c, 'last_used_at', 'lastUsedAt') || '—').slice(0, 19))}</td>
          <td>${st === 'active' ? `<button type="button" class="btn-revoke-cred" data-tid="${escAttr(tid)}" data-cid="${escAttr(cid)}">${esc(__('tenants.revoke'))}</button>` : '—'}</td>
        </tr>`;
      }).join('');
    }

    try {
      if (savedKey) document.getElementById('apiKey').value = savedKey;
    } catch (_) {}
    document.getElementById('apiKey').addEventListener('change', () => {
      try { localStorage.setItem('virbius.ops.apiKey', apiKey()); } catch (_) {}
    });
    document.getElementById('tenantSelect').onchange = () => {
      document.getElementById('tenantId').value = document.getElementById('tenantSelect').value;
      reloadAll().catch(e => log(e.message, 'err'));
    };
    document.getElementById('btnReloadTenants').onclick = () =>
      loadTenantsPage().then(() => log(__('tenants.refreshed'))).catch(e => log(e.message, 'err'));
    document.getElementById('btnCreateTenant').onclick = async () => {
      const tenantId = document.getElementById('newTenantId').value.trim();
      const name = document.getElementById('newTenantName').value.trim();
      if (!tenantId || !name) { log(__('tenants.id-name-required'), 'warn'); return; }
      try {
        const data = await adminRoot('/tenants', {
          method: 'POST',
          body: JSON.stringify({ tenant_id: tenantId, name })
        });
        log(data);
        document.getElementById('newTenantId').value = '';
        document.getElementById('newTenantName').value = '';
        await loadTenantsPage();
        document.getElementById('tenantSelect').value = tenantId;
        document.getElementById('tenantId').value = tenantId;
      } catch (e) { log(e.message, 'err'); }
    };
    document.getElementById('btnIssueCred').onclick = async () => {
      const role = document.getElementById('issueCredRole').value;
      const label = document.getElementById('issueCredLabel').value.trim();
      try {
        const data = await admin('/api-credentials', {
          method: 'POST',
          body: JSON.stringify({ role, label: label || null })
        });
        log({ issued: data, warning: __('tenants.key-show-once') }, 'warn');
        if (data.api_key) {
          document.getElementById('apiKey').value = data.api_key;
          try { localStorage.setItem('virbius.ops.apiKey', data.api_key); } catch (_) {}
        }
        await loadCredentialsFor(tenant());
      } catch (e) { log(e.message, 'err'); }
    };
    document.getElementById('btnIssuePlatformCred').onclick = async () => {
      const label = document.getElementById('issueCredLabel').value.trim();
      try {
        const data = await adminFetch('/api/v1/admin/platform/api-credentials', {
          method: 'POST',
          body: JSON.stringify({ role: 'platform_admin', label: label || null })
        });
        log({ issued: data, warning: __('tenants.platform-key-show-once') }, 'warn');
        if (data.api_key) {
          document.getElementById('apiKey').value = data.api_key;
          try { localStorage.setItem('virbius.ops.apiKey', data.api_key); } catch (_) {}
        }
        await loadCredentialsFor(tenant());
      } catch (e) { log(e.message, 'err'); }
    };
    document.getElementById('tenantTableBody').addEventListener('click', async (e) => {
      const sw = e.target.closest('.btn-switch-tenant');
      if (sw) {
        document.getElementById('tenantSelect').value = sw.dataset.tid;
        document.getElementById('tenantId').value = sw.dataset.tid;
        await reloadAll();
        log({ switched: sw.dataset.tid }, 'ok');
        return;
      }
      const lc = e.target.closest('.btn-load-cred');
      if (lc) await loadCredentialsFor(lc.dataset.tid).catch(err => log(err.message, 'err'));
    });
    document.getElementById('credTableBody').addEventListener('click', async (e) => {
      const btn = e.target.closest('.btn-revoke-cred');
      if (!btn) return;
      const tid = btn.dataset.tid;
      const cid = btn.dataset.cid;
      const path = tid === '*'
        ? '/api/v1/admin/platform/api-credentials/' + encodeURIComponent(cid) + '/revoke'
        : '/api/v1/admin/tenants/' + encodeURIComponent(tid) + '/api-credentials/' + encodeURIComponent(cid) + '/revoke';
      try {
        await adminFetch(path, { method: 'POST' });
        log({ revoked: cid }, 'ok');
        await loadCredentialsFor(tenant());
      } catch (err) { log(err.message, 'err'); }
    });
