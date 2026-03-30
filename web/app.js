let peer = null;
let conn = null;
let myId = null;

// DOM Elements
const setupSection = document.getElementById('setup-section');
const hostingSection = document.getElementById('hosting-section');
const shareSection = document.getElementById('share-section');
const statusBadge = document.getElementById('status-badge');
const myPeerIdEl = document.getElementById('my-peer-id');
const remotePeerIdEl = document.getElementById('remote-peer-id');
const dropZone = document.getElementById('drop-zone');
const fileInput = document.getElementById('file-input');
const transferList = document.getElementById('transfer-list');
const joinIdInput = document.getElementById('join-id');
const qrcodeContainer = document.getElementById('qrcode-container');

// --- Initialization ---

function initPeer(id = null) {
  if (peer) peer.destroy();
  
  peer = new Peer(id, { debug: 1 });

  peer.on('open', (id) => {
    myId = id;
    myPeerIdEl.textContent = id;
    updateStatus('Waiting', 'waiting');
    generateQRCode(id);
  });

  peer.on('connection', (connection) => {
    if (conn) {
      connection.close();
      return;
    }
    setupConnection(connection);
  });

  peer.on('error', (err) => {
    console.error('Peer error:', err);
    if (err.type === 'peer-unavailable') {
      alert('Peer not found. Make sure the code is correct.');
    } else {
      alert('Error: ' + err.type);
    }
    resetToSetup();
  });
}

function generateQRCode(id) {
  qrcodeContainer.innerHTML = '';
  // Construct the join URL
  const baseUrl = window.location.origin + window.location.pathname;
  const joinUrl = `${baseUrl}#${id}`;
  
  new QRCode(qrcodeContainer, {
    text: joinUrl,
    width: 256,
    height: 256,
    colorDark: "#1a73e8",
    colorLight: "#ffffff",
    correctLevel: QRCode.CorrectLevel.H
  });
}

// --- Connection Handling ---

function setupConnection(connection) {
  conn = connection;
  
  conn.on('open', () => {
    showSection(shareSection);
    remotePeerIdEl.textContent = conn.peer;
    updateStatus('Connected', 'connected');
  });

  conn.on('data', (data) => {
    handleIncomingData(data);
  });

  conn.on('close', () => {
    resetToSetup();
  });
}

function handleIncomingData(data) {
  if (data.type === 'file-start') {
    createTransferUI(data.transferId, data.name, data.size, 'receiving');
  } else if (data.type === 'file-chunk') {
    handleFileChunk(data);
  } else if (data.type === 'file-end') {
    finalizeFile(data.transferId);
  }
}

// --- File Transfer ---

const incomingFiles = {};

function handleFileChunk(data) {
  const { transferId, chunk, index } = data;
  if (!incomingFiles[transferId]) {
    incomingFiles[transferId] = { chunks: [], receivedSize: 0 };
  }
  
  incomingFiles[transferId].chunks[index] = chunk;
  incomingFiles[transferId].receivedSize += chunk.byteLength;
  
  const progress = (incomingFiles[transferId].receivedSize / data.totalSize) * 100;
  updateTransferProgress(transferId, progress);
}

function finalizeFile(transferId) {
  const fileData = incomingFiles[transferId];
  if (!fileData) return;

  const blob = new Blob(fileData.chunks);
  const url = URL.createObjectURL(blob);
  
  const item = document.getElementById(`transfer-${transferId}`);
  if (item) {
    const bar = item.querySelector('.progress-bar');
    bar.style.width = '100%';
    bar.classList.add('complete');
    
    // Create download button for mobile
    const btn = document.createElement('button');
    btn.className = 'primary-btn';
    btn.style.marginTop = '10px';
    btn.style.width = '100%';
    btn.textContent = 'Save File';
    btn.onclick = () => {
      const a = document.createElement('a');
      a.href = url;
      a.download = item.dataset.filename;
      a.click();
    };
    item.appendChild(btn);
  }
  
  delete incomingFiles[transferId];
}

async function sendFile(file) {
  if (!conn || !conn.open) return;

  const transferId = Math.random().toString(36).substr(2, 9);
  const chunkSize = 16384; 
  const totalChunks = Math.ceil(file.size / chunkSize);

  createTransferUI(transferId, file.name, file.size, 'sending');

  conn.send({
    type: 'file-start',
    transferId,
    name: file.name,
    size: file.size
  });

  let offset = 0;
  for (let i = 0; i < totalChunks; i++) {
    const chunk = await file.slice(offset, offset + chunkSize).arrayBuffer();
    conn.send({
      type: 'file-chunk',
      transferId,
      chunk,
      index: i,
      totalSize: file.size
    });
    offset += chunkSize;
    updateTransferProgress(transferId, (offset / file.size) * 100);
    if (i % 5 === 0) await new Promise(r => setTimeout(r, 10));
  }

  conn.send({ type: 'file-end', transferId });
}

// --- UI Helpers ---

function updateStatus(text, className) {
  statusBadge.textContent = text;
  statusBadge.className = `status-badge ${className}`;
}

function showSection(section) {
  [setupSection, hostingSection, shareSection].forEach(s => s.classList.add('hidden'));
  section.classList.remove('hidden');
  section.classList.add('active');
}

function resetToSetup() {
  if (conn) conn.close();
  conn = null;
  showSection(setupSection);
  updateStatus('Disconnected', 'disconnected');
  window.location.hash = '';
}

function createTransferUI(id, name, size, type) {
  const div = document.createElement('div');
  div.id = `transfer-${id}`;
  div.className = 'transfer-item';
  div.dataset.filename = name;
  const sizeStr = (size / (1024 * 1024)).toFixed(2) + ' MB';
  div.innerHTML = `
    <div style="display:flex; justify-content:space-between; font-size:0.9rem">
      <span>${type === 'sending' ? '⬆️' : '⬇️'} ${name}</span>
      <span>${sizeStr}</span>
    </div>
    <div class="progress-container"><div class="progress-bar"></div></div>
  `;
  transferList.prepend(div);
}

function updateTransferProgress(id, progress) {
  const item = document.getElementById(`transfer-${id}`);
  if (item) item.querySelector('.progress-bar').style.width = `${progress}%`;
}

// --- Event Listeners ---

document.getElementById('btn-host').addEventListener('click', () => {
  const randomId = Math.floor(100000 + Math.random() * 900000).toString();
  initPeer(randomId);
  showSection(hostingSection);
});

document.getElementById('btn-join').addEventListener('click', () => {
  const id = joinIdInput.value.trim();
  if (!id) return;
  initPeer();
  peer.on('open', () => setupConnection(peer.connect(id)));
});

document.getElementById('btn-cancel-host').addEventListener('click', resetToSetup);
document.getElementById('btn-disconnect').addEventListener('click', resetToSetup);

dropZone.addEventListener('click', () => fileInput.click());
fileInput.addEventListener('change', () => {
  Array.from(fileInput.files).forEach(sendFile);
  fileInput.value = '';
});

// Auto-join from URL hash
window.addEventListener('load', () => {
  const hashId = window.location.hash.substring(1);
  if (hashId && hashId.length === 6) {
    initPeer();
    peer.on('open', () => setupConnection(peer.connect(hashId)));
  }
});
