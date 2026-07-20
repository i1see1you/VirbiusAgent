// ── License management page ──

async function loadLicenses() {
  try {
    const data = await admin('/licenses/list');
    const tbody = document.getElementById('licTableBody');
    if (!tbody) return;
    tbody.innerHTML = (data || []).map(l => {
      const licId = l.license_id || l.licenseId || '';
      const appId = l.app_id || l.appId || '';
      const agentName = l.agent_name || l.agentName || '';
      const quota = l.risk_quota ?? l.riskQuota ?? '';
      const rateLimit = l.tool_rate_limit ?? l.toolRateLimit ?? '';
      const expiry = (l.expiry || '').slice(0, 19);
      const issued = (l.issued_at || l.issuedAt || '').slice(0, 19);
      const status = l.status || '';
      const statusClass = status === 'active' ? 'tag-ok' : (status === 'revoked' ? 'tag-err' : 'tag-warn');
      const desc = l.description || '';
      const agentAid = l.agent_aid || l.agentAid || '';
      const allowedTools = (l.allowed_tools || []).join(', ');
      const sigHash = l.signature_hash || l.signatureHash || '';
      return `<tr>
        <td><code>${esc(licId)}</code></td>
        <td><code>${esc(appId)}</code></td>
        <td>${esc(agentName)}</td>
        <td>${esc(String(quota))}</td>
        <td>${esc(String(rateLimit))}</td>
        <td>${esc(expiry)}</td>
        <td><span class="${statusClass}">${esc(status)}</span></td>
        <td>
          <button type="button" class="btn-lic-detail" data-lic="${escAttr(licId)}" data-app="${escAttr(appId)}" data-name="${escAttr(agentName)}" data-quota="${escAttr(String(quota))}" data-rate="${escAttr(String(rateLimit))}" data-expiry="${escAttr(expiry)}" data-issued="${escAttr(issued)}" data-status="${escAttr(status)}" data-desc="${escAttr(desc)}" data-aid="${escAttr(agentAid)}" data-tools="${escAttr(allowedTools)}" data-sighash="${escAttr(sigHash)}">${__('license.btn-detail')}</button>
          ${status === 'active' ? `<button type="button" class="btn-lic-revoke" data-lic="${escAttr(licId)}">${__('license.btn-revoke')}</button>` : ''}
        </td>
      </tr>`;
    }).join('') || `<tr><td colspan="8" class="hint">${__('license.no-records')}</td></tr>`;
  } catch (e) {
    log(e.message, 'err');
  }
}

async function loadLicenseKeys() {
  try {
    const pubKey = await admin('/licenses/public-key').catch(() => null);
    const keySection = document.getElementById('licKeySection');
    if (!keySection) return;
    if (pubKey && pubKey.public_key_pem) {
      document.getElementById('licPublicKey').value = pubKey.public_key_pem;
      keySection.style.display = '';
    } else {
      keySection.style.display = 'none';
    }
  } catch (e) {
    // no key yet — hide section
    const keySection = document.getElementById('licKeySection');
    if (keySection) keySection.style.display = 'none';
  }
}

async function loadLicensePage() {
  await loadLicenses();
  await loadLicenseKeys();
}

function toggleIssueForm() {
  const form = document.getElementById('licIssueForm');
  form.style.display = form.style.display === 'none' ? 'block' : 'none';
}

function hideIssueForm() {
  document.getElementById('licIssueForm').style.display = 'none';
  document.getElementById('licAppId').value = '';
  document.getElementById('licAgentName').value = '';
  document.getElementById('licAllowedTools').value = '';
  document.getElementById('licRiskQuota').value = '60';
  document.getElementById('licToolRateLimit').value = '50';
  document.getElementById('licExpirySeconds').value = '86400';
  document.getElementById('licDescription').value = '';
}

async function doIssueLicense() {
  const appId = document.getElementById('licAppId').value.trim();
  if (!appId) { log(__('license.app-id-required'), 'warn'); return; }
  const agentName = document.getElementById('licAgentName').value.trim();
  const toolsRaw = document.getElementById('licAllowedTools').value.trim();
  const allowedTools = toolsRaw ? toolsRaw.split(',').map(s => s.trim()).filter(Boolean) : [];
  const riskQuota = parseInt(document.getElementById('licRiskQuota').value) || 60;
  const toolRateLimit = parseInt(document.getElementById('licToolRateLimit').value) || 50;
  const expirySeconds = parseInt(document.getElementById('licExpirySeconds').value) || 86400;
  const description = document.getElementById('licDescription').value.trim();

  try {
    const data = await admin('/licenses/issue', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        app_id: appId,
        agent_name: agentName,
        allowed_tools: allowedTools,
        risk_quota: riskQuota,
        tool_rate_limit: toolRateLimit,
        expiry_seconds: expirySeconds,
        description: description
      })
    });
    hideIssueForm();
    log({ issued: data, warning: __('license.jwt-warning') }, 'warn');
    // Show JWT in a dialog
    if (data.jwt) {
      showJwtDialog(data.jwt, data.license_id || data.licenseId || '');
    }
    await loadLicenses();
  } catch (e) {
    log(e.message, 'err');
  }
}

function showJwtDialog(jwt, licId) {
  const overlay = document.getElementById('licJwtOverlay');
  document.getElementById('licJwtText').value = jwt;
  document.getElementById('licJwtLabel').textContent = 'License JWT (' + licId + ')';
  overlay.style.display = 'flex';
}

function closeJwtDialog() {
  document.getElementById('licJwtOverlay').style.display = 'none';
}

function copyJwt() {
  const txt = document.getElementById('licJwtText');
  txt.select();
  document.execCommand('copy');
  log(__('license.jwt-copied'), 'ok');
}

function copyPublicKey() {
  const txt = document.getElementById('licPublicKey');
  txt.select();
  document.execCommand('copy');
  log(__('license.key-copied'), 'ok');
}

async function doRevokeLicense(licId) {
  const reason = prompt(__('license.revoke-reason-prompt'), 'manual_revoke');
  if (reason === null) return;
  try {
    await admin('/licenses/' + encodeURIComponent(licId) + '/revoke', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason: reason || 'manual_revoke' })
    });
    log({ revoked: licId }, 'ok');
    await loadLicenses();
  } catch (e) {
    log(e.message, 'err');
  }
}

async function doRotateKey() {
  if (!confirm(__('license.rotate-confirm'))) return;
  try {
    const data = await admin('/licenses/rotate-key', { method: 'POST' });
    log({ rotated: data }, 'ok');
    await loadLicenseKeys();
  } catch (e) {
    log(e.message, 'err');
  }
}

function showLicenseDetail(btn) {
  const overlay = document.getElementById('licDetailOverlay');
  document.getElementById('licDetailId').textContent = btn.dataset.lic || '';
  document.getElementById('licDetailApp').textContent = btn.dataset.app || '';
  document.getElementById('licDetailName').textContent = btn.dataset.name || '';
  document.getElementById('licDetailAid').textContent = btn.dataset.aid || '';
  document.getElementById('licDetailTools').textContent = btn.dataset.tools || '';
  document.getElementById('licDetailQuota').textContent = btn.dataset.quota || '';
  document.getElementById('licDetailRate').textContent = btn.dataset.rate || '';
  document.getElementById('licDetailIssued').textContent = btn.dataset.issued || '';
  document.getElementById('licDetailExpiry').textContent = btn.dataset.expiry || '';
  document.getElementById('licDetailStatus').textContent = btn.dataset.status || '';
  document.getElementById('licDetailDesc').textContent = btn.dataset.desc || '';
  // Show only the hash prefix (first 16 chars) for audit identification
  const sigHash = btn.dataset.sighash || '';
  document.getElementById('licDetailSigHash').textContent = sigHash ? ('sha256:' + sigHash.substring(0, 16) + '...') : '—';
  document.getElementById('licDetailSigHashFull').textContent = sigHash || '—';
  // Store license id for revoke button
  document.getElementById('licDetailRevokeBtn').dataset.lic = btn.dataset.lic;
  // Show/hide revoke based on status
  document.getElementById('licDetailRevokeBtn').style.display = btn.dataset.status === 'active' ? '' : 'none';
  overlay.style.display = 'flex';
}

function closeLicenseDetail() {
  document.getElementById('licDetailOverlay').style.display = 'none';
}

// ── Event handlers ──
document.addEventListener('DOMContentLoaded', () => {
  const btnIssue = document.getElementById('btnLicIssue');
  if (btnIssue) btnIssue.onclick = toggleIssueForm;
  const btnIssueConfirm = document.getElementById('btnLicIssueConfirm');
  if (btnIssueConfirm) btnIssueConfirm.onclick = () => doIssueLicense();
  const btnIssueCancel = document.getElementById('btnLicIssueCancel');
  if (btnIssueCancel) btnIssueCancel.onclick = hideIssueForm;
  const btnLicRefresh = document.getElementById('btnLicRefresh');
  if (btnLicRefresh) btnLicRefresh.onclick = () => loadLicensePage().then(() => log(__('license.refreshed'))).catch(e => log(e.message, 'err'));
  const btnRotate = document.getElementById('btnLicRotate');
  if (btnRotate) btnRotate.onclick = () => doRotateKey();
  const btnCopyKey = document.getElementById('btnCopyPublicKey');
  if (btnCopyKey) btnCopyKey.onclick = copyPublicKey;
  const btnCopyJwt = document.getElementById('btnCopyJwt');
  if (btnCopyJwt) btnCopyJwt.onclick = copyJwt;
  const btnCloseJwt = document.getElementById('btnCloseJwt');
  if (btnCloseJwt) btnCloseJwt.onclick = closeJwtDialog;
  const btnCloseDetail = document.getElementById('btnCloseDetail');
  if (btnCloseDetail) btnCloseDetail.onclick = closeLicenseDetail;
  const btnDetailRevoke = document.getElementById('licDetailRevokeBtn');
  if (btnDetailRevoke) btnDetailRevoke.onclick = (e) => {
    const licId = e.target.dataset.lic;
    if (licId) {
      closeLicenseDetail();
      doRevokeLicense(licId);
    }
  };

  // Table click delegation
  const licTableBody = document.getElementById('licTableBody');
  if (licTableBody) {
    licTableBody.addEventListener('click', (e) => {
      const detailBtn = e.target.closest('.btn-lic-detail');
      if (detailBtn) { showLicenseDetail(detailBtn); return; }
      const revokeBtn = e.target.closest('.btn-lic-revoke');
      if (revokeBtn) { doRevokeLicense(revokeBtn.dataset.lic); return; }
    });
  }
});
