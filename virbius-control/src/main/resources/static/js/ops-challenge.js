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
    const tab = document.getElementById('tab-challenge');
    if (!tab) return;

    // Start polling when tab becomes active
    document.querySelectorAll('.tab-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        if (btn.dataset.tab === 'challenge') {
          startPolling();
        } else {
          stopPolling();
        }
      });
    });

    // Refresh button
    const refreshBtn = document.getElementById('chRefresh');
    if (refreshBtn) {
      refreshBtn.addEventListener('click', loadChallenges);
    }
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
      const resp = await fetch(`/api/v1/challenges?tenant_id=${currentTenant}&status=pending&max=50`);
      if (!resp.ok) {
        showEmpty('Failed to load challenges');
        return;
      }
      const challenges = await resp.json();
      renderChallenges(challenges);
    } catch (e) {
      showEmpty('Connection error: ' + e.message);
    }
  }

  function renderChallenges(challenges) {
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
      const riskBadge = ch.risk_score >= 80
        ? '<span class="badge badge-danger">Critical</span>'
        : ch.risk_score >= 50
        ? '<span class="badge badge-warning">High</span>'
        : '<span class="badge badge-info">Medium</span>';

      return `<tr data-challenge-id="${esc(ch.challenge_id)}">
        <td><code>${esc(ch.challenge_id)}</code></td>
        <td><strong>${esc(ch.tool_name || 'N/A')}</strong></td>
        <td>${riskBadge} <span class="muted">(${ch.risk_score || 0})</span></td>
        <td><code>${esc(ch.rule_id || '-')}</code></td>
        <td>${esc(ch.session_id ? ch.session_id.substring(0, 12) + '...' : '-')}</td>
        <td>${created}</td>
        <td>${expires}</td>
        <td class="action-cell">
          <button class="btn btn-sm btn-success" onclick="challengeApprove('${esc(ch.challenge_id)}')">Approve</button>
          <button class="btn btn-sm btn-danger" onclick="challengeReject('${esc(ch.challenge_id)}')">Reject</button>
        </td>
      </tr>`;
    }).join('');
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
