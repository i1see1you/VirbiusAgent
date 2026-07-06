    function syncListDimUi() {
      const isVar = document.getElementById('newListDim').value === 'var';
      document.getElementById('listVarWrap').style.display = isVar ? '' : 'none';
      if (isVar) fillListVarLogicalSelect();
    }

    function fillListVarLogicalSelect(selected) {
      const sel = document.getElementById('listVarLogical');
      const logicals = contextVars.map(v => v.logical).filter(Boolean);
      const opts = logicals.length
        ? logicals.map(l => `<option value="${escAttr(l)}">${esc(l)}</option>`).join('')
        : '<option value="">' + __('lists.no-mapping') + '</option>';
      sel.innerHTML = opts;
      if (selected && logicals.includes(selected)) {
        sel.value = selected;
        document.getElementById('listVarLogicalCustom').value = selected;
      } else if (selected) {
        sel.innerHTML = `<option value="${escAttr(selected)}">${esc(selected)}</option>` + opts;
        sel.value = selected;
        document.getElementById('listVarLogicalCustom').value = selected;
      }
    }

    function buildListDimension() {
      const base = document.getElementById('newListDim').value;
      if (base !== 'var') return base;
      const logical = document.getElementById('listVarLogicalCustom').value.trim()
        || document.getElementById('listVarLogical').value.trim();
      if (!logical) throw new Error(__('lists.var-dim-required'));
      if (!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(logical)) {
        throw new Error(__('lists.var-name-invalid'));
      }
      return 'var:' + logical;
    }

    let listCatalog = [];
    let listMetaByName = {};
    const MEMORY_LIST_MAX_ACTIVE = 1000;

    function entryValue(e) {
      if (typeof e === 'string') return e;
      return field(e, 'value') || '';
    }

    function flattenListRows(lists) {
      const rows = [];
      if (!Array.isArray(lists)) return rows;
      lists.forEach(item => {
        const name = field(item, 'list_name', 'listName') || '';
        const dim = field(item, 'dimension') || '';
        const storage = inferListStorage(dim, field(item, 'storage'));
        const entries = field(item, 'entries') || [];
        entries.forEach(e => {
          rows.push({
            listName: name,
            dim,
            storage,
            value: entryValue(e),
            createdAt: field(e, 'created_at', 'createdAt') || '',
            expiresAt: field(e, 'expires_at', 'expiresAt') || '',
            remark: field(e, 'remark') || ''
          });
        });
      });
      return rows.sort((a, b) => (a.listName + a.value).localeCompare(b.listName + b.value));
    }

    async function loadLists() {
      const data = await admin('/lists');
      listMetaByName = {};
      (data.lists || []).forEach(item => {
        const name = field(item, 'list_name', 'listName');
        if (!name) return;
        const dim = field(item, 'dimension') || '';
        const storage = inferListStorage(dim, field(item, 'storage'));
        listMetaByName[name] = {
          dimension: dim,
          storage,
          entries: field(item, 'entries') || [],
          activeEntryCount: field(item, 'active_entry_count', 'activeEntryCount')
        };
      });
      listCatalog = Object.keys(listMetaByName).sort();
      const sel = document.getElementById('entryListName');
      const prev = sel.value;
      sel.innerHTML = '<option value="">' + __('lists.placeholder-select') + '</option>' +
        listCatalog.map(n => `<option value="${escAttr(n)}">${esc(n)}</option>`).join('');
      if (prev && listCatalog.includes(prev)) sel.value = prev;
      fillWizardListSelect();
      const tbody = document.querySelector('#listsTable tbody');
      tbody.innerHTML = '';
      flattenListRows(data.lists).forEach(row => {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td><code>${esc(row.listName)}</code></td>
          <td>${esc(formatListDimension(row.dim))}</td>
          <td>${esc(formatListStorage(row.storage))}</td>
          <td><code>${esc(row.value)}</code></td>
          <td>${esc(fmtTime(row.createdAt))}</td>
          <td>${esc(fmtTime(row.expiresAt))}</td>
          <td>${esc(row.remark)}</td>
          <td><button class="danger" data-name="${escAttr(row.listName)}" data-val="${escAttr(row.value)}">${__('common.delete')}</button></td>`;
        tbody.appendChild(tr);
      });
      tbody.querySelectorAll('button.danger').forEach(btn => {
        btn.onclick = async () => {
          const resp = await admin(`/lists/${encodeURIComponent(btn.dataset.name)}/entries/${encodeURIComponent(btn.dataset.val)}`, { method: 'DELETE' });
          await loadLists();
          log(__('lists.entry-deleted') + listSyncHint(resp), 'ok');
        };
      });
    }

    document.getElementById('btnListCreate').onclick = async () => {
      const name = document.getElementById('newListName').value.trim();
      const remark = document.getElementById('newListRemark').value.trim() || null;
      if (!name) { log(__('lists.name-required'), 'warn'); return; }
      let dim;
      try { dim = buildListDimension(); } catch (e) { log(e.message, 'err'); return; }
      await admin('/lists/' + encodeURIComponent(name), {
        method: 'PUT',
        body: JSON.stringify({ dimension: dim, remark })
      });
      document.getElementById('newListName').value = '';
      document.getElementById('newListRemark').value = '';
      await loadLists();
      log(__('lists.created'), 'ok');
    };
    document.getElementById('newListDim').onchange = syncListDimUi;

    document.getElementById('btnListAdd').onclick = () => {
      const listName = document.getElementById('entryListName').value;
      const val = document.getElementById('entryVal').value.trim();
      const expires = document.getElementById('entryExpires').value.trim();
      const remark = document.getElementById('entryRemark').value.trim();
      if (!listName || !val) { log(__('lists.select-name-and-value'), 'warn'); return; }
      const meta = listMetaByName[listName];
      if (meta && meta.storage === 'memory') {
        const active = meta.activeEntryCount != null
          ? Number(meta.activeEntryCount)
          : countActiveListEntries(meta.entries);
        const newActive = isListEntryActive(expires || null);
        const duplicate = (meta.entries || []).some(e => entryValue(e) === val);
        if (newActive && !duplicate && active >= MEMORY_LIST_MAX_ACTIVE) {
          log(__('lists.memory-limit', MEMORY_LIST_MAX_ACTIVE), 'warn');
          return;
        }
      }
      const entry = { value: val };
      if (remark) entry.remark = remark;
      if (expires) entry.expires_at = expires;
      admin('/lists/' + encodeURIComponent(listName) + '/entries', {
        method: 'POST',
        body: JSON.stringify({ entries: [entry] })
      })
        .then(resp => {
          document.getElementById('entryVal').value = '';
          document.getElementById('entryExpires').value = '';
          document.getElementById('entryRemark').value = '';
          return loadLists().then(() => resp);
        })
        .then(resp => log(__('lists.entry-added') + listSyncHint(resp), 'ok'))
        .catch(e => log(e.message, 'err'));
    };
