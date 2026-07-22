/**
 * Challenge approval queue: lists pending challenges, allows approve/reject.
 *
 * Polls GET /api/v1/challenges?status=pending every 5s when the tab is active.
 */
(function () {
  'use strict';

  const POLL_INTERVAL = 5000;
  let pollTimer = null;
  let currentTenant = 'default';

  function init() {
    // The challenge panel element (id="panel-challenge") — if it doesn't
    // exist, this page doesn't include the challenge UI, so bail out.
    const panel = document.getElementById('panel-challenge');
    if (!panel) return;

    // Start/stop polling based on which panel is shown.
    // ops-nav.js dispatches a 'panel-show' CustomEvent with
    // detail = 'panel-<tab>' whenever the user switches tabs.
    document.addEventListener('panel-show', (e) => {
      if (e.detail === 'panel-challenge') {
        startPolling();
      } else {
        stopPolling();
      }
    });

    // If the challenge panel is already active on page load, start polling.
    if (panel.classList.contains('active')) {
      startPolling();
    }

    // Refresh button
    const refreshBtn = document.getElementById('chRefresh');
    if (refreshBtn) {
      refreshBtn.addEventListener('click', loadChallenges);
    }

    // Sub-tab switching (pending / approved)
    const subTabs = panel.querySelectorAll('.sub-tab[data-subtab]');
    subTabs.forEach(tab => {
      tab.addEventListener('click', () => {
        const target = tab.getAttribute('data-subtab');
        // toggle tab active state
        subTabs.forEach(t => t.classList.toggle('active', t === tab));
        // toggle sub-panel visibility
        const pendingPanel = document.getElementById('chSubPending');
        const approvedPanel = document.getElementById('chSubApproved');
        if (pendingPanel) pendingPanel.classList.toggle('active', target === 'pending');
        if (approvedPanel) approvedPanel.classList.toggle('active', target === 'approved');
      });
    });
  }

  function startPolling() {
    loadChallenges();
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = setInterval(loadChallenges, POLL_INTERVAL);
  }

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  async function loadChallenges() {
    try {
      const [pendingResp, approvedResp] = await Promise.all([
        fetch(`/api/v1/challenges?tenant_id=${currentTenant}&status=pending&max=50`),
        fetch(`/api/v1/challenges?tenant_id=${currentTenant}&status=approved&max=50`),
      ]);

      if (!pendingResp.ok) {
        showEmpty('Failed to load challenges');
        return;
      }
      const pending = await pendingResp.json();
      renderPending(pending);

      if (approvedResp.ok) {
        const approved = await approvedResp.json();
        renderApproved(approved);
      }
    } catch (e) {
      showEmpty('Connection error: ' + e.message);
    }
  }

  function renderPending(challenges) {
    const tbody = document.getElementById('chQueueBody');
    const countEl = document.getElementById('chPendingCount');
    if (!tbody) return;

    if (countEl) {
      countEl.textContent = challenges.length;
    }

    if (!challenges || challenges.length === 0) {
      tbody.innerHTML = '<tr><td colspan="8" class="empty-state">No pending challenges</td></tr>';
      return;
    }

    tbody.innerHTML = challenges.map(ch => {
      const created = formatTime(ch.created_at);
      const expires = formatTime(ch.expires_at);
      const riskBadge = riskBadgeHTML(ch.risk_score);
      const isExpired = ch.expires_at && (Date.now() / 1000) > ch.expires_at;

      return `<tr data-challenge-id="${esc(ch.challenge_id)}">
        <td><code>${esc(ch.challenge_id)}</code></td>
        <td><strong>${esc(ch.tool_name || 'N/A')}</strong></td>
        <td>${riskBadge} <span class="muted">(${ch.risk_score || 0})</span></td>
        <td><code>${esc(ch.rule_id || '-')}</code></td>
        <td>${esc(ch.session_id ? ch.session_id.substring(0, 12) + '...' : '-')}</td>
        <td>${created}</td>
        <td>${isExpired ? '<span style="color:#dc2626">' + expires + '</span>' : expires}</td>
        <td class="action-cell">
          ${isExpired ? '<span class="muted">已过期</span>' : `<button class="btn btn-sm btn-success" onclick="challengeApprove('${esc(ch.challenge_id)}')">Approve</button>
          <button class="btn btn-sm btn-danger" onclick="challengeReject('${esc(ch.challenge_id)}')">Reject</button>`}
        </td>
      </tr>`;
    }).join('');
  }

  function renderApproved(challenges) {
    const tbody = document.getElementById('chApprovedBody');
    const countEl = document.getElementById('chApprovedCount');
    if (!tbody) return;

    if (countEl) {
      countEl.textContent = challenges.length;
    }

    if (!challenges || challenges.length === 0) {
      tbody.innerHTML = '<tr><td colspan="8" class="empty-state">No approved challenges</td></tr>';
      return;
    }

    tbody.innerHTML = challenges.map(ch => {
      const created = formatTime(ch.created_at);
      const riskBadge = riskBadgeHTML(ch.risk_score);
      const approvedAt = ch.approved_at ? formatTime(ch.approved_at) : '-';
      const approvedBy = esc(ch.approved_by || '-');

      return `<tr>
        <td><code>${esc(ch.challenge_id)}</code></td>
        <td><strong>${esc(ch.tool_name || 'N/A')}</strong></td>
        <td>${riskBadge} <span class="muted">(${ch.risk_score || 0})</span></td>
        <td><code>${esc(ch.rule_id || '-')}</code></td>
        <td>${esc(ch.session_id ? ch.session_id.substring(0, 12) + '...' : '-')}</td>
        <td>${created}</td>
        <td>${approvedBy}</td>
        <td>${approvedAt}</td>
      </tr>`;
    }).join('');
  }

  function riskBadgeHTML(score) {
    return score >= 80
      ? '<span class="badge badge-danger">Critical</span>'
      : score >= 50
      ? '<span class="badge badge-warning">High</span>'
      : '<span class="badge badge-info">Medium</span>';
  }

  function showEmpty(msg) {
    const tbody = document.getElementById('chQueueBody');
    if (tbody) {
      tbody.innerHTML = `<tr><td colspan="8" class="empty-state">${esc(msg)}</td></tr>`;
    }
  }

  window.challengeApprove = async function (challengeId) {
    const approvedBy = prompt('Approver name:', 'operator');
    if (approvedBy === null) return;

    const comment = prompt('Approval comment (optional):', '') || '';

    try {
      const resp = await fetch(`/api/v1/challenges/${challengeId}/approve`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ approved_by: approvedBy, comment }),
      });
      const result = await resp.json();
      if (resp.ok && result.token) {
        alert(`Challenge approved!\nToken: ${result.token}\n(expires in 10 minutes)`);
        loadChallenges();
      } else {
        alert('Approve failed: ' + (result.message || result.status || 'unknown error'));
      }
    } catch (e) {
      alert('Approve error: ' + e.message);
    }
  };

  window.challengeReject = async function (challengeId) {
    const rejectedBy = prompt('Rejector name:', 'operator');
    if (rejectedBy === null) return;

    const reason = prompt('Rejection reason:', '');
    if (!reason) {
      alert('Rejection reason is required');
      return;
    }

    try {
      const resp = await fetch(`/api/v1/challenges/${challengeId}/reject`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ rejected_by: rejectedBy, reason }),
      });
      const result = await resp.json();
      if (resp.ok) {
        alert('Challenge rejected');
        loadChallenges();
      } else {
        alert('Reject failed: ' + (result.message || result.status || 'unknown error'));
      }
    } catch (e) {
      alert('Reject error: ' + e.message);
    }
  };

  function formatTime(epochSec) {
    if (!epochSec) return '-';
    const d = new Date(epochSec * 1000);
    return d.toLocaleTimeString();
  }

  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, c =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])
    );
  }

  // Initialize on DOM ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
