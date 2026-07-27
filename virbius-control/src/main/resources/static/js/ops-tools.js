    // ── Tool Registry ──

    let toolRegistry = [];
    let editingToolName = null;

    async function loadToolRegistry() {
      try {
        const data = await admin('/tools');
        toolRegistry = Array.isArray(data) ? data : [];
        renderToolTable();
      } catch (e) {
        log(e.message, 'err');
      }
    }

    function renderToolTable() {
      const tbody = document.getElementById('toolTableBody');
      if (!tbody) return;
      tbody.innerHTML = '';
      for (const t of toolRegistry) {
        const tr = document.createElement('tr');
        const riskBadge = renderRiskBadge(t.risk_class);
        const sandbox = esc(t.sandbox_type || 'none');
        const fastPath = t.fast_path ? '<span class="tag">⚡</span>' : '';
        const approval = renderApprovalBadge(t.approval_mode);
        const schema = t.allowed_args_schema
          ? '<span class="tag" title="' + escAttr(t.allowed_args_schema) + '">schema</span>' : '';
        tr.innerHTML = `
          <td>${esc(t.tool_name)}</td>
          <td>${riskBadge}</td>
          <td>${sandbox}</td>
          <td>${t.timeout_ms || 30000}</td>
          <td>${fastPath}</td>
          <td>${approval}</td>
          <td>${schema}</td>
          <td>${esc(t.description || '')}</td>
          <td><button type="button" class="seg" onclick="editTool('${escAttr(t.tool_name)}')">编辑</button>
              <button type="button" class="danger seg" onclick="deleteTool('${escAttr(t.tool_name)}')">删除</button></td>`;
        tbody.appendChild(tr);
      }
    }

    function renderApprovalBadge(mode) {
      if (mode === 'lax') {
        return '<span class="tag" style="background:#fff3cd;color:#856404" title="弱审批：审批一次后同工具任意参数豁免">lax</span>';
      }
      return '<span class="tag" title="强审批：要求参数完全一致">strict</span>';
    }

    function renderRiskBadge(rc) {
      const map = {
        low: '<span class="tag" style="background:#d4edda;color:#155724">🟢 low (1)</span>',
        medium: '<span class="tag" style="background:#fff3cd;color#856404">🟡 medium (3)</span>',
        high: '<span class="tag" style="background:#f8d7da;color:#721c24">🔴 high (5)</span>',
        network: '<span class="tag" style="background:#d1ecf1;color:#0c5460">🔵 network (4)</span>'
      };
      return map[rc] || esc(rc || 'low');
    }

    function showToolEditor(tool) {
      document.getElementById('toolEditor').style.display = '';
      document.getElementById('fToolName').value = tool?.tool_name || '';
      document.getElementById('fToolName').disabled = !!tool;
      document.getElementById('fToolRiskClassSel').value = tool?.risk_class || 'low';
      document.getElementById('fToolSandboxType').value = tool?.sandbox_type || 'none';
      document.getElementById('fToolTimeoutMs').value = tool?.timeout_ms || 30000;
      document.getElementById('fToolFastPath').checked = tool?.fast_path || false;
      document.getElementById('fToolApprovalMode').value = tool?.approval_mode || 'strict';
      document.getElementById('fToolArgsSchema').value = tool?.allowed_args_schema || '';
      document.getElementById('fToolDescription').value = tool?.description || '';
      editingToolName = tool?.tool_name || null;
    }

    function hideToolEditor() {
      document.getElementById('toolEditor').style.display = 'none';
      editingToolName = null;
    }

    function editTool(toolName) {
      const tool = toolRegistry.find(t => t.tool_name === toolName);
      if (tool) showToolEditor(tool);
    }

    async function deleteTool(toolName) {
      if (!confirm('确认删除工具 "' + toolName + '"？')) return;
      try {
        await admin('/tools/' + encodeURIComponent(toolName), { method: 'DELETE' });
        log('工具已删除: ' + toolName, 'ok');
        await loadToolRegistry();
      } catch (e) {
        log(e.message, 'err');
      }
    }

    async function saveTool() {
      const toolName = document.getElementById('fToolName').value.trim();
      if (!toolName) {
        log('tool_name 不能为空', 'err');
        return;
      }
      if (!/^[a-z][a-z0-9_-]*$/.test(toolName)) {
        log('tool_name 必须以小写字母开头，只能包含小写字母、数字、下划线和连字符', 'err');
        return;
      }
      const schemaStr = document.getElementById('fToolArgsSchema').value.trim();
      if (schemaStr) {
        try {
          JSON.parse(schemaStr);
        } catch (e) {
          log('allowed_args_schema 不是有效的 JSON: ' + e.message, 'err');
          return;
        }
      }
      const body = {
        tool_name: toolName,
        risk_class: document.getElementById('fToolRiskClassSel').value,
        sandbox_type: document.getElementById('fToolSandboxType').value,
        timeout_ms: parseInt(document.getElementById('fToolTimeoutMs').value, 10) || 30000,
        fast_path: document.getElementById('fToolFastPath').checked,
        approval_mode: document.getElementById('fToolApprovalMode').value,
        allowed_args_schema: schemaStr || null,
        description: document.getElementById('fToolDescription').value.trim() || null
      };
      try {
        await admin('/tools', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });
        log('工具已保存: ' + toolName, 'ok');
        hideToolEditor();
        await loadToolRegistry();
      } catch (e) {
        log(e.message, 'err');
      }
    }
