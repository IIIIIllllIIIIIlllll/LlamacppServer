function refreshHintUi() {
  const statusText = state.statusText ? String(state.statusText) : '';
  const saveText = state.saveHintText ? String(state.saveHintText) : '';
  const merged = statusText ? (saveText ? (statusText + ' · ' + saveText) : statusText) : saveText;
  if (els.saveHint) els.saveHint.textContent = merged;
}

function setStatus(text) {
  state.statusText = (text == null ? '' : String(text));
  refreshHintUi();
}

function setSaveHint(text) {
  state.saveHintText = (text == null ? '' : String(text));
  refreshHintUi();
}

async function fetchJson(url, options) {
  const res = await fetch(url, options);
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch (e) { }
  if (!res.ok) {
    const message = (json && (json.message || json.error?.message)) ? (json.message || json.error.message) : (text || ('HTTP ' + res.status));
    throw new Error(message);
  }
  return json;
}

let mcpToolsLoading = false;
function normalizeToolName(name) {
  return (name == null ? '' : String(name)).trim();
}

const BUILTIN_TOOL_SERVER_URL = 'builtin://local';
const BUILTIN_TOOL_SERVER_NAME = '内置工具';

const BUILTIN_TOOLS = [
  {
    name: 'get_current_time',
    description: "get current time in a specific timezones",
    inputSchema: {
      $schema: 'http://json-schema.org/draft-07/schema#',
      type: 'object',
      properties: {
        timezone: {
          description: "IANA timezone name (e.g., 'America/New_York', 'Europe/London')",
          type: 'string'
        }
      },
      additionalProperties: false
    }
  },
  {
    name: 'convert_time',
    description: "convert time between timezones",
    inputSchema: {
      $schema: 'http://json-schema.org/draft-07/schema#',
      type: 'object',
      properties: {
        source_timezone: {
          description: "Source IANA timezone name (e.g., 'America/New_York', 'Europe/London'). Use 'Asia/Shanghai' as local timezone if no source timezone provided by the user.",
          type: 'string'
        },
        time: {
          description: 'Time to convert in 24-hour format (HH:MM)',
          type: 'string'
        },
        target_timezone: {
          description: "Target IANA timezone name (e.g., 'Asia/Tokyo', 'America/San_Francisco')",
          type: 'string'
        }
      },
      required: ['time', 'target_timezone'],
      additionalProperties: false
    }
  }
];

function injectBuiltinToolsData(data) {
  const obj = (data && typeof data === 'object') ? data : {};
  const servers0 = obj?.servers;
  const servers = (servers0 && typeof servers0 === 'object') ? servers0 : {};
  const outServers = { ...servers };
  outServers[BUILTIN_TOOL_SERVER_URL] = { name: BUILTIN_TOOL_SERVER_NAME, tools: BUILTIN_TOOLS };
  return { ...obj, servers: outServers };
}

function isMcpToolEnabled(toolName) {
  const tn = normalizeToolName(toolName);
  if (!tn) return false;
  const list = Array.isArray(state.enabledMcpTools) ? state.enabledMcpTools : [];
  return list.includes(tn);
}

function setMcpToolEnabled(toolName, enabled) {
  const tn = normalizeToolName(toolName);
  if (!tn) return;
  const list = Array.isArray(state.enabledMcpTools) ? state.enabledMcpTools.slice() : [];
  const has = list.includes(tn);
  if (enabled && !has) list.push(tn);
  if (!enabled && has) {
    const idx = list.indexOf(tn);
    if (idx >= 0) list.splice(idx, 1);
  }
  state.enabledMcpTools = list;
}

function updateMcpToolsStatus() {
  const data = state.mcpToolsData || {};
  const servers = data?.servers;
  const serverCount = (servers && typeof servers === 'object') ? Object.keys(servers).length : 0;
  const enabledCount = Array.isArray(state.enabledMcpTools) ? state.enabledMcpTools.length : 0;
  if (els.mcpToolsStatus) {
    if (!serverCount) els.mcpToolsStatus.textContent = '暂无服务';
    else els.mcpToolsStatus.textContent = `服务数：${serverCount} · 已启用：${enabledCount}`;
  }
}

function renderMcpTools(data) {
  const normalized = injectBuiltinToolsData(data);
  state.mcpToolsData = normalized;
  const wrap = els.mcpToolsList;
  if (!wrap) return;
  wrap.textContent = '';
  const servers = normalized?.servers;
  const entries = (servers && typeof servers === 'object') ? Object.entries(servers) : [];
  if (!entries.length) {
    wrap.textContent = '暂无 MCP 工具';
    updateMcpToolsStatus();
    return;
  }

  entries.sort((a, b) => {
    const au = a?.[0] == null ? '' : String(a[0]);
    const bu = b?.[0] == null ? '' : String(b[0]);
    const aIsBuiltin = au === BUILTIN_TOOL_SERVER_URL;
    const bIsBuiltin = bu === BUILTIN_TOOL_SERVER_URL;
    if (aIsBuiltin && !bIsBuiltin) return -1;
    if (!aIsBuiltin && bIsBuiltin) return 1;

    const as = (a?.[1] && typeof a[1] === 'object') ? a[1] : {};
    const bs = (b?.[1] && typeof b[1] === 'object') ? b[1] : {};
    const an = (typeof as.name === 'string' ? as.name : '').trim();
    const bn = (typeof bs.name === 'string' ? bs.name : '').trim();
    const nameCmp = an.localeCompare(bn, 'zh-CN', { sensitivity: 'base' });
    if (nameCmp !== 0) return nameCmp;
    return au.localeCompare(bu, 'zh-CN', { sensitivity: 'base' });
  });

  for (const [url, server] of entries) {
    const serverObj = (server && typeof server === 'object') ? server : {};
    const serverName = (typeof serverObj.name === 'string' && serverObj.name.trim()) ? serverObj.name.trim() : '';
    const tools = Array.isArray(serverObj.tools) ? serverObj.tools : [];

    const serverEl = document.createElement('details');
    serverEl.className = 'mcp-server';
    serverEl.open = true;

    const toolSeen = new Set();
    let totalTools = 0;
    let enabledTools = 0;
    for (const t of tools) {
      const toolObj = (t && typeof t === 'object') ? t : {};
      const tn0 = (typeof toolObj.name === 'string' && toolObj.name.trim()) ? toolObj.name.trim() : '';
      if (!tn0) continue;
      if (toolSeen.has(tn0)) continue;
      toolSeen.add(tn0);
      totalTools++;
      if (isMcpToolEnabled(tn0)) enabledTools++;
    }

    const summary = document.createElement('summary');
    summary.className = 'mcp-server-summary';

    const titleWrap = document.createElement('div');
    titleWrap.className = 'mcp-server-title';
    titleWrap.textContent = serverName ? `${serverName}` : `MCP`;

    const badge = document.createElement('div');
    badge.className = 'mcp-server-badge';
    badge.textContent = `${enabledTools}/${totalTools}`;

    summary.appendChild(titleWrap);
    summary.appendChild(badge);
    serverEl.appendChild(summary);

    const body = document.createElement('div');
    body.className = 'mcp-server-body';

    const urlEl = document.createElement('div');
    urlEl.className = 'mcp-server-url';
    urlEl.textContent = String(url || '');
    body.appendChild(urlEl);

    const list = document.createElement('div');
    list.className = 'mcp-tool-list';
    toolSeen.clear();
    for (const t of tools) {
      const toolObj = (t && typeof t === 'object') ? t : {};
      const tn = (typeof toolObj.name === 'string' && toolObj.name.trim()) ? toolObj.name.trim() : '';
      if (!tn) continue;
      if (toolSeen.has(tn)) continue;
      toolSeen.add(tn);

      const cb = document.createElement('input');
      cb.className = 'mcp-tool-toggle';
      cb.type = 'checkbox';
      cb.checked = isMcpToolEnabled(tn);

      const row = document.createElement('div');
      row.className = 'mcp-tool-row';

      const labelEl = document.createElement('div');
      labelEl.className = 'mcp-tool-label';
      labelEl.textContent = tn;

      const desc = (typeof toolObj.description === 'string' && toolObj.description.trim()) ? toolObj.description.trim() : '';
      if (desc) {
        row.title = desc;
        labelEl.title = desc;
      }

      cb.addEventListener('change', () => {
        setMcpToolEnabled(tn, !!cb.checked);
        updateMcpToolsStatus();
        scheduleSave('MCP工具');
      });

      row.addEventListener('click', (e) => {
        const target = e && e.target ? e.target : null;
        if (target && target.closest && target.closest('input')) return;
        cb.checked = !cb.checked;
        cb.dispatchEvent(new Event('change', { bubbles: true }));
      });

      row.appendChild(cb);
      row.appendChild(labelEl);
      list.appendChild(row);
    }
    if (list.childNodes.length) body.appendChild(list);
    serverEl.appendChild(body);
    wrap.appendChild(serverEl);
  }
  updateMcpToolsStatus();
}

async function refreshMcpTools() {
  if (mcpToolsLoading) return;
  mcpToolsLoading = true;
  if (els.mcpToolsStatus) els.mcpToolsStatus.textContent = '加载中...';
  try {
    const resp = await fetchJson('/api/mcp/tools', { method: 'GET' });
    if (resp && resp.success === false) {
      throw new Error(String(resp.error || '获取失败'));
    }
    const data = resp?.data || {};
    renderMcpTools(data);
  } catch (e) {
    if (els.mcpToolsStatus) els.mcpToolsStatus.textContent = String(e && e.message ? e.message : e);
    if (els.mcpToolsList) els.mcpToolsList.textContent = '加载失败';
  } finally {
    mcpToolsLoading = false;
  }
}

function getFileExt(fileName) {
  const n = (fileName == null ? '' : String(fileName)).trim();
  const idx = n.lastIndexOf('.');
  if (idx < 0) return '';
  return n.slice(idx + 1).toLowerCase();
}

const IMAGE_EXTS = ['png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp', 'svg'];

function isLikelyImageUrl(url) {
  const u = (url == null ? '' : String(url)).toLowerCase();
  const q = u.indexOf('?');
  const clean = q >= 0 ? u.slice(0, q) : u;
  return IMAGE_EXTS.some(ext => clean.endsWith('.' + ext));
}

function isLikelyImageFile(file) {
  const type = (file && file.type) ? String(file.type).toLowerCase() : '';
  if (type.startsWith('image/')) return true;
  const ext = getFileExt(file && file.name ? file.name : '');
  return IMAGE_EXTS.includes(ext);
}

function removeDomainFromUrl(url) {
  const raw = (url == null ? '' : String(url)).trim();
  if (!raw) return '';
  try {
    const u = new URL(raw, window.location.href);
    if (u.origin === window.location.origin) return u.pathname + u.search + u.hash;
    return u.href;
  } catch (e) {
    return raw;
  }
}

function renderMessageAttachments(attachmentsEl, msg) {
  if (!attachmentsEl) return;
  attachmentsEl.innerHTML = '';
  const atts = Array.isArray(msg?.attachments) ? msg.attachments : [];
  if (!atts.length) {
    attachmentsEl.style.display = 'none';
    return;
  }
  attachmentsEl.style.display = 'flex';

  for (const a of atts) {
    if (!a || !a.url) continue;
    const urlObj = new URL(a.url);
    // 获取路径部分（包含查询参数和哈希）
    const path = urlObj.pathname + urlObj.search + urlObj.hash;

    const item = document.createElement('div');
    item.className = 'attachment-item';

    const head = document.createElement('div');
    head.className = 'attachment-head';

    const nameEl = document.createElement('div');
    nameEl.className = 'attachment-name';
    nameEl.textContent = a.name ? String(a.name) : (a.type ? String(a.type) : 'file');

    const link = document.createElement('a');
    link.className = 'attachment-link';

    link.href = String(removeDomainFromUrl(a.url));
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.textContent = '打开';

    head.appendChild(nameEl);
    head.appendChild(link);
    item.appendChild(head);

    const isImage = a.isImage === true || isLikelyImageUrl(a.url) || IMAGE_EXTS.includes(getFileExt(a.name || ''));
    if (isImage) {
      const preview = document.createElement('div');
      preview.className = 'attachment-preview';
      const img = document.createElement('img');
      img.src = String(path);
      img.alt = a.name ? String(a.name) : 'image';
      preview.appendChild(img);
      item.appendChild(preview);
    }

    attachmentsEl.appendChild(item);
  }

  if (!attachmentsEl.childNodes || attachmentsEl.childNodes.length === 0) {
    attachmentsEl.style.display = 'none';
  }
}

function renderAttachment() {
  const p = state.pendingAttachment;
  if (!p || !p.file) {
    els.attachInfo.style.display = 'none';
    els.attachName.textContent = '';
    return;
  }
  els.attachInfo.style.display = 'flex';
  els.attachName.textContent = p.originalName || p.file.name || '';
}

function clearAttachment() {
  state.pendingAttachment = null;
  if (els.attachInput) els.attachInput.value = '';
  renderAttachment();
}

function setAttachment(file) {
  if (!file) {
    clearAttachment();
    return;
  }
  state.pendingAttachment = { file, originalName: file.name || 'file', messageId: null, uploadedName: null };
  renderAttachment();
}

async function maybeUploadPendingAttachment() {
  const p = state.pendingAttachment;
  if (!p || !p.file || !p.messageId) return null;
  if (p.uploadedName) return p.uploadedName;

  const form = new FormData();
  form.append('file', p.file, p.originalName || p.file.name || 'file');
  const resp = await fetchJson('/api/chat/completion/file/upload', {
    method: 'POST',
    body: form,
    signal: state.abortController ? state.abortController.signal : undefined
  });
  const savedName = resp && resp.data && resp.data.name ? String(resp.data.name) : '';
  if (!savedName) throw new Error('上传失败：缺少返回name');
  p.uploadedName = savedName;

  const m = state.messages.find(x => x.id === p.messageId);
  if (m) {
    const downloadPath = '/api/chat/completion/file/download?name=' + encodeURIComponent(savedName);
    const absoluteUrl = (typeof window !== 'undefined' && window.location && window.location.origin)
      ? (window.location.origin + downloadPath)
      : downloadPath;
    m.attachments = Array.isArray(m.attachments) ? m.attachments : [];
    const name = p.originalName || p.file.name || '';
    m.attachments.push({ type: 'file', url: absoluteUrl, name, isImage: isLikelyImageFile(p.file) });
    updateAttachments(m.id);
  }
  clearAttachment();
  return savedName;
}

//========================================================================================================================================
// 压缩/解压 JSON 字符串

const LZ_PREFIX = 'lz:';

function lzCompressIfNeeded(text) {
  if (text == null) return text;
  const s = String(text);
  if (!s) return s;
  if (s.startsWith(LZ_PREFIX)) return s;
  if (s.length < 256) return s;
  try {
    const compressed = window.LZString?.compressToEncodedURIComponent ? window.LZString.compressToEncodedURIComponent(s) : null;
    if (!compressed) return s;
    return LZ_PREFIX + compressed;
  } catch (e) {
    return s;
  }
}

function lzDecompressIfNeeded(text) {
  if (text == null) return text;
  const s = String(text);
  if (!s) return s;
  if (!s.startsWith(LZ_PREFIX)) return s;
  const payload = s.slice(LZ_PREFIX.length);
  try {
    const out = window.LZString?.decompressFromEncodedURIComponent ? window.LZString.decompressFromEncodedURIComponent(payload) : null;
    return out == null ? s : out;
  } catch (e) {
    return s;
  }
}

function formatTime(ts) {
  if (!ts) return '';
  try {
    return new Date(ts).toLocaleString();
  } catch (e) {
    return String(ts);
  }
}

const COMPLETION_LOCAL_BACKUP_PREFIX = 'completion_backup:';

function getCompletionBackupKey(completionId) {
  const id = (completionId == null ? '' : String(completionId)).trim();
  if (!id) return '';
  return COMPLETION_LOCAL_BACKUP_PREFIX + id;
}

function writeCompletionBackup(completionId, snapshot) {
  const key = getCompletionBackupKey(completionId);
  if (!key) return false;
  try {
    const raw = JSON.stringify(snapshot || {});
    const packed = lzCompressIfNeeded(raw);
    window.localStorage.setItem(key, packed);
    return true;
  } catch (e) {
    return false;
  }
}

function readCompletionBackup(completionId) {
  const key = getCompletionBackupKey(completionId);
  if (!key) return null;
  try {
    const packed = window.localStorage.getItem(key);
    if (!packed) return null;
    const raw = lzDecompressIfNeeded(packed);
    if (!raw) return null;
    return JSON.parse(raw);
  } catch (e) {
    return null;
  }
}

function clearCompletionBackup(completionId) {
  const key = getCompletionBackupKey(completionId);
  if (!key) return;
  try {
    window.localStorage.removeItem(key);
  } catch (e) {
  }
}

