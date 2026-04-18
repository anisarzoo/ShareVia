const DEFAULT_CONFIG = {
  signalingHost: 'sharevia-signal.onrender.com',
  signalingPort: '443',
  signalingPath: '/peerjs',
  signalingSecure: true,
  chunkSize: 65536,
  ackEvery: 32,
};

const STORAGE_KEY = 'sharevia_ext_config_v1';
const HISTORY_STORAGE_KEY = 'sharevia_ext_history_v1';
const CONNECTION_TIMEOUT = 15000;
const MAX_HISTORY_ENTRIES = 100;

const state = {
  peer: null,
  connections: new Map(),
  myId: '',
  pendingJoinId: null,
  config: loadConfig(),
  incomingTransfers: new Map(),
  outgoingTransfers: new Map(),
  historyFilter: 'all',
  receivedArchiveItems: [],
};

const elements = {
  setupSection: document.getElementById('setup-section'),
  hostingSection: document.getElementById('hosting-section'),
  shareSection: document.getElementById('share-section'),
  transfersPanel: document.getElementById('transfers-panel'),
  myPeerId: document.getElementById('my-peer-id'),
  remotePeerId: document.getElementById('remote-peer-id'),
  qrcodeContainer: document.getElementById('qrcode-container'),
  transferList: document.getElementById('transfer-list'),
  dropZone: document.getElementById('drop-zone'),
  fileInput: document.getElementById('file-input'),
  folderInput: document.getElementById('folder-input'),
  btnSaveAll: document.getElementById('btn-save-all'),
  historyTabs: Array.from(document.querySelectorAll('[data-history-tab]')),
  btnPickFiles: document.getElementById('btn-pick-files'),
  btnPickFolder: document.getElementById('btn-pick-folder'),
  noteInbox: document.getElementById('note-inbox'),
  textNote: document.getElementById('text-note'),
  advancedPanel: document.getElementById('advanced-panel'),
  btnAdvanced: document.getElementById('btn-advanced'),
  formSettings: document.getElementById('form-settings'),
  btnHeaderDisconnect: document.getElementById('btn-header-disconnect'),
  webJoinIdInput: document.getElementById('web-join-id'),
  btnWebJoin: document.getElementById('btn-web-join'),
  btnDashboardSend: document.getElementById('btn-dashboard-send'),
};

function loadConfig() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? { ...DEFAULT_CONFIG, ...JSON.parse(raw) } : { ...DEFAULT_CONFIG };
  } catch { return { ...DEFAULT_CONFIG }; }
}

function persistConfig() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state.config));
}

function formatClockTime(ts) {
  return new Date(ts).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1048576).toFixed(1)} MB`;
}

function showSection(section) {
  [elements.setupSection, elements.hostingSection, elements.shareSection].forEach(s => s.classList.add('hidden'));
  section.classList.remove('hidden');
  section.classList.add('active');

  const inRoom = section !== elements.setupSection;
  elements.transfersPanel.classList.toggle('hidden', !inRoom);
  elements.btnHeaderDisconnect.classList.toggle('hidden', !inRoom);
  if (inRoom) {
    elements.btnHeaderDisconnect.textContent = section === elements.hostingSection ? 'Cancel' : 'Leave';
  }
}

function initPeer(preferredId) {
  if (state.peer) state.peer.destroy();
  
  const id = preferredId || String(Math.floor(100000 + Math.random() * 900000));
  state.peer = new Peer(id, {
    host: state.config.signalingHost,
    port: parseInt(state.config.signalingPort),
    path: state.config.signalingPath,
    secure: state.config.signalingSecure,
    debug: 1
  });

  state.peer.on('open', (id) => {
    state.myId = id;
    elements.myPeerId.textContent = id;
    generateQRCode(id);
    if (state.pendingJoinId) {
      connectToPeer(state.pendingJoinId);
      state.pendingJoinId = null;
    }
  });

  state.peer.on('connection', setupConnection);
  state.peer.on('error', err => {
    console.error(err);
    if (err.type === 'peer-unavailable') alert('Room not found.');
    resetToSetup();
  });
}

function connectToPeer(targetId) {
  const conn = state.peer.connect(targetId, { reliable: true });
  setupConnection(conn);
}

function setupConnection(conn) {
  const peerId = conn.peer;
  state.connections.set(peerId, conn);

  conn.on('open', () => {
    showSection(elements.shareSection);
    elements.remotePeerId.textContent = peerId;
  });

  conn.on('data', data => handleData(data, peerId));
  conn.on('close', () => resetToSetup());
}

function handleData(payload, from) {
  if (!payload || typeof payload !== 'object') return;
  const transferKey = `${from}-${payload.transferId}`;

  switch (payload.type) {
    case 'file-start':
      state.incomingTransfers.set(transferKey, { 
        ...payload, 
        receivedBytes: 0, 
        chunks: [], 
        startTs: performance.now() 
      });
      createTransferUI(transferKey, payload.name, payload.size, 'incoming');
      break;
    case 'file-chunk':
      const inRec = state.incomingTransfers.get(transferKey);
      if (!inRec) return;
      inRec.chunks[payload.index] = payload.chunk;
      inRec.receivedBytes += payload.chunk.byteLength;
      updateProgress(transferKey, inRec.receivedBytes, inRec.size, inRec.startTs);
      
      if (inRec.receivedBytes >= inRec.size) {
        const blob = new Blob(inRec.chunks);
        const url = URL.createObjectURL(blob);
        addDownloadAction(transferKey, url, inRec.name);
        state.receivedArchiveItems.push({ name: inRec.name, blob });
        updateSaveAllButton();
      }
      break;
    case 'text-note':
      addNote(payload.text, false);
      break;
  }
}

function resetToSetup() {
  if (state.peer) state.peer.destroy();
  state.peer = null;
  state.connections.clear();
  elements.transferList.innerHTML = '';
  elements.noteInbox.innerHTML = '';
  showSection(elements.setupSection);
}

function createTransferUI(id, name, size, direction) {
  const item = document.createElement('div');
  item.className = 'transfer-item';
  item.id = `tr-${id}`;
  item.innerHTML = `
    <div class="transfer-name">${direction === 'outgoing' ? '↑' : '↓'} ${name}</div>
    <div class="transfer-meta">${formatBytes(size)}</div>
    <div class="progress-wrap"><div class="progress-bar" id="pb-${id}"></div></div>
    <div class="transfer-foot"><span id="st-${id}">0%</span><span id="sp-${id}">-</span></div>
  `;
  elements.transferList.prepend(item);
}

function updateProgress(id, current, total, startTs) {
  const percent = Math.min(100, (current / total) * 100);
  const bar = document.getElementById(`pb-${id}`);
  const status = document.getElementById(`st-${id}`);
  if (bar) bar.style.width = `${percent}%`;
  if (status) status.textContent = `${percent.toFixed(0)}% (${formatBytes(current)} / ${formatBytes(total)})`;
}

function addDownloadAction(id, url, name) {
  const foot = document.getElementById(`tr-${id}`);
  const btn = document.createElement('button');
  btn.className = 'btn btn-secondary';
  btn.style.width = '100%';
  btn.style.marginTop = '8px';
  btn.textContent = 'Save File';
  btn.onclick = () => {
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.click();
  };
  foot.appendChild(btn);
}

function generateQRCode(id) {
  elements.qrcodeContainer.innerHTML = '';
  new QRCode(elements.qrcodeContainer, {
    text: `https://sharevia-p2p.web.app/#${id}`,
    width: 160,
    height: 160
  });
}

function addNote(text, isSelf) {
  const div = document.createElement('div');
  div.className = 'note-item' + (isSelf ? ' self' : '');
  div.style.padding = '8px';
  div.style.borderRadius = '8px';
  div.style.background = isSelf ? 'rgba(22, 159, 144, 0.1)' : 'rgba(0,0,0,0.05)';
  div.style.marginBottom = '4px';
  div.textContent = isSelf ? `You: ${text}` : text;
  elements.noteInbox.prepend(div);
}

function updateSaveAllButton() {
  elements.btnSaveAll.disabled = state.receivedArchiveItems.length === 0;
}

elements.btnWebScan = document.getElementById('btn-web-scan');
elements.scannerModal = document.getElementById('scanner-modal');
elements.btnCloseScanner = document.getElementById('btn-close-scanner');

let html5QrCode = null;

// Events
elements.btnDashboardSend.onclick = () => {
  showSection(elements.hostingSection);
  initPeer();
};

elements.btnWebScan.onclick = async () => {
    elements.scannerModal.classList.remove('hidden');
    if (!html5QrCode) html5QrCode = new Html5Qrcode("qr-reader");
    await html5QrCode.start(
        { facingMode: "environment" },
        { fps: 10, qrbox: 200 },
        (text) => {
            const id = text.split('#')[1] || text;
            if (id.length === 6) {
                elements.webJoinIdInput.value = id;
                stopScanner();
                elements.btnWebJoin.click();
            }
        }
    );
};

const stopScanner = async () => {
    if (html5QrCode) await html5QrCode.stop();
    elements.scannerModal.classList.add('hidden');
};

elements.btnCloseScanner.onclick = stopScanner;

elements.btnWebJoin.onclick = () => {
  const id = elements.webJoinIdInput.value.trim();
  if (id.length === 6) {
    state.pendingJoinId = id;
    initPeer();
  }
};

elements.btnHeaderDisconnect.onclick = resetToSetup;

elements.btnPickFiles.onclick = () => elements.fileInput.click();
elements.btnPickFolder.onclick = () => elements.folderInput.click();

elements.fileInput.onchange = () => {
  const files = elements.fileInput.files;
  for (let f of files) sendFile(f);
};

async function sendFile(file) {
  const transferId = Math.random().toString(36).slice(2, 9);
  const transferKey = `out-${transferId}`;
  const totalChunks = Math.ceil(file.size / state.config.chunkSize);
  const startTs = performance.now();

  createTransferUI(transferKey, file.name, file.size, 'outgoing');

  state.connections.forEach(conn => {
    conn.send({ type: 'file-start', transferId, name: file.name, size: file.size, totalChunks });
  });

  let offset = 0;
  for (let i = 0; i < totalChunks; i++) {
    const chunk = await file.slice(offset, offset + state.config.chunkSize).arrayBuffer();
    state.connections.forEach(conn => {
      conn.send({ type: 'file-chunk', transferId, index: i, chunk });
    });
    offset += state.config.chunkSize;
    updateProgress(transferKey, offset, file.size, startTs);
  }
}

document.getElementById('btn-send-note').onclick = () => {
  const text = elements.textNote.value.trim();
  if (!text) return;
  state.connections.forEach(conn => conn.send({ type: 'text-note', text }));
  addNote(text, true);
  elements.textNote.value = '';
};

elements.btnAdvanced.onclick = () => elements.advancedPanel.classList.toggle('hidden');
elements.formSettings.onsubmit = (e) => {
  e.preventDefault();
  state.config.signalingHost = document.getElementById('signal-host').value || DEFAULT_CONFIG.signalingHost;
  persistConfig();
  elements.advancedPanel.classList.add('hidden');
};

document.getElementById('btn-copy-code').onclick = () => {
  navigator.clipboard.writeText(state.myId);
};

document.getElementById('btn-copy-link').onclick = () => {
  navigator.clipboard.writeText(`https://sharevia-p2p.web.app/#${state.myId}`);
};
