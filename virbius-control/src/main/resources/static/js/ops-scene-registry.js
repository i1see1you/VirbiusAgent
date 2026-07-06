    let srEditIndex = -1;

    function validateUri(uri) {
      if (!uri.startsWith('/')) return __('sr.uri-err-format');
      if (uri.lastIndexOf('*') > 0 && uri.indexOf('*') !== uri.length - 1) return __('sr.uri-err-wildcard');
      return null;
    }

    function buildUriChip(uri, onRemove) {
      const chip = document.createElement('span');
      chip.className = 'uri-chip';
      chip.innerHTML = `<span>${esc(uri)}</span><span class="remove" data-uri="${escAttr(uri)}">×</span>`;
      chip.querySelector('.remove').onclick = onRemove;
      return chip;
    }

    function renderUriChips(uris, autoFocus) {
      const container = document.getElementById('srUrisContainer');
      const existingInput = container.querySelector('.uri-chip-input');
      container.innerHTML = '';
      const input = document.createElement('input');
      input.className = 'uri-chip-input';
      input.placeholder = __('sr.uri-hint');
      (uris || []).forEach(u => {
        const chip = buildUriChip(u, () => {
          removeUriChip(u);
        });
        container.appendChild(chip);
      });
      container.appendChild(input);
      if (autoFocus !== false) input.focus();
      input.onkeydown = e => {
        if (e.key === 'Enter' || e.key === ',') {
          e.preventDefault();
          const val = input.value.trim();
          if (val) addUriChip(val);
          input.value = '';
        }
        if (e.key === 'Backspace' && !input.value) {
          const chips = container.querySelectorAll('.uri-chip');
          if (chips.length) {
            const last = chips[chips.length - 1];
            const uri = last.querySelector('.remove').dataset.uri;
            removeUriChip(uri);
          }
        }
      };
      input.onblur = () => {
        const val = input.value.trim();
        if (val) { addUriChip(val); input.value = ''; }
      };
      container.onclick = e => { if (e.target === container) input.focus(); };
      container.classList.remove('uri-chip-err');
    }

    function getUriChips() {
      const chips = document.querySelectorAll('#srUrisContainer .uri-chip span:first-child');
      return Array.from(chips).map(s => s.textContent);
    }

    function addUriChip(raw) {
      const uri = raw.replace(/,$/, '').trim();
      if (!uri) return;
      const err = validateUri(uri);
      if (err) { log(err + ': ' + uri, 'warn'); document.getElementById('srUrisContainer').classList.add('uri-chip-err'); return; }
      document.getElementById('srUrisContainer').classList.remove('uri-chip-err');
      const existing = getUriChips();
      if (existing.some(u => u === uri)) { log(__('sr.uri-err-duplicate') + uri, 'warn'); return; }
      existing.push(uri);
      renderUriChips(existing);
    }

    function removeUriChip(uri) {
      const existing = getUriChips().filter(u => u !== uri);
      renderUriChips(existing);
    }

    function renderKvList(kvs) {
      const list = document.getElementById('srKvList');
      list.innerHTML = '';
      if (!kvs || !Object.keys(kvs).length) return;
      Object.entries(kvs).forEach(([k, v]) => {
        const row = document.createElement('div');
        row.className = 'kv-row';
        row.innerHTML = `<code>${esc(k)}</code> = <code>${esc(v)}</code> <button class="kv-del" data-k="${escAttr(k)}">×</button>`;
        row.querySelector('.kv-del').onclick = () => {
          const cur = readKvPairs();
          delete cur[k];
          renderKvList(cur);
        };
        list.appendChild(row);
      });
    }

    function readKvPairs() {
      const result = {};
      document.querySelectorAll('#srKvList .kv-row').forEach(row => {
        const code = row.querySelectorAll('code');
        if (code.length >= 2) {
          result[code[0].textContent] = code[1].textContent;
        }
      });
      return result;
    }

    document.getElementById('btnMqAdd').onclick = () => {
      const k = document.getElementById('srMqKey').value.trim();
      const v = document.getElementById('srMqVal').value.trim();
      if (!k || !v) return;
      const cur = readKvPairs();
      cur[k] = v;
      renderKvList(cur);
      document.getElementById('srMqKey').value = '';
      document.getElementById('srMqVal').value = '';
    };

    function fillSceneForm(row, idx) {
      document.getElementById('srSceneId').value = row.sceneId || '';
      document.getElementById('srAppId').value = row.appId || '';
      document.getElementById('srDefault').checked = !!row.defaultScene;
      renderUriChips(row.uris || []);
      document.getElementById('srPriority').value = row.priority != null ? row.priority : 0;
      renderKvList(row.matchQuery || {});
      srEditIndex = idx;
      document.getElementById('srEditIndex').value = idx;
      document.getElementById('btnSceneRegAdd').textContent = __('sr.btn-update');
      document.getElementById('btnSceneRegAdd').dataset.i18n = 'sr.btn-update';
      document.getElementById('btnSceneRegCancel').style.display = '';
    }

    function resetSceneForm() {
      document.getElementById('srSceneId').value = '';
      document.getElementById('srAppId').value = '';
      document.getElementById('srDefault').checked = false;
      renderUriChips([]);
      document.getElementById('srPriority').value = '0';
      renderKvList({});
      srEditIndex = -1;
      document.getElementById('srEditIndex').value = '-1';
      document.getElementById('btnSceneRegAdd').textContent = __('sr.btn-add');
      document.getElementById('btnSceneRegAdd').dataset.i18n = 'sr.btn-add';
      document.getElementById('btnSceneRegCancel').style.display = 'none';
      document.getElementById('srUrisContainer').classList.remove('uri-chip-err');
    }

    async function loadSceneRegistry() {
      sceneRegistryHydrating = true;
      try {
        const data = await admin(`/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata`);
        const reg = data.scene_registry || {};
        document.getElementById('srFailUnknown').checked = !!reg.fail_on_unknown_app;
        document.getElementById('srFailUnresolved').checked = !!reg.fail_on_unresolved_scene;
        sceneEntries = (data.scene_entries || []).map(r => ({
          sceneId: r.scene_id || '',
          appId: r.app_id || '',
          defaultScene: !!r.default,
          uris: Array.isArray(r.uris) ? r.uris : [],
          priority: r.priority != null ? Number(r.priority) : 0,
          matchQuery: (r.match && r.match.query) ? { ...r.match.query } : {}
        }));
        renderSceneRegistryTable();
      } finally {
        sceneRegistryHydrating = false;
      }
    }

    async function saveSceneRegistry(sync) {
      if (!sceneEntries.length) {
        throw new Error(__('sr.at-least-one'));
      }
      const data = await admin(
        `/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata/scene-registry?sync=${sync}`,
        { method: 'PUT', body: JSON.stringify(readSceneRegistryPayload()) }
      );
      log(sync ? __('sr.synced') : __('sr.saved'), 'ok');
      await loadSceneRegistry();
      return data;
    }

    function onSceneRegistryFlagsChange() {
      if (sceneRegistryHydrating || !sceneEntries.length) return;
      saveSceneRegistry(false).catch(e => {
        log(e.message, 'err');
        loadSceneRegistry();
      });
    }

    function formatMatchQuery(q) {
      if (!q || !Object.keys(q).length) return '—';
      return Object.entries(q).map(([k, v]) => `${k}=${v}`).join(', ');
    }

    function renderSceneRegistryTable() {
      const tbody = document.querySelector('#sceneRegTable tbody');
      tbody.innerHTML = '';
      sceneEntries.forEach((row, idx) => {
        const tr = document.createElement('tr');
        if (idx === srEditIndex) tr.style.background = '#fefce8';
        tr.innerHTML = `<td><code>${esc(row.sceneId)}</code></td>
          <td><code>${esc(row.appId)}</code></td>
          <td>${row.defaultScene ? '✓' : '—'}</td>
          <td><code>${esc((row.uris || []).join(', '))}</code></td>
          <td>${row.priority != null ? row.priority : 0}</td>
          <td><code>${esc(formatMatchQuery(row.matchQuery))}</code></td>
          <td><button class="btn-edit" data-i="${idx}">${__('sr.btn-edit')}</button></td>
          <td><button class="danger" data-i="${idx}">${__('common.delete')}</button></td>`;
        tr.querySelector('.btn-edit').onclick = () => fillSceneForm(row, idx);
        tr.querySelector('.danger').onclick = async () => {
          if (!confirm(__('sr.confirm-delete', row.sceneId))) return;
          sceneEntries.splice(idx, 1);
          if (srEditIndex === idx) resetSceneForm();
          else if (srEditIndex > idx) srEditIndex--;
          renderSceneRegistryTable();
          try {
            await saveSceneRegistry(false);
          } catch (e) {
            log(e.message, 'err');
            await loadSceneRegistry();
          }
        };
        tbody.appendChild(tr);
      });
    }

    function readSceneRegistryPayload() {
      return {
        fail_on_unknown_app: document.getElementById('srFailUnknown').checked,
        fail_on_unresolved_scene: document.getElementById('srFailUnresolved').checked,
        scenes: sceneEntries.map(e => {
          const row = {
            scene_id: e.sceneId,
            app_id: e.appId,
            default_scene: e.defaultScene,
            uris: e.uris && e.uris.length ? e.uris : [],
            priority: e.priority != null ? e.priority : 0
          };
          if (e.matchQuery && Object.keys(e.matchQuery).length) row.match_query = e.matchQuery;
          return row;
        })
      };
    }

    document.getElementById('srFailUnknown').onchange = onSceneRegistryFlagsChange;
    document.getElementById('srFailUnresolved').onchange = onSceneRegistryFlagsChange;

    document.getElementById('btnSceneRegAdd').onclick = async () => {
      const sceneId = document.getElementById('srSceneId').value.trim();
      const appId = document.getElementById('srAppId').value.trim();
      const uris = getUriChips();
      if (!sceneId || !appId) { log(__('sr.fill-id-and-app'), 'warn'); return; }
      if (srEditIndex < 0 && sceneEntries.some(e => e.sceneId === sceneId)) { log(__('sr.id-exists'), 'warn'); return; }
      const matchQuery = readKvPairs();
      if (uris.length) {
        for (const u of uris) {
          const err = validateUri(u);
          if (err) { log(err + ': ' + u, 'warn'); return; }
        }
      }
      const entry = { sceneId, appId, defaultScene: document.getElementById('srDefault').checked, uris, priority: Number(document.getElementById('srPriority').value) || 0, matchQuery };
      if (srEditIndex >= 0) {
        sceneEntries[srEditIndex] = entry;
      } else {
        sceneEntries.push(entry);
      }
      resetSceneForm();
      renderSceneRegistryTable();
      try {
        await saveSceneRegistry(false);
      } catch (e) {
        log(e.message, 'err');
        await loadSceneRegistry();
      }
    };

    document.getElementById('btnSceneRegCancel').onclick = resetSceneForm;

    document.getElementById('btnSyncSceneRegistry').onclick = () =>
      saveSceneRegistry(true).catch(e => log(e.message, 'err'));

    renderUriChips([], false);

    renderUriChips([], false);

    renderUriChips([], false);

    renderUriChips([], false);
