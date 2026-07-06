    async function loadGatewayRoutes() {
      const data = await admin(`/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata`);
      const gw = data.gateway || {};
      document.getElementById('gwEvaluate').checked = gw.evaluate !== false;
      document.getElementById('gwFailMode').value = (gw.fail_mode || 'open').toLowerCase();
      const cs = gw.cloud_scan || {};
      document.getElementById('gwAgentUrl').value = cs.agent_url || '';
      document.getElementById('gwTimeoutMs').value = cs.timeout_ms != null ? cs.timeout_ms : 3000;
      gatewayRoutes = (data.gateway_routes || []).map(r => ({
        uri: r.uri || '',
        methods: Array.isArray(r.methods) && r.methods.length ? r.methods : ['POST']
      }));
      renderGatewayRoutesTable();
    }

    function renderGatewayRoutesTable() {
      const tbody = document.querySelector('#gwRoutesTable tbody');
      tbody.innerHTML = '';
      gatewayRoutes.forEach((row, idx) => {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td><code>${esc(row.uri)}</code></td>
          <td>${esc((row.methods || []).join(', '))}</td>
          <td><button class="danger" data-i="${idx}">${__('common.delete')}</button></td>`;
        tr.querySelector('button').onclick = () => { gatewayRoutes.splice(idx, 1); renderGatewayRoutesTable(); };
        tbody.appendChild(tr);
      });
    }

    function readGatewayRoutesPayload() {
      const agentUrl = document.getElementById('gwAgentUrl').value.trim();
      const timeoutMs = Number(document.getElementById('gwTimeoutMs').value);
      const cloudScan = {};
      if (agentUrl) cloudScan.agent_url = agentUrl;
      if (!Number.isNaN(timeoutMs) && timeoutMs > 0) cloudScan.timeout_ms = timeoutMs;
      return {
        evaluate: document.getElementById('gwEvaluate').checked,
        fail_mode: document.getElementById('gwFailMode').value || 'open',
        cloud_scan: cloudScan,
        routes: gatewayRoutes.map(r => ({
          uri: r.uri,
          methods: r.methods && r.methods.length ? r.methods : ['POST']
        }))
      };
    }

    document.getElementById('btnGwRouteAdd').onclick = () => {
      const uri = document.getElementById('grUri').value.trim();
      if (!uri) { log(__('gw.uri-required'), 'warn'); return; }
      if (gatewayRoutes.some(r => r.uri === uri)) { log(__('gw.uri-exists'), 'warn'); return; }
      gatewayRoutes.push({
        uri,
        methods: parseMethodsText(document.getElementById('grMethods').value)
      });
      document.getElementById('grUri').value = '';
      document.getElementById('grMethods').value = 'POST';
      renderGatewayRoutesTable();
    };
    document.getElementById('btnSaveGatewayRoutes').onclick = () => {
      if (!gatewayRoutes.length) {
        log(__('gw.at-least-one'), 'warn');
        return;
      }
      return admin(`/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata/gateway-routes?sync=true`, {
        method: 'PUT',
        body: JSON.stringify(readGatewayRoutesPayload())
      }).then(async data => {
        log(data);
        await loadGatewayRoutes();
      }).catch(e => log(e.message, 'err'));
    };
