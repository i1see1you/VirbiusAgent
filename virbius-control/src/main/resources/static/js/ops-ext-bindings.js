    function populateExtScopeSelects() {
      const appIdsSel = document.getElementById('extScopeAppIds');
      const scenesSel = document.getElementById('extScopeScenes');
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

    function onExtScopeChange() {
      const scope = document.getElementById('extScope').value;
      document.getElementById('extScopeAppIds').style.display = scope === 'service' ? '' : 'none';
      document.getElementById('extScopeScenes').style.display = scope === 'route' ? '' : 'none';
      if (scope === 'service' || scope === 'route') populateExtScopeSelects();
    }

    function readExtScope() {
      const scope = document.getElementById('extScope').value;
      if (scope === 'service') {
        const sel = document.getElementById('extScopeAppIds');
        return { bindScope: 'service', appIds: Array.from(sel.selectedOptions).map(o => o.value) };
      }
      if (scope === 'route') {
        const sel = document.getElementById('extScopeScenes');
        return { bindScope: 'route', scenes: Array.from(sel.selectedOptions).map(o => o.value) };
      }
      return { bindScope: 'global' };
    }

    function formatExtScopeDisplay(row) {
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

    async function loadExtendedVars() {
      const data = await admin(`/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata`);
      extendedVars = (data.extended_var_list || []).map(v => ({
        logical: v.logical,
        expr: v.expr || '',
        scope: v.scope || { bindScope: 'global' }
      }));
      renderExtVarsTable();
    }

    function renderExtVarsTable() {
      const tbody = document.querySelector('#extVarTable tbody');
      tbody.innerHTML = '';
      extendedVars.forEach((row, idx) => {
        const tr = document.createElement('tr');
        const exprShort = row.expr.length > 60 ? row.expr.substring(0, 57) + '...' : row.expr;
        tr.innerHTML = `<td><code>${esc(row.logical)}</code></td>
                     <td><code style="font-size:0.8rem;word-break:break-all" title="${escAttr(row.expr)}">${esc(exprShort)}</code></td>
                     <td style="font-size:0.82rem">${formatExtScopeDisplay(row)}</td>
                     <td><button class="danger" data-i="${idx}">${esc(__('common.delete'))}</button></td>`;
        tr.querySelector('button').onclick = async () => {
          if (!confirm(__('ext.confirm-delete', row.logical))) return;
          try {
            await admin(`/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata/extended-vars/${encodeURIComponent(row.logical)}`, { method: 'DELETE' });
            extendedVars.splice(idx, 1);
            renderExtVarsTable();
          } catch (e) {
            log(e.message, 'err');
          }
        };
        const codeEl = tr.querySelector('td:first-child code');
        codeEl.style.cursor = 'pointer';
        codeEl.title = __('bind.click-to-copy');
        codeEl.onclick = () => copyVarRef(row.logical);
        tbody.appendChild(tr);
      });
    }

    document.getElementById('extScope').onchange = onExtScopeChange;

    document.getElementById('btnExtVarAdd').onclick = () => {
      const logical = document.getElementById('extLogical').value.trim();
      const expr = document.getElementById('extExpr').value.trim();
      if (!logical || !expr) return;
      if (!logical.match(/^[a-z][a-z0-9_]*$/)) { log(__('ext.err-name'), 'warn'); return; }
      if (contextVars.some(v => v.logical === logical)) { log(__('ext.err-conflict'), 'warn'); return; }
      if (extendedVars.some(v => v.logical === logical)) { log(__('ext.err-duplicate'), 'warn'); return; }
      extendedVars.push({ logical, expr, scope: readExtScope() });
      document.getElementById('extLogical').value = '';
      document.getElementById('extExpr').value = '';
      renderExtVarsTable();
    };

    document.getElementById('btnSaveExtVars').onclick = () =>
      admin(`/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata/extended-vars?sync=true`, {
        method: 'PUT',
        body: JSON.stringify({ vars: extendedVars.map(v => {
          const m = { logical: v.logical, expr: v.expr };
          if (v.scope && v.scope.bindScope && v.scope.bindScope !== 'global') m.scope = v.scope;
          return m;
        })})
      }).then(log).catch(e => log(e.message, 'err'));

    document.addEventListener('panel-show', e => {
      if (e.detail === 'panel-ext-bindings') {
        populateExtScopeSelects();
        populateExtVarHelp();
      }
    });

    document.getElementById('extExpr').addEventListener('focus', () => populateExtVarHelp());

    function populateExtVarHelp() {
      const container = document.getElementById('extHelpVars');
      if (!container) return;
      const cvars = (contextVars || []).filter(v => v.logical);
      container.innerHTML = cvars.length
        ? cvars.map(v => '<span class="async-var-chip" style="cursor:pointer" data-var="' + escAttr("ctx.var('" + v.logical + "')") + '">' + esc(v.logical) + '</span>').join('')
        : '<span style="color:#94a3b8;font-style:italic">(' + __('ext.help-no-vars') + ')</span>';
      container.querySelectorAll('.async-var-chip').forEach(chip => {
        chip.onclick = () => {
          const ta = document.getElementById('extExpr');
          const text = chip.dataset.var;
          const pos = ta.selectionStart || ta.value.length;
          ta.value = ta.value.slice(0, pos) + text + ta.value.slice(pos);
          ta.focus();
          ta.selectionStart = ta.selectionEnd = pos + text.length;
        };
      });
      const examples = document.getElementById('extHelpExamples');
      if (examples) {
        examples.textContent = [
          "if ctx.var('app_id') == 'evil' then 'true' else 'false' end",
          "ctx.var('app_id') == 'medical-prod'",
          "tonumber(ctx.var('risk_score') or '0') > 50",
          "ctx.var('app_id') or 'default'"
        ].join('\n');
      }
    }
