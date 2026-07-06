    function populateScopeSelects() {
      const appIdsSel = document.getElementById('bScopeAppIds');
      const scenesSel = document.getElementById('bScopeScenes');
      appIdsSel.innerHTML = '';
      scenesSel.innerHTML = '';
      const seenApps = new Set();
      const seenScenes = new Set();
      (sceneEntries || []).forEach(e => {
        if (e.appId && !seenApps.has(e.appId)) {
          seenApps.add(e.appId);
          const opt = document.createElement('option');
          opt.value = e.appId;
          opt.textContent = e.appId;
          appIdsSel.appendChild(opt);
        }
        if (e.sceneId && !seenScenes.has(e.sceneId)) {
          seenScenes.add(e.sceneId);
          const opt = document.createElement('option');
          opt.value = e.sceneId;
          opt.textContent = e.sceneId;
          scenesSel.appendChild(opt);
        }
      });
    }

    function onScopeChange() {
      const scope = document.getElementById('bScope').value;
      document.getElementById('bScopeAppIds').style.display = scope === 'service' ? '' : 'none';
      document.getElementById('bScopeScenes').style.display = scope === 'route' ? '' : 'none';
      if (scope === 'service') populateScopeSelects();
      if (scope === 'route') populateScopeSelects();
    }

    function readScope() {
      const scope = document.getElementById('bScope').value;
      if (scope === 'global') return { bindScope: 'global' };
      if (scope === 'service') {
        const sel = document.getElementById('bScopeAppIds');
        return { bindScope: 'service', appIds: Array.from(sel.selectedOptions).map(o => o.value) };
      }
      if (scope === 'route') {
        const sel = document.getElementById('bScopeScenes');
        return { bindScope: 'route', scenes: Array.from(sel.selectedOptions).map(o => o.value) };
      }
      return { bindScope: 'global' };
    }

    function formatScopeDisplay(row) {
      const s = row.scope;
      if (!s || s.bindScope === 'global' || !s.bindScope) return __('bind.scope-global');
      if (s.bindScope === 'service') {
        const ids = (s.appIds || []).join(', ');
        return __('bind.scope-service') + (ids ? ': ' + esc(ids) : '');
      }
      if (s.bindScope === 'route') {
        const scenes = (s.scenes || []).join(', ');
        return __('bind.scope-route') + (scenes ? ': ' + esc(scenes) : '');
      }
      return __('bind.scope-global');
    }

    async function loadBindings() {
      const data = await admin(`/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata`);
      contextVars = (data.context_vars || []).map(v => ({
        logical: v.logical,
        from: v.from,
        name: v.name || '',
        field: v.field || '',
        scope: v.scope || { bindScope: 'global' }
      }));
      renderBindingsTable();
      fillListVarLogicalSelect();
      fillCumVarLogicalSelect();
    }

    function renderBindingsTable() {
      const tbody = document.querySelector('#bindTable tbody');
      tbody.innerHTML = '';
      contextVars.forEach((row, idx) => {
        const wire = row.from === 'subject' || row.from === 'network' ? row.field : row.name;
        const tr = document.createElement('tr');
        tr.innerHTML = `<td><code>${esc(row.logical)}</code></td><td>${esc(row.from)}</td><td><code>${esc(wire)}</code></td>
                     <td style="font-size:0.82rem">${formatScopeDisplay(row)}</td>
                     <td><button class="danger" data-i="${idx}">${esc(__('common.delete'))}</button></td>`;
        const codeEl = tr.querySelector('td:first-child code');
        codeEl.style.cursor = 'pointer';
        codeEl.title = __('bind.click-to-copy');
        codeEl.onclick = () => copyVarRef(row.logical);
        tr.querySelector('button').onclick = async () => {
          if (!confirm(__('ext.confirm-delete', row.logical))) return;
          try {
            await admin(`/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata/context-bindings/${encodeURIComponent(row.logical)}`, { method: 'DELETE' });
            contextVars.splice(idx, 1);
            renderBindingsTable();
          } catch (e) {
            log(e.message, 'err');
          }
        };
        tbody.appendChild(tr);
      });
    }

    document.getElementById('bScope').onchange = onScopeChange;

    document.getElementById('btnBindAdd').onclick = () => {
      const logical = document.getElementById('bLogical').value.trim();
      if (!logical) return;
      const from = document.getElementById('bFrom').value || 'query';
      const wireRaw = document.getElementById('bWire').value.trim();
      const wire = wireRaw || logical;
      const row = { logical, from, name: '', field: '', scope: readScope() };
      if (from === 'subject' || from === 'network') row.field = wire;
      else row.name = wire;
      if (!contextVars.some(x => x.logical === logical)) contextVars.push(row);
      document.getElementById('bLogical').value = '';
      document.getElementById('bWire').value = '';
      renderBindingsTable();
    };

    document.getElementById('btnSaveBindings').onclick = () =>
      admin(`/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata/context-bindings?sync=true`, {
        method: 'PUT',
        body: JSON.stringify({ vars: contextVars.map(v => {
          const m = { logical: v.logical, from: v.from };
          if (v.name) m.name = v.name;
          if (v.field) m.field = v.field;
          if (v.scope && v.scope.bindScope && v.scope.bindScope !== 'global') m.scope = v.scope;
          return m;
        })})
      }).then(log).catch(e => log(e.message, 'err'));

    document.addEventListener('panel-show', e => {
      if (e.detail === 'panel-bindings') {
        populateScopeSelects();
      }
    });
