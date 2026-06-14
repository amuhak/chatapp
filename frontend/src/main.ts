import './style.css'
import { renderAuth } from './auth.ts'
import {
  generateDeviceKeys,
  encryptSymmetricKey,
  decryptSymmetricKey,
  generateRandomSymmetricKey
} from './crypto.ts'

const appElement = document.querySelector<HTMLDivElement>('#app')!;

async function init() {
  const token = localStorage.getItem('token');

  if (token) {
    try {
      const response = await fetch('/api/identity/validate', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      const data = await response.json();
      if (data.valid) {
        localStorage.setItem('username', data.username);
        localStorage.setItem('userUuid', data.userUuid);
        showApp(data.username, data.userUuid);
        return;
      }
    } catch (err) {
      console.error('Failed to validate token', err);
    }
  }

  showAuth();
}

function showAuth() {
  renderAuth(appElement, (_token, username, userUuid) => {
    showApp(username, userUuid);
  });
}

async function ensureDeviceRegistered(token: string): Promise<{ deviceId: string; deviceName: string }> {
  let deviceId = localStorage.getItem('deviceId');
  let deviceName = localStorage.getItem('deviceName');

  if (deviceId && deviceName) {
    return { deviceId, deviceName };
  }

  // Generate new keys
  const keys = await generateDeviceKeys();
  localStorage.setItem('publicIdentityKey', keys.publicKeys.publicIdentityKey);
  localStorage.setItem('publicSignKey', keys.publicKeys.publicSignKey);
  localStorage.setItem('privateIdentityKey', keys.privateKeys.privateIdentityKey);
  localStorage.setItem('privateSignKey', keys.privateKeys.privateSignKey);

  // Generate a clean platform name
  let platform = 'Web Client';
  if (navigator.userAgent.indexOf('Win') !== -1) platform = 'Windows Device';
  else if (navigator.userAgent.indexOf('Mac') !== -1) platform = 'macOS Device';
  else if (navigator.userAgent.indexOf('Linux') !== -1) platform = 'Linux Device';
  else if (navigator.userAgent.indexOf('Android') !== -1) platform = 'Android Phone';
  else if (navigator.userAgent.indexOf('like Mac') !== -1) platform = 'iPhone';

  const randomId = Math.floor(1000 + Math.random() * 9000);
  const name = `${platform} #${randomId}`;
  localStorage.setItem('deviceName', name);

  // Upload asymmetric keys to the delivery-service
  const response = await fetch('/api/delivery/asymmetric/upload', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    body: JSON.stringify({
      deviceName: name,
      publicIdentityKey: keys.publicKeys.publicIdentityKey,
      publicSignKey: keys.publicKeys.publicSignKey
    })
  });

  if (!response.ok) {
    throw new Error('Failed to upload device keys to secure registry');
  }

  const data = await response.json();
  localStorage.setItem('deviceId', data.deviceId);
  return { deviceId: data.deviceId, deviceName: name };
}

function showApp(username: string, userUuid: string) {
  const token = localStorage.getItem('token')!;
  
  appElement.innerHTML = `
    <header class="app-header">
      <div class="header-logo">
        <span class="logo-icon">🔑</span>
        <h2>Secure Key Exchange</h2>
      </div>
      <div class="header-user">
        <span class="user-badge" id="current-user-badge">👤 ${username}</span>
        <button id="logout-btn" class="logout-btn">Logout</button>
      </div>
    </header>

    <main class="app-content">
      <div id="loader-overlay" class="loader-overlay">
        <div class="spinner"></div>
        <p>Registering device and preparing keys...</p>
      </div>

      <div class="dashboard-layout hidden" id="dashboard">
        <div class="tabs-nav">
          <button class="tab-btn active" data-tab="device-tab">📱 My Devices</button>
          <button class="tab-btn" data-tab="send-tab">📤 Exchange Key</button>
          <button class="tab-btn" data-tab="receive-tab">📥 Pending Keys <span id="pending-badge" class="badge hidden">0</span></button>
        </div>

        <div class="tab-content active" id="device-tab">
          <div class="card device-info-card">
            <h3>Active Device Registration</h3>
            <div class="info-grid">
              <div><strong>User UUID:</strong> <code class="selectable">${userUuid}</code></div>
              <div><strong>Device ID:</strong> <code class="selectable" id="my-device-id-display">-</code></div>
              <div><strong>Device Name:</strong> <span id="my-device-name-display">-</span></div>
            </div>
            <div class="keys-display">
              <div class="key-box">
                <span class="key-header">Identity Key (Public RSA-OAEP JWK)</span>
                <textarea id="identity-key-text" readonly></textarea>
                <button class="btn btn-sm btn-copy" data-target="identity-key-text">Copy Key</button>
              </div>
              <div class="key-box">
                <span class="key-header">Signing Key (Public RSASSA-PKCS1 JWK)</span>
                <textarea id="sign-key-text" readonly></textarea>
                <button class="btn btn-sm btn-copy" data-target="sign-key-text">Copy Key</button>
              </div>
            </div>
          </div>

          <div class="card">
            <div class="card-header">
              <h3>All Registered Devices for User</h3>
              <button class="btn btn-secondary btn-sm" id="refresh-devices-btn">🔄 Refresh</button>
            </div>
            <div class="table-container">
              <table class="devices-table">
                <thead>
                  <tr>
                    <th>Device Name</th>
                    <th>Device ID</th>
                    <th>Identity Key Type</th>
                  </tr>
                </thead>
                <tbody id="my-devices-list">
                  <tr><td colspan="3" class="placeholder-text">Loading registered devices...</td></tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <div class="tab-content" id="send-tab">
          <div class="card">
            <h3>Find Recipient & Fetch Device Public Keys</h3>
            <form id="fetch-recipient-form" class="inline-form">
              <div class="form-group flex-grow">
                <label for="recipient-uuid">Recipient User UUID</label>
                <input type="text" id="recipient-uuid" placeholder="Enter recipient's full User UUID" required>
              </div>
              <button type="submit" class="btn btn-primary">Find Devices</button>
            </form>
          </div>

          <div id="recipient-results" class="hidden">
            <div class="card">
              <h3>Recipient Devices Found</h3>
              <div class="table-container">
                <table class="devices-table">
                  <thead>
                    <tr>
                      <th>Device Name</th>
                      <th>Device ID</th>
                      <th>Public Identity Key</th>
                    </tr>
                  </thead>
                  <tbody id="recipient-devices-list"></tbody>
                </table>
              </div>
            </div>

            <div class="card payload-card">
              <h3>Generate & Encrypt Symmetric Key</h3>
              <p class="description-text">
                Generate a random symmetric key, encrypt it with each of the recipient's device public keys, and upload the encrypted bundle to the secure delivery server.
              </p>
              <div class="key-generation-group">
                <div class="form-group flex-grow">
                  <label for="symmetric-key-input">Symmetric Key (Plaintext Hex)</label>
                  <input type="text" id="symmetric-key-input" readonly>
                </div>
                <button type="button" class="btn btn-secondary" id="generate-key-btn">🎲 Generate Random Key</button>
              </div>
              
              <div class="actions-group">
                <button type="button" class="btn btn-success btn-lg" id="upload-symmetric-btn" disabled>
                  🚀 Encrypt & Upload to All Devices
                </button>
              </div>
              <div id="upload-status" class="status-box hidden"></div>
            </div>
          </div>
        </div>

        <div class="tab-content" id="receive-tab">
          <div class="card">
            <div class="card-header">
              <h3>Incoming Pending Symmetric Keys</h3>
              <button class="btn btn-secondary btn-sm" id="refresh-incoming-btn">🔄 Check for New Keys</button>
            </div>
            <p class="description-text">
              These are encrypted symmetric keys sent to this device by other users. Decrypt them in-browser and acknowledge receipt.
            </p>
            <div class="pending-keys-container" id="pending-keys-list">
              <div class="placeholder-text">Loading incoming keys...</div>
            </div>
          </div>
        </div>
      </div>
    </main>
  `;

  // Start initialization
  const loader = document.getElementById('loader-overlay')!;
  const dashboard = document.getElementById('dashboard')!;

  ensureDeviceRegistered(token)
    .then(({ deviceId, deviceName }) => {
      loader.classList.add('hidden');
      dashboard.classList.remove('hidden');

      // Populate my device info
      document.getElementById('my-device-id-display')!.textContent = deviceId;
      document.getElementById('my-device-name-display')!.textContent = deviceName;
      document.getElementById('identity-key-text')!.textContent = localStorage.getItem('publicIdentityKey') || '';
      document.getElementById('sign-key-text')!.textContent = localStorage.getItem('publicSignKey') || '';

      // Initialize tabs and fetch data
      setupTabs();
      fetchMyDevices();
      startPollingPendingKeys();
    })
    .catch(err => {
      loader.innerHTML = `
        <div class="error-message">
          <strong>Initialization Failed</strong><br>
          ${err.message || 'Could not register cryptographic keys with the delivery service.'}
        </div>
      `;
      console.error(err);
    });

  // Setup logout
  document.querySelector('#logout-btn')?.addEventListener('click', async () => {
    if (token) {
      try {
        await fetch('/api/identity/logout', {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${token}`
          }
        });
      } catch (e) {
        console.error('Logout error', e);
      }
    }
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    localStorage.removeItem('userUuid');
    localStorage.removeItem('deviceId');
    localStorage.removeItem('deviceName');
    localStorage.removeItem('publicIdentityKey');
    localStorage.removeItem('publicSignKey');
    localStorage.removeItem('privateIdentityKey');
    localStorage.removeItem('privateSignKey');
    showAuth();
  });

  // Setup tab switcher logic
  function setupTabs() {
    const tabs = document.querySelectorAll('.tab-btn');
    const contents = document.querySelectorAll('.tab-content');

    tabs.forEach(tab => {
      tab.addEventListener('click', () => {
        tabs.forEach(t => t.classList.remove('active'));
        contents.forEach(c => c.classList.remove('active'));

        tab.classList.add('active');
        const targetTab = tab.getAttribute('data-tab');
        document.getElementById(targetTab!)?.classList.add('active');

        // Auto-refresh based on selected tab
        if (targetTab === 'device-tab') {
          fetchMyDevices();
        } else if (targetTab === 'receive-tab') {
          fetchPendingKeys();
        }
      });
    });

    // Copy buttons
    document.querySelectorAll('.btn-copy').forEach(btn => {
      btn.addEventListener('click', () => {
        const targetId = btn.getAttribute('data-target')!;
        const textarea = document.getElementById(targetId) as HTMLTextAreaElement;
        textarea.select();
        document.execCommand('copy');
        const originalText = btn.textContent;
        btn.textContent = 'Copied!';
        btn.classList.add('btn-success');
        setTimeout(() => {
          btn.textContent = originalText;
          btn.classList.remove('btn-success');
        }, 1500);
      });
    });
  }

  // Fetch my registered devices
  async function fetchMyDevices() {
    const myDevicesList = document.getElementById('my-devices-list')!;
    try {
      const response = await fetch('/api/delivery/asymmetric/fetch', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify({ UUIDs: [userUuid] })
      });
      if (!response.ok) throw new Error('Failed to fetch devices');
      const devices = await response.json();
      const deviceMap = devices[userUuid] || {};
      const deviceList = Object.entries(deviceMap).map(([deviceId, publicIdentityKey]) => ({
        deviceId,
        publicIdentityKey,
        deviceName: `Device (${deviceId.substring(0, 8)})`
      }));

      myDevicesList.innerHTML = deviceList.map((d: any) => `
        <tr class="${d.deviceId === localStorage.getItem('deviceId') ? 'active-row' : ''}">
          <td>
            <strong>${escapeHtml(d.deviceName)}</strong>
            ${d.deviceId === localStorage.getItem('deviceId') ? ' <span class="tag tag-success">This Device</span>' : ''}
          </td>
          <td><code class="selectable">${escapeHtml(d.deviceId)}</code></td>
          <td><span class="tag tag-outline">RSA-OAEP-256</span></td>
        </tr>
      `).join('');
    } catch (err) {
      myDevicesList.innerHTML = `<tr><td colspan="3" class="error-text">Error fetching devices: ${err}</td></tr>`;
    }
  }

  // Recipient Device Finding
  let recipientDevicesCache: any[] = [];
  const fetchRecipientForm = document.getElementById('fetch-recipient-form')!;
  const recipientResults = document.getElementById('recipient-results')!;
  const recipientDevicesList = document.getElementById('recipient-devices-list')!;
  const symmetricKeyInput = document.getElementById('symmetric-key-input') as HTMLInputElement;
  const uploadSymmetricBtn = document.getElementById('upload-symmetric-btn') as HTMLButtonElement;
  const generateKeyBtn = document.getElementById('generate-key-btn')!;

  fetchRecipientForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const rUuid = (document.getElementById('recipient-uuid') as HTMLInputElement).value.trim();
    if (!rUuid) return;

    recipientDevicesList.innerHTML = '<tr><td colspan="3" class="placeholder-text">Searching recipient keys...</td></tr>';
    recipientResults.classList.remove('hidden');
    uploadSymmetricBtn.disabled = true;

    try {
      const response = await fetch('/api/delivery/asymmetric/fetch', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify({ UUIDs: [rUuid] })
      });
      if (!response.ok) throw new Error('Recipient details not found');
      const devices = await response.json();
      const deviceMap = devices[rUuid] || {};
      const deviceList = Object.entries(deviceMap).map(([deviceId, publicIdentityKey]) => ({
        deviceId,
        publicIdentityKey,
        deviceName: `Device (${deviceId.substring(0, 8)})`
      }));
      recipientDevicesCache = deviceList;

      if (deviceList.length === 0) {
        recipientDevicesList.innerHTML = `<tr><td colspan="3" class="error-text">No active devices found for this User UUID. They must log in at least once to create key pairs.</td></tr>`;
        return;
      }

      recipientDevicesList.innerHTML = deviceList.map((d: any) => `
        <tr>
          <td><strong>${escapeHtml(d.deviceName)}</strong></td>
          <td><code class="selectable">${escapeHtml(d.deviceId)}</code></td>
          <td><span class="tag tag-outline">Available (RSA)</span></td>
        </tr>
      `).join('');

      // Auto generate a symmetric key if field is empty
      if (!symmetricKeyInput.value) {
        symmetricKeyInput.value = generateRandomSymmetricKey();
      }
      uploadSymmetricBtn.disabled = false;
    } catch (err) {
      recipientDevicesList.innerHTML = `<tr><td colspan="3" class="error-text">Error searching recipient devices. Verify UUID structure.</td></tr>`;
      console.error(err);
    }
  });

  generateKeyBtn.addEventListener('click', () => {
    symmetricKeyInput.value = generateRandomSymmetricKey();
  });

  // Symmetric key encrypt and upload
  const uploadStatus = document.getElementById('upload-status')!;
  uploadSymmetricBtn.addEventListener('click', async () => {
    const rUuid = (document.getElementById('recipient-uuid') as HTMLInputElement).value.trim();
    const plainKey = symmetricKeyInput.value;
    if (!rUuid || !plainKey || recipientDevicesCache.length === 0) return;

    uploadStatus.className = 'status-box status-loading';
    uploadStatus.textContent = 'Encrypting symmetric key for each recipient device...';
    uploadStatus.classList.remove('hidden');
    uploadSymmetricBtn.disabled = true;

    try {
      const encryptedDevicesMap: Record<string, string> = {};

      for (const device of recipientDevicesCache) {
        // Encrypt plain key using this recipient device's public identity key (RSA)
        const encryptedKey = await encryptSymmetricKey(plainKey, device.publicIdentityKey);
        encryptedDevicesMap[device.deviceId] = encryptedKey;
      }

      // Payload matching the SymmtricKeyUploadPayload record
      const payload = {
        keys: {
          [rUuid]: encryptedDevicesMap
        }
      };

      const response = await fetch('/api/delivery/symmetric/upload', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || 'Server rejected key upload');
      }

      uploadStatus.className = 'status-box status-success';
      uploadStatus.innerHTML = `
        <strong>Success!</strong> Symmetric key successfully encrypted and distributed to all <strong>${recipientDevicesCache.length}</strong> recipient devices.
      `;
      // Clear key input
      symmetricKeyInput.value = '';
    } catch (err: any) {
      uploadStatus.className = 'status-box status-error';
      uploadStatus.textContent = `Encryption or upload failed: ${err.message || err}`;
      uploadSymmetricBtn.disabled = false;
      console.error(err);
    }
  });

  // Fetch pending symmetric keys
  const pendingKeysList = document.getElementById('pending-keys-list')!;
  const pendingBadge = document.getElementById('pending-badge')!;

  async function fetchPendingKeys() {
    const myDeviceId = localStorage.getItem('deviceId');
    if (!myDeviceId) return;

    try {
      const response = await fetch(`/api/delivery/symmetric/fetch?deviceId=${myDeviceId}`, {
        headers: { 'Authorization': `Bearer ${token}` }
      });
      if (!response.ok) throw new Error('Could not fetch pending keys');
      const keys = await response.json();

      updatePendingBadge(keys.length);

      if (keys.length === 0) {
        pendingKeysList.innerHTML = `
          <div class="empty-state">
            <span class="empty-icon">🎉</span>
            <p>Your queue is empty! No pending secure keys waiting for this device.</p>
          </div>
        `;
        return;
      }

      pendingKeysList.innerHTML = keys.map((key: any) => `
        <div class="key-card" id="key-card-${key.uuid}">
          <div class="key-card-info">
            <div>
              <span class="lbl">Sender User UUID</span>
              <code class="val selectable">${escapeHtml(key.keySenderUserUuid)}</code>
            </div>
            <div>
              <span class="lbl">Key ID (UUID)</span>
              <code class="val selectable">${escapeHtml(key.uuid)}</code>
            </div>
            <div>
              <span class="lbl">Encrypted Ciphertext (Base64)</span>
              <code class="val val-cipher truncated">${escapeHtml(key.senderKey)}</code>
            </div>
          </div>
          <div class="key-card-action">
            <button class="btn btn-primary btn-decrypt" 
                    data-uuid="${key.uuid}" 
                    data-sender="${escapeHtml(key.keySenderUserUuid)}"
                    data-ciphertext="${escapeHtml(key.senderKey)}">
              🔑 Decrypt & ACK
            </button>
          </div>
          <div class="decrypted-result hidden" id="decrypted-result-${key.uuid}">
            <strong>Decrypted Plaintext Hex:</strong>
            <code class="selectable text-success" id="decrypted-value-${key.uuid}"></code>
          </div>
        </div>
      `).join('');

      // Decrypt action handlers
      pendingKeysList.querySelectorAll('.btn-decrypt').forEach(btn => {
        btn.addEventListener('click', async () => {
          const keyUuid = btn.getAttribute('data-uuid')!;
          const ciphertext = btn.getAttribute('data-ciphertext')!;
          const privateIdentityKey = localStorage.getItem('privateIdentityKey')!;
          const card = document.getElementById(`key-card-${keyUuid}`)!;
          const actionDiv = card.querySelector('.key-card-action')!;
          const resultDiv = document.getElementById(`decrypted-result-${keyUuid}`)!;
          const valCode = document.getElementById(`decrypted-value-${keyUuid}`)!;

          try {
            actionDiv.innerHTML = '<span class="loading-inline">Decrypting...</span>';

            // Decrypt standard RSA-OAEP Base64 ciphertext in browser using stored private key
            const decryptedKey = await decryptSymmetricKey(ciphertext, privateIdentityKey);
            
            // Show result
            valCode.textContent = decryptedKey;
            resultDiv.classList.remove('hidden');

            // Send Acknowledge POST /api/delivery/ack to remove from DB queue
            actionDiv.innerHTML = '<span class="loading-inline">Sending ACK...</span>';
            const ackResponse = await fetch('/api/delivery/ack', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`
              },
              body: JSON.stringify({
                deviceId: myDeviceId,
                keyUuid: keyUuid
              })
            });

            if (!ackResponse.ok) throw new Error('ACK failed');

            // Completed! Transition and remove card
            actionDiv.innerHTML = '<span class="text-success font-weight-bold">✓ Acknowledged</span>';
            setTimeout(() => {
              card.classList.add('slide-out');
              setTimeout(() => {
                card.remove();
                // Recalculate badge size
                const remaining = pendingKeysList.querySelectorAll('.key-card').length;
                updatePendingBadge(remaining);
                if (remaining === 0) {
                  fetchPendingKeys();
                }
              }, 400);
            }, 1000);

          } catch (e: any) {
            actionDiv.innerHTML = `
              <span class="text-error">Decryption Failed</span>
              <button class="btn btn-sm btn-secondary mt-1" onclick="location.reload()">Retry</button>
            `;
            console.error('Decryption failed', e);
          }
        });
      });

    } catch (err) {
      pendingKeysList.innerHTML = `<div class="error-text">Error fetching pending keys: ${err}</div>`;
    }
  }

  function updatePendingBadge(count: number) {
    if (count > 0) {
      pendingBadge.textContent = count.toString();
      pendingBadge.classList.remove('hidden');
    } else {
      pendingBadge.classList.add('hidden');
    }
  }

  // Setup refresh action button listeners
  document.getElementById('refresh-devices-btn')?.addEventListener('click', fetchMyDevices);
  document.getElementById('refresh-incoming-btn')?.addEventListener('click', fetchPendingKeys);

  // Background polling loop (runs every 10 seconds for real-time responsiveness without WebSocket)
  let pollInterval: any = null;
  function startPollingPendingKeys() {
    fetchPendingKeys(); // Initial call
    if (pollInterval) clearInterval(pollInterval);
    pollInterval = setInterval(fetchPendingKeys, 10000);
  }
}

// Simple HTML escaping helper for defense against XSS in dynamic DOM generation
function escapeHtml(unsafe: string): string {
  return unsafe
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

init();
