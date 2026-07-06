    function formatCumDimension(dim) {
      if (!dim) return '—';
      if (String(dim).startsWith('var:')) return 'var(' + dim.slice(4) + ')';
      return String(dim);
    }

    function syncCumDimUi() {
      const isVar = document.getElementById('cumDim').value === 'var';
      document.getElementById('cumVarWrap').style.display = isVar ? '' : 'none';
      document.getElementById('cumVarHint').style.display = isVar ? '' : 'none';
      if (isVar) {
        const cur = document.getElementById('cumVarLogicalCustom').value.trim()
          || document.getElementById('cumVarLogical').value;
        fillCumVarLogicalSelect(cur);
      }
    }

    function fillCumVarLogicalSelect(selected) {
      const sel = document.getElementById('cumVarLogical');
      const logicals = contextVars.map(v => v.logical).filter(Boolean);
      const opts = logicals.length
        ? logicals.map(l => `<option value="${escAttr(l)}">${esc(l)}</option>`).join('')
        : '<option value="">' + __('cum.no-mapping') + '</option>';
      sel.innerHTML = opts;
      if (selected && logicals.includes(selected)) {
        sel.value = selected;
        document.getElementById('cumVarLogicalCustom').value = selected;
      } else if (selected) {
        sel.innerHTML = `<option value="${escAttr(selected)}">${esc(selected)}</option>` + opts;
        sel.value = selected;
        document.getElementById('cumVarLogicalCustom').value = selected;
      }
    }

    function parseCumDimensionIntoUi(dim) {
      const d = (dim || 'user_id').trim();
      if (d.startsWith('var:')) {
        document.getElementById('cumDim').value = 'var';
        fillCumVarLogicalSelect(d.slice(4));
      } else if (d === 'app_id') {
        document.getElementById('cumDim').value = 'var';
        fillCumVarLogicalSelect('app_id');
      } else {
        document.getElementById('cumDim').value = d;
        document.getElementById('cumVarLogicalCustom').value = '';
      }
      syncCumDimUi();
    }

    function buildCumDimension() {
      const base = document.getElementById('cumDim').value;
      if (base !== 'var') return base;
      const logical = document.getElementById('cumVarLogicalCustom').value.trim()
        || document.getElementById('cumVarLogical').value.trim();
      if (!logical) throw new Error(__('cum.var-dim-required'));
      if (!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(logical)) {
        throw new Error(__('cum.var-name-invalid'));
      }
      return 'var:' + logical;
    }

    function formatCumWindow(c) {
      const kind = field(c, 'window_kind', 'windowKind') || 'rolling';
      if (kind === 'calendar_day') {
        const tz = field(c, 'timezone') || 'UTC';
        return `calendar_day (${tz})`;
      }
      const min = field(c, 'window_minutes', 'windowMinutes');
      const hr = field(c, 'window_hours', 'windowHours');
      if (min) return `rolling ${min}m`;
      if (hr) return `rolling ${hr}h`;
      return 'rolling';
    }

    function syncCumWinLenHint() {
      const hours = document.getElementById('cumWinUnitHr').checked;
      document.getElementById('cumWinLenHint').textContent = hours ? __('cum.hours') : __('cum.minutes');
    }

    function setCumWinUnit(unit) {
      const hours = unit === 'hours';
      document.getElementById('cumWinUnitMin').checked = !hours;
      document.getElementById('cumWinUnitHr').checked = hours;
      syncCumWinLenHint();
    }

    function syncCumWindowFields() {
      const kind = document.getElementById('cumWinKind').value;
      const rolling = kind === 'rolling';
      document.getElementById('cumWinUnitWrap').style.display = rolling ? '' : 'none';
      document.getElementById('cumWinLenWrap').style.display = rolling ? '' : 'none';
      document.getElementById('cumTzWrap').style.display = rolling ? 'none' : '';
    }

    function openCumulativeEditor(c, isNew) {
      isNewCumulative = !!isNew;
      selectedCumulativeName = isNew ? null : field(c, 'cumulative_name', 'cumulativeName');
      document.getElementById('cumEditor').style.display = 'block';
      document.getElementById('cumEditTitle').textContent = isNew ? __('cum.edit-title-new') : __('cum.edit-title-edit');
      document.getElementById('cumEditName').textContent = isNew ? '' : (selectedCumulativeName || '');
      document.getElementById('cumNameRow').style.display = isNew ? '' : 'none';
      document.getElementById('btnCumDelete').style.display = isNew ? 'none' : '';
      document.getElementById('cumName').value = isNew ? '' : (selectedCumulativeName || '');
      document.getElementById('cumName').disabled = !isNew;
      document.getElementById('cumDesc').value = field(c, 'description') || '';
      parseCumDimensionIntoUi(field(c, 'dimension'));
      document.getElementById('cumStatus').value = field(c, 'status') || 'active';
      document.getElementById('cumPriority').value = field(c, 'priority') ?? 10;
      document.getElementById('cumWinKind').value = field(c, 'window_kind', 'windowKind') || 'rolling';
      const winHr = field(c, 'window_hours', 'windowHours');
      const winMin = field(c, 'window_minutes', 'windowMinutes');
      if (winHr != null && winHr !== '' && Number(winHr) > 0) {
        setCumWinUnit('hours');
        document.getElementById('cumWinLen').value = winHr;
      } else {
        setCumWinUnit('minutes');
        document.getElementById('cumWinLen').value = winMin != null && winMin !== '' ? winMin : 60;
      }
      document.getElementById('cumTz').value = field(c, 'timezone') || 'Asia/Shanghai';
      document.getElementById('cumIngestPredicate').value = field(c, 'ingest_predicate', 'ingestPredicate') || '';
      syncCumWindowFields();
      if (isNew) parseCumDimensionIntoUi('user_id');
    }

    function readCumulativePayload(name) {
      const kind = document.getElementById('cumWinKind').value;
      const body = {
        description: document.getElementById('cumDesc').value.trim() || null,
        dimension: buildCumDimension(),
        window_kind: kind,
        priority: Number(document.getElementById('cumPriority').value) || 0,
        status: document.getElementById('cumStatus').value
      };
      const ingestPredicate = document.getElementById('cumIngestPredicate').value.trim();
      if (ingestPredicate) {
        body.ingest_predicate_runtime = 'lua';
        body.ingest_predicate = ingestPredicate;
      } else {
        body.ingest_predicate_runtime = null;
        body.ingest_predicate = null;
      }
      if (kind === 'rolling') {
        const len = Number(document.getElementById('cumWinLen').value);
        if (document.getElementById('cumWinUnitHr').checked) {
          body.window_hours = len > 0 ? len : 1;
          body.window_minutes = null;
        } else {
          body.window_minutes = len > 0 ? len : 60;
          body.window_hours = null;
        }
        body.timezone = null;
      } else {
        body.window_minutes = null;
        body.window_hours = null;
        body.timezone = document.getElementById('cumTz').value.trim() || 'UTC';
      }
      return { name, body };
    }

    async function loadCumulatives() {
      cumulativeRows = await admin('/cumulatives');
      const tbody = document.querySelector('#cumTable tbody');
      tbody.innerHTML = '';
      cumulativeRows.forEach(c => {
        const name = field(c, 'cumulative_name', 'cumulativeName');
        const tr = document.createElement('tr');
        if (name === selectedCumulativeName) tr.classList.add('sel');
        const st = field(c, 'status') || 'active';
        tr.innerHTML = `<td><code>${esc(name)}</code></td><td>${esc(formatCumDimension(field(c, 'dimension')))}</td>
          <td>${esc(formatCumWindow(c))}</td>
          <td><span class="tag ${st === 'disabled' ? 'disabled' : ''}">${esc(st)}</span></td>
          <td><button type="button" class="secondary" data-name="${escAttr(name)}">${__('common.edit')}</button></td>`;
        tr.onclick = (e) => {
          if (e.target.tagName === 'BUTTON') return;
          selectCumulative(name);
        };
        tr.querySelector('button').onclick = (e) => { e.stopPropagation(); selectCumulative(name); };
        tbody.appendChild(tr);
      });
      fillWizardCumSelect();
    }

    async function selectCumulative(name) {
      selectedCumulativeName = name;
      const c = await admin('/cumulatives/' + encodeURIComponent(name));
      const def = field(c, 'definition') || c;
      openCumulativeEditor(def, false);
      await loadCumulatives();
    }

    document.getElementById('cumWinKind').onchange = syncCumWindowFields;
    document.getElementById('cumWinUnitMin').onchange = syncCumWinLenHint;
    document.getElementById('cumWinUnitHr').onchange = syncCumWinLenHint;
    document.getElementById('cumDim').onchange = syncCumDimUi;
    document.getElementById('cumVarLogical').onchange = () => {
      const v = document.getElementById('cumVarLogical').value;
      if (v) document.getElementById('cumVarLogicalCustom').value = v;
    };
    document.getElementById('btnCumNew').onclick = () => openCumulativeEditor({}, true);
    document.getElementById('btnCumRefresh').onclick = () => loadCumulatives().then(() => log(__('cum.list-refreshed'), 'ok')).catch(e => log(e.message, 'err'));
    document.getElementById('btnCumSave').onclick = async () => {
      const name = isNewCumulative
        ? document.getElementById('cumName').value.trim()
        : selectedCumulativeName;
      if (!name) {
        log(__('cum.name-required'), 'warn');
        return;
      }
      let payload;
      try {
        payload = readCumulativePayload(name);
      } catch (e) {
        log(e.message, 'err');
        return;
      }
      const { body } = payload;
      try {
        const data = await admin('/cumulatives/' + encodeURIComponent(name), {
          method: 'PUT',
          body: JSON.stringify(body)
        });
        log(data);
        isNewCumulative = false;
        selectedCumulativeName = name;
        await selectCumulative(name);
      } catch (e) {
        log(e.message, 'err');
      }
    };
    document.getElementById('btnCumDelete').onclick = async () => {
      if (!selectedCumulativeName || !confirm(__('cum.confirm-delete', selectedCumulativeName))) return;
      try {
        const data = await admin('/cumulatives/' + encodeURIComponent(selectedCumulativeName), { method: 'DELETE' });
        log(data);
        selectedCumulativeName = null;
        isNewCumulative = false;
        document.getElementById('cumEditor').style.display = 'none';
        await loadCumulatives();
      } catch (e) {
        log(e.message, 'err');
      }
    };
