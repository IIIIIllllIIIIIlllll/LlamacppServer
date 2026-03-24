function viewModelDetails(modelId) {
    fetch(`/api/models/details?modelId=${modelId}`).then(r => r.json()).then(data => {
        if (data.success) {
            const model = data.model;
            window.__modelDetailModelId = modelId;
            showModelDetailModal(model);
        } else { showToast(t('toast.error', '错误'), data.error, 'error'); }
    });
}

function showModelDetailModal(model) {
    const modalId = 'modelDetailModal';
    let modal = document.getElementById(modalId);
    if (!modal) {
        modal = document.createElement('div'); modal.id = modalId; modal.className = 'modal';
        modal.innerHTML = `<div class="modal-content model-detail"><div class="modal-header"><h3 class="modal-title">${t('modal.model_detail.title', '模型详情')}</h3><button class="modal-close" onclick="closeModal('${modalId}')">&times;</button></div><div class="modal-body" id="${modalId}Content"></div><div class="modal-footer"><button class="btn btn-secondary" onclick="closeModal('${modalId}')">${t('common.close', '关闭')}</button></div></div>`;
        document.body.appendChild(modal);
    }
    const content = document.getElementById(modalId + 'Content');
    const isMobileView = !!document.getElementById('mobileMainModels') || /index-mobile\.html$/i.test((location && location.pathname) ? location.pathname : '');
    let tabs = `<div style="display:flex; gap:8px; margin-bottom:12px;">` +
                `<button class="btn btn-secondary" id="${modalId}TabInfo">${t('modal.model_detail.tab.overview', '概览')}</button>` +
                `<button class="btn btn-secondary" id="${modalId}TabSampling">${t('modal.model_detail.tab.sampling', '采样设置')}</button>` +
                `<button class="btn btn-secondary" id="${modalId}TabProps">props</button>` +
                `<button class="btn btn-secondary" id="${modalId}TabChatTemplate">${t('modal.model_detail.tab.chat_template', '聊天模板')}</button>` +
                `<button class="btn btn-secondary" id="${modalId}TabToken">${t('modal.model_detail.tab.token', 'Token计算')}</button>` +
                `</div>`;
    let wrapperStart = isMobileView
        ? `<div style="display:flex; flex-direction:column; flex:1; min-height:0;">`
        : `<div style="display:flex; flex-direction:column; height:60vh; min-height:60vh;">`;
    let bodyStart = `<div style="flex:1; min-height:0;">`;
    let infoPanel = `<div id="${modalId}InfoPanel" style="height:100%;">` +
                    `<div style="display:grid; grid-template-columns: 1fr 2fr; gap: 10px; height:100%; overflow:auto;">` +
                    `<div><strong>${t('modal.model_detail.label.name', '名称:')}</strong></div><div>${model.name}</div>` +
                    `<div><strong>${t('modal.model_detail.label.path', '路径:')}</strong></div><div style="word-break:break-all;">${model.path}</div>` +
                    `<div><strong>${t('modal.model_detail.label.size', '大小:')}</strong></div><div>${formatFileSize(model.size)}</div>` +
                    `${model.isLoaded ? `<div><strong>${t('modal.model_detail.label.status', '状态:')}</strong></div><div>${t('modal.model_detail.status.running', '已启动')}${model.port ? `${t('modal.model_detail.status.port_prefix', '（端口 ')}${model.port}${t('modal.model_detail.status.port_suffix', '）')}` : ''}</div>` : `<div><strong>${t('modal.model_detail.label.status', '状态:')}</strong></div><div>${t('modal.model_detail.status.stopped', '未启动')}</div>`}` +
                    `${model.startCmd ? `<div><strong>${t('modal.model_detail.label.start_cmd', '启动命令:')}</strong></div><div style="word-break:break-all; font-family: monospace;">${model.startCmd}</div>` : ``}` +
                    `${(() => { let s=''; if (model.metadata) { for (const [k,v] of Object.entries(model.metadata)) { s += `<div><strong>${k}:</strong></div><div style="word-break:break-all;">${v}</div>`; } } return s; })()}` +
                    `</div>` +
                    `</div>`;
    let samplingPanel = `<div id="${modalId}SamplingPanel" style="display:none; height:100%; overflow:auto; font-size: 12px; ">` +
                        `<div style="padding:10px 12px; border-radius:0.75rem; background:#f3f4f6; color:#111827; line-height:1.7; margin-bottom:12px;">` +
                        `${t('modal.model_detail.sampling.desc', '开启该功能后，将强制使用指定的采样配置，而忽略其它客户端中的采样。比如你在这里强制设置温度为1.0，而在openwebui中设置温度为0.7，此时llamacpp将忽略0.7，使用1.0。这个功能的意义是为了快速切换采样来变更模型的工作模式，比如Qwen3.5，它对采样的敏感度非常高，而且有多种采样搭配，为了避免反复修改采样配置，在这里设置并切换更加方便。')}` +
                        `</div>` +
                        `<div style="display:flex; gap:8px; align-items:center; margin-bottom:8px;">` +
                        `<label for="${modalId}SamplingConfigSelect" style="white-space:nowrap;">${t('modal.model_detail.sampling.config', '采样配置')}</label>` +
                        `<select class="form-control" id="${modalId}SamplingConfigSelect" style="max-width:320px;"></select>` +
                        `<button class="btn btn-secondary" id="${modalId}SamplingSaveBtn">${t('modal.model_detail.sampling.save', '保存设定')}</button>` +
                        `<button class="btn btn-secondary" id="${modalId}SamplingAddBtn">${t('modal.model_detail.sampling.add', '新增配置')}</button>` +
                        `<button class="btn btn-secondary" id="${modalId}SamplingUpdateBtn">${t('modal.model_detail.sampling.update', '更新采样')}</button>` +
                        `<button class="btn btn-danger" id="${modalId}SamplingDeleteBtn">${t('modal.model_detail.sampling.delete', '删除配置')}</button>` +
                        `</div>` +
                        `<div id="${modalId}SamplingDetails" style="display:grid; grid-template-columns: 1fr 1fr; gap:8px;"></div>` +
                        `</div>`;
    let propsPanel = `<div id="${modalId}PropsPanel" style="display:none; height:100%;">` +
                       `<div style="display:flex; gap:8px; margin-bottom:8px;">` +
                       `<button class="btn btn-primary" id="${modalId}PropsFetchBtn">${t('modal.model_detail.props.fetch', '请求 props')}</button>` +
                       `</div>` +
                       `<pre id="${modalId}PropsViewer" style="height:calc(100% - 48px); overflow:auto; font-size:13px; background:#111827; color:#e5e7eb; padding:10px; border-radius:0.75rem;"></pre>` +
                       `</div>`;
    let chatTemplatePanel = `<div id="${modalId}ChatTemplatePanel" style="display:none; height:100%;">` +
                        `<div style="display:flex; gap:8px; margin-bottom:8px;">` +
                        `<button class="btn btn-primary" id="${modalId}ChatTemplateDefaultBtn">${t('common.default', '默认')}</button>` +
                        `<button class="btn btn-primary" id="${modalId}ChatTemplateReloadBtn">${t('common.refresh', '刷新')}</button>` +
                        `<button class="btn btn-primary" id="${modalId}ChatTemplateSaveBtn">${t('common.save', '保存')}</button>` +
                        `<button class="btn btn-danger" id="${modalId}ChatTemplateDeleteBtn">${t('common.delete', '删除')}</button>` +
                        `</div>` +
                        `<textarea class="form-control" id="${modalId}ChatTemplateTextarea" rows="18" placeholder="${escapeAttrCompat(t('modal.model_detail.chat_template.optional_placeholder', '(可选)'))}" style="height:calc(100% - 48px); resize: vertical;"></textarea>` +
                        `</div>`;
    let tokenPanel = `<div id="${modalId}TokenPanel" style="display:none; height:100%;">` +
                        `<div style="display:flex; gap:8px; margin-bottom:8px; align-items:center;">` +
                        `<button class="btn btn-primary" id="${modalId}TokenCalcBtn">${t('modal.model_detail.token.calc', '生成 prompt 并计算 tokens')}</button>` +
                        `<div style="margin-left:auto; font-size:13px; color:#374151;">${t('modal.model_detail.token.tokens', 'tokens')}: <strong id="${modalId}TokenCount">-</strong></div>` +
                        `</div>` +
                        `<div style="display:grid; grid-template-columns: 1fr 1fr; gap:12px; height:calc(100% - 48px); min-height:0;">` +
                            `<div style="display:flex; flex-direction:column; min-height:0;">` +
                                `<textarea class="form-control" id="${modalId}TokenInput" rows="12" placeholder="${escapeAttrCompat(t('modal.model_detail.token.input_placeholder', '输入要计算的文本...'))}" style="flex:1; min-height:0; resize:none;"></textarea>` +
                            `</div>` +
                            `<div style="display:flex; flex-direction:column; min-height:0;">` +
                                `<textarea class="form-control" id="${modalId}TokenPromptOutput" rows="12" readonly style="flex:1; min-height:0; resize:none; background:#f9fafb;"></textarea>` +
                            `</div>` +
                        `</div>` +
                    `</div>`;
    let bodyEnd = `</div>`;
    let wrapperEnd = `</div>`;
    content.innerHTML = wrapperStart + tabs + bodyStart + infoPanel + samplingPanel + propsPanel + chatTemplatePanel + tokenPanel + bodyEnd + wrapperEnd;
    modal.classList.add('show');
    const tabInfo = document.getElementById(modalId + 'TabInfo');
    const tabSampling = document.getElementById(modalId + 'TabSampling');
    const tabProps = document.getElementById(modalId + 'TabProps');
    const tabChatTemplate = document.getElementById(modalId + 'TabChatTemplate');
    const tabToken = document.getElementById(modalId + 'TabToken');
    const samplingConfigSelect = document.getElementById(modalId + 'SamplingConfigSelect');
    const samplingSaveBtn = document.getElementById(modalId + 'SamplingSaveBtn');
    const samplingAddBtn = document.getElementById(modalId + 'SamplingAddBtn');
    const samplingUpdateBtn = document.getElementById(modalId + 'SamplingUpdateBtn');
    const samplingDeleteBtn = document.getElementById(modalId + 'SamplingDeleteBtn');
    const fetchPropsBtn = document.getElementById(modalId + 'PropsFetchBtn');
    const tplReloadBtn = document.getElementById(modalId + 'ChatTemplateReloadBtn');
    const tplDefaultBtn = document.getElementById(modalId + 'ChatTemplateDefaultBtn');
    const tplSaveBtn = document.getElementById(modalId + 'ChatTemplateSaveBtn');
    const tplDeleteBtn = document.getElementById(modalId + 'ChatTemplateDeleteBtn');
    const tokenCalcBtn = document.getElementById(modalId + 'TokenCalcBtn');
    const tokenInputEl = document.getElementById(modalId + 'TokenInput');
    if (tabInfo) tabInfo.onclick = () => openModelDetailTab('info');
    if (tabSampling) tabSampling.onclick = () => { openModelDetailTab('sampling'); loadModelSamplingSettings(); };
    if (tabProps) tabProps.onclick = () => { openModelDetailTab('props'); loadModelProps(); };
    if (tabChatTemplate) tabChatTemplate.onclick = () => { openModelDetailTab('chatTemplate'); loadModelChatTemplate(false); };
    if (tabToken) tabToken.onclick = () => openModelDetailTab('token');
    if (samplingConfigSelect) samplingConfigSelect.onchange = () => renderSelectedModelSamplingSettings();
    if (samplingSaveBtn) samplingSaveBtn.onclick = () => saveModelSamplingSelection();
    if (samplingAddBtn) samplingAddBtn.onclick = () => addModelSamplingConfig();
    if (samplingUpdateBtn) samplingUpdateBtn.onclick = () => updateModelSamplingConfig();
    if (samplingDeleteBtn) samplingDeleteBtn.onclick = () => deleteModelSamplingConfig();
    if (fetchPropsBtn) fetchPropsBtn.onclick = () => loadModelProps();
    if (tplReloadBtn) tplReloadBtn.onclick = () => loadModelChatTemplate(true);
    if (tplDefaultBtn) tplDefaultBtn.onclick = () => loadModelDefaultChatTemplate();
    if (tplSaveBtn) tplSaveBtn.onclick = () => saveModelChatTemplate();
    if (tplDeleteBtn) tplDeleteBtn.onclick = () => deleteModelChatTemplate();
    if (tokenCalcBtn) tokenCalcBtn.onclick = () => calculateModelTokens();
    if (tokenInputEl) {
        tokenInputEl.onkeydown = (e) => {
            if (e && e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                if (typeof calculateModelTokens === 'function') calculateModelTokens();
                e.preventDefault();
                return false;
            }
        };
    }
    openModelDetailTab('info');
}

function openModelDetailTab(tab) {
    const modalId = 'modelDetailModal';
    const info = document.getElementById(modalId + 'InfoPanel');
    const sampling = document.getElementById(modalId + 'SamplingPanel');
    const props = document.getElementById(modalId + 'PropsPanel');
    const chatTemplate = document.getElementById(modalId + 'ChatTemplatePanel');
    const token = document.getElementById(modalId + 'TokenPanel');
    const btnInfo = document.getElementById(modalId + 'TabInfo');
    const btnSampling = document.getElementById(modalId + 'TabSampling');
    const btnProps = document.getElementById(modalId + 'TabProps');
    const btnChatTemplate = document.getElementById(modalId + 'TabChatTemplate');
    const btnToken = document.getElementById(modalId + 'TabToken');
    if (info) info.style.display = tab === 'info' ? '' : 'none';
    if (sampling) sampling.style.display = tab === 'sampling' ? '' : 'none';
    if (props) props.style.display = tab === 'props' ? '' : 'none';
    if (chatTemplate) chatTemplate.style.display = tab === 'chatTemplate' ? '' : 'none';
    if (token) token.style.display = tab === 'token' ? '' : 'none';
    const applyTabBtnStyle = (btn, active) => {
        if (!btn) return;
        btn.classList.remove('btn-primary');
        btn.classList.remove('btn-secondary');
        btn.classList.add(active ? 'btn-primary' : 'btn-secondary');
    };
    applyTabBtnStyle(btnInfo, tab === 'info');
    applyTabBtnStyle(btnSampling, tab === 'sampling');
    applyTabBtnStyle(btnProps, tab === 'props');
    applyTabBtnStyle(btnChatTemplate, tab === 'chatTemplate');
    applyTabBtnStyle(btnToken, tab === 'token');
}

function loadModelProps() {
    const modelId = window.__modelDetailModelId;
    const viewer = document.getElementById('modelDetailModalPropsViewer');
    if (!modelId || !viewer) return;
    viewer.textContent = t('common.loading', '加载中...');
    fetch('/api/models/props?modelId=' + encodeURIComponent(modelId))
        .then(r => r.json())
        .then(res => {
            const d = res && res.success ? res.data : null;
            const props = d && d.props ? d.props : null;
            viewer.textContent = props ? JSON.stringify(props, null, 2) : JSON.stringify(res, null, 2);
        })
        .catch(() => {
            viewer.textContent = t('common.request_failed', '请求失败');
        });
}

function extractModelConfigFromGetResponse(res, modelId) {
    if (!(res && res.success)) return {};
    const data = res.data;
    if (!data) return {};
    if (data && typeof data === 'object' && data.config && typeof data.config === 'object') return data.config || {};
    if (data && typeof data === 'object') {
        const direct = data[modelId];
        if (direct && typeof direct === 'object') return direct;
    }
    return {};
}

function normalizeModelConfigBundle(rawEntry) {
    if (!rawEntry || typeof rawEntry !== 'object') {
        return {
            selectedConfig: '',
            configs: {}
        };
    }
    if (rawEntry.configs && typeof rawEntry.configs === 'object' && !Array.isArray(rawEntry.configs)) {
        const configs = {};
        const names = Object.keys(rawEntry.configs);
        for (let i = 0; i < names.length; i++) {
            const name = names[i];
            const cfg = rawEntry.configs[name];
            configs[name] = cfg && typeof cfg === 'object' && !Array.isArray(cfg) ? cfg : {};
        }
        const selectedRaw = rawEntry.selectedConfig === null || rawEntry.selectedConfig === undefined ? '' : String(rawEntry.selectedConfig).trim();
        const selectedConfig = selectedRaw && configs[selectedRaw] ? selectedRaw : (Object.keys(configs)[0] || '');
        return { selectedConfig, configs };
    }
    return {
        selectedConfig: '默认配置',
        configs: { '默认配置': rawEntry }
    };
}

function extractModelConfigBundleFromGetResponse(res, modelId) {
    if (!(res && res.success)) return normalizeModelConfigBundle(null);
    const data = res.data;
    if (!data || typeof data !== 'object') return normalizeModelConfigBundle(null);
    if (data.configs && typeof data.configs === 'object') return normalizeModelConfigBundle(data);
    const direct = data[modelId];
    return normalizeModelConfigBundle(direct);
}

function parseCommandArgs(command) {
    const text = command === null || command === undefined ? '' : String(command);
    if (!text.trim()) return [];
    const args = [];
    const re = /"([^"\\]*(?:\\.[^"\\]*)*)"|'([^'\\]*(?:\\.[^'\\]*)*)'|[^\s]+/g;
    let match;
    while ((match = re.exec(text)) !== null) {
        if (match[1] !== undefined) args.push(match[1]);
        else if (match[2] !== undefined) args.push(match[2]);
        else args.push(match[0]);
    }
    return args;
}

function extractSamplingSettingsFromConfig(cfg) {
    const config = cfg && typeof cfg === 'object' ? cfg : {};
    const out = {
        temp: null,
        topP: null,
        topK: null,
        minP: null,
        presencePenalty: null,
        repeatPenalty: null,
        frequencyPenalty: null
    };
    const readValue = (keys) => {
        for (let i = 0; i < keys.length; i++) {
            const key = keys[i];
            if (Object.prototype.hasOwnProperty.call(config, key) && config[key] !== null && config[key] !== undefined && String(config[key]).trim() !== '') {
                return config[key];
            }
        }
        return null;
    };
    out.temp = readValue(['temp', 'temperature']);
    out.topP = readValue(['topP', 'top_p', 'top-p']);
    out.topK = readValue(['topK', 'top_k', 'top-k']);
    out.minP = readValue(['minP', 'min_p', 'min-p']);
    out.presencePenalty = readValue(['presencePenalty', 'presence_penalty', 'presence-penalty']);
    out.repeatPenalty = readValue(['repeatPenalty', 'repeat_penalty', 'repeat-penalty']);
    out.frequencyPenalty = readValue(['frequencyPenalty', 'frequency_penalty', 'frequency-penalty']);

    const args = parseCommandArgs(config.cmd);
    const flagMap = {
        '--temp': 'temp',
        '--top-p': 'topP',
        '--top-k': 'topK',
        '--min-p': 'minP',
        '--presence-penalty': 'presencePenalty',
        '--repeat-penalty': 'repeatPenalty',
        '--frequency-penalty': 'frequencyPenalty'
    };
    for (let i = 0; i < args.length; i++) {
        const token = args[i];
        if (!token || token[0] !== '-') continue;
        const eq = token.indexOf('=');
        let key = token;
        let val = null;
        if (eq > -1) {
            key = token.slice(0, eq);
            val = token.slice(eq + 1);
        } else if (i + 1 < args.length) {
            val = args[i + 1];
        }
        if (val !== null && val !== undefined && String(val).startsWith('--')) continue;
        const target = flagMap[key];
        if (!target || out[target] !== null || val === null || val === undefined || String(val).trim() === '') continue;
        out[target] = val;
    }
    return out;
}

function renderSelectedModelSamplingSettings() {
    const modalId = 'modelDetailModal';
    const select = document.getElementById(modalId + 'SamplingConfigSelect');
    const details = document.getElementById(modalId + 'SamplingDetails');
    const bundle = window.__modelDetailSamplingBundle;
    if (!select || !details || !bundle || !bundle.configs) return;
    const name = select.value || '';
    if (!name) {
        details.style.display = 'none';
        details.innerHTML = '';
        return;
    }
    details.style.display = 'grid';
    const cfg = bundle.configs[name] || {};
    const s = extractSamplingSettingsFromConfig(cfg);
    const safe = (v) => escapeAttrCompat(v === null || v === undefined ? '' : String(v));
    const fields = [
        ['Temp', '--temp', 'SamplingFieldTemp', s.temp],
        ['Top-P', '--top-p', 'SamplingFieldTopP', s.topP],
        ['Top-K', '--top-k', 'SamplingFieldTopK', s.topK],
        ['Min-P', '--min-p', 'SamplingFieldMinP', s.minP],
        ['Presence Penalty', '--presence-penalty', 'SamplingFieldPresencePenalty', s.presencePenalty],
        ['Repeat Penalty', '--repeat-penalty', 'SamplingFieldRepeatPenalty', s.repeatPenalty],
        ['Frequency Penalty', '--frequency-penalty', 'SamplingFieldFrequencyPenalty', s.frequencyPenalty]
    ];
    details.innerHTML = fields.map((item) => {
        const id = modalId + item[2];
        const value = item[3] === null || item[3] === undefined ? '' : String(item[3]);
        return `<div style="padding:8px 10px; border:1px solid #e5e7eb; border-radius:0.75rem;"><div style="font-size:12px; color:#6b7280; margin-bottom:6px;">${safe(item[0])} ${safe(item[1])}</div><input class="form-control" id="${safe(id)}" value="${safe(value)}" /></div>`;
    }).join('');
    const inputs = details.querySelectorAll('input');
    for (let i = 0; i < inputs.length; i++) {
        inputs[i].oninput = () => updateSamplingBundleFromForm();
    }
    updateSamplingBundleFromForm();
}

function getSamplingDraftFromForm() {
    const modalId = 'modelDetailModal';
    const read = (suffix) => {
        const el = document.getElementById(modalId + suffix);
        if (!el) return '';
        return el.value === null || el.value === undefined ? '' : String(el.value).trim();
    };
    const out = {};
    const setNumber = (key, raw, intOnly) => {
        if (!raw) return;
        const n = intOnly ? parseInt(raw, 10) : parseFloat(raw);
        if (!Number.isNaN(n) && Number.isFinite(n)) {
            out[key] = n;
        }
    };
    setNumber('temperature', read('SamplingFieldTemp'), false);
    setNumber('top_p', read('SamplingFieldTopP'), false);
    setNumber('top_k', read('SamplingFieldTopK'), true);
    setNumber('min_p', read('SamplingFieldMinP'), false);
    setNumber('presence_penalty', read('SamplingFieldPresencePenalty'), false);
    setNumber('repeat_penalty', read('SamplingFieldRepeatPenalty'), false);
    setNumber('frequency_penalty', read('SamplingFieldFrequencyPenalty'), false);
    return out;
}

function updateSamplingBundleFromForm() {
    const modalId = 'modelDetailModal';
    const select = document.getElementById(modalId + 'SamplingConfigSelect');
    const bundle = window.__modelDetailSamplingBundle;
    if (!select || !bundle || !bundle.configs) return;
    const name = select.value || '';
    if (!name || !bundle.configs[name]) return;
    bundle.configs[name] = getSamplingDraftFromForm();
}

function loadModelSamplingSettings() {
    const modelId = window.__modelDetailModelId;
    const modalId = 'modelDetailModal';
    const select = document.getElementById(modalId + 'SamplingConfigSelect');
    const details = document.getElementById(modalId + 'SamplingDetails');
    if (!modelId || !select || !details) return;
    details.innerHTML = `<div style="grid-column:1 / -1; padding:12px; border:1px solid #e5e7eb; border-radius:0.75rem; color:#6b7280;">${escapeAttrCompat(t('common.loading', '加载中...'))}</div>`;
    const configReq = fetch('/api/sys/model/sampling/setting/list').then(r => r.json());
    const selectedReq = fetch(`/api/sys/model/sampling/setting/get?modelId=${encodeURIComponent(modelId)}`).then(r => r.json());
    Promise.all([configReq, selectedReq])
        .then(([configRes, selectedRes]) => {
            if (!(configRes && configRes.success)) {
                details.innerHTML = `<div style="grid-column:1 / -1; padding:12px; border:1px solid #fecaca; border-radius:0.75rem; color:#b91c1c;">${escapeAttrCompat(t('common.request_failed', '请求失败'))}</div>`;
                return;
            }
            const samplingConfigs = configRes.data && typeof configRes.data === 'object' && configRes.data.configs && typeof configRes.data.configs === 'object'
                ? configRes.data.configs
                : {};
            const bundle = normalizeModelConfigBundle({ configs: samplingConfigs, selectedConfig: '' });
            window.__modelDetailSamplingBundle = bundle;
            const names = Object.keys(bundle.configs || {});
            const offOption = `<option value="">${escapeAttrCompat(t('modal.model_detail.sampling.off', '关闭功能'))}</option>`;
            const dynamicOptions = names.map((name) => `<option value="${escapeAttrCompat(name)}">${escapeAttrCompat(name)}</option>`).join('');
            select.innerHTML = offOption + dynamicOptions;
            const selectedName = selectedRes && selectedRes.success && selectedRes.data && selectedRes.data.samplingConfigName
                ? String(selectedRes.data.samplingConfigName).trim()
                : '';
            select.value = (selectedName && bundle.configs[selectedName]) ? selectedName : '';
            renderSelectedModelSamplingSettings();
        })
        .catch(() => {
            details.innerHTML = `<div style="grid-column:1 / -1; padding:12px; border:1px solid #fecaca; border-radius:0.75rem; color:#b91c1c;">${escapeAttrCompat(t('common.request_failed', '请求失败'))}</div>`;
        });
}

function saveModelSamplingSelection() {
    const modelId = window.__modelDetailModelId;
    const modalId = 'modelDetailModal';
    const select = document.getElementById(modalId + 'SamplingConfigSelect');
    if (!modelId || !select) return;
    const samplingConfigName = select.value == null ? '' : String(select.value).trim();
    fetch('/api/sys/model/sampling/setting/set', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ modelId, samplingConfigName })
    })
        .then(r => r.json())
        .then(res => {
            if (res && res.success) {
                showToast(t('toast.success', '成功'), t('modal.model_detail.sampling.saved', '采样设定已保存'), 'success');
            } else {
                showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('common.save_failed', '保存失败'), 'error');
            }
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
}

function addModelSamplingConfig() {
    const modalId = 'modelDetailModal';
    const select = document.getElementById(modalId + 'SamplingConfigSelect');
    if (!select) return;
    const configName = prompt(t('modal.model_detail.sampling.new_name_prompt', '请输入新的采样配置名称'));
    const samplingConfigName = configName === null || configName === undefined ? '' : String(configName).trim();
    if (!samplingConfigName) return;
    const bundle = window.__modelDetailSamplingBundle;
    const sampling = bundle && bundle.configs && bundle.configs[select.value] ? bundle.configs[select.value] : {};
    fetch('/api/sys/model/sampling/setting/add', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ samplingConfigName, sampling })
    })
        .then(r => r.json())
        .then(res => {
            if (res && res.success) {
                if (!window.__modelDetailSamplingBundle) window.__modelDetailSamplingBundle = { configs: {} };
                if (!window.__modelDetailSamplingBundle.configs) window.__modelDetailSamplingBundle.configs = {};
                const savedSampling = res.data && res.data.sampling && typeof res.data.sampling === 'object' ? res.data.sampling : sampling;
                window.__modelDetailSamplingBundle.configs[samplingConfigName] = savedSampling;
                let option = null;
                for (let i = 0; i < select.options.length; i++) {
                    if (select.options[i].value === samplingConfigName) {
                        option = select.options[i];
                        break;
                    }
                }
                if (!option) {
                    option = document.createElement('option');
                    option.value = samplingConfigName;
                    option.textContent = samplingConfigName;
                    select.appendChild(option);
                }
                select.value = samplingConfigName;
                renderSelectedModelSamplingSettings();
                showToast(t('toast.success', '成功'), t('modal.model_detail.sampling.added', '采样配置已新增'), 'success');
            } else {
                showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('common.save_failed', '保存失败'), 'error');
            }
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
}

function updateModelSamplingConfig() {
    const modalId = 'modelDetailModal';
    const select = document.getElementById(modalId + 'SamplingConfigSelect');
    if (!select) return;
    const samplingConfigName = select.value == null ? '' : String(select.value).trim();
    if (!samplingConfigName) {
        showToast(t('toast.info', '提示'), t('modal.model_detail.sampling.select_first', '请先选择一个采样配置'), 'info');
        return;
    }
    const sampling = getSamplingDraftFromForm();
    fetch('/api/sys/model/sampling/setting/add', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ samplingConfigName, sampling })
    })
        .then(r => r.json())
        .then(res => {
            if (res && res.success) {
                if (!window.__modelDetailSamplingBundle) window.__modelDetailSamplingBundle = { configs: {} };
                if (!window.__modelDetailSamplingBundle.configs) window.__modelDetailSamplingBundle.configs = {};
                window.__modelDetailSamplingBundle.configs[samplingConfigName] = sampling;
                showToast(t('toast.success', '成功'), t('modal.model_detail.sampling.updated', '采样配置已更新'), 'success');
            } else {
                showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('common.save_failed', '保存失败'), 'error');
            }
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
}

function deleteModelSamplingConfig() {
    const modalId = 'modelDetailModal';
    const select = document.getElementById(modalId + 'SamplingConfigSelect');
    if (!select) return;
    const samplingConfigName = select.value == null ? '' : String(select.value).trim();
    if (!samplingConfigName) {
        showToast(t('toast.info', '提示'), t('modal.model_detail.sampling.select_first', '请先选择一个采样配置'), 'info');
        return;
    }
    if (!confirm(t('confirm.delete', '确定要删除吗？') + `\n${samplingConfigName}`)) return;
    fetch('/api/sys/model/sampling/setting/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ samplingConfigName })
    })
        .then(r => r.json())
        .then(res => {
            if (res && res.success) {
                if (window.__modelDetailSamplingBundle && window.__modelDetailSamplingBundle.configs) {
                    delete window.__modelDetailSamplingBundle.configs[samplingConfigName];
                }
                for (let i = select.options.length - 1; i >= 0; i--) {
                    if (select.options[i].value === samplingConfigName) {
                        select.remove(i);
                    }
                }
                select.value = '';
                renderSelectedModelSamplingSettings();
                showToast(t('toast.success', '成功'), t('common.delete_success', '删除成功'), 'success');
            } else {
                showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('common.delete_failed', '删除失败'), 'error');
            }
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
}

function loadModelChatTemplate(showEmptyTip = false) {
    const modelId = window.__modelDetailModelId;
    const el = document.getElementById('modelDetailModalChatTemplateTextarea');
    if (!modelId || !el) return;
    el.value = '';
    fetch(`/api/model/template/get?modelId=${encodeURIComponent(modelId)}`)
        .then(r => r.json())
        .then(res => {
            if (!(res && res.success)) {
                showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('common.request_failed', '请求失败'), 'error');
                return;
            }
            const d = res.data || {};
            if (showEmptyTip && d.exists === false) {
                showToast(t('toast.info', '提示'), t('modal.model_detail.chat_template.no_saved', '该模型暂无已保存的聊天模板'), 'info');
            }
            const tpl = d.chatTemplate !== undefined && d.chatTemplate !== null ? String(d.chatTemplate) : '';
            el.value = tpl;
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
}

function loadModelDefaultChatTemplate() {
    const modelId = window.__modelDetailModelId;
    const el = document.getElementById('modelDetailModalChatTemplateTextarea');
    if (!modelId || !el) return;
    fetch(`/api/model/template/default?modelId=${encodeURIComponent(modelId)}`)
        .then(r => r.json())
        .then(res => {
            if (!(res && res.success)) {
                showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('common.request_failed', '请求失败'), 'error');
                return;
            }
            const d = res.data || {};
            const tpl = d.chatTemplate !== undefined && d.chatTemplate !== null ? String(d.chatTemplate) : '';
            el.value = tpl;
            if (d.exists) showToast(t('toast.success', '成功'), t('modal.model_detail.chat_template.default_loaded', '已加载默认模板'), 'success');
            else showToast(t('toast.info', '提示'), t('modal.model_detail.chat_template.no_default', '该模型未提供默认模板'), 'info');
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
}

function saveModelChatTemplate() {
    const modelId = window.__modelDetailModelId;
    const el = document.getElementById('modelDetailModalChatTemplateTextarea');
    if (!modelId || !el) return;
    const text = el.value == null ? '' : String(el.value);
    if (!text.trim()) {
        showToast(t('toast.error', '错误'), t('modal.model_detail.chat_template.empty', '聊天模板不能为空；如需清空请使用“删除”按钮。'), 'error');
        el.focus();
        return;
    }

    const previewLimit = 300;
    const preview = text.length > previewLimit ? (text.slice(0, previewLimit) + '\n' + t('modal.model_detail.chat_template.truncated', '…(已截断)')) : text;
    if (!confirm(t('confirm.chat_template.save', '确认保存以下聊天模板吗？') + '\n\n' + preview)) return;
    fetch('/api/model/template/set', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ modelId, chatTemplate: text })
    })
        .then(r => r.json())
        .then(res => {
            if (res && res.success) {
                showToast(t('toast.success', '成功'), t('modal.model_detail.chat_template.saved', '聊天模板已保存'), 'success');
            } else {
                showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('common.save_failed', '保存失败'), 'error');
            }
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
}

function deleteModelChatTemplate() {
    const modelId = window.__modelDetailModelId;
    const el = document.getElementById('modelDetailModalChatTemplateTextarea');
    if (!modelId || !el) return;
    if (!confirm(t('confirm.chat_template.delete', '确定要删除该模型已保存的聊天模板吗？'))) return;
    fetch('/api/model/template/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ modelId })
    })
        .then(r => r.json())
        .then(res => {
            if (res && res.success) {
                const d = res.data || {};
                if (d.deleted) {
                    el.value = '';
                    showToast(t('toast.success', '成功'), t('modal.model_detail.chat_template.deleted', '聊天模板已删除'), 'success');
                } else if (d.existed === false) {
                    showToast(t('toast.info', '提示'), t('modal.model_detail.chat_template.no_saved', '该模型暂无已保存的聊天模板'), 'info');
                } else {
                    showToast(t('toast.info', '提示'), t('modal.model_detail.chat_template.not_deleted', '聊天模板未删除'), 'info');
                }
            } else {
                showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('common.delete_failed', '删除失败'), 'error');
            }
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
}

async function calculateModelTokens() {
    const modelId = window.__modelDetailModelId;
    const inputEl = document.getElementById('modelDetailModalTokenInput');
    const promptEl = document.getElementById('modelDetailModalTokenPromptOutput');
    const countEl = document.getElementById('modelDetailModalTokenCount');
    const btn = document.getElementById('modelDetailModalTokenCalcBtn');
    if (!modelId || !inputEl || !promptEl || !countEl || !btn) return;

    const userText = inputEl.value == null ? '' : String(inputEl.value);
    if (!userText.trim()) {
        showToast(t('toast.info', '提示'), t('modal.model_detail.token.input_required', '请输入文本内容'), 'info');
        inputEl.focus();
        return;
    }

    const prevText = btn.textContent;
    btn.disabled = true;
    btn.textContent = t('common.calculating', '计算中...');
    countEl.textContent = '...';
    promptEl.value = '';

    try {
        const applyRes = await fetch('/apply-template', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                modelId,
                messages: [{ role: 'user', content: userText }]
            })
        });
        const applyJson = await applyRes.json().catch(() => null);
        if (!applyRes.ok) {
            const msg = applyJson && (applyJson.error || applyJson.message) ? (applyJson.error || applyJson.message) : ('HTTP ' + applyRes.status);
            throw new Error(msg);
        }
        const prompt = applyJson && applyJson.prompt != null ? String(applyJson.prompt) : '';
        if (!prompt) throw new Error(t('modal.model_detail.token.missing_prompt', 'apply-template 响应缺少 prompt'));
        promptEl.value = prompt;

        const tokRes = await fetch('/tokenize', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                modelId,
                content: prompt,
                add_special: true,
                parse_special: true,
                with_pieces: false
            })
        });
        const tokJson = await tokRes.json().catch(() => null);
        if (!tokRes.ok) {
            const msg = tokJson && (tokJson.error || tokJson.message) ? (tokJson.error || tokJson.message) : ('HTTP ' + tokRes.status);
            throw new Error(msg);
        }
        if (!tokJson || !Array.isArray(tokJson.tokens)) throw new Error(t('modal.model_detail.token.missing_tokens', 'tokenize 响应缺少 tokens'));
        countEl.textContent = String(tokJson.tokens.length);
    } catch (e) {
        countEl.textContent = '-';
        showToast(t('toast.error', '错误'), e && e.message ? e.message : t('common.request_failed', '请求失败'), 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = prevText;
    }
}
