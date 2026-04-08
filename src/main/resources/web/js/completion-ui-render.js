function t(key, fallback) {
  if (window.I18N && typeof window.I18N.t === 'function') {
    return window.I18N.t(key, fallback);
  }
  return fallback == null ? key : fallback;
}

function tf(key, params, fallback) {
  const template = t(key, fallback);
  if (!params || template == null) return template;
  let out = String(template);
  for (const k of Object.keys(params)) {
    out = out.split(`{${k}}`).join(String(params[k]));
  }
  return out;
}

function completionEscapeHtml(text) {
  return String(text == null ? '' : text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function completionMarkdownToHtml(text) {
  const input = String(text == null ? '' : text);
  if (!window.marked || typeof window.marked.parse !== 'function') {
    return completionEscapeHtml(input).replace(/\n/g, '<br>');
  }
  let parsed = '';
  try {
    parsed = window.marked.parse(completionEscapeHtml(input), {
      gfm: true,
      breaks: true,
      mangle: false,
      headerIds: false
    });
  } catch (e) {
    return completionEscapeHtml(input).replace(/\n/g, '<br>');
  }
  return String(parsed || '');
}

function wrapHtmlFragment(fragment) {
  const raw = String(fragment == null ? '' : fragment).trim();
  if (!raw) return '';
  if (/<html[\s>]/i.test(raw)) return raw;
  return '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"></head><body>' + raw + '</body></html>';
}

function extractRunnableHtml(text) {
  const input = String(text == null ? '' : text);
  const trimmed = input.trim();
  if (!trimmed) return '';
  if (/^\s*<!doctype html[\s>]/i.test(trimmed) || /^\s*<html[\s>]/i.test(trimmed)) {
    return wrapHtmlFragment(trimmed);
  }
  const fence = /```+([a-zA-Z0-9_-]*)\s*([\s\S]*?)```+/g;
  let match = null;
  while ((match = fence.exec(input)) !== null) {
    const lang = String(match[1] || '').toLowerCase();
    if (!lang || !(lang === 'html' || lang === 'htm' || lang === 'xhtml')) continue;
    const body = String(match[2] || '').trim();
    if (body) return wrapHtmlFragment(body);
  }
  return '';
}

let completionMarkdownRaf = 0;
const pendingCompletionMarkdownRenders = new Map();

function renderMessageContentNow(el, text) {
  if (!el) return;
  const raw = String(text == null ? '' : text);
  el.innerHTML = completionMarkdownToHtml(raw);
  if (window.hljs && typeof window.hljs.highlightElement === 'function' && (raw.includes('```') || raw.includes('`'))) {
    const blocks = el.querySelectorAll('pre code');
    for (const block of blocks) window.hljs.highlightElement(block);
  }
}

function flushCompletionMarkdownRenders() {
  completionMarkdownRaf = 0;
  for (const [node, value] of pendingCompletionMarkdownRenders.entries()) {
    renderMessageContentNow(node, value);
  }
  pendingCompletionMarkdownRenders.clear();
}

function requestRenderMessageContent(el, text) {
  if (!el) return;
  pendingCompletionMarkdownRenders.set(el, text);
  if (completionMarkdownRaf) return;
  completionMarkdownRaf = requestAnimationFrame(flushCompletionMarkdownRenders);
}

function normalizeSpeakerName(name, fallback) {
  const raw = (name == null ? '' : String(name)).trim();
  const cleaned = raw.replace(/[\r\n:]+/g, ' ').trim();
  return cleaned || fallback;
}

function getUserSpeakerName() {
  return normalizeSpeakerName(els.userName.value, 'User');
}

function getAssistantSpeakerName() {
  return normalizeSpeakerName(getCurrentCompletionTitle(), 'Assistant');
}

function getUserMessagePrefix() {
  return (els.userPrefix.value == null ? '' : String(els.userPrefix.value));
}

function getUserMessageSuffix() {
  return (els.userSuffix.value == null ? '' : String(els.userSuffix.value));
}

function getAssistantMessagePrefix() {
  return (els.assistantPrefix.value == null ? '' : String(els.assistantPrefix.value));
}

function getAssistantMessageSuffix() {
  return (els.assistantSuffix.value == null ? '' : String(els.assistantSuffix.value));
}

function getCurrentCompletionTitle() {
  const name = (els.titleInput.value || '').trim();
  return name || t('page.chat.completion.role.default', '默认角色');
}

function refreshCompletionTitleInMessages() {
  if (!state.currentCompletionId) return;
  const taskName = getCurrentCompletionTitle();
  const userName = getUserSpeakerName();
  for (const msgEl of els.chatList.querySelectorAll('.msg')) {
    const id = msgEl.dataset.id;
    if (!id) continue;
    const msg = getMessageById(id);
    if (!msg) continue;
    const left = msgEl.querySelector('.meta-left');
    if (!left) continue;
    left.textContent = msg.role === 'user' ? userName : (msg.role === 'assistant' ? taskName : t('page.chat.completion.role.system', '系统'));
  }
  if (els.sessionsList) {
    const active = els.sessionsList.querySelector('.session-item.active .session-title');
    if (active) active.textContent = taskName;
  }
}

function openDrawer() {
  if (!els.drawer) return;
  lastDrawerFocus = document.activeElement;
  els.drawer.classList.add('open');
  els.drawer.setAttribute('aria-hidden', 'false');
  els.drawer.removeAttribute('inert');
  if (!els.drawer.hasAttribute('role')) els.drawer.setAttribute('role', 'dialog');
  if (!els.drawer.hasAttribute('aria-modal')) els.drawer.setAttribute('aria-modal', 'true');
  els.backdrop.classList.add('show');
  if (els.drawerClose) {
    setTimeout(() => {
      try { els.drawerClose.focus({ preventScroll: true }); } catch (e) { }
    }, 0);
  }
}

function closeDrawer() {
  if (!els.drawer) return;
  const active = document.activeElement;
  const isFocusInside = active && els.drawer.contains(active);
  const focusTarget = (lastDrawerFocus && document.contains(lastDrawerFocus)) ? lastDrawerFocus : els.sessionsToggle;
  if (isFocusInside) {
    if (focusTarget && typeof focusTarget.focus === 'function') focusTarget.focus({ preventScroll: true });
    else if (active && typeof active.blur === 'function') active.blur();
  }
  els.drawer.classList.remove('open');
  els.drawer.setAttribute('aria-hidden', 'true');
  els.drawer.setAttribute('inert', '');
  els.backdrop.classList.remove('show');
  lastDrawerFocus = null;
}

let lastDrawerFocus = null;
let lastSettingsFocus = null;
let settingsTabsBound = false;

function bindSettingsTabs() {
  if (settingsTabsBound) return;
  const panel = els.settingsPanel;
  if (!panel) return;
  const navItems = Array.from(panel.querySelectorAll('.nav-item'));
  const tabs = Array.from(panel.querySelectorAll('.settings-tab'));
  if (!navItems.length || !tabs.length) return;

  for (const btn of navItems) {
    btn.addEventListener('click', () => {
      const key = btn?.dataset?.tab ? String(btn.dataset.tab) : '';
      if (!key) return;
      for (const b of navItems) b.classList.toggle('active', b === btn);
      for (const t of tabs) t.classList.toggle('active', t && t.id === 'tab-' + key);
    });
  }

  settingsTabsBound = true;
}

function setSettingsOpen(open) {
  const isOpen = !!open;
  const panel = els.settingsPanel;
  if (!panel) return;

  if (isOpen) {
    lastSettingsFocus = document.activeElement;
    bindSettingsTabs();
    panel.classList.add('open');
    panel.setAttribute('aria-hidden', 'false');
    panel.removeAttribute('inert');
    if (!panel.hasAttribute('role')) panel.setAttribute('role', 'dialog');
    if (!panel.hasAttribute('aria-modal')) panel.setAttribute('aria-modal', 'true');
    if (els.closeSettings) els.closeSettings.focus({ preventScroll: true });
  } else {
    const active = document.activeElement;
    const isFocusInside = active && panel.contains(active);
    const focusTarget = (lastSettingsFocus && document.contains(lastSettingsFocus)) ? lastSettingsFocus : els.toggleSettings;
    if (isFocusInside) {
      if (focusTarget && typeof focusTarget.focus === 'function') focusTarget.focus({ preventScroll: true });
      else if (active && typeof active.blur === 'function') active.blur();
    }
    panel.classList.remove('open');
    panel.setAttribute('aria-hidden', 'true');
    panel.setAttribute('inert', '');
    lastSettingsFocus = null;
  }
  document.documentElement.style.overflow = isOpen ? 'hidden' : '';
}

function setBusyGenerating(isBusy) {
  els.sendBtn.disabled = isBusy;
  els.stopBtn.disabled = !isBusy;
  els.modelSelect.disabled = isBusy;
  els.refreshModels.disabled = isBusy;
  els.streamToggle.disabled = isBusy;
  els.thinkingToggle.disabled = isBusy;
  els.webSearchToggle.disabled = isBusy;
  els.promptInput.disabled = false;
}

function setCompletionsLoading(isLoading) {
  state.isLoadingCompletions = isLoading;
  els.newSessionBtn.disabled = isLoading;
  els.reloadSessionsBtn.disabled = isLoading;
  els.drawerHint.textContent = isLoading ? t('toast.loading.message', '加载中…') : '';
}

let scrollRaf = 0;

function maybeScrollToBottom() {
  if (!els.autoScroll.checked) return;
  if (scrollRaf) return;
  scrollRaf = requestAnimationFrame(() => {
    scrollRaf = 0;
    const list = els.chatList;
    if (!list) return;
    const max = Math.max(0, list.scrollHeight - list.clientHeight);
    list.scrollTop = max;
  });
}

function findMessageIndexById(id) {
  if (!id) return -1;
  return state.messages.findIndex(m => m && m.id === id);
}

function findMessageEntryById(id) {
  if (!id) return null;
  const msgIndex = state.messages.findIndex(m => m && m.id === id);
  if (msgIndex >= 0) return { list: state.messages, idx: msgIndex, msg: state.messages[msgIndex] };
  const logIndex = state.systemLogs.findIndex(m => m && m.id === id);
  if (logIndex >= 0) return { list: state.systemLogs, idx: logIndex, msg: state.systemLogs[logIndex] };
  return null;
}

function getMessageById(id) {
  const entry = findMessageEntryById(id);
  return entry ? entry.msg : null;
}

function cloneMessageVersionData(value) {
  if (value == null) return value;
  try {
    return JSON.parse(JSON.stringify(value));
  } catch (e) {
    return value;
  }
}

function createAssistantVersionSnapshot(source, fallbackTs) {
  const base = (source && typeof source === 'object') ? source : {};
  const snapshot = {
    content: (base.content == null ? '' : String(base.content)),
    reasoning: (typeof base.reasoning === 'string' ? base.reasoning : ''),
    ts: (typeof base.ts === 'number' ? base.ts : (typeof fallbackTs === 'number' ? fallbackTs : Date.now()))
  };
  if (typeof base.uiContent === 'string') snapshot.uiContent = base.uiContent;
  if (base.timings !== undefined) snapshot.timings = cloneMessageVersionData(base.timings);
  if (Array.isArray(base.tool_calls) && base.tool_calls.length) {
    snapshot.tool_calls = cloneMessageVersionData(base.tool_calls);
  }
  return snapshot;
}

function getAssistantMessageVersions(msg) {
  if (!msg || msg.role !== 'assistant') return [];
  const fallbackTs = (typeof msg.ts === 'number' ? msg.ts : Date.now());
  const rawVersions = Array.isArray(msg.versions) ? msg.versions : [];
  const versions = rawVersions.length
    ? rawVersions.map(item => createAssistantVersionSnapshot(item, fallbackTs))
    : [createAssistantVersionSnapshot(msg, fallbackTs)];
  msg.versions = versions;
  let index = Number(msg.activeVersionIndex);
  if (!Number.isFinite(index)) index = versions.length - 1;
  index = Math.max(0, Math.min(index, versions.length - 1));
  msg.activeVersionIndex = index;
  msg.versionSwitchLocked = msg.versionSwitchLocked === true;
  return versions;
}

function applyAssistantVersionToMessage(msg, index) {
  if (!msg || msg.role !== 'assistant') return null;
  const versions = getAssistantMessageVersions(msg);
  if (!versions.length) return null;
  const nextIndex = Math.max(0, Math.min(Number(index) || 0, versions.length - 1));
  const snapshot = versions[nextIndex];
  msg.activeVersionIndex = nextIndex;
  msg.content = snapshot.content || '';
  msg.reasoning = snapshot.reasoning || '';
  msg.ts = (typeof snapshot.ts === 'number' ? snapshot.ts : (typeof msg.ts === 'number' ? msg.ts : Date.now()));
  if (typeof snapshot.uiContent === 'string') msg.uiContent = snapshot.uiContent;
  else delete msg.uiContent;
  if (snapshot.timings !== undefined) msg.timings = cloneMessageVersionData(snapshot.timings);
  else delete msg.timings;
  if (Array.isArray(snapshot.tool_calls) && snapshot.tool_calls.length) {
    msg.tool_calls = cloneMessageVersionData(snapshot.tool_calls);
  } else {
    delete msg.tool_calls;
  }
  return snapshot;
}

function syncAssistantMessageVersionFromTopLevel(msg) {
  if (!msg || msg.role !== 'assistant') return null;
  const versions = getAssistantMessageVersions(msg);
  const index = Math.max(0, Math.min(Number(msg.activeVersionIndex) || 0, versions.length - 1));
  const snapshot = createAssistantVersionSnapshot(msg, msg.ts);
  versions[index] = snapshot;
  return snapshot;
}

function beginAssistantMessageRegeneration(messageId) {
  const msg = getMessageById(messageId);
  if (!msg || msg.role !== 'assistant') return null;
  syncAssistantMessageVersionFromTopLevel(msg);
  const versions = getAssistantMessageVersions(msg);
  const nextTs = Date.now();
  versions.push(createAssistantVersionSnapshot({ content: '', reasoning: '', ts: nextTs }, nextTs));
  msg.activeVersionIndex = versions.length - 1;
  msg.versionSwitchLocked = true;
  applyAssistantVersionToMessage(msg, msg.activeVersionIndex);
  return msg;
}

function finalizeAssistantMessageRegeneration(messageId) {
  const msg = getMessageById(messageId);
  if (!msg || msg.role !== 'assistant') return false;
  syncAssistantMessageVersionFromTopLevel(msg);
  const wasLocked = msg.versionSwitchLocked === true;
  msg.versionSwitchLocked = false;
  if (wasLocked) {
    rerenderAll();
  }
  return wasLocked;
}

function rollbackAssistantMessageVersion(messageId) {
  const msg = getMessageById(messageId);
  if (!msg || msg.role !== 'assistant') return false;
  const versions = getAssistantMessageVersions(msg);
  if (versions.length <= 1) return false;
  const index = Math.max(0, Math.min(Number(msg.activeVersionIndex) || 0, versions.length - 1));
  const snapshot = versions[index] || {};
  const hasData = !!(
    String(snapshot.content || '').trim()
    || String(snapshot.uiContent || '').trim()
    || String(snapshot.reasoning || '').trim()
    || (Array.isArray(snapshot.tool_calls) && snapshot.tool_calls.length)
  );
  if (hasData) return false;
  versions.splice(index, 1);
  msg.activeVersionIndex = Math.max(0, Math.min(index - 1, versions.length - 1));
  msg.versionSwitchLocked = false;
  applyAssistantVersionToMessage(msg, msg.activeVersionIndex);
  rerenderAll();
  return true;
}

function switchAssistantVersion(messageId, direction) {
  if (state.abortController) return;
  const msg = getMessageById(messageId);
  if (!msg || msg.role !== 'assistant' || msg.versionSwitchLocked) return;
  const versions = getAssistantMessageVersions(msg);
  if (versions.length <= 1) return;
  syncAssistantMessageVersionFromTopLevel(msg);
  const current = Math.max(0, Math.min(Number(msg.activeVersionIndex) || 0, versions.length - 1));
  const next = Math.max(0, Math.min(current + Number(direction || 0), versions.length - 1));
  if (next === current) return;
  applyAssistantVersionToMessage(msg, next);
  rerenderAll();
}

function upsertMessageDomEntry(id) {
  const key = String(id || '');
  if (!key) return null;
  const cached = state.messageEls.get(key) || {};
  let contentEl = cached.contentEl || null;
  let reasoningEl = cached.reasoningEl || null;
  let attachmentsEl = cached.attachmentsEl || null;
  let tokenEl = cached.tokenEl || null;

  if (!contentEl || !reasoningEl || !attachmentsEl || !tokenEl) {
    const wrap = els.chatList ? els.chatList.querySelector('.msg[data-id="' + key + '"]') : null;
    if (wrap) {
      if (!contentEl) contentEl = wrap.querySelector('.content');
      if (!reasoningEl) reasoningEl = wrap.querySelector('.reasoning pre');
      if (!attachmentsEl) attachmentsEl = wrap.querySelector('.attachments');
      if (!tokenEl) tokenEl = wrap.querySelector('.token-info');
    }
  }

  if (contentEl || reasoningEl || attachmentsEl || tokenEl) {
    const out = { contentEl, reasoningEl, attachmentsEl, tokenEl };
    state.messageEls.set(key, out);
    return out;
  }
  return null;
}

function removeMessageByIdSilently(messageId) {
  const entry = findMessageEntryById(messageId);
  if (!entry) return false;
  entry.list.splice(entry.idx, 1);
  return true;
}

function openEditModal(messageId) {
  if (state.abortController) return;
  const entry = findMessageEntryById(messageId);
  if (!entry) return;
  const msg = entry.msg;
  state.editingMessageId = msg.id;
  state.lastEditModalFocus = document.activeElement;
  els.editTextarea.value = msg.content || '';
  els.editModal.classList.add('show');
  els.editModal.setAttribute('aria-hidden', 'false');
  els.editModal.removeAttribute('inert');
  setTimeout(() => {
    try { els.editTextarea.focus(); } catch (e) { }
  }, 0);
}

function closeEditModal() {
  state.editingMessageId = null;
  const modal = els.editModal;
  const active = document.activeElement;
  const lastFocus = state.lastEditModalFocus;
  const focusBack = (lastFocus && lastFocus !== modal && typeof lastFocus.focus === 'function' && lastFocus.isConnected && !(modal && modal.contains(lastFocus)))
    ? lastFocus
    : null;
  if (modal && active && modal.contains(active)) {
    if (focusBack) {
      try { focusBack.focus({ preventScroll: true }); } catch (e) { }
    } else if (els.promptInput && typeof els.promptInput.focus === 'function') {
      try { els.promptInput.focus({ preventScroll: true }); } catch (e) { }
    } else if (active && typeof active.blur === 'function') {
      try { active.blur(); } catch (e) { }
    }
  }
  state.lastEditModalFocus = null;
  els.editModal.classList.remove('show');
  els.editModal.setAttribute('aria-hidden', 'true');
  els.editModal.setAttribute('inert', '');
}

function openKVCacheModal() {
  els.kvCacheModal.classList.add('show');
  els.kvCacheModal.setAttribute('aria-hidden', 'false');
}

function closeKVCacheModal() {
  els.kvCacheModal.classList.remove('show');
  els.kvCacheModal.setAttribute('aria-hidden', 'true');
}

function openMcpToolsModal() {
  els.mcpToolsModal.classList.add('show');
  els.mcpToolsModal.setAttribute('aria-hidden', 'false');
  if (typeof refreshMcpTools === 'function') refreshMcpTools();
}

function closeMcpToolsModal() {
  els.mcpToolsModal.classList.remove('show');
  els.mcpToolsModal.setAttribute('aria-hidden', 'true');
}

function saveEditModal() {
  const id = state.editingMessageId;
  if (!id) return;
  const text = (els.editTextarea.value == null ? '' : String(els.editTextarea.value));
  updateMessage(id, text);
  scheduleSave(t('page.chat.completion.save_reason.edit_message', '编辑气泡'));
  closeEditModal();
}

function deleteMessage(messageId) {
  if (state.abortController) return;
  const entry = findMessageEntryById(messageId);
  if (!entry) return;
  if (!confirm(t('confirm.chat.completion.delete_message', '确认删除该气泡？此操作不可撤销。'))) return;
  entry.list.splice(entry.idx, 1);
  rerenderAll();
  scheduleSave(t('page.chat.completion.save_reason.delete_message', '删除气泡'));
}

function getRunnableHtmlFromMessage(msg) {
  const raw = (msg && typeof msg.uiContent === 'string') ? msg.uiContent : (msg && msg.content ? msg.content : '');
  if (typeof extractRunnableHtml === 'function') return extractRunnableHtml(raw);
  return '';
}

function openHtmlPreviewFromMessage(id) {
  const msg = getMessageById(id);
  if (!msg) return;
  const html = getRunnableHtmlFromMessage(msg);
  if (!html) {
    if (typeof setStatus === 'function') setStatus(t('page.chat.completion.status.html_not_found', '未检测到可运行的HTML'));
    return;
  }
  openHtmlPreview(html);
}

function syncHtmlPreviewAction(id) {
  const msg = getMessageById(id);
  if (!msg || msg.role !== 'assistant') return;
  const wrap = els.chatList ? els.chatList.querySelector('.msg.assistant[data-id="' + id + '"]') : null;
  if (!wrap) return;
  const btn = wrap.querySelector('.msg-actions .msg-btn[data-action="run-html"]');
  if (!btn) return;
  const html = getRunnableHtmlFromMessage(msg);
  btn.style.display = html ? '' : 'none';
  btn.disabled = !html;
}

function renderMessage(msg) {
  if (msg && msg.role === 'assistant') {
    applyAssistantVersionToMessage(msg, msg.activeVersionIndex);
  }
  const wrap = document.createElement('div');
  wrap.className = 'msg ' + msg.role;
  wrap.dataset.id = msg.id;

  const avatar = (msg.role === 'tool' || msg.role === 'system') ? null : (() => {
    const el = document.createElement('div');
    el.className = 'avatar';
    el.dataset.role = msg.role;
    if (msg.role === 'assistant') {
      el.classList.add('clickable');
      el.textContent = 'AI';
      applyAssistantAvatar(el);
    } else {
      el.textContent = msg.role === 'user' ? t('page.chat.completion.avatar.user', '你') : 'SYS';
    }
    return el;
  })();

  const bubble = document.createElement('div');
  bubble.className = 'bubble';

  const meta = document.createElement('div');
  meta.className = 'meta';
  const left = document.createElement('div');
  left.className = 'meta-left';
  const taskName = getCurrentCompletionTitle();
  const userName = getUserSpeakerName();
  left.textContent = msg.role === 'user' ? userName : (msg.role === 'assistant' ? taskName : (msg.role === 'tool' ? t('page.chat.completion.role.tool', 'Tool') : t('page.chat.completion.role.system', '系统')));

  const right = document.createElement('div');
  right.className = 'meta-right';

  const timeEl = document.createElement('div');
  timeEl.textContent = msg.ts ? new Date(msg.ts).toLocaleTimeString() : '';

  const actions = document.createElement('div');
  actions.className = 'msg-actions';

  function addAction(label, cls, onClick) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'msg-btn' + (cls ? (' ' + cls) : '');
    b.textContent = label;
    b.addEventListener('click', (e) => {
      e.stopPropagation();
      onClick();
    });
    actions.appendChild(b);
    return b;
  }

  if (msg.role === 'assistant') {
    const versions = getAssistantMessageVersions(msg);
    const showVersionSwitch = versions.length > 1 || msg.versionSwitchLocked;
    if (showVersionSwitch) {
      const versionBar = document.createElement('div');
      versionBar.className = 'version-switcher';

      const prevBtn = document.createElement('button');
      prevBtn.type = 'button';
      prevBtn.className = 'msg-btn ghost version-switcher-btn';
      prevBtn.textContent = '←';
      prevBtn.disabled = msg.versionSwitchLocked || msg.activeVersionIndex <= 0;
      prevBtn.setAttribute('aria-label', t('page.chat.completion.action.previous_version', '上一版'));
      prevBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        switchAssistantVersion(msg.id, -1);
      });

      const status = document.createElement('div');
      status.className = 'version-switcher-status';
      status.textContent = (msg.activeVersionIndex + 1) + ' / ' + versions.length + (msg.versionSwitchLocked ? ' · ' + t('page.chat.completion.status.generating_short', '生成中') : '');

      const nextBtn = document.createElement('button');
      nextBtn.type = 'button';
      nextBtn.className = 'msg-btn ghost version-switcher-btn';
      nextBtn.textContent = '→';
      nextBtn.disabled = msg.versionSwitchLocked || msg.activeVersionIndex >= versions.length - 1;
      nextBtn.setAttribute('aria-label', t('page.chat.completion.action.next_version', '下一版'));
      nextBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        switchAssistantVersion(msg.id, 1);
      });

      versionBar.appendChild(prevBtn);
      versionBar.appendChild(status);
      versionBar.appendChild(nextBtn);
      actions.appendChild(versionBar);
    }
  }

  if (msg.role === 'assistant' || msg.role === 'user') {
    addAction(t('page.chat.completion.action.regenerate', '重生成'), 'ghost', () => regenerateMessage(msg.id));
  }
  if (msg.role === 'assistant') {
    const runHtmlBtn = addAction(t('page.chat.completion.action.run_html', '运行HTML'), 'ghost', () => openHtmlPreviewFromMessage(msg.id));
    runHtmlBtn.dataset.action = 'run-html';
    const html = getRunnableHtmlFromMessage(msg);
    if (!html) runHtmlBtn.style.display = 'none';
  }
  addAction(t('page.chat.completion.action.edit', '编辑'), 'ghost', () => openEditModal(msg.id));
  addAction(t('page.chat.completion.action.delete', '删除'), 'danger', () => deleteMessage(msg.id));

  right.appendChild(timeEl);
  right.appendChild(actions);
  meta.appendChild(left);
  meta.appendChild(right);

  const head = document.createElement('div');
  head.className = 'msg-head';
  if (avatar) head.appendChild(avatar);
  head.appendChild(meta);

  const content = document.createElement('div');
  content.className = 'content';
  const displayText = (msg && typeof msg.uiContent === 'string') ? msg.uiContent : (msg.content || '');
  renderMessageContentNow(content, displayText);

  let reasoningPre = null;
  const reasoningText = (typeof msg.reasoning === 'string' ? msg.reasoning : '');
  if (msg.role === 'assistant' && reasoningText.trim()) {
    const details = document.createElement('details');
    details.className = 'reasoning';
    const summary = document.createElement('summary');
    summary.textContent = t('page.chat.completion.action.thinking_details', '思考过程');
    reasoningPre = document.createElement('pre');
    reasoningPre.textContent = reasoningText;
    details.appendChild(summary);
    details.appendChild(reasoningPre);
    bubble.appendChild(details);
  }

  if (msg.role === 'tool') {
    const details = document.createElement('details');
    details.className = 'tool-result';
    const summary = document.createElement('summary');
    const toolName = (msg && msg.tool_name != null) ? String(msg.tool_name) : t('page.chat.completion.role.tool', 'Tool');
    summary.textContent = toolName + t('page.chat.completion.punctuation.colon', '：') + computeToolStatusText(msg);
    details.appendChild(summary);
    const argsText = (msg && msg.tool_arguments != null) ? String(msg.tool_arguments) : '';
    const reqLabel = document.createElement('div');
    reqLabel.className = 'tool-io-label';
    reqLabel.textContent = t('page.chat.completion.tool.label.request', '请求');
    const reqPre = document.createElement('pre');
    reqPre.className = 'tool-io-box tool-io-request';
    reqPre.textContent = String(argsText).trim() ? argsText : t('page.chat.completion.tool.empty_paren', '(空)');

    const respLabel = document.createElement('div');
    respLabel.className = 'tool-io-label';
    respLabel.textContent = t('page.chat.completion.tool.label.response', '响应');
    const respBox = document.createElement('div');
    respBox.className = 'tool-io-box tool-io-response';
    respBox.appendChild(content);

    details.appendChild(reqLabel);
    details.appendChild(reqPre);
    details.appendChild(respLabel);
    details.appendChild(respBox);
    bubble.appendChild(details);
  } else {
    bubble.appendChild(content);
  }

  const tokenInfoEl = (msg.role === 'assistant')
    ? (() => {
      const el = document.createElement('div');
      el.className = 'token-info';
      el.style.display = 'none';
      bubble.appendChild(el);
      return el;
    })()
    : null;

  const attachmentsEl = document.createElement('div');
  attachmentsEl.className = 'attachments';
  bubble.appendChild(attachmentsEl);
  renderMessageAttachments(attachmentsEl, msg);

  wrap.appendChild(head);
  wrap.appendChild(bubble);

  state.messageEls.set(msg.id, { contentEl: content, reasoningEl: reasoningPre, attachmentsEl, tokenEl: tokenInfoEl });
  els.chatList.appendChild(wrap);
  syncMessageTimingsUi(msg.id);
  maybeScrollToBottom();
}

function computeToolStatusText(msg) {
  const status = (msg && msg.tool_status != null) ? String(msg.tool_status) : '';
  if (status === 'pending') return t('page.chat.completion.tool.status.pending', '执行中');
  if (status === 'cancelled') return t('page.chat.completion.tool.status.cancelled', '已取消');
  if (msg && msg.is_error) return t('page.chat.completion.tool.status.failed', '失败');
  return t('page.chat.completion.tool.status.result', '结果');
}

function syncToolMessageMeta(id) {
  const key = String(id || '');
  if (!key) return;
  const msg = getMessageById(key);
  if (!msg || msg.role !== 'tool') return;
  const wrap = els.chatList ? els.chatList.querySelector('.msg.tool[data-id="' + key + '"]') : null;
  if (!wrap) return;

  const summary = wrap.querySelector('details.tool-result > summary');
  if (summary) {
    const toolName = (msg.tool_name != null) ? String(msg.tool_name) : t('page.chat.completion.role.tool', 'Tool');
    summary.textContent = toolName + t('page.chat.completion.punctuation.colon', '：') + computeToolStatusText(msg);
  }

  const reqPre = wrap.querySelector('pre.tool-io-request');
  if (reqPre) {
    const argsText = (msg.tool_arguments != null) ? String(msg.tool_arguments) : '';
    reqPre.textContent = String(argsText).trim() ? argsText : t('page.chat.completion.tool.empty_paren', '(空)');
  }
}

function buildAvatarUrl(charactorId) {
  const id = (charactorId == null ? '' : String(charactorId)).trim();
  if (!id) return '';
  const q = 'name=' + encodeURIComponent(id) + '&t=' + encodeURIComponent(String(state.avatarNonce || 0));
  return '/api/chat/completion/avatar/get?' + q;
}

function viewAvatar(charactorId) {
  const url = buildAvatarUrl(charactorId);
  if (!url) return;
  openAvatarViewer(url);
}

let avatarViewerOverlay = null;
let avatarViewerKeydown = null;
let avatarViewerResize = null;
let avatarViewerUrl = '';
let avatarViewerMode = '';

function closeAvatarViewer() {
  if (!avatarViewerOverlay) return;
  try {
    if (avatarViewerKeydown) document.removeEventListener('keydown', avatarViewerKeydown);
  } catch (e) { }
  try {
    if (avatarViewerResize) window.removeEventListener('resize', avatarViewerResize);
  } catch (e) { }
  try { avatarViewerOverlay.remove(); } catch (e) { }
  avatarViewerOverlay = null;
  avatarViewerKeydown = null;
  avatarViewerResize = null;
  avatarViewerUrl = '';
  avatarViewerMode = '';
}

function computeAvatarViewerPlacement() {
  const mainEl = document.querySelector('.main');
  const shellEl = document.querySelector('.chat-shell');
  if (!mainEl || !shellEl) return { mode: 'center' };
  const mainRect = mainEl.getBoundingClientRect();
  const shellRect = shellEl.getBoundingClientRect();
  if (!mainRect || !shellRect) return { mode: 'center' };

  const leftSpace = shellRect.left - mainRect.left;
  const rightSpace = mainRect.right - shellRect.right;
  const shellW = shellRect.width;
  const mainW = mainRect.width;

  const wideGap = (mainW > 0 && shellW > 0) ? (shellW <= mainW * 0.78) : false;
  const leftEnough = leftSpace >= 180;
  if (!wideGap || !leftEnough) return { mode: 'center' };

  const pad = 12;
  let left = mainRect.left + pad;
  let top = shellRect.top;
  let width = Math.max(0, leftSpace - pad * 2);
  let height = Math.max(0, shellRect.height);

  const vw = window.innerWidth || 0;
  const vh = window.innerHeight || 0;
  if (width < 160 || height < 160 || vw <= 0 || vh <= 0) return { mode: 'center' };

  left = Math.max(0, Math.min(left, vw - 1));
  top = Math.max(0, Math.min(top, vh - 1));
  width = Math.max(1, Math.min(width, vw - left));
  height = Math.max(1, Math.min(height, vh - top));
  if (width < 160 || height < 160) return { mode: 'center' };

  return { mode: 'left', left, top, width, height };
}

function openAvatarViewer(url) {
  if (!url) return;
  closeAvatarViewer();

  const initialPlacement = computeAvatarViewerPlacement();
  const initialMode = (initialPlacement && initialPlacement.mode === 'left') ? 'left' : 'center';
  avatarViewerUrl = url;
  avatarViewerMode = initialMode;

  const overlay = document.createElement('div');
  overlay.style.position = 'fixed';
  overlay.style.zIndex = '9999';
  if (initialMode === 'center') {
    overlay.style.inset = '0';
    overlay.style.background = 'rgba(0, 0, 0, 0.55)';
    overlay.style.backdropFilter = 'blur(6px)';
    overlay.style.cursor = 'zoom-out';
  } else {
    overlay.style.left = '0';
    overlay.style.top = '0';
    overlay.style.width = '0';
    overlay.style.height = '0';
    overlay.style.pointerEvents = 'none';
  }

  const box = document.createElement('div');
  box.style.position = 'fixed';
  if (initialMode === 'center') {
    box.style.background = 'rgba(255, 255, 255, 0.95)';
    box.style.border = '1px solid var(--border)';
    box.style.borderRadius = '14px';
    box.style.boxShadow = 'var(--shadow)';
  } else {
    box.style.background = 'transparent';
    box.style.border = 'none';
    box.style.borderRadius = '0';
    box.style.boxShadow = 'none';
  }
  box.style.overflow = 'hidden';
  box.style.cursor = 'default';

  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.textContent = '×';
  closeBtn.style.position = 'absolute';
  closeBtn.style.right = '8px';
  closeBtn.style.top = '8px';
  closeBtn.style.width = '34px';
  closeBtn.style.height = '34px';
  closeBtn.style.borderRadius = '12px';
  closeBtn.style.border = '1px solid var(--border)';
  closeBtn.style.background = '#ffffff';
  closeBtn.style.color = 'var(--text)';
  closeBtn.style.cursor = 'pointer';
  closeBtn.style.lineHeight = '1';
  closeBtn.style.fontSize = '20px';
  closeBtn.style.display = initialMode === 'center' ? 'none' : 'inline-flex';
  closeBtn.style.alignItems = 'center';
  closeBtn.style.justifyContent = 'center';
  closeBtn.style.pointerEvents = 'auto';
  closeBtn.addEventListener('click', (e) => {
    if (e) e.stopPropagation();
    closeAvatarViewer();
  });

  const img = document.createElement('img');
  img.alt = 'avatar';
  img.decoding = 'async';
  img.loading = 'eager';
  img.style.width = '100%';
  img.style.height = '100%';
  img.style.objectFit = 'contain';
  img.style.display = 'block';
  img.style.cursor = 'pointer';
  img.addEventListener('click', (e) => {
    if (e) e.stopPropagation();
    closeAvatarViewer();
  });

  function layout() {
    const p = computeAvatarViewerPlacement();
    const nextMode = (p && p.mode === 'left') ? 'left' : 'center';
    if (nextMode !== avatarViewerMode) {
      const u = avatarViewerUrl;
      closeAvatarViewer();
      openAvatarViewer(u);
      return;
    }
    if (nextMode === 'left' && p) {
      box.style.left = p.left + 'px';
      box.style.top = p.top + 'px';
      box.style.width = p.width + 'px';
      box.style.height = p.height + 'px';
    } else {
      const vw = window.innerWidth || 0;
      const vh = window.innerHeight || 0;
      const w = Math.max(1, Math.floor(vw * 0.92));
      const h = Math.max(1, Math.floor(vh * 0.92));
      const left = Math.floor((vw - w) / 2);
      const top = Math.floor((vh - h) / 2);
      box.style.left = left + 'px';
      box.style.top = top + 'px';
      box.style.width = w + 'px';
      box.style.height = h + 'px';
    }
  }

  box.addEventListener('click', (e) => {
    e.stopPropagation();
  });
  if (initialMode === 'center') {
    overlay.addEventListener('click', () => closeAvatarViewer());
  }

  avatarViewerKeydown = (e) => {
    if (e && e.key === 'Escape') closeAvatarViewer();
  };
  document.addEventListener('keydown', avatarViewerKeydown);

  avatarViewerResize = () => layout();
  window.addEventListener('resize', avatarViewerResize);

  if (initialMode === 'left') {
    box.style.pointerEvents = 'auto';
  }

  box.appendChild(img);
  box.appendChild(closeBtn);
  overlay.appendChild(box);
  document.body.appendChild(overlay);
  avatarViewerOverlay = overlay;

  layout();
  img.src = url;
}

function applyAssistantAvatar(avatarEl) {
  if (!avatarEl) return;
  const charactorId = state.currentCompletionId;
  if (!charactorId) return;
  const url = buildAvatarUrl(charactorId);
  if (!url) return;

  avatarEl.textContent = 'AI';
  for (const n of Array.from(avatarEl.querySelectorAll('img'))) {
    try { n.remove(); } catch (e) { }
  }

  const img = document.createElement('img');
  img.alt = 'avatar';
  img.decoding = 'async';
  img.loading = 'eager';
  img.style.display = 'none';
  img.addEventListener('load', () => {
    avatarEl.textContent = '';
    img.style.display = 'block';
    avatarEl.appendChild(img);
  });
  img.addEventListener('error', () => {
    try { img.remove(); } catch (e) { }
    avatarEl.textContent = 'AI';
  });
  avatarEl.appendChild(img);
  img.src = url;
}

let htmlPreviewOverlay = null;
let htmlPreviewKeydown = null;

function closeHtmlPreview() {
  try { if (htmlPreviewKeydown) document.removeEventListener('keydown', htmlPreviewKeydown); } catch (e) { }
  try { if (htmlPreviewOverlay) htmlPreviewOverlay.remove(); } catch (e) { }
  htmlPreviewOverlay = null;
  htmlPreviewKeydown = null;
}

function openHtmlPreview(html) {
  closeHtmlPreview();
  const overlay = document.createElement('div');
  overlay.style.position = 'fixed';
  overlay.style.inset = '0';
  overlay.style.zIndex = '9999';
  overlay.style.background = 'rgba(0, 0, 0, 0.55)';
  overlay.style.backdropFilter = 'blur(6px)';
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) closeHtmlPreview();
  });
  const card = document.createElement('div');
  card.style.position = 'fixed';
  card.style.inset = '0';
  card.style.display = 'flex';
  card.style.flexDirection = 'column';
  card.style.border = 'none';
  card.style.borderRadius = '0';
  card.style.background = '#ffffff';
  card.style.boxShadow = 'none';
  const head = document.createElement('div');
  head.style.display = 'flex';
  head.style.alignItems = 'center';
  head.style.justifyContent = 'space-between';
  head.style.gap = '10px';
  head.style.padding = '10px 12px';
  head.style.borderBottom = '1px solid var(--border)';
  head.style.background = '#f9fafb';
  const title = document.createElement('div');
  title.textContent = t('page.chat.completion.modal.html_preview.title', 'HTML预览');
  title.style.fontWeight = '800';
  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.textContent = '×';
  closeBtn.className = 'btn icon-btn';
  closeBtn.addEventListener('click', () => closeHtmlPreview());
  head.appendChild(title);
  head.appendChild(closeBtn);
  const body = document.createElement('div');
  body.style.flex = '1 1 auto';
  body.style.overflow = 'hidden';
  const frame = document.createElement('iframe');
  frame.setAttribute('sandbox', 'allow-scripts allow-forms allow-popups');
  frame.style.border = 'none';
  frame.style.width = '100%';
  frame.style.height = '100%';
  card.appendChild(head);
  body.appendChild(frame);
  card.appendChild(body);
  overlay.appendChild(card);
  document.body.appendChild(overlay);
  htmlPreviewOverlay = overlay;
  htmlPreviewKeydown = (e) => {
    if (e && e.key === 'Escape') closeHtmlPreview();
  };
  document.addEventListener('keydown', htmlPreviewKeydown);
  setTimeout(() => {
    try { frame.srcdoc = html; } catch (e) { }
  }, 0);
}

let avatarUploadInput = null;

function getAvatarUploadInput() {
  if (avatarUploadInput) return avatarUploadInput;
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = 'image/*';
  input.style.position = 'fixed';
  input.style.left = '-9999px';
  input.style.top = '-9999px';
  input.style.width = '1px';
  input.style.height = '1px';
  input.addEventListener('change', async (e) => {
    const file = e && e.target && e.target.files ? e.target.files[0] : null;
    input.value = '';
    if (!file) return;
    try {
      await uploadAvatarForCurrentRole(file);
    } catch (err) {
      const msg = (err && err.message ? err.message : String(err));
      alert(tf('page.chat.completion.avatar.upload_failed', { message: msg }, '头像上传失败：{message}'));
    }
  });
  document.body.appendChild(input);
  avatarUploadInput = input;
  return input;
}

async function uploadAvatarForCurrentRole(file) {
  if (!file) return;
  const maxBytes = 1024 * 1024;
  if (typeof file.size === 'number' && file.size > maxBytes) {
    throw new Error(t('page.chat.completion.avatar.size_limit', '头像图片需小于1MB'));
  }
  const allow = ['png', 'jpg', 'jpeg', 'gif', 'webp'];
  const ext = getFileExt(file && file.name ? file.name : '');
  const type = (file && file.type) ? String(file.type).toLowerCase() : '';
  const typeOk = type === 'image/png' || type === 'image/jpeg' || type === 'image/gif' || type === 'image/webp';
  const extOk = allow.includes(ext);
  if (!typeOk && !extOk) {
    throw new Error(tf('page.chat.completion.avatar.type_limit', { types: 'png/jpg/jpeg/gif/webp' }, '仅支持图片格式: {types}'));
  }
  const id = state.currentCompletionId;
  if (!id) {
    throw new Error(t('page.chat.completion.avatar.missing_role_id', '缺少角色ID'));
  }
  const fd = new FormData();
  fd.append('file', file, file.name || 'avatar');
  await fetchJson('/api/chat/completion/avatar/upload?name=' + encodeURIComponent(String(id)), {
    method: 'POST',
    body: fd
  });
  state.avatarNonce = Date.now();
  if (els.avatarSettingPreview) applyAssistantAvatar(els.avatarSettingPreview);
  rerenderAll();
}

function clearChatDom() {
  els.chatList.innerHTML = '';
  state.messageEls.clear();
}

function rerenderAll() {
  clearChatDom();
  for (const m of getRenderableMessages()) {
    if (m && m.hidden) continue;
    renderMessage(m);
  }
  maybeScrollToBottom();
}

function addMessage(role, content, extra) {
  const msg = { id: uid(), role, content: content || '', ts: Date.now(), order: nextMessageOrder() };
  if (extra && typeof extra === 'object') Object.assign(msg, extra);
  if (role === 'assistant') {
    applyAssistantVersionToMessage(msg, msg.activeVersionIndex);
    syncAssistantMessageVersionFromTopLevel(msg);
  }
  state.messages.push(msg);
  renderMessage(msg);
  return msg;
}

function addHiddenMessage(role, content, extra) {
  const msg = { id: uid(), role, content: content || '', ts: Date.now(), hidden: true, order: nextMessageOrder() };
  if (extra && typeof extra === 'object') Object.assign(msg, extra);
  if (role === 'assistant') {
    applyAssistantVersionToMessage(msg, msg.activeVersionIndex);
    syncAssistantMessageVersionFromTopLevel(msg);
  }
  state.messages.push(msg);
  return msg;
}

function addSystemLog(content, extra) {
  const msg = { id: uid(), role: 'system', content: content || '', ts: Date.now(), isSystemLog: true, order: nextMessageOrder() };
  if (extra && typeof extra === 'object') Object.assign(msg, extra);
  state.systemLogs.push(msg);
  renderMessage(msg);
  return msg;
}

function updateMessage(id, content) {
  const entry = upsertMessageDomEntry(id);
  const el = entry && entry.contentEl ? entry.contentEl : null;
  const m = getMessageById(id);
  if (m) m.content = content || '';
  if (m && m.role === 'assistant') syncAssistantMessageVersionFromTopLevel(m);
  const displayText = (m && typeof m.uiContent === 'string') ? m.uiContent : (content || '');
  if (el) requestRenderMessageContent(el, displayText);
  syncToolMessageMeta(id);
  syncHtmlPreviewAction(id);
  syncMessageTimingsUi(id);
  maybeScrollToBottom();
}

function setMessageUiContent(id, uiText) {
  const entry = upsertMessageDomEntry(id);
  const el = entry && entry.contentEl ? entry.contentEl : null;
  const m = getMessageById(id);
  if (m) m.uiContent = (uiText == null ? '' : String(uiText));
  if (m && m.role === 'assistant') syncAssistantMessageVersionFromTopLevel(m);
  if (el) requestRenderMessageContent(el, (uiText == null ? '' : String(uiText)));
  syncToolMessageMeta(id);
  syncHtmlPreviewAction(id);
  syncMessageTimingsUi(id);
  maybeScrollToBottom();
}

function setMessageUiAndContent(id, uiText, contentText) {
  const m = getMessageById(id);
  if (m) {
    m.content = contentText || '';
    m.uiContent = (uiText == null ? '' : String(uiText));
    if (m.role === 'assistant') syncAssistantMessageVersionFromTopLevel(m);
  }
  setMessageUiContent(id, uiText);
}

function setMessageToolCalls(id, toolCalls) {
  const m = getMessageById(id);
  if (!m) return;
  m.tool_calls = (Array.isArray(toolCalls) ? toolCalls : []);
  if (m.role === 'assistant') syncAssistantMessageVersionFromTopLevel(m);
  syncMessageTimingsUi(id);
}

function updateAttachments(id) {
  const m = getMessageById(id);
  if (!m) return;
  const entry = upsertMessageDomEntry(id);
  const attachmentsEl = entry && entry.attachmentsEl ? entry.attachmentsEl : null;
  if (!attachmentsEl) return;
  renderMessageAttachments(attachmentsEl, m);
  maybeScrollToBottom();
}

let reasoningRaf = 0;
const pendingReasoningRenders = new Map();

function requestRenderReasoning(pre, text) {
  if (!pre) return;
  pendingReasoningRenders.set(pre, text);
  if (reasoningRaf) return;
  reasoningRaf = requestAnimationFrame(() => {
    reasoningRaf = 0;
    for (const [node, value] of pendingReasoningRenders.entries()) {
      node.textContent = value;
    }
    pendingReasoningRenders.clear();
  });
}

function updateReasoning(id, reasoning) {
  const entry = upsertMessageDomEntry(id);
  const pre = entry && entry.reasoningEl ? entry.reasoningEl : null;
  const reasoningText = (reasoning == null ? '' : String(reasoning));
  if (pre) {
    requestRenderReasoning(pre, reasoningText);
  } else if (reasoningText.trim()) {
    const wrap = els.chatList.querySelector('.msg.assistant[data-id="' + id + '"]');
    const bubble = wrap ? wrap.querySelector('.bubble') : null;
    if (bubble) {
      const details = document.createElement('details');
      details.className = 'reasoning';
      const summary = document.createElement('summary');
      summary.textContent = t('page.chat.completion.action.thinking_details', '思考过程');
      const newPre = document.createElement('pre');
      newPre.textContent = reasoningText;
      details.appendChild(summary);
      details.appendChild(newPre);
      bubble.insertBefore(details, bubble.firstChild);
      upsertMessageDomEntry(id);
      const next = state.messageEls.get(String(id || '')) || {};
      state.messageEls.set(String(id || ''), {
        contentEl: next.contentEl || bubble.querySelector('.content'),
        reasoningEl: newPre,
        attachmentsEl: next.attachmentsEl || bubble.querySelector('.attachments'),
        tokenEl: next.tokenEl || bubble.querySelector('.token-info')
      });
    }
  }
  const m = getMessageById(id);
  if (m) m.reasoning = reasoningText;
  if (m && m.role === 'assistant') syncAssistantMessageVersionFromTopLevel(m);
  maybeScrollToBottom();
}

