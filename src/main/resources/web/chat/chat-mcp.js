(function () {
  function uniqueStrings(values) {
    const seen = new Set();
    const result = [];
    (Array.isArray(values) ? values : []).forEach(function (value) {
      const text = typeof value === 'string' ? value.trim() : '';
      if (!text || seen.has(text)) {
        return;
      }
      seen.add(text);
      result.push(text);
    });
    return result;
  }

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function cloneJson(value) {
    return value == null ? value : JSON.parse(JSON.stringify(value));
  }

  function toPlainObject(value) {
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  }

  function normalizeTransportType(value) {
    const text = typeof value === 'string' ? value.trim().toLowerCase() : '';
    if (!text || text === 'sse') {
      return 'sse';
    }
    if (
      text === 'streamable-http' ||
      text === 'streamable_http' ||
      text === 'streamablehttp' ||
      text === 'http'
    ) {
      return 'streamable-http';
    }
    return text;
  }

  function getMcpTemplateConfig(transport) {
    const normalized = normalizeTransportType(transport);
    return {
      transport: normalized === 'streamable-http' ? 'streamable_http' : 'sse',
      url: 'your mcp server url',
      headers: {},
      timeout: 5,
      sse_read_timeout: 300
    };
  }

  function normalizeToolDefinition(tool, serverUrl, server) {
    const source = toPlainObject(tool);
    const inputSchema =
      source.input_schema && typeof source.input_schema === 'object'
        ? cloneJson(source.input_schema)
        : source.inputSchema && typeof source.inputSchema === 'object'
          ? cloneJson(source.inputSchema)
          : { type: 'object', properties: {} };
    return {
      name: typeof source.name === 'string' ? source.name.trim() : '',
      description: typeof source.description === 'string' ? source.description : '',
      input_schema: inputSchema,
      mcpServerUrl: serverUrl,
      mcpServerName: server && typeof server.name === 'string' ? server.name : '',
      mcpTransportType: server && typeof server.type === 'string' ? normalizeTransportType(server.type) : 'sse'
    };
  }

  function normalizeRegistry(registry) {
    const source = toPlainObject(registry);
    const result = {};
    Object.keys(source).forEach(function (url) {
      const trimmedUrl = typeof url === 'string' ? url.trim() : '';
      if (!trimmedUrl) {
        return;
      }
      const server = toPlainObject(source[url]);
      const tools = Array.isArray(server.tools) ? server.tools : [];
      result[trimmedUrl] = {
        url: trimmedUrl,
        name: typeof server.name === 'string' && server.name.trim() ? server.name.trim() : trimmedUrl,
        description: typeof server.description === 'string' ? server.description : '',
        type: normalizeTransportType(server.type),
        isActive: server.isActive !== false,
        tools: tools
          .map(function (tool) {
            return normalizeToolDefinition(tool, trimmedUrl, server);
          })
          .filter(function (tool) {
            return !!tool.name;
          })
      };
    });
    return result;
  }

  function normalizeAssistantUrls(assistant, fallback) {
    const current =
      assistant && Array.isArray(assistant.mcpServerUrls)
        ? assistant.mcpServerUrls
        : assistant && Array.isArray(assistant.mcpServers)
          ? assistant.mcpServers
          : [];
    const fallbackUrls =
      fallback && Array.isArray(fallback.mcpServerUrls)
        ? fallback.mcpServerUrls
        : fallback && Array.isArray(fallback.mcpServers)
          ? fallback.mcpServers
          : [];
    return uniqueStrings(current.length ? current : fallbackUrls);
  }

  function normalizeToolStateMap(value) {
    const source = toPlainObject(value);
    const result = {};
    Object.keys(source).forEach(function (key) {
      const normalizedKey = typeof key === 'string' ? key.trim() : '';
      if (!normalizedKey) {
        return;
      }
      if (source[key] === false) {
        result[normalizedKey] = false;
      }
    });
    return result;
  }

  function normalizeToolCall(rawCall, fallbackIndex) {
    const call = toPlainObject(rawCall);
    const func = toPlainObject(call.function);
    let args = '';
    if (typeof func.arguments === 'string') {
      args = func.arguments;
    } else if (func.arguments && typeof func.arguments === 'object') {
      try {
        args = JSON.stringify(func.arguments);
      } catch (error) {
        args = '';
      }
    }
    return {
      id: typeof call.id === 'string' && call.id.trim() ? call.id.trim() : '',
      index: Number.isInteger(call.index) ? call.index : fallbackIndex,
      type: typeof call.type === 'string' && call.type.trim() ? call.type.trim() : 'function',
      function: {
        name: typeof func.name === 'string' ? func.name : '',
        arguments: args
      }
    };
  }

  function normalizeToolCalls(toolCalls) {
    return (Array.isArray(toolCalls) ? toolCalls : [])
      .map(function (toolCall, index) {
        return normalizeToolCall(toolCall, index);
      })
      .filter(function (toolCall) {
        return !!toolCall.id || !!toolCall.function.name || !!toolCall.function.arguments;
      });
  }

  function mergeToolCallChunks(existing, incoming) {
    const target = Array.isArray(existing) ? existing : [];
    normalizeToolCalls(incoming).forEach(function (chunk, offset) {
      const index = Number.isInteger(chunk.index) ? chunk.index : offset;
      if (!target[index]) {
        target[index] = {
          id: chunk.id || '',
          index: index,
          type: chunk.type || 'function',
          function: {
            name: chunk.function.name || '',
            arguments: chunk.function.arguments || ''
          }
        };
        return;
      }
      const current = target[index];
      if (chunk.id) {
        current.id = chunk.id;
      }
      if (chunk.type) {
        current.type = chunk.type;
      }
      if (chunk.function.name) {
        current.function.name += chunk.function.name;
      }
      if (chunk.function.arguments) {
        current.function.arguments += chunk.function.arguments;
      }
    });
    return target;
  }

  function finalizeToolCalls(toolCalls) {
    return normalizeToolCalls(toolCalls)
      .filter(function (toolCall) {
        return !!toolCall.function.name;
      })
      .map(function (toolCall, index) {
        if (!toolCall.id) {
          toolCall.id = 'call_' + Date.now().toString(36) + '_' + index;
        }
        return toolCall;
      });
  }

  function extractToolResultText(apiJson) {
    if (apiJson && apiJson.success === false) {
      return apiJson.error ? '工具执行失败：' + apiJson.error : '工具执行失败';
    }
    const contentText =
      apiJson &&
      apiJson.data &&
      typeof apiJson.data.content === 'string'
        ? apiJson.data.content
        : '';
    if (!contentText) {
      return '';
    }
    let parsed;
    try {
      parsed = JSON.parse(contentText);
    } catch (error) {
      return contentText;
    }
    if (parsed && parsed.error && parsed.error.message) {
      return '工具执行失败：' + parsed.error.message;
    }
    const blocks = [];
    const result = parsed && parsed.result && typeof parsed.result === 'object' ? parsed.result : null;
    if (result && Array.isArray(result.content)) {
      result.content.forEach(function (item) {
        if (!item || typeof item !== 'object') {
          return;
        }
        if (typeof item.text === 'string' && item.text.trim()) {
          blocks.push(item.text.trim());
          return;
        }
        if (item.type === 'json' && item.json && typeof item.json === 'object') {
          blocks.push(JSON.stringify(item.json, null, 2));
        }
      });
    }
    if (result && result.structuredContent && typeof result.structuredContent === 'object') {
      blocks.push(JSON.stringify(result.structuredContent, null, 2));
    }
    return blocks.join('\n\n').trim() || contentText;
  }

  function parseToolArgumentsPayload(argumentsText) {
    const raw = typeof argumentsText === 'string' ? argumentsText.trim() : '';
    if (!raw) {
      return {};
    }
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (error) {
      throw new Error('工具参数不是完整的 JSON：' + raw);
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('工具参数必须是 JSON 对象');
    }
    return parsed;
  }

  function create(options) {
    const state = options && options.state ? options.state : {};
    const els = options && options.els ? options.els : {};
    const apiFetch = options && typeof options.fetchJson === 'function' ? options.fetchJson : null;
    const getHeaders = options && typeof options.getHeaders === 'function' ? options.getHeaders : function () { return {}; };
    const showToast = options && typeof options.showToast === 'function' ? options.showToast : function () {};
    const persistState = options && typeof options.persistState === 'function' ? options.persistState : function () {};
    const nowTime = options && typeof options.nowTime === 'function'
      ? options.nowTime
      : function () {
          return new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
        };
    const getCurrentAssistant = options && typeof options.getCurrentAssistant === 'function'
      ? options.getCurrentAssistant
      : function () { return null; };

    let registryServers = {};
    let eventsBound = false;
    let collapsedServers = {};

    function getAssistantUrls(assistant) {
      return normalizeAssistantUrls(assistant || getCurrentAssistant(), null);
    }

    function updateCurrentAssistantUrls(nextUrls) {
      const assistant = getCurrentAssistant();
      if (!assistant) {
        return;
      }
      const normalized = uniqueStrings(nextUrls);
      assistant.mcpServerUrls = normalized.slice();
      state.mcpServerUrls = normalized.slice();
    }

    function getAssistantToolStateMap(assistant) {
      return normalizeToolStateMap(assistant && assistant.mcpToolStates);
    }

    function updateCurrentAssistantToolStateMap(nextStateMap) {
      const assistant = getCurrentAssistant();
      if (!assistant) {
        return;
      }
      assistant.mcpToolStates = normalizeToolStateMap(nextStateMap);
    }

    function buildToolKey(serverUrl, toolName) {
      return String(serverUrl || '').trim() + '::' + String(toolName || '').trim();
    }

    function isToolEnabledForAssistant(assistant, tool) {
      if (!tool || !tool.name || !tool.mcpServerUrl) {
        return false;
      }
      const enabledUrls = new Set(getAssistantUrls(assistant));
      if (!enabledUrls.has(tool.mcpServerUrl)) {
        return false;
      }
      const toolStates = getAssistantToolStateMap(assistant);
      return toolStates[buildToolKey(tool.mcpServerUrl, tool.name)] !== false;
    }

    function setToolEnabled(serverUrl, toolName, enabled) {
      const toolStates = getAssistantToolStateMap(getCurrentAssistant());
      const key = buildToolKey(serverUrl, toolName);
      if (!key.trim()) {
        return;
      }
      if (enabled) {
        delete toolStates[key];
      } else {
        toolStates[key] = false;
      }
      updateCurrentAssistantToolStateMap(toolStates);
      renderServerList();
    }

    function removeServerToolStates(targetUrl) {
      if (!targetUrl || !Array.isArray(state.assistants)) {
        return;
      }
      state.assistants.forEach(function (assistant) {
        const toolStates = getAssistantToolStateMap(assistant);
        const nextStateMap = {};
        Object.keys(toolStates).forEach(function (key) {
          if (!key.startsWith(String(targetUrl) + '::')) {
            nextStateMap[key] = toolStates[key];
          }
        });
        assistant.mcpToolStates = nextStateMap;
      });
    }

    function isServerCollapsed(url) {
      return collapsedServers[String(url || '').trim()] === true;
    }

    function toggleServerCollapsed(url) {
      const key = String(url || '').trim();
      if (!key) {
        return;
      }
      collapsedServers[key] = !isServerCollapsed(key);
      renderServerList();
    }

    function buildToolMap(tools) {
      const map = new Map();
      (Array.isArray(tools) ? tools : []).forEach(function (tool) {
        if (tool && tool.name && !map.has(tool.name)) {
          map.set(tool.name, tool);
        }
      });
      return map;
    }

    function snapshotScrollPositions() {
      if (!els.mcpServerList) {
        return null;
      }
      const containers = [];
      const list = els.mcpServerList;
      const content = typeof list.closest === 'function' ? list.closest('.settings-content') : null;
      [content, list].forEach(function (element) {
        if (!element || containers.indexOf(element) >= 0) {
          return;
        }
        containers.push(element);
      });
      const toolLists = Array.from(list.querySelectorAll('.mcp-tool-list')).map(function (element) {
        return {
          serverUrl: element.getAttribute('data-mcp-tool-list') || '',
          top: element.scrollTop,
          left: element.scrollLeft
        };
      });
      const activeElement = document.activeElement;
      return {
        containers: containers.map(function (element) {
          return {
            element: element,
            top: element.scrollTop,
            left: element.scrollLeft
          };
        }),
        toolLists: toolLists,
        activeToggle: activeElement && activeElement.matches && activeElement.matches('[data-mcp-tool-toggle]')
          ? {
              serverUrl: activeElement.getAttribute('data-mcp-tool-server') || '',
              toolName: activeElement.getAttribute('data-mcp-tool-name') || ''
            }
          : null
      };
    }

    function restoreScrollPositions(snapshot) {
      const data = snapshot && typeof snapshot === 'object' ? snapshot : null;
      if (!data) {
        return;
      }
      (Array.isArray(data.containers) ? data.containers : []).forEach(function (item) {
        if (!item || !item.element || !item.element.isConnected) {
          return;
        }
        item.element.scrollTop = item.top;
        item.element.scrollLeft = item.left;
      });
      if (els.mcpServerList) {
        const toolListMap = new Map();
        Array.from(els.mcpServerList.querySelectorAll('.mcp-tool-list')).forEach(function (element) {
          toolListMap.set(element.getAttribute('data-mcp-tool-list') || '', element);
        });
        (Array.isArray(data.toolLists) ? data.toolLists : []).forEach(function (item) {
          if (!item) {
            return;
          }
          const element = toolListMap.get(item.serverUrl || '');
          if (!element) {
            return;
          }
          element.scrollTop = item.top;
          element.scrollLeft = item.left;
        });
        const activeToggle = data.activeToggle;
        if (activeToggle && activeToggle.serverUrl && activeToggle.toolName) {
          const nextToggle = Array.from(els.mcpServerList.querySelectorAll('[data-mcp-tool-toggle]')).find(function (element) {
            return (element.getAttribute('data-mcp-tool-server') || '') === activeToggle.serverUrl &&
              (element.getAttribute('data-mcp-tool-name') || '') === activeToggle.toolName;
          });
          if (nextToggle && typeof nextToggle.focus === 'function') {
            try {
              nextToggle.focus({ preventScroll: true });
            } catch (error) {
              nextToggle.focus();
            }
          }
        }
      }
    }

    function renderServerList() {
      if (!els.mcpServerList) {
        return;
      }
      const scrollSnapshots = snapshotScrollPositions();
      const entries = Object.values(registryServers).sort(function (a, b) {
        return String(a.name || a.url).localeCompare(String(b.name || b.url), 'zh-Hans-CN');
      });
      const currentAssistant = getCurrentAssistant();
      const enabledUrls = new Set(getAssistantUrls(currentAssistant));
      if (!entries.length) {
        els.mcpServerList.innerHTML = '<div class="mcp-empty">暂无已连接的 MCP 服务</div>';
      } else {
        els.mcpServerList.innerHTML = entries
          .map(function (server) {
            const isServerEnabled = enabledUrls.has(server.url);
            const isCollapsed = isServerCollapsed(server.url);
            const enabledToolCount = server.tools.filter(function (tool) {
              return isToolEnabledForAssistant(currentAssistant, tool);
            }).length;
            const toolItems = server.tools.length
              ? server.tools
                  .map(function (tool) {
                    const toolEnabled = isToolEnabledForAssistant(currentAssistant, tool);
                    const toolKey = buildToolKey(server.url, tool.name);
                    return (
                      '<label class="mcp-tool-item' + (!isServerEnabled ? ' is-disabled' : '') + '">' +
                        '<div class="mcp-tool-copy">' +
                          '<div class="mcp-tool-name-row">' +
                            '<strong class="mcp-tool-name">' + escapeHtml(tool.name) + '</strong>' +
                          '</div>' +
                          '<div class="mcp-tool-desc">' + escapeHtml(tool.description || '该工具暂无描述') + '</div>' +
                        '</div>' +
                        '<span class="toggle-switch">' +
                          '<input type="checkbox" data-mcp-tool-toggle="' + escapeHtml(toolKey) + '"' +
                            ' data-mcp-tool-server="' + escapeHtml(server.url) + '"' +
                            ' data-mcp-tool-name="' + escapeHtml(tool.name) + '"' +
                            (toolEnabled ? ' checked' : '') +
                            (!isServerEnabled ? ' disabled' : '') +
                          '>' +
                          '<span class="toggle-slider"></span>' +
                        '</span>' +
                      '</label>'
                    );
                  })
                  .join('')
              : '<div class="mcp-empty">该服务当前未发现可用工具</div>';
            return (
              '<div class="mcp-server-item' + (!isServerEnabled ? ' is-disabled' : '') + '" data-mcp-server="' + escapeHtml(server.url) + '">' +
                '<div class="mcp-server-main">' +
                  '<div class="mcp-server-head">' +
                    '<div class="mcp-server-head-main">' +
                      '<strong>' + escapeHtml(server.name || server.url) + '</strong>' +
                      '<span class="mcp-badge">' + escapeHtml(server.type === 'streamable-http' ? 'HTTP' : 'SSE') + '</span>' +
                      '<span class="mcp-badge subtle">' + enabledToolCount + ' / ' + server.tools.length + ' 个工具已启用</span>' +
                    '</div>' +
                    '<div class="mcp-server-controls">' +
                      '<button type="button" class="ghost-button mcp-collapse-btn" data-mcp-collapse="' + escapeHtml(server.url) + '" aria-expanded="' + (!isCollapsed ? 'true' : 'false') + '">' +
                        '<span class="mcp-collapse-icon">' + (isCollapsed ? '▸' : '▾') + '</span>' +
                        '<span>' + (isCollapsed ? '展开工具' : '收起工具') + '</span>' +
                      '</button>' +
                      '<label class="settings-toggle mcp-server-toggle">' +
                        '<span class="settings-toggle-text">' +
                          '<span>' + (isServerEnabled ? '已启用' : '未启用') + '</span>' +
                        '</span>' +
                        '<span class="toggle-switch">' +
                          '<input type="checkbox" data-mcp-toggle="' + escapeHtml(server.url) + '"' +
                            (isServerEnabled ? ' checked' : '') +
                          '>' +
                          '<span class="toggle-slider"></span>' +
                        '</span>' +
                      '</label>' +
                    '</div>' +
                  '</div>' +
                  '<div class="mcp-server-url">' + escapeHtml(server.url) + '</div>' +
                  (server.description ? '<div class="mcp-server-desc">' + escapeHtml(server.description) + '</div>' : '') +
                  '<div class="mcp-server-tools">仅启用的工具会出现在发送给模型的请求中</div>' +
                  '<div class="mcp-tool-list' + (isCollapsed ? ' is-collapsed' : '') + '" data-mcp-tool-list="' + escapeHtml(server.url) + '">' + toolItems + '</div>' +
                  '<div class="mcp-server-actions">' +
                    '<button type="button" class="ghost danger" data-mcp-remove="' + escapeHtml(server.url) + '">移除服务</button>' +
                  '</div>' +
                '</div>' +
              '</div>'
            );
          })
          .join('');
      }
      if (els.mcpSummary) {
        const enabledServerCount = entries.filter(function (server) {
          return enabledUrls.has(server.url);
        }).length;
        const totalToolCount = entries.reduce(function (sum, server) {
          return sum + server.tools.length;
        }, 0);
        const enabledTools = getEnabledTools(currentAssistant);
        els.mcpSummary.textContent = enabledTools.length
          ? '当前助手已启用 ' + enabledServerCount + ' 个 MCP 服务，向模型暴露 ' + enabledTools.length + ' / ' + totalToolCount + ' 个工具'
          : '当前助手未向模型暴露任何 MCP 工具';
      }
      restoreScrollPositions(scrollSnapshots);
      if (typeof requestAnimationFrame === 'function') {
        requestAnimationFrame(function () {
          restoreScrollPositions(scrollSnapshots);
        });
      }
    }

    async function refreshRegistry(options) {
      if (!apiFetch) {
        return {};
      }
      const opts = options || {};
      const json = await apiFetch('/api/mcp/tools', {
        headers: getHeaders()
      });
      if (!json || json.success === false) {
        throw new Error((json && json.error) || '加载 MCP 服务失败');
      }
      const servers =
        json &&
        json.data &&
        json.data.servers &&
        typeof json.data.servers === 'object'
          ? json.data.servers
          : {};
      registryServers = normalizeRegistry(servers);
      renderServerList();
      if (!opts.silentToast) {
        showToast('success', 'MCP 服务列表已刷新');
      }
      return registryServers;
    }

    function sanitizeAddUrl(rawUrl) {
      const url = typeof rawUrl === 'string' ? rawUrl.trim() : '';
      if (!url) {
        throw new Error('请先输入 MCP 地址');
      }
      if (!/^https?:\/\//i.test(url)) {
        throw new Error('MCP 地址需以 http:// 或 https:// 开头');
      }
      return url;
    }

    function normalizeConfigTransport(value) {
      const normalized = normalizeTransportType(value);
      if (normalized !== 'sse' && normalized !== 'streamable-http') {
        throw new Error('transport 仅支持 sse 或 streamable_http');
      }
      return normalized;
    }

    function normalizeConfigHeaders(value) {
      if (value == null) {
        return {};
      }
      if (!value || typeof value !== 'object' || Array.isArray(value)) {
        throw new Error('headers 必须是 JSON 对象');
      }
      const headers = {};
      Object.keys(value).forEach(function (key) {
        const name = typeof key === 'string' ? key.trim() : '';
        if (!name) {
          return;
        }
        const headerValue = value[key];
        if (headerValue == null) {
          return;
        }
        headers[name] = String(headerValue);
      });
      return headers;
    }

    function normalizeOptionalNumber(value, fieldName) {
      if (value == null || value === '') {
        return null;
      }
      const numeric = Number(value);
      if (!Number.isFinite(numeric) || numeric <= 0) {
        throw new Error(fieldName + ' 必须是大于 0 的数字');
      }
      return numeric;
    }

    function parseJsonServerConfig(rawText) {
      const text = typeof rawText === 'string' ? rawText.trim() : '';
      if (!text) {
        throw new Error('请先输入 MCP JSON 配置');
      }
      let parsed;
      try {
        parsed = JSON.parse(text);
      } catch (error) {
        throw new Error('JSON 格式错误，请检查后重试');
      }
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('MCP 配置必须是 JSON 对象');
      }
      const type = normalizeConfigTransport(parsed.transport || parsed.type);
      const url = sanitizeAddUrl(parsed.url || parsed.baseUrl);
      const headers = normalizeConfigHeaders(parsed.headers);
      const timeout = normalizeOptionalNumber(parsed.timeout, 'timeout');
      const sseReadTimeout = normalizeOptionalNumber(parsed.sse_read_timeout, 'sse_read_timeout');
      return {
        type: type,
        url: url,
        headers: headers,
        timeout: timeout,
        sse_read_timeout: sseReadTimeout
      };
    }

    function buildConfigPayload(config) {
      const serverId = config && typeof config.name === 'string' && config.name.trim()
        ? config.name.trim()
        : 'mcp_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 8);
      const serverConfig = {
        type: normalizeTransportType(config && config.type),
        url: config && config.url ? config.url : '',
        isActive: true
      };
      const headers = config && config.headers && typeof config.headers === 'object' ? config.headers : {};
      if (Object.keys(headers).length) {
        serverConfig.headers = headers;
      }
      if (config && Number.isFinite(config.timeout)) {
        serverConfig.timeout = config.timeout;
      }
      if (config && Number.isFinite(config.sse_read_timeout)) {
        serverConfig.sse_read_timeout = config.sse_read_timeout;
      }
      return {
        mcpServers: {
          [serverId]: serverConfig
        }
      };
    }

    async function connectServer(config) {
      if (!apiFetch) {
        return;
      }
      const normalizedConfig = {
        name: config && typeof config.name === 'string' ? config.name.trim() : '',
        type: normalizeConfigTransport(config && config.type),
        url: sanitizeAddUrl(config && config.url),
        headers: normalizeConfigHeaders(config && config.headers),
        timeout: normalizeOptionalNumber(config && config.timeout, 'timeout'),
        sse_read_timeout: normalizeOptionalNumber(config && config.sse_read_timeout, 'sse_read_timeout')
      };
      const json = await apiFetch('/api/mcp/add', {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify(buildConfigPayload(normalizedConfig))
      });
      if (!json || json.success === false) {
        throw new Error((json && json.error) || '连接 MCP 服务失败');
      }
      await refreshRegistry({ silentToast: true });
      const nextUrls = getAssistantUrls();
      if (!nextUrls.includes(normalizedConfig.url)) {
        nextUrls.push(normalizedConfig.url);
        updateCurrentAssistantUrls(nextUrls);
      }
      renderServerList();
      await persistState({ immediate: true, showErrorToast: true });
      showToast('success', 'MCP 服务已连接');
    }

    function getTemplateText(transport) {
      return JSON.stringify(getMcpTemplateConfig(transport), null, 2);
    }

    function setDialogTemplate(transport) {
      if (!els.mcpJsonInput) {
        return;
      }
      els.mcpJsonInput.value = getTemplateText(transport);
      els.mcpJsonInput.focus();
      els.mcpJsonInput.setSelectionRange(els.mcpJsonInput.value.length, els.mcpJsonInput.value.length);
    }

    function openAddDialog(transport) {
      if (!els.mcpAddDialog) {
        return;
      }
      if (els.mcpJsonInput && !String(els.mcpJsonInput.value || '').trim()) {
        els.mcpJsonInput.value = getTemplateText(transport || 'streamable-http');
      }
      els.mcpAddDialog.classList.add('open');
      els.mcpAddDialog.setAttribute('aria-hidden', 'false');
      if (document.body) {
        document.body.classList.add('mcp-dialog-open');
      }
      if (els.mcpJsonInput) {
        els.mcpJsonInput.focus();
      }
    }

    function closeAddDialog(options) {
      if (!els.mcpAddDialog) {
        return;
      }
      const opts = options || {};
      els.mcpAddDialog.classList.remove('open');
      els.mcpAddDialog.setAttribute('aria-hidden', 'true');
      if (document.body) {
        document.body.classList.remove('mcp-dialog-open');
      }
      if (opts.restoreFocus !== false && els.mcpOpenAddDialogBtn) {
        els.mcpOpenAddDialogBtn.focus();
      }
    }

    async function addServerFromDialog() {
      const config = parseJsonServerConfig(els.mcpJsonInput ? els.mcpJsonInput.value : '');
      const name = els.mcpServerNameInput ? els.mcpServerNameInput.value.trim() : '';
      await connectServer({ ...config, name: name });
      closeAddDialog();
    }

    async function addServer() {
      const type = els.mcpTransportSelect ? normalizeTransportType(els.mcpTransportSelect.value) : 'sse';
      const url = sanitizeAddUrl(els.mcpUrlInput ? els.mcpUrlInput.value : '');
      await connectServer({ type: type, url: url, headers: {} });
      if (els.mcpUrlInput) {
        els.mcpUrlInput.value = '';
      }
    }

    async function removeServer(url) {
      if (!apiFetch || !url) {
        return;
      }
      const json = await apiFetch('/api/mcp/remove', {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify({ url: url })
      });
      if (!json || json.success === false) {
        throw new Error((json && json.error) || '移除 MCP 服务失败');
      }
      delete registryServers[url];
      removeServerToolStates(url);
      if (Array.isArray(state.assistants)) {
        state.assistants.forEach(function (assistant) {
          assistant.mcpServerUrls = normalizeAssistantUrls(assistant, null).filter(function (item) {
            return item !== url;
          });
        });
      }
      updateCurrentAssistantUrls(getAssistantUrls().filter(function (item) {
        return item !== url;
      }));
      renderServerList();
      await persistState({ immediate: true, showErrorToast: true });
      showToast('success', 'MCP 服务已移除');
    }

    function toggleServer(url, enabled) {
      const nextUrls = getAssistantUrls().filter(function (item) {
        return item !== url;
      });
      if (enabled) {
        nextUrls.push(url);
      }
      updateCurrentAssistantUrls(nextUrls);
      renderServerList();
    }

    function bindEvents() {
      if (eventsBound) {
        return;
      }
      eventsBound = true;
      if (els.mcpOpenAddDialogBtn) {
        els.mcpOpenAddDialogBtn.addEventListener('click', function () {
          openAddDialog('streamable-http');
        });
      }
      if (els.mcpDialogCloseBtn) {
        els.mcpDialogCloseBtn.addEventListener('click', function () {
          closeAddDialog();
        });
      }
      if (els.mcpDialogCancelBtn) {
        els.mcpDialogCancelBtn.addEventListener('click', function () {
          closeAddDialog();
        });
      }
      if (els.mcpUseStreamableTemplateBtn) {
        els.mcpUseStreamableTemplateBtn.addEventListener('click', function () {
          setDialogTemplate('streamable-http');
        });
      }
      if (els.mcpUseSseTemplateBtn) {
        els.mcpUseSseTemplateBtn.addEventListener('click', function () {
          setDialogTemplate('sse');
        });
      }
      if (els.mcpDialogSaveBtn) {
        els.mcpDialogSaveBtn.addEventListener('click', function () {
          addServerFromDialog().catch(function (error) {
            showToast('error', error && error.message ? error.message : '连接 MCP 服务失败');
          });
        });
      }
      if (els.mcpAddDialog) {
        els.mcpAddDialog.addEventListener('click', function (event) {
          if (event.target === els.mcpAddDialog) {
            closeAddDialog();
          }
        });
      }
      if (els.mcpJsonInput) {
        els.mcpJsonInput.addEventListener('keydown', function (event) {
          if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
            event.preventDefault();
            addServerFromDialog().catch(function (error) {
              showToast('error', error && error.message ? error.message : '连接 MCP 服务失败');
            });
            return;
          }
          if (event.key === 'Escape') {
            event.preventDefault();
            closeAddDialog();
          }
        });
      }
      document.addEventListener('keydown', function (event) {
        if (event.key !== 'Escape' || !els.mcpAddDialog || !els.mcpAddDialog.classList.contains('open')) {
          return;
        }
        event.preventDefault();
        closeAddDialog();
      });
      if (els.mcpAddBtn) {
        els.mcpAddBtn.addEventListener('click', function () {
          addServer().catch(function (error) {
            showToast('error', error && error.message ? error.message : '连接 MCP 服务失败');
          });
        });
      }
      if (els.mcpRefreshBtn) {
        els.mcpRefreshBtn.addEventListener('click', function () {
          refreshRegistry().catch(function (error) {
            showToast('error', error && error.message ? error.message : '刷新 MCP 服务失败');
          });
        });
      }
      if (els.mcpUrlInput) {
        els.mcpUrlInput.addEventListener('keydown', function (event) {
          if (event.key !== 'Enter') {
            return;
          }
          event.preventDefault();
          addServer().catch(function (error) {
            showToast('error', error && error.message ? error.message : '连接 MCP 服务失败');
          });
        });
      }
      if (els.mcpServerList) {
        els.mcpServerList.addEventListener('change', function (event) {
          const input = event.target && event.target.closest('[data-mcp-toggle]');
          if (input) {
            toggleServer(input.getAttribute('data-mcp-toggle') || '', !!input.checked);
            return;
          }
          const toolInput = event.target && event.target.closest('[data-mcp-tool-toggle]');
          if (!toolInput) {
            return;
          }
          setToolEnabled(
            toolInput.getAttribute('data-mcp-tool-server') || '',
            toolInput.getAttribute('data-mcp-tool-name') || '',
            !!toolInput.checked
          );
        });
        els.mcpServerList.addEventListener('click', function (event) {
          const collapseButton = event.target && event.target.closest('[data-mcp-collapse]');
          if (collapseButton) {
            toggleServerCollapsed(collapseButton.getAttribute('data-mcp-collapse') || '');
            return;
          }
          const button = event.target && event.target.closest('[data-mcp-remove]');
          if (!button) {
            return;
          }
          removeServer(button.getAttribute('data-mcp-remove') || '').catch(function (error) {
            showToast('error', error && error.message ? error.message : '移除 MCP 服务失败');
          });
        });
      }
    }

    function getEnabledTools(assistant) {
      const tools = [];
      Object.keys(registryServers).forEach(function (url) {
        registryServers[url].tools.forEach(function (tool) {
          if (isToolEnabledForAssistant(assistant, tool)) {
            tools.push(cloneJson(tool));
          }
        });
      });
      return tools;
    }

    function toModelTool(tool) {
      return {
        type: 'function',
        function: {
          name: tool.name,
          description: tool.description || '',
          parameters: cloneJson(tool.input_schema || { type: 'object', properties: {} })
        }
      };
    }

    async function executeToolCalls(toolCalls, availableTools, context) {
      const callList = finalizeToolCalls(toolCalls);
      const toolMap = buildToolMap(availableTools);
      const results = [];
      for (let index = 0; index < callList.length; index += 1) {
        const toolCall = callList[index];
        const tool = toolMap.get(toolCall.function.name);
        let content = '';
        let isError = false;
        try {
          if (!tool) {
            throw new Error('未找到可执行的 MCP 工具: ' + toolCall.function.name);
          }
          const parsedArguments = parseToolArgumentsPayload(toolCall.function.arguments);
          const response = await apiFetch('/api/tools/execute', {
            method: 'POST',
            headers: getHeaders(),
            body: JSON.stringify({
              tool_name: tool.name,
              arguments: parsedArguments,
              mcpServerUrl: tool.mcpServerUrl,
              preparedQuery: context && context.preparedQuery ? context.preparedQuery : ''
            })
          });
          if (!response || response.success === false) {
            throw new Error((response && response.error) || '工具调用失败');
          }
          content = extractToolResultText(response).trim() || '(工具未返回文本内容)';
        } catch (error) {
          isError = true;
          content = '工具执行失败：' + (error && error.message ? error.message : '未知错误');
        }
        results.push({
          role: 'tool',
          content: content,
          toolCallId: toolCall.id,
          toolName: toolCall.function.name,
          time: nowTime(),
          isError: isError
        });
      }
      return results;
    }

    return {
      bindEvents: bindEvents,
      refreshRegistry: refreshRegistry,
      render: renderServerList,
      closeAddDialog: closeAddDialog,
      addServer: addServer,
      removeServer: removeServer,
      getEnabledTools: getEnabledTools,
      toModelTool: toModelTool,
      executeToolCalls: executeToolCalls,
      normalizeAssistantUrls: normalizeAssistantUrls,
      normalizeToolCalls: normalizeToolCalls,
      mergeToolCallChunks: mergeToolCallChunks,
      finalizeToolCalls: finalizeToolCalls
    };
  }

  window.ChatMcpModule = {
    create: create,
    normalizeAssistantUrls: normalizeAssistantUrls,
    normalizeToolCalls: normalizeToolCalls,
    mergeToolCallChunks: mergeToolCallChunks,
    finalizeToolCalls: finalizeToolCalls
  };
})();
