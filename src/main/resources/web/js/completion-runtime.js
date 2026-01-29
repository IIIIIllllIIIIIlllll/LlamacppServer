// 这里生成发送的消息
// 构造/v1/completions用的消息。
function buildPrompt(messages) {
  const lines = [];
  const sys = (els.systemPrompt.value || '').trim();
  if (sys) lines.push('System: ' + sys);
  // 插入换行符，不得删除
  lines.push('***');
  const userName = getUserSpeakerName();
  const assistantName = getAssistantSpeakerName();
  const userPrefix = getUserMessagePrefix();
  const userSuffix = getUserMessageSuffix();
  const assistantPrefix = getAssistantMessagePrefix();
  const assistantSuffix = getAssistantMessageSuffix();
  const history = Array.isArray(messages) ? messages : state.messages;
  for (const m of (Array.isArray(history) ? history : [])) {
    if (m && m.noContext) continue;
    if (m.role === 'system') {
      lines.push('System: ' + (m.content || ''));
    } else if (m.role === 'user') {
      let t = (m.content || '');
      const atts = Array.isArray(m.attachments) ? m.attachments : [];
      for (const a of atts) {
        if (a && a.url) {
          t += (t ? '\n' : '') + '[file] ' + String(a.url);
        }
      }
      lines.push(userName + ': ' + userPrefix + t + userSuffix);
    } else if (m.role === 'assistant') {
      lines.push(assistantName + ': ' + assistantPrefix + (m.content || '') + assistantSuffix);
    }
  }
  lines.push(assistantName + ': ' + assistantPrefix);
  return lines.join('\n');
}

// 给/v1/chat/completions端点构造请求内容
function buildContent(messages, options) {
  const history = Array.isArray(messages) ? messages : state.messages;
  const includeNoContext = !!(options && options.includeNoContext);
  const out = [];
  function pushText(role, text) {
    const v = (text == null ? '' : String(text));
    if (v) out.push({ role, content: v });
  }
  const sys = (els.systemPrompt.value || '').trim();
  const rolePrompt = (els.rolePrompt.value || '').trim();

  if (sys) out.push({ role: 'system', content: sys });
  if (rolePrompt) out.push({ role: 'system', content: rolePrompt });
  // 参考酒馆的提示词
  out.push({ role: 'system', content: '[Start a new Chat]' });

  const userPrefix = getUserMessagePrefix();
  const userSuffix = getUserMessageSuffix();
  // 实际上，这个东西就不生效了，不过先拼在里面看看吧
  const assistantPrefix = getAssistantMessagePrefix();
  const assistantSuffix = getAssistantMessageSuffix();
  // 遍历全部的历史消息，拼接为messages
  for (const m of (Array.isArray(history) ? history : [])) {
    if (m && m.noContext && !includeNoContext) continue;
    const role = m?.role;
    if (role !== 'system' && role !== 'user' && role !== 'assistant' && role !== 'tool') continue;
    const raw = (m?.content == null ? '' : String(m.content));
    if (role === 'system') {
      out.push({ role: 'system', content: raw });
    } else if (role === 'user') {
      pushText('user', userPrefix);
      const atts = Array.isArray(m?.attachments) ? m.attachments : [];
      if (atts.length > 0) {
        const parts = [];
        if (raw && raw.length) parts.push({ type: 'text', text: raw });
        for (const a of atts) {
          if (!a || !a.url) continue;
          parts.push({ type: 'file', text: String(a.url) });
        }
        out.push({ role: 'user', content: parts.length ? parts : raw });
      } else {
        out.push({ role: 'user', content: raw });
      }
      pushText('user', userSuffix);
    } else if (role === 'assistant') {
      const toolCalls = (Array.isArray(m?.tool_calls) ? m.tool_calls : null);
      if (toolCalls && toolCalls.length) {
        out.push({ role: 'assistant', content: raw, tool_calls: toolCalls });
      } else if (raw && raw.trim().length > 0) {
        pushText('assistant', assistantPrefix);
        out.push({ role: 'assistant', content: raw });
        pushText('assistant', assistantSuffix);
      }
    } else if (role === 'tool') {
      const toolCallId = (m?.tool_call_id == null ? '' : String(m.tool_call_id));
      out.push({ role: 'tool', content: raw, tool_call_id: toolCallId });
    }
  }
  return out;
}

function extractDeltaParts(json) {
  const c0 = json?.choices?.[0];
  if (!c0) return { content: '', reasoning: '' };
  let content = '';
  let reasoning = '';
  if (typeof c0.text === 'string') content = c0.text;
  if (typeof c0.delta?.content === 'string') content = c0.delta.content;
  if (typeof c0.message?.content === 'string') content = c0.message.content;
  if (typeof c0.delta?.reasoning_content === 'string') reasoning = c0.delta.reasoning_content;
  if (typeof c0.message?.reasoning_content === 'string') reasoning = c0.message.reasoning_content;
  if (typeof c0.reasoning_content === 'string') reasoning = c0.reasoning_content;
  if (typeof json?.reasoning_content === 'string') reasoning = json.reasoning_content;
  return { content, reasoning };
}

function getTimingsFromResponse(json) {
  const t = json?.timings;
  if (t && typeof t === 'object') return t;
  return null;
}

function normalizeTimingsNumber(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function formatTimingsText(timings) {
  if (!timings) return '';
  const cacheN = normalizeTimingsNumber(timings.cache_n) ?? 0;
  const promptN = normalizeTimingsNumber(timings.prompt_n) ?? 0;
  const predictedN = normalizeTimingsNumber(timings.predicted_n) ?? 0;
  const promptTotal = cacheN + promptN;
  const total = promptTotal + predictedN;
  if (!Number.isFinite(total) || total <= 0) return '';
  return `令牌统计：提示词 ${promptTotal}（缓存 ${cacheN} + 新增 ${promptN}），生成 ${predictedN}，合计 ${total}`;
}

function upsertTimingsLog(messageId, timings) {
  if (!messageId || !timings) return;
  state.timingsLog = Array.isArray(state.timingsLog) ? state.timingsLog : [];
  const id = String(messageId);
  for (let i = state.timingsLog.length - 1; i >= 0; i--) {
    const it = state.timingsLog[i];
    if (it && String(it.messageId) === id) {
      it.ts = Date.now();
      it.timings = timings;
      return;
    }
  }
  state.timingsLog.push({ messageId: id, ts: Date.now(), timings });
}

function getLatestTimingsFromLog(messageId) {
  const id = String(messageId || '');
  const list = Array.isArray(state.timingsLog) ? state.timingsLog : [];
  for (let i = list.length - 1; i >= 0; i--) {
    const it = list[i];
    if (it && String(it.messageId) === id && it.timings) return it.timings;
  }
  return null;
}

function shouldShowTimingsForMessage(m) {
  if (!m || m.role !== 'assistant') return false;
  if (Array.isArray(m.tool_calls) && m.tool_calls.length) return false;
  const displayText = (m && typeof m.uiContent === 'string') ? m.uiContent : (m && m.content != null ? String(m.content) : '');
  if (!String(displayText).trim()) return false;
  return true;
}

let timingsUiRaf = 0;
const pendingTimingsUiIds = new Set();

function syncMessageTimingsUiNow(messageId) {
  const id = String(messageId || '');
  if (!id) return;
  const m = getMessageById(id);
  const timings = (m && m.timings) ? m.timings : getLatestTimingsFromLog(id);

  const entry = upsertMessageDomEntry(id);
  const tokenEl = entry && entry.tokenEl ? entry.tokenEl : null;
  if (!tokenEl) return;

  const text = formatTimingsText(timings);
  const show = !!text && shouldShowTimingsForMessage(m);
  tokenEl.textContent = show ? text : '';
  tokenEl.style.display = show ? '' : 'none';
}

function syncMessageTimingsUi(messageId) {
  const id = String(messageId || '');
  if (!id) return;
  pendingTimingsUiIds.add(id);
  if (timingsUiRaf) return;
  timingsUiRaf = requestAnimationFrame(() => {
    timingsUiRaf = 0;
    for (const mid of pendingTimingsUiIds) {
      syncMessageTimingsUiNow(mid);
    }
    pendingTimingsUiIds.clear();
  });
}

function setMessageTimings(messageId, timings) {
  if (!messageId || !timings) return;
  const m = getMessageById(messageId);
  if (m) m.timings = timings;
  upsertTimingsLog(messageId, timings);
  syncMessageTimingsUi(messageId);
}

async function consumeSseStream(res, assistantMsgId) {
  const reader = res.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let text = '';
  let reasoning = '';
  let toolCallsAgg = [];
  let latestTimings = null;
  let revealed = false;
  let gotDone = false;
  let sawAnyUsefulDelta = false;
  let rawErrorCandidate = '';

  function revealIfNeeded() {
    if (revealed) return;
    const m = getMessageById(assistantMsgId);
    if (m && m.hidden === true) {
      m.hidden = false;
      rerenderAll();
    }
    revealed = true;
  }

  function shouldRevealNow(parts, tcs) {
    const hasText = !!(parts && ((parts.content && String(parts.content).length) || (parts.reasoning && String(parts.reasoning).length)));
    const hasTools = Array.isArray(tcs) && tcs.length > 0;
    return hasText || hasTools;
  }
  function processSseLine(line) {
    if (!line) return;
    if (!line.startsWith('data:')) return;
    const data = line.startsWith('data: ') ? line.slice(6).trim() : line.slice(5).trim();
    if (data === '[DONE]') {
      gotDone = true;
      if (!revealed && (String(text).trim() || String(reasoning).trim() || (Array.isArray(toolCallsAgg) && toolCallsAgg.length))) {
        revealIfNeeded();
      }
      if (latestTimings) {
        setMessageTimings(assistantMsgId, latestTimings);
      }
      updateMessage(assistantMsgId, text);
      if (reasoning) updateReasoning(assistantMsgId, reasoning);
      return true;
    }
    try {
      const json = JSON.parse(data);
      if (json?.error) {
        const errMsg = (json?.error?.message != null ? String(json.error.message) : String(json.error));
        const err = new Error(errMsg || '请求失败');
        err.name = 'SseError';
        throw err;
      }
      const parts = extractDeltaParts(json);
      const tcs = getToolCallsFromResponse(json);
      const timings = getTimingsFromResponse(json);
      if (timings) latestTimings = timings;
      const useful = shouldRevealNow(parts, tcs);
      if (!revealed && useful) {
        revealIfNeeded();
      }
      if (useful) sawAnyUsefulDelta = true;
      if (Array.isArray(tcs) && tcs.length) {
        toolCallsAgg = mergeToolCallsDelta(toolCallsAgg, tcs);
        setMessageToolCalls(assistantMsgId, toolCallsAgg);
      }
      if (parts.reasoning) {
        reasoning += parts.reasoning;
        updateReasoning(assistantMsgId, reasoning);
      }
      if (parts.content) {
        text += parts.content;
        updateMessage(assistantMsgId, text);
      }
    } catch (e) {
      if (e && e.name === 'SseError') throw e;
      if (!sawAnyUsefulDelta && !rawErrorCandidate && data) rawErrorCandidate = String(data).slice(0, 800);
    }
    return false;
  }
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() || '';
    for (const line of lines) {
      const isDone = processSseLine(line);
      if (isDone) {
        return { content: text, reasoning, tool_calls: toolCallsAgg, timings: latestTimings };
      }
    }
  }
  if (buffer) {
    const tailLines = buffer.split(/\r?\n/);
    buffer = '';
    for (const line of tailLines) {
      const isDone = processSseLine(line);
      if (isDone) {
        return { content: text, reasoning, tool_calls: toolCallsAgg, timings: latestTimings };
      }
    }
  }
  if (!gotDone && !sawAnyUsefulDelta) {
    const err = new Error(rawErrorCandidate || '请求失败');
    err.name = 'SseError';
    throw err;
  }
  if (!revealed && (String(text).trim() || String(reasoning).trim() || (Array.isArray(toolCallsAgg) && toolCallsAgg.length))) {
    revealIfNeeded();
  }
  if (latestTimings) {
    setMessageTimings(assistantMsgId, latestTimings);
  }
  updateMessage(assistantMsgId, text);
  if (reasoning) updateReasoning(assistantMsgId, reasoning);
  return { content: text, reasoning, tool_calls: toolCallsAgg, timings: latestTimings };
}

function currentParams() {
  const rawMinP = String(els.minP.value || '').trim();
  const rawRepeatPenalty = String(els.repeatPenalty.value || '').trim();
  const stopLines = String(els.stopSequences.value || '')
    .split(/\r?\n/)
    .map(s => s.trim())
    .filter(Boolean);

  const min_p = rawMinP ? Number(rawMinP) : undefined;
  const repeat_penalty = rawRepeatPenalty ? Number(rawRepeatPenalty) : undefined;
  const stop = stopLines.length ? stopLines : undefined;

  return {
    max_tokens: Number(els.maxTokens.value || 1024),
    temperature: Number(els.temperature.value || 0.7),
    top_p: Number(els.topP.value || 1),
    min_p: (Number.isFinite(min_p) ? min_p : undefined),
    repeat_penalty: (Number.isFinite(repeat_penalty) ? repeat_penalty : undefined),
    stop
  };
}

function getWebSearchMcpTools(preparedQuery) {
  const q = (preparedQuery == null ? '' : String(preparedQuery)).trim();
  const prepared = q ? (q + '\n') : '';
  const test = 'key word';
  return [{
    type: 'function',
    function: {
      name: 'builtin_web_search',
      description: 'Web search tool for finding current information, news, and real-time data from the internet.\n\nThis tool has been configured with search parameters based on the conversation context:\n- Prepared queries: "' + test + '"\n\nYou can use this tool as-is to search with the prepared queries, or provide additionalContext to refine or replace the search terms.',
      parameters: {
        $schema: 'http://json-schema.org/draft-07/schema#',
        type: 'object',
        properties: {
          additionalContext: {
            description: 'Optional additional context, keywords, or specific focus to enhance the search',
            type: 'string'
          }
        },
        required: ['additionalContext'],
        additionalProperties: false
      }
    }
  }];
}

function getEnabledMcpToolsForRequest() {
  const enabled = Array.isArray(state.enabledMcpTools) ? state.enabledMcpTools : [];
  if (!enabled.length) return [];
  const data = state?.mcpToolsData || {};
  let tools = Array.isArray(data?.tools) ? data.tools : [];
  if (!tools.length) {
    const servers = data?.servers;
    if (servers && typeof servers === 'object') {
      const merged = [];
      for (const server of Object.values(servers)) {
        const toolArr = Array.isArray(server?.tools) ? server.tools : [];
        for (const t of toolArr) merged.push(t);
      }
      tools = merged;
    }
  }
  if (!tools.length) return [];

  const toolByName = new Map();
  for (const t of tools) {
    const tn = normalizeToolName(t?.name);
    if (!tn) continue;
    if (!toolByName.has(tn)) toolByName.set(tn, t);
  }

  const out = [];
  const seen = new Set();
  for (const n of enabled) {
    const tn = normalizeToolName(n);
    if (!tn || seen.has(tn)) continue;
    const toolObj = toolByName.get(tn);
    if (!toolObj) continue;
    const def = buildOpenAiToolFromMcpTool(toolObj);
    if (!def?.function?.name) continue;
    out.push(def);
    seen.add(tn);
  }
  return out;
}

function buildOpenAiToolFromMcpTool(toolObj) {
  const toolName = normalizeToolName(toolObj?.name);
  const desc = (typeof toolObj?.description === 'string' ? toolObj.description : '').trim();
  const inputSchema = toolObj?.inputSchema;
  const parameters = (inputSchema && typeof inputSchema === 'object')
    ? inputSchema
    : {
      $schema: 'http://json-schema.org/draft-07/schema#',
      type: 'object',
      properties: {},
      additionalProperties: true
    };
  return {
    type: 'function',
    function: {
      name: toolName,
      description: desc || ('MCP tool: ' + toolName),
      parameters
    }
  };
}

function getPreparedQueryFromMessages(messages) {
  const history = Array.isArray(messages) ? messages : state.messages;
  for (let i = history.length - 1; i >= 0; i--) {
    const m = history[i];
    if (!m || m.role !== 'user') continue;
    const raw = (m.content == null ? '' : String(m.content)).trim();
    if (raw) return raw;
  }
  return '';
}

function getToolCallsFromResponse(json) {
  if (!json) return [];
  const direct = json?.tool_calls;
  if (Array.isArray(direct) && direct.length) return direct;
  const c0 = json?.choices?.[0];
  const msg = c0?.message;
  if (Array.isArray(msg?.tool_calls) && msg.tool_calls.length) return msg.tool_calls;
  const delta = c0?.delta;
  if (Array.isArray(delta?.tool_calls) && delta.tool_calls.length) return delta.tool_calls;
  return [];
}

function mergeToolCallsDelta(target, deltaToolCalls) {
  const out = Array.isArray(target) ? target : [];
  if (!Array.isArray(deltaToolCalls) || !deltaToolCalls.length) return out;
  for (let i = 0; i < deltaToolCalls.length; i++) {
    const d = deltaToolCalls[i];
    if (!d) continue;
    const idx = (Number.isFinite(d.index) ? d.index : i);
    if (!out[idx]) out[idx] = { id: '', type: d.type || 'function', function: { name: '', arguments: '' } };
    const cur = out[idx];
    if (typeof d.id === 'string' && d.id) cur.id = d.id;
    const fn = d.function || {};
    if (typeof fn.name === 'string' && fn.name) {
      cur.function = cur.function || {};
      cur.function.name = fn.name;
    }
    if (typeof fn.arguments === 'string' && fn.arguments) {
      cur.function = cur.function || {};
      cur.function.arguments = (cur.function.arguments || '') + fn.arguments;
    }
  }
  return out.filter(Boolean);
}

const TOOL_OUTPUT_CONTEXT_LIMIT = 20000;
const TOOL_OUTPUT_UI_LIMIT = 20000;

function buildTruncationNote(totalChars) {
  const n = Number.isFinite(totalChars) ? totalChars : Number(totalChars || 0);
  return '\n…(内容过长已截断，总长度 ' + (Number.isFinite(n) ? n : 0) + ' 字符)';
}

function truncateWithNote(text, limit, totalChars) {
  const s = (text == null ? '' : String(text));
  const lim = Number(limit || 0);
  if (!lim || lim <= 0) return '';
  if (s.length <= lim) return s;
  const note = buildTruncationNote(totalChars != null ? totalChars : s.length);
  const room = Math.max(0, lim - note.length);
  return s.slice(0, room) + note;
}

function normalizeToolOutputForMessage(toolText, uiText) {
  const raw = (toolText == null ? '' : String(toolText));
  const total = raw.length;
  const content = (total > TOOL_OUTPUT_CONTEXT_LIMIT) ? truncateWithNote(raw, TOOL_OUTPUT_CONTEXT_LIMIT, total) : raw;
  const uiRaw = (uiText == null ? '' : String(uiText));
  const uiTotal = uiRaw.length;
  const uiContent = (uiTotal > TOOL_OUTPUT_UI_LIMIT) ? truncateWithNote(uiRaw, TOOL_OUTPUT_UI_LIMIT, uiTotal) : uiRaw;
  return { content, uiContent, totalChars: total, uiTotalChars: uiTotal };
}

let toolExecuteQueue = Promise.resolve();

function enqueueToolExecute(work, options) {
  const opt = (options && typeof options === 'object') ? options : {};
  const signal = opt.signal || null;
  const run = () => {
    if (signal && signal.aborted) {
      const err = new Error('AbortError');
      err.name = 'AbortError';
      throw err;
    }
    return Promise.resolve().then(work);
  };
  const p = toolExecuteQueue.then(run, run);
  toolExecuteQueue = p.catch(() => { });
  return p;
}

async function executeToolCalls(toolCalls, preparedQuery) {
  const callList = (Array.isArray(toolCalls) ? toolCalls : []);
  const pending = [];
  const msgIdByToolCallId = new Map();
  const signal = state.toolAbortController ? state.toolAbortController.signal : null;

  for (const tc of callList) {
    const toolCallId = (typeof tc?.id === 'string' && tc.id) ? tc.id : uid();
    const fn = tc?.function || {};
    const name = (typeof fn?.name === 'string' ? fn.name : (typeof tc?.name === 'string' ? tc.name : ''));
    const args = (typeof fn?.arguments === 'string' ? fn.arguments : (typeof tc?.arguments === 'string' ? tc.arguments : ''));
    pending.push({ toolCallId, name, args });
    const msg = addMessage('tool', '', {
      tool_call_id: toolCallId,
      tool_name: name,
      tool_arguments: args,
      tool_status: 'pending',
      uiContent: '执行中…'
    });
    msgIdByToolCallId.set(toolCallId, msg.id);
  }

  if (pending.length) {
    await flushSave('工具请求');
  }

  const results = [];
  let hasError = false;
  function cancelAllToolMessages(fromIndex) {
    const start = Number.isFinite(fromIndex) ? fromIndex : 0;
    for (let i = start; i < pending.length; i++) {
      const tc = pending[i];
      const toolCallId = tc.toolCallId;
      const messageId = msgIdByToolCallId.get(toolCallId);
      if (!messageId) continue;
      const m = getMessageById(messageId);
      if (m) {
        m.tool_status = 'cancelled';
        m.noContext = true;
      }
      setMessageUiAndContent(messageId, '已取消', '');
    }
  }
  for (const tc of pending) {
    if (signal && signal.aborted) {
      cancelAllToolMessages(pending.indexOf(tc));
      const err = new Error('AbortError');
      err.name = 'AbortError';
      throw err;
    }
    const toolCallId = tc.toolCallId;
    const name = tc.name;
    const args = tc.args;
    const messageId = msgIdByToolCallId.get(toolCallId);
    try {
      const resp = await enqueueToolExecute(() => fetchJson('/api/tools/execute', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tool_name: name, arguments: args, preparedQuery }),
        signal
      }), { signal });
      if (resp && resp.success === false) {
        hasError = true;
        const errText = String(resp.error || '工具执行失败');
        const result = {
          tool_call_id: toolCallId,
          tool_name: name,
          tool_arguments: args,
          is_error: true,
          error: errText,
          content: JSON.stringify({ success: false, tool_name: name, error: errText })
        };
        results.push(result);
        if (messageId) {
          const normalized = normalizeToolOutputForMessage(String(result.content || ''), String(result.content || ''));
          const m = getMessageById(messageId);
          if (m) {
            m.is_error = true;
            m.tool_status = 'done';
          }
          setMessageUiAndContent(messageId, normalized.uiContent, normalized.content);
        }
        continue;
      }
      const out = resp?.data?.content;
      const outText = (out == null ? '' : String(out));
      let uiText = outText;
      if (!String(uiText).trim()) {
        try { uiText = JSON.stringify(resp); } catch (e) { uiText = outText; }
      }
      const result = { tool_call_id: toolCallId, tool_name: name, tool_arguments: args, is_error: false, content: outText, ui: uiText };
      results.push(result);
      if (messageId) {
        const normalized = normalizeToolOutputForMessage(String(result.content || ''), String(result.ui || result.content || ''));
        const m = getMessageById(messageId);
        if (m) {
          m.is_error = false;
          m.tool_status = 'done';
        }
        setMessageUiAndContent(messageId, normalized.uiContent, normalized.content);
      }
    } catch (e) {
      if ((e && e.name === 'AbortError') || (signal && signal.aborted)) {
        cancelAllToolMessages(pending.indexOf(tc));
        const err = new Error('AbortError');
        err.name = 'AbortError';
        throw err;
      }
      hasError = true;
      const errText = String(e && e.message ? e.message : e);
      const result = {
        tool_call_id: toolCallId,
        tool_name: name,
        tool_arguments: args,
        is_error: true,
        error: errText,
        content: JSON.stringify({ success: false, tool_name: name, error: errText })
      };
      results.push(result);
      if (messageId) {
        const normalized = normalizeToolOutputForMessage(String(result.content || ''), String(result.content || ''));
        const m = getMessageById(messageId);
        if (m) {
          m.is_error = true;
          m.tool_status = 'done';
        }
        setMessageUiAndContent(messageId, normalized.uiContent, normalized.content);
      }
    }
  }

  if (hasError) {
    for (const toolCallId of msgIdByToolCallId.keys()) {
      const messageId = msgIdByToolCallId.get(toolCallId);
      if (!messageId) continue;
      const m = getMessageById(messageId);
      if (m) m.noContext = true;
    }
  }

  return { results, hasError };
}

async function generateIntoMessage(contextMessages, assistantMsgId) {
  const model = els.modelSelect.value || '';
  const params = currentParams();
  state.abortController = new AbortController();
  state.toolAbortController = new AbortController();
  setStatus('生成中…');
  setBusyGenerating(true);
  setSaveHint('');
  let currentAssistantId = assistantMsgId;

  try {
    await maybeUploadPendingAttachment();
    const isChat = state.apiModel === 1;
    const useStream = !!els.streamToggle.checked;
    let allowTools = true;
    let toolRounds = 0;
    let includeNoContextInRequest = false;
    let stopAfterThisRequest = false;
    let pendingAssistantExtra = null;

    if (isChat && Array.isArray(state.enabledMcpTools) && state.enabledMcpTools.length && !state.mcpToolsData) {
      await refreshMcpTools();
    }

    while (true) {
      const url = isChat ? '/v1/chat/completions' : '/v1/completions';
      const stop = (params.stop != null
        ? params.stop
        : (isChat ? ["<|endoftext|>"] : ['\n' + getUserSpeakerName(), '\n***']));
      const body = isChat
        ? (() => {
          const preparedQuery = getPreparedQueryFromMessages(state.messages);
          const allTools = [];
          if (!!els.webSearchToggle.checked) {
            allTools.push(...getWebSearchMcpTools(preparedQuery));
          }
          allTools.push(...getEnabledMcpToolsForRequest());
          allowTools = allTools.length > 0;
          const b = {
            model,
            messages: buildContent(state.messages, { includeNoContext: includeNoContextInRequest }),
            max_tokens: params.max_tokens,
            temperature: params.temperature,
            top_p: params.top_p,
            min_p: params.min_p,
            repeat_penalty: params.repeat_penalty,
            enable_thinking: !!els.thinkingToggle.checked,
            stream: useStream,
            stop
          };
          if (allowTools) {
            b.tools = allTools;
            b.tool_choice = 'auto';
            b.parse_tool_calls = true;
          }
          return b;
        })()
        : {
          model,
          prompt: buildPrompt(state.messages),
          max_tokens: params.max_tokens,
          temperature: params.temperature,
          top_p: params.top_p,
          min_p: params.min_p,
          repeat_penalty: params.repeat_penalty,
          enable_thinking: !!els.thinkingToggle.checked,
          stream: useStream,
          stop
        };

      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: state.abortController.signal
      });

      if (!res.ok) {
        const t = await res.text();
        let msg = t;
        try {
          const j = t ? JSON.parse(t) : null;
          msg = j?.message || j?.error?.message || t;
          setStatus(msg);
        } catch (e) { }
        const errLine = 'HTTP ' + res.status + ' · ' + (msg || '');
        throw new Error(msg || ('HTTP ' + res.status));
      }

      const ct = (res.headers.get('content-type') || '').toLowerCase();
      let toolCalls = [];
      if (ct.includes('text/event-stream')) {
        if (!currentAssistantId) {
          const extra = (pendingAssistantExtra && typeof pendingAssistantExtra === 'object') ? pendingAssistantExtra : null;
          pendingAssistantExtra = null;
          const placeholder = addHiddenMessage('assistant', '', extra || undefined);
          currentAssistantId = placeholder.id;
        }
        const r = await consumeSseStream(res, currentAssistantId);
        toolCalls = r?.tool_calls || [];
      } else {
        if (!currentAssistantId) {
          const extra = (pendingAssistantExtra && typeof pendingAssistantExtra === 'object') ? pendingAssistantExtra : null;
          pendingAssistantExtra = null;
          const placeholder = addMessage('assistant', '', extra || undefined);
          currentAssistantId = placeholder.id;
        }
        const data = await res.json();
        const parts = extractDeltaParts(data);
        toolCalls = getToolCallsFromResponse(data);
        if (Array.isArray(toolCalls) && toolCalls.length) {
          setMessageToolCalls(currentAssistantId, toolCalls);
        }
        updateMessage(currentAssistantId, parts.content || '');
        if (parts.reasoning) updateReasoning(currentAssistantId, parts.reasoning);
        const timings = getTimingsFromResponse(data);
        if (timings) setMessageTimings(currentAssistantId, timings);
      }

      if (stopAfterThisRequest) {
        break;
      }

      if (isChat && Array.isArray(toolCalls) && toolCalls.length && toolRounds < 3) {
        setStatus('调用工具中…');
        setMessageToolCalls(currentAssistantId, toolCalls);
        const curMsg = getMessageById(currentAssistantId);
        const curText = (curMsg && curMsg.content != null) ? String(curMsg.content).trim() : '';
        if (!curText) {
          const toolNames = toolCalls
            .map(tc => (tc && tc.function && typeof tc.function.name === 'string') ? tc.function.name : (tc && typeof tc.name === 'string' ? tc.name : ''))
            .map(s => (s == null ? '' : String(s)).trim())
            .filter(Boolean)
            .join(', ');
          setMessageUiAndContent(currentAssistantId, toolNames ? ('调用工具：' + toolNames) : '调用工具', '');
        }
        const preparedQuery = getPreparedQueryFromMessages(state.messages);
        const r0 = await executeToolCalls(toolCalls, preparedQuery);
        const toolResults = r0?.results || [];
        const hasError = !!r0?.hasError;
        scheduleSave('工具');
        if (hasError) {
          const errTexts = toolResults.filter(x => x && x.is_error && x.error).map(x => String(x.error));
          const errText = errTexts.length ? errTexts.join('\n') : '工具调用失败';
          addSystemLog('工具调用失败：' + errText, { noContext: true });
          setMessageUiAndContent(currentAssistantId, '工具调用失败', '');
          currentAssistantId = null;
          pendingAssistantExtra = { noContext: true };
          toolRounds++;
          allowTools = false;
          includeNoContextInRequest = true;
          stopAfterThisRequest = true;
          setStatus('工具调用失败，继续生成说明…');
          continue;
        }
        currentAssistantId = null;
        pendingAssistantExtra = null;
        toolRounds++;
        setStatus('工具调用完成，继续生成…');
        continue;
      }

      break;
    }

    setStatus('完成');
    scheduleSave('完成');
  } catch (e) {
    if (currentAssistantId) {
      const m = getMessageById(currentAssistantId);
      const canDrop = m && m.role === 'assistant'
        && m.hidden === true
        && !String(m.content || '').trim()
        && !String(m.uiContent || '').trim()
        && !String(m.reasoning || '').trim()
        && !(Array.isArray(m.tool_calls) && m.tool_calls.length)
        && !(Array.isArray(m.attachments) && m.attachments.length);
      if (canDrop) removeMessageByIdSilently(currentAssistantId);
    }
    if (e.name === 'AbortError') {
      setStatus('已停止');
      await flushSave('停止');
    } else {
      setStatus('生成失败：' + e.message);
      addSystemLog('生成失败：' + e.message);
      await flushSave('失败');
    }
  } finally {
    setBusyGenerating(false);
    state.abortController = null;
    state.toolAbortController = null;
  }
}

async function regenerateMessage(messageId) {
  if (state.abortController) return;
  const idx = findMessageIndexById(messageId);
  if (idx < 0) return;
  const msg = state.messages[idx];
  if (msg.role === 'system') return;

  const hasLater = idx < state.messages.length - 1;
  if (hasLater) {
    if (!confirm('将删除该气泡之后的对话内容并从这里重新生成，继续？')) return;
  }

  {
    const cutoff = Number.isFinite(msg?.order) ? msg.order : (Number.isFinite(msg?.ts) ? msg.ts : 0);
    if (cutoff) {
      state.systemLogs = (Array.isArray(state.systemLogs) ? state.systemLogs : []).filter(m => {
        const o = Number.isFinite(m?.order) ? m.order : (Number.isFinite(m?.ts) ? m.ts : 0);
        return o <= cutoff;
      });
    }
  }

  if (msg.role === 'assistant') {
    const hasToolCalls = Array.isArray(msg.tool_calls) && msg.tool_calls.length > 0;
    if (hasToolCalls) {
      state.messages = state.messages.slice(0, idx);
      rerenderAll();
      if (!!els.streamToggle.checked) {
        await generateIntoMessage(state.messages, null);
      } else {
        const assistantMsg = addMessage('assistant', '');
        await generateIntoMessage(state.messages, assistantMsg.id);
      }
      scheduleSave('重生成');
      return;
    }

    state.messages = state.messages.slice(0, idx + 1);
    rerenderAll();
    updateMessage(msg.id, '');
    await generateIntoMessage(state.messages.slice(0, idx), msg.id);
    scheduleSave('重生成');
    return;
  }

  if (msg.role === 'user') {
    state.messages = state.messages.slice(0, idx + 1);
    rerenderAll();
    if (!!els.streamToggle.checked) {
      await generateIntoMessage(state.messages, null);
    } else {
      const assistantMsg = addMessage('assistant', '');
      await generateIntoMessage(state.messages, assistantMsg.id);
    }
    scheduleSave('重生成');
  }
}

function scheduleSave(reason) {
  if (!state.currentCompletionId) return;
  if (state.saveTimer) clearTimeout(state.saveTimer);
  const id = String(state.currentCompletionId);
  try {
    const payload = buildCompletionPayload();
    writeCompletionBackup(id, { id, updatedAt: payload.updatedAt, reason: (reason == null ? '' : String(reason)), payload });
  } catch (e) {
  }
  state.saveTimer = setTimeout(() => saveCompletionSafely(reason), 450);
}

async function saveCompletionSafely(reason) {
  if (!state.currentCompletionId) return;
  try {
    await saveCompletion(reason);
  } catch (e) {
    const errText = String(e && e.message ? e.message : e);
    setSaveHint('保存失败：' + errText);
    if (state.lastSaveErrorText !== errText) {
      state.lastSaveErrorText = errText;
      addSystemLog('保存失败：' + errText, { noContext: true });
    }
  }
}

async function flushSave(reason) {
  if (!state.currentCompletionId) return;
  if (state.saveTimer) clearTimeout(state.saveTimer);
  const id = String(state.currentCompletionId);
  try {
    const payload = buildCompletionPayload();
    writeCompletionBackup(id, { id, updatedAt: payload.updatedAt, reason: (reason == null ? '' : String(reason)), payload });
  } catch (e) {
  }
  await saveCompletionSafely(reason);
}

function buildParamsJson() {
  persistActiveTopic();
  return JSON.stringify({
    model: els.modelSelect.value || '',
    apiModel: state.apiModel,
    userName: (els.userName.value || '').trim(),
    userPrefix: (els.userPrefix.value == null ? '' : String(els.userPrefix.value)),
    userSuffix: (els.userSuffix.value == null ? '' : String(els.userSuffix.value)),
    assistantPrefix: (els.assistantPrefix.value == null ? '' : String(els.assistantPrefix.value)),
    assistantSuffix: (els.assistantSuffix.value == null ? '' : String(els.assistantSuffix.value)),
    enableThinking: !!els.thinkingToggle.checked,
    enableWebSearch: !!els.webSearchToggle.checked,
    enabledMcpTools: (Array.isArray(state.enabledMcpTools) ? state.enabledMcpTools : []),
    params: currentParams(),
    history: state.messages,
    systemLogs: state.systemLogs,
    activeTopicId: state.activeTopicId,
    topics: Array.isArray(state.topics) ? state.topics : [],
    topicData: (state.topicData && typeof state.topicData === 'object') ? state.topicData : {}
  });
}

function buildTimingsJson() {
  persistActiveTopic();
  return JSON.stringify(Array.isArray(state.timingsLog) ? state.timingsLog : []);
}

function buildCompletionPayload() {
  return {
    id: Number(state.currentCompletionId),
    title: els.titleInput.value || '',
    prompt: lzCompressIfNeeded(els.rolePrompt.value || ''),
    systemPrompt: lzCompressIfNeeded(els.systemPrompt.value || ''),
    paramsJson: lzCompressIfNeeded(buildParamsJson()),
    timingsJson: lzCompressIfNeeded(buildTimingsJson()),
    apiModel: state.apiModel,
    createdAt: Number(state.currentCreatedAt || 0),
    updatedAt: Date.now()
  };
}

async function saveCompletion(reason) {
  if (!state.currentCompletionId) return;
  const payload = buildCompletionPayload();
  setSaveHint('保存中…');
  await fetchJson('/api/chat/completion/save?name=' + encodeURIComponent(state.currentCompletionId), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  state.lastSavedAt = Date.now();
  setSaveHint((reason ? ('已保存（' + reason + '）') : '已保存') + ' · ' + new Date(state.lastSavedAt).toLocaleTimeString());
  clearCompletionBackup(state.currentCompletionId);
}

async function loadModels() {
  if (state.isLoadingModels) return;
  state.isLoadingModels = true;
  try {
    setStatus('加载模型…');
    els.refreshModels.disabled = true;
    const data = await fetchJson('/v1/models', { method: 'GET' });
    const models = Array.isArray(data?.data) ? data.data : [];
    //const models = data.data;
    const current = els.modelSelect.value;
    els.modelSelect.innerHTML = '';
    if (models.length === 0) {
      const opt = document.createElement('option');
      opt.value = '';
      opt.textContent = '未发现已加载模型';
      els.modelSelect.appendChild(opt);
      els.modelSelect.disabled = true;
    } else {
      els.modelSelect.disabled = false;
      for (const m of models) {
        const opt = document.createElement('option');
        opt.value = m.id;
        opt.textContent = m.id;
        els.modelSelect.appendChild(opt);
      }
      if (current && models.some(m => m.id === current)) {
        els.modelSelect.value = current;
      }
    }
    setStatus('就绪');
  } catch (e) {
    setStatus('模型加载失败：' + e.message);
  } finally {
    state.isLoadingModels = false;
    els.refreshModels.disabled = false;
  }
}

function renderCompletions(items, currentId) {
  if (!Array.isArray(items)) items = [];
  els.sessionsList.innerHTML = '';
  if (items.length === 0) {
    const li = document.createElement('li');
    li.className = 'session-item';
    li.innerHTML = '<div class="session-meta"><div class="session-title">暂无角色</div><div class="session-sub">点击“新建角色”创建角色</div></div>';
    li.style.cursor = 'default';
    els.sessionsList.appendChild(li);
    return;
  }
  for (const s of items) {
    const li = document.createElement('li');
    const id = s?.id == null ? '' : String(s.id);
    li.className = 'session-item' + (id && id === String(currentId || '') ? ' active' : '');
    li.dataset.id = id;
    const title = s?.title || ('角色 ' + id);
    const sub = (s?.updatedAt ? ('更新：' + formatTime(s.updatedAt)) : (s?.createdAt ? ('创建：' + formatTime(s.createdAt)) : ''));
    li.innerHTML = `
      <div class="session-meta">
        <div class="session-title"></div>
        <div class="session-sub"></div>
      </div>
      <button class="btn icon-btn danger" type="button" data-action="delete" title="删除">×</button>
    `;
    li.querySelector('.session-title').textContent = title;
    li.querySelector('.session-sub').textContent = sub;
    els.sessionsList.appendChild(li);
  }
}

async function loadCompletions(ensureCurrent) {
  setCompletionsLoading(true);
  try {
    const data = await fetchJson('/api/chat/completion/list', { method: 'GET' });
    const items = Array.isArray(data?.data) ? data.data : [];
    let current = state.currentCompletionId;
    if (!current && items[0]?.id != null) current = String(items[0].id);
    renderCompletions(items, current);
    if (ensureCurrent) {
      if (!current) {
        await createCompletion();
        return;
      }
      state.currentCompletionId = String(current);
      await loadCompletion(current);
    }
  } catch (e) {
    els.sessionsList.innerHTML = '';
    const li = document.createElement('li');
    li.className = 'session-item';
    li.innerHTML = '<div class="session-meta"><div class="session-title">加载失败</div><div class="session-sub"></div></div>';
    li.querySelector('.session-sub').textContent = e.message;
    li.style.cursor = 'default';
    els.sessionsList.appendChild(li);
  } finally {
    setCompletionsLoading(false);
  }
}

async function createCompletion() {
  setCompletionsLoading(true);
  try {
    const seedTitle = '默认角色-' + Date.now();
    const data = await fetchJson('/api/chat/completion/create', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: seedTitle })
    });
    const created = data?.data || {};
    if (created.id == null) throw new Error('新建失败：缺少角色ID');
    state.currentCompletionId = String(created.id);
    state.currentCreatedAt = Number(created.createdAt || 0);
    await loadCompletions(false);
    await loadCompletion(state.currentCompletionId);
  } catch (e) {
    els.drawerHint.textContent = '新建失败：' + e.message;
  } finally {
    setCompletionsLoading(false);
  }
}

async function deleteCompletion(id) {
  if (!id) return;
  if (!confirm('确认删除该角色？此操作不可撤销。')) return;
  setCompletionsLoading(true);
  try {
    await fetchJson('/api/chat/completion/delete?name=' + encodeURIComponent(id), { method: 'DELETE' });
    if (String(id) === String(state.currentCompletionId || '')) {
      state.currentCompletionId = null;
      state.currentCreatedAt = 0;
    }
    await loadCompletions(false);
    if (state.currentCompletionId) {
      await loadCompletion(state.currentCompletionId);
    } else {
      els.titleInput.value = '';
      els.systemPrompt.value = '';
      els.rolePrompt.value = '';
      state.messages = [];
      state.systemLogs = [];
      state.timingsLog = [];
      state.msgSeq = 0;
      rerenderAll();
    }
  } catch (e) {
    els.drawerHint.textContent = '删除失败：' + e.message;
  } finally {
    setCompletionsLoading(false);
  }
}

async function switchCompletion(id) {
  if (!id) return;
  if (String(id) === String(state.currentCompletionId || '')) return;
  setCompletionsLoading(true);
  try {
    const targetId = String(id);
    state.currentCompletionId = targetId;
    if (els.sessionsList && els.sessionsList.childNodes && els.sessionsList.childNodes.length) {
      for (const li of els.sessionsList.querySelectorAll('.session-item')) {
        const liId = li && li.dataset && li.dataset.id ? String(li.dataset.id) : '';
        li.classList.toggle('active', liId === targetId);
      }
    }
    await loadCompletion(state.currentCompletionId);
    closeDrawer();
  } catch (e) {
    els.drawerHint.textContent = '切换失败：' + e.message;
  } finally {
    setCompletionsLoading(false);
  }
}

function normalizeHistory(history) {
  if (!Array.isArray(history)) return [];
  const out = [];
  const toolCallById = new Map();
  for (const m of history) {
    const role = m?.role;
    if (role !== 'user' && role !== 'assistant' && role !== 'system' && role !== 'tool') continue;
    const toolCalls = Array.isArray(m?.tool_calls) ? m.tool_calls : null;
    if (role === 'assistant' && toolCalls && toolCalls.length) {
      for (const tc of toolCalls) {
        if (!tc) continue;
        const tcId = (typeof tc?.id === 'string' ? tc.id : '').trim();
        const fn = tc?.function || {};
        const tcName = (typeof fn?.name === 'string' ? fn.name : (typeof tc?.name === 'string' ? tc.name : '')).trim();
        const tcArgs = (typeof fn?.arguments === 'string' ? fn.arguments : (typeof tc?.arguments === 'string' ? tc.arguments : ''));
        if (!tcId) continue;
        const prev = toolCallById.get(tcId) || {};
        toolCallById.set(tcId, {
          tool_name: tcName || prev.tool_name || '',
          tool_arguments: (tcArgs != null ? String(tcArgs) : (prev.tool_arguments || ''))
        });
      }
    }
    const attachments = Array.isArray(m?.attachments)
      ? m.attachments
        .filter(a => a && typeof a.url === 'string' && a.url.trim() && (a.type === 'file' || a.type === 'image_url' || a.type === 'text_file' || a.type === 'file_url'))
        .map(a => {
          const t = String(a.type);
          if (t === 'file') {
            return { type: 'file', url: String(a.url), name: (a.name == null ? '' : String(a.name)), isImage: a.isImage === true };
          }
          if (t === 'image_url') {
            return { type: 'file', url: String(a.url), name: (a.name == null ? '' : String(a.name)), isImage: true };
          }
          return { type: 'file', url: String(a.url), name: (a.name == null ? '' : String(a.name)), isImage: false };
        })
      : [];
    const toolCallId = (typeof m?.tool_call_id === 'string' ? m.tool_call_id : undefined);
    let toolName = (typeof m?.tool_name === 'string' ? m.tool_name : undefined);
    let toolArguments = (typeof m?.tool_arguments === 'string' ? m.tool_arguments : undefined);
    if (role === 'tool' && toolCallId && (!toolName || !String(toolArguments || '').trim())) {
      const saved = toolCallById.get(toolCallId);
      if (saved) {
        if (!toolName && saved.tool_name) toolName = saved.tool_name;
        if (!String(toolArguments || '').trim() && saved.tool_arguments) toolArguments = saved.tool_arguments;
      }
    }
    out.push({
      id: m?.id || uid(),
      role,
      content: (m?.content == null ? '' : String(m.content)),
      attachments,
      reasoning: (typeof m?.reasoning === 'string' ? m.reasoning : ''),
      hidden: m?.hidden === true,
      uiContent: (typeof m?.uiContent === 'string' ? m.uiContent : undefined),
      noContext: m?.noContext === true,
      tool_call_id: toolCallId,
      tool_name: toolName,
      tool_arguments: toolArguments,
      is_error: m?.is_error === true,
      tool_status: (typeof m?.tool_status === 'string' ? m.tool_status : undefined),
      tool_calls: (toolCalls && toolCalls.length ? toolCalls : undefined),
      ts: typeof m?.ts === 'number' ? m.ts : Date.now(),
      order: typeof m?.order === 'number' ? m.order : undefined,
      isSystemLog: m?.isSystemLog === true
    });
  }
  return out;
}

function normalizeSystemLogs(history) {
  return normalizeHistory(history).filter(m => m.role === 'system').map(m => {
    m.isSystemLog = true;
    return m;
  });
}

function getRenderableMessages() {
  const all = [];
  for (const m of state.messages) {
    if (m && m.hidden) continue;
    all.push(m);
  }
  for (const m of state.systemLogs) {
    if (m && m.hidden) continue;
    all.push(m);
  }
  all.sort((a, b) => {
    const ao = Number.isFinite(a?.order) ? a.order : (Number.isFinite(a?.ts) ? a.ts : 0);
    const bo = Number.isFinite(b?.order) ? b.order : (Number.isFinite(b?.ts) ? b.ts : 0);
    if (ao !== bo) return ao - bo;
    const at = Number.isFinite(a?.ts) ? a.ts : 0;
    const bt = Number.isFinite(b?.ts) ? b.ts : 0;
    return at - bt;
  });
  return all;
}

function syncMessageSequence() {
  let max = 0;
  for (const m of state.messages.concat(state.systemLogs)) {
    const v = Number.isFinite(m?.order) ? m.order : (Number.isFinite(m?.ts) ? m.ts : 0);
    if (v > max) max = v;
  }
  state.msgSeq = max;
}

function applyCompletionData(s) {
  const data = (s && typeof s === 'object') ? s : {};
  els.titleInput.value = data.title || '';
  els.systemPrompt.value = lzDecompressIfNeeded(data.systemPrompt || '');
  els.rolePrompt.value = lzDecompressIfNeeded(data.prompt || '');
  state.currentCreatedAt = Number(data.createdAt || 0);
  {
    const timingsText = lzDecompressIfNeeded(data.timingsJson || '');
    let parsed = null;
    try { parsed = timingsText ? JSON.parse(timingsText) : null; } catch (e) { parsed = null; }
    if (Array.isArray(parsed)) state.timingsLog = parsed;
    else state.timingsLog = [];
  }

  let ext = {};
  const paramsJsonText = lzDecompressIfNeeded(data.paramsJson || '');
  try { ext = paramsJsonText ? JSON.parse(paramsJsonText) : {}; } catch (e) { ext = {}; }
  if (ext?.model) els.modelSelect.value = ext.model;
  els.userName.value = (ext?.userName == null ? '' : String(ext.userName));
  els.userPrefix.value = (ext?.userPrefix == null ? '' : String(ext.userPrefix));
  els.userSuffix.value = (ext?.userSuffix == null ? '' : String(ext.userSuffix));
  els.assistantPrefix.value = (ext?.assistantPrefix == null ? '' : String(ext.assistantPrefix));
  els.assistantSuffix.value = (ext?.assistantSuffix == null ? '' : String(ext.assistantSuffix));
  if (ext?.params?.max_tokens != null) els.maxTokens.value = String(ext.params.max_tokens);
  if (ext?.params?.temperature != null) els.temperature.value = String(ext.params.temperature);
  if (ext?.params?.top_p != null) els.topP.value = String(ext.params.top_p);
  els.minP.value = '';
  els.repeatPenalty.value = '';
  els.stopSequences.value = '';
  if (ext?.params?.min_p != null) els.minP.value = String(ext.params.min_p);
  if (ext?.params?.repeat_penalty != null) els.repeatPenalty.value = String(ext.params.repeat_penalty);
  const stopVal = ext?.params?.stop;
  if (Array.isArray(stopVal)) els.stopSequences.value = stopVal.map(s => String(s)).join('\n');
  else if (typeof stopVal === 'string') els.stopSequences.value = stopVal;
  if (ext?.enableThinking != null) els.thinkingToggle.checked = !!ext.enableThinking;
  else els.thinkingToggle.checked = true;
  if (ext?.enableWebSearch != null) els.webSearchToggle.checked = !!ext.enableWebSearch;
  else els.webSearchToggle.checked = false;

  if (Array.isArray(ext?.enabledMcpTools)) {
    state.enabledMcpTools = ext.enabledMcpTools.map(normalizeToolName).filter(Boolean);
  } else if (!Array.isArray(state.enabledMcpTools)) {
    state.enabledMcpTools = [];
  }
  if (state.mcpToolsData && els.mcpToolsList && els.mcpToolsList.childNodes && els.mcpToolsList.childNodes.length) {
    renderMcpTools(state.mcpToolsData);
  } else {
    updateMcpToolsStatus();
  }

  const loadedApiModel = (ext?.apiModel != null ? ext.apiModel : data.apiModel);
  if (loadedApiModel != null) {
    state.apiModel = Number(loadedApiModel) === 0 ? 0 : 1;
  }
  els.apiModelSelect.value = String(state.apiModel);

  const topicsFromExt = Array.isArray(ext?.topics) ? ext.topics : null;
  const topicDataFromExt = (ext?.topicData && typeof ext.topicData === 'object') ? ext.topicData : null;
  const activeTopicIdFromExt = ext?.activeTopicId == null ? null : String(ext.activeTopicId);
  let usedTopicData = false;
  if (topicsFromExt && topicsFromExt.length) {
    state.topics = topicsFromExt
      .map(t => t && typeof t === 'object' ? t : null)
      .filter(Boolean)
      .map(t => ({
        id: String(t.id || topicId()),
        title: normalizeTopicTitle(t.title),
        createdAt: (() => {
          const c = Number.isFinite(t.createdAt) ? t.createdAt : Number(t.createdAt || 0);
          const u = Number.isFinite(t.updatedAt) ? t.updatedAt : Number(t.updatedAt || 0);
          const base = c > 0 ? c : (u > 0 ? u : Date.now());
          return base;
        })(),
        updatedAt: (() => {
          const c = Number.isFinite(t.createdAt) ? t.createdAt : Number(t.createdAt || 0);
          const u = Number.isFinite(t.updatedAt) ? t.updatedAt : Number(t.updatedAt || 0);
          const base = u > 0 ? u : (c > 0 ? c : Date.now());
          return base;
        })()
      }));
    state.topicData = topicDataFromExt || {};
    const pick = (activeTopicIdFromExt && state.topics.some(t => String(t.id) === activeTopicIdFromExt))
      ? activeTopicIdFromExt
      : String(state.topics[0].id);
    state.activeTopicId = null;
    setActiveTopic(pick, { skipPersist: true, skipSave: true, fallbackTimings: true });
    usedTopicData = true;
  } else {
    const historyAll = normalizeHistory(ext?.history);
    const legacySystemLogs = historyAll.filter(m => m.role === 'system');
    const topic0 = topicId();
    state.topics = [{
      id: topic0,
      title: '默认话题',
      createdAt: Date.now(),
      updatedAt: Date.now()
    }];
    state.activeTopicId = topic0;
    state.topicData = {};
    state.messages = historyAll.filter(m => m.role !== 'system');
    state.systemLogs = normalizeSystemLogs((Array.isArray(ext?.systemLogs) && ext.systemLogs.length) ? ext.systemLogs : legacySystemLogs);
    state.topicData[topic0] = {
      history: state.messages,
      systemLogs: state.systemLogs,
      timingsLog: Array.isArray(state.timingsLog) ? state.timingsLog : []
    };
    renderTopics();
  }
  if (!usedTopicData) {
    syncMessageSequence();
    rerenderAll();
  }
  if (els.avatarSettingPreview) applyAssistantAvatar(els.avatarSettingPreview);
  setSaveHint('');
}

async function maybeRestoreCompletionFromBackup(completionId, serverUpdatedAt) {
  const id = (completionId == null ? '' : String(completionId)).trim();
  if (!id) return;
  const backup = readCompletionBackup(id);
  const payload = backup && backup.payload && typeof backup.payload === 'object' ? backup.payload : null;
  if (!payload) return;
  const localUpdatedAt = Number(backup.updatedAt || payload.updatedAt || 0);
  const serverMs = Number(serverUpdatedAt || 0);
  if (Number.isFinite(localUpdatedAt) && localUpdatedAt > serverMs) {
    applyCompletionData(payload);
    setStatus('已从本地恢复未保存的记录');
    await flushSave('恢复');
  } else if (Number.isFinite(serverMs) && serverMs >= localUpdatedAt) {
    clearCompletionBackup(id);
  }
}

async function loadCompletion(id) {
  const data = await fetchJson('/api/chat/completion/get?name=' + encodeURIComponent(id), { method: 'GET' });
  const s = data?.data || {};
  applyCompletionData(s);
  await maybeRestoreCompletionFromBackup(id, Number(s.updatedAt || 0));
}

async function runCompletion() {
  const userText = (els.promptInput.value || '').trim();
  if (!userText) {
    setStatus('请输入消息');
    return;
  }
  if (!state.currentCompletionId) {
    await loadCompletions(true);
    if (!state.currentCompletionId) return;
  }

  els.promptInput.value = '';
  const userMsg = addMessage('user', userText);
  if (state.pendingAttachment && state.pendingAttachment.file) {
    state.pendingAttachment.messageId = userMsg.id;
  }
  await flushSave('发送');
  if (!!els.streamToggle.checked) {
    await generateIntoMessage(state.messages, null);
  } else {
    const assistantMsg = addMessage('assistant', '');
    await generateIntoMessage(state.messages, assistantMsg.id);
  }
}

async function kvCacheAction(action) {
  const a = String(action || '').toLowerCase();
  const verb = a === 'load' ? '加载' : '保存';
  try {
    const modelId = els.modelSelect.value;
    if (!modelId) {
      setStatus('请先选择一个模型');
      return;
    }
    const slotId = 0;
    setStatus(verb + 'KV缓存中...');
    const response = await fetchJson('/api/models/slots/' + (a === 'load' ? 'load' : 'save'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        modelId: modelId,
        slotId: slotId
      })
    });

    if (response.success) {
      setStatus('KV缓存' + verb + '成功');
    } else {
      setStatus('KV缓存' + verb + '失败: ' + (response.message || '未知错误'));
    }
  } catch (e) {
    setStatus('KV缓存' + verb + '失败: ' + e.message);
  }
}
