// 会话抽屉相关元素
const els = {
  sessionsToggle: document.getElementById('sessionsToggle'),
  drawer: document.getElementById('sessionsDrawer'),
  backdrop: document.getElementById('drawerBackdrop'),
  drawerClose: document.getElementById('drawerClose'),
  sessionsList: document.getElementById('sessionsList'),
  newSessionBtn: document.getElementById('newSessionBtn'),
  reloadSessionsBtn: document.getElementById('reloadSessionsBtn'),
  drawerHint: document.getElementById('drawerHint'),

  topicToggle: document.getElementById('topicToggle'),
  topicOverlay: document.getElementById('topicOverlay'),
  topicBackdrop: document.getElementById('topicBackdrop'),
  topicOverlayClose: document.getElementById('topicOverlayClose'),
  topicModal: document.getElementById('topicModal'),
  topicModalClose: document.getElementById('topicModalClose'),

  titleInput: document.getElementById('titleInput'),
  chatList: document.getElementById('chatList'),
  promptInput: document.getElementById('promptInput'),
  modelRowToggle: document.getElementById('modelRowToggle'),
  modelRow: document.querySelector('.model-row'),
  modelRowBackdrop: document.getElementById('modelRowBackdrop'),

  modelSelect: document.getElementById('modelSelect'),
  refreshModels: document.getElementById('refreshModels'),

  attachBtn: document.getElementById('attachBtn'),
  attachInput: document.getElementById('attachInput'),
  attachInfo: document.getElementById('attachInfo'),
  attachName: document.getElementById('attachName'),
  attachClear: document.getElementById('attachClear'),

  sendBtn: document.getElementById('sendBtn'),
  stopBtn: document.getElementById('stopBtn'),
  clearChat: document.getElementById('clearChat'),

  toggleSettings: document.getElementById('toggleSettings'),
  settingsPanel: document.getElementById('settingsPanel'),
  closeSettings: document.getElementById('closeSettings'),
  sysPromptBox: document.getElementById('sysPromptBox'),
  systemPrompt: document.getElementById('systemPrompt'),
  rolePromptBox: document.getElementById('rolePromptBox'),
  rolePrompt: document.getElementById('rolePrompt'),
  refreshMcpTools: document.getElementById('refreshMcpTools'),
  mcpToolsStatus: document.getElementById('mcpToolsStatus'),
  mcpToolsList: document.getElementById('mcpToolsList'),

  maxTokens: document.getElementById('maxTokens'),
  temperature: document.getElementById('temperature'),
  topP: document.getElementById('topP'),
  minP: document.getElementById('minP'),
  repeatPenalty: document.getElementById('repeatPenalty'),
  stopSequences: document.getElementById('stopSequences'),
  userName: document.getElementById('userName'),
  userPrefix: document.getElementById('userPrefix'),
  userSuffix: document.getElementById('userSuffix'),
  assistantPrefix: document.getElementById('assistantPrefix'),
  assistantSuffix: document.getElementById('assistantSuffix'),
  apiModelSelect: document.getElementById('apiModelSelect'),
  avatarSettingPreview: document.getElementById('avatarSettingPreview'),
  avatarSettingUpload: document.getElementById('avatarSettingUpload'),

  topicList: document.getElementById('topicList'),
  newTopicBtn: document.getElementById('newTopicBtn'),

  autoScroll: document.getElementById('autoScroll'),
  streamToggle: document.getElementById('streamToggle'),
  thinkingToggle: document.getElementById('thinkingToggle'),
  webSearchToggle: document.getElementById('webSearchToggle'),
  saveHint: document.getElementById('saveHint'),

  kvCacheToggle: document.getElementById('kvCacheToggle'),
  kvCacheModal: document.getElementById('kvCacheModal'),
  kvCacheClose: document.getElementById('kvCacheClose'),
  kvCacheCancel: document.getElementById('kvCacheCancel'),
  saveKV: document.getElementById('saveKV'),
  loadKV: document.getElementById('loadKV'),

  mcpToolsToggle: document.getElementById('mcpToolsToggle'),
  mcpToolsModal: document.getElementById('mcpToolsModal'),
  mcpToolsClose: document.getElementById('mcpToolsClose'),
  mcpToolsDone: document.getElementById('mcpToolsDone'),

  editModal: document.getElementById('editModal'),
  editTextarea: document.getElementById('editTextarea'),
  editClose: document.getElementById('editClose'),
  editCancel: document.getElementById('editCancel'),
  editSave: document.getElementById('editSave')
};

// 会话抽屉状态
const state = {
  currentCompletionId: null,
  currentCreatedAt: 0,
  isLoadingCompletions: false,
  isLoadingModels: false,
  abortController: null,
  saveTimer: null,
  lastSavedAt: 0,
  saveHintText: '',
  statusText: '',
  messages: [],
  systemLogs: [],
  timingsLog: [],
  activeTopicId: null,
  topics: [],
  topicData: {},
  messageEls: new Map(),
  editingMessageId: null,
  msgSeq: 0,
  // 用/v1/chat/completion还是/v1/completions
  // 值为1时，用/v1/chat/completion
  // 值为0时，用/v1/completions
  apiModel: 1,
  pendingAttachment: null,
  avatarNonce: 0,
  mcpToolsData: null,
  enabledMcpTools: []
};

function uid() {
  if (window.crypto?.randomUUID) return crypto.randomUUID();
  return String(Date.now()) + '-' + String(Math.random()).slice(2);
}

function nextMessageOrder() {
  state.msgSeq = (Number.isFinite(state.msgSeq) ? state.msgSeq : 0) + 1;
  return state.msgSeq;
}

function topicId() {
  return 't-' + String(Date.now()) + '-' + String(Math.random()).slice(2, 8);
}

function normalizeTopicTitle(v) {
  const t = (v == null ? '' : String(v)).trim();
  return t || '未命名话题';
}

function persistActiveTopic() {
  const id = state.activeTopicId;
  if (!id) return;
  if (!state.topicData || typeof state.topicData !== 'object') state.topicData = {};
  state.topicData[id] = {
    history: Array.isArray(state.messages) ? state.messages : [],
    systemLogs: Array.isArray(state.systemLogs) ? state.systemLogs : [],
    timingsLog: Array.isArray(state.timingsLog) ? state.timingsLog : []
  };
  if (Array.isArray(state.topics)) {
    const t = state.topics.find(x => x && String(x.id) === String(id));
    if (t) t.updatedAt = Date.now();
  }
}

function setActiveTopic(topicIdToLoad, options) {
  const id = topicIdToLoad == null ? null : String(topicIdToLoad);
  if (!id) return;
  const opt = (options && typeof options === 'object') ? options : {};
  if (!opt.skipPersist) persistActiveTopic();
  state.activeTopicId = id;
  const data = (state.topicData && state.topicData[id]) ? state.topicData[id] : null;
  const historyAll = normalizeHistory(data?.history);
  const legacySystemLogs = historyAll.filter(m => m.role === 'system');
  state.messages = historyAll.filter(m => m.role !== 'system');
  state.systemLogs = normalizeSystemLogs(
    (Array.isArray(data?.systemLogs) && data.systemLogs.length) ? data.systemLogs : legacySystemLogs
  );
  state.timingsLog = Array.isArray(data?.timingsLog)
    ? data.timingsLog
    : (opt.fallbackTimings ? (Array.isArray(state.timingsLog) ? state.timingsLog : []) : []);
  syncMessageSequence();
  rerenderAll();
  renderTopics();
  if (!opt.skipSave) scheduleSave('切换话题');
}

function renderTopics() {
  if (!els.topicList) return;
  const topics = Array.isArray(state.topics) ? state.topics : [];
  els.topicList.innerHTML = '';
  if (!topics.length) {
    const div = document.createElement('div');
    div.className = 'hint';
    div.textContent = '暂无话题';
    els.topicList.appendChild(div);
    return;
  }
  for (const t of topics) {
    if (!t) continue;
    const id = t.id == null ? '' : String(t.id);
    const item = document.createElement('div');
    item.className = 'topic-item' + (id && id === String(state.activeTopicId || '') ? ' active' : '');
    item.dataset.id = id;
    const name = document.createElement('div');
    name.className = 'name';
    name.textContent = normalizeTopicTitle(t.title);
    const meta = document.createElement('div');
    meta.className = 'hint';
    meta.textContent = id && id === String(state.activeTopicId || '') ? '当前' : '';
    item.appendChild(name);
    item.appendChild(meta);
    item.addEventListener('click', () => {
      if (state.abortController) return;
      if (!id || id === String(state.activeTopicId || '')) return;
      setActiveTopic(id);
    });
    els.topicList.appendChild(item);
  }
}

