    async function reloadAll() {
      await loadTenantSelect();
      await loadLists();
      await loadCumulatives();
      await loadBindings();
      await loadExtendedVars();
      await loadExtendedVars();
      await loadSceneRegistry();
      await loadGatewayRoutes();
      await loadRules();
      if (document.getElementById('panel-rollout').classList.contains('active')) {
        await refreshRolloutDashboard();
      }
      log({ ok: true, tenant: tenant(), bundle: bundleId(), version: bundleVer() }, 'ok');
    }

    function setActiveNav(tab) {
      document.querySelectorAll('.nav-item[data-tab]').forEach(b => {
        b.classList.toggle('active', b.dataset.tab === tab);
      });
      if (tab === 'rules') {
        document.getElementById('navGroupRules').classList.add('expanded');
      }
    }

    function setActiveLayer(layer) {
      currentLayer = layer;
      document.querySelectorAll('.nav-sub[data-layer]').forEach(b => {
        b.classList.toggle('active', b.dataset.layer === layer);
      });
      const label = document.getElementById('rulesLayerLabel');
      if (label) label.textContent = layer;
    }

    function showPanel(tab) {
      const currentActive = document.querySelector('.panel.active');
      if (currentActive && currentActive.id === 'panel-rules' && tab !== 'rules' && isRuleFormDirty()) {
        if (!confirm(__('rules.confirm-unsaved'))) return;
        resetRuleFormDirty();
      }
      logEl.textContent = '';
      logEl.className = 'log-info';
      document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
      const panel = document.getElementById('panel-' + tab);
      if (panel) panel.classList.add('active');
      setActiveNav(tab);
      document.dispatchEvent(new CustomEvent('panel-show', { detail: 'panel-' + tab }));
      if (tab === 'rollout') {
        refreshRolloutDashboard().catch(e => log(e.message, 'err'));
        startRolloutAutoRefresh();
      } else {
        stopRolloutAutoRefresh();
      }
      if (tab === 'monitor') {
        loadMonitorPage();
        startMonitorRefresh();
      } else {
        stopMonitorRefresh();
      }
      if (tab === 'audit-center') {
        const q = new URLSearchParams(window.location.search).get('trace_id');
        if (q) {
          document.getElementById('acTraceId').value = q;
          searchAuditCenter(q).catch(e => log(e.message, 'err'));
        } else {
          loadAuditRecent(100).catch(e => log(e.message, 'err'));
        }
      }
      if (tab === 'tenants') {
        loadTenantsPage().catch(e => log(e.message, 'err'));
      }
    }

    document.getElementById('btnSidebarToggle').onclick = () => {
      const sb = document.getElementById('sidebar');
      const collapsed = sb.classList.toggle('collapsed');
      document.getElementById('btnSidebarToggle').textContent = collapsed ? '▶' : '◀';
      document.getElementById('btnSidebarToggle').title = collapsed ? __('nav.expand') : __('nav.collapse');
      try { localStorage.setItem('virbius.ops.sidebarCollapsed', collapsed ? '1' : '0'); } catch (_) {}
    };
    try {
      if (localStorage.getItem('virbius.ops.sidebarCollapsed') === '1') {
        document.getElementById('sidebar').classList.add('collapsed');
        document.getElementById('btnSidebarToggle').textContent = '▶';
      }
    } catch (_) {}

    document.querySelectorAll('.nav-item[data-tab]:not(#navRulesHead)').forEach(btn => {
      btn.onclick = () => showPanel(btn.dataset.tab);
    });

    document.getElementById('navRulesHead').onclick = (e) => {
      const group = document.getElementById('navGroupRules');
      if (e.target.classList.contains('nav-chevron')) {
        group.classList.toggle('expanded');
      } else {
        showPanel('rules');
      }
    };

    document.querySelectorAll('.nav-sub[data-layer]').forEach(btn => {
      btn.onclick = () => {
        if (isRuleFormDirty() && !confirm(__('rules.confirm-unsaved'))) return;
        showPanel('rules');
        setActiveLayer(btn.dataset.layer);
        selectedRuleId = null;
        isNewRule = false;
        document.getElementById('ruleEditor').style.display = 'none';
        resetRuleFormDirty();
        loadRules().catch(e => log(e.message, 'err'));
      };
    });

    document.getElementById('tenantId').addEventListener('change', () =>
      reloadAll().catch(e => log(e.message, 'err')));

    reloadAll().catch(e => log(e.message, 'err'));
