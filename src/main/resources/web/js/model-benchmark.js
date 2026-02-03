function openModelBenchmarkDialog(modelId, modelName) {
    window.__benchmarkModelId = modelId;
    window.__benchmarkModelName = modelName;
    if (typeof loadModel === 'function') {
        loadModel(modelId, modelName, 'benchmark');
        return;
    }
    const modalId = 'modelBenchmarkModal';
    let modal = document.getElementById(modalId);
    if (!modal) {
        modal = document.createElement('div');
        modal.id = modalId;
        modal.className = 'modal';
        modal.innerHTML = `
            <div class="modal-content load-model-modal benchmark-modal">
                <div class="modal-header">
                    <h3 class="modal-title"><i class="fas fa-tachometer-alt"></i> ${t('modal.model_benchmark.title', '模型性能测试')}</h3>
                    <button class="modal-close" onclick="closeModal('${modalId}')">&times;</button>
                </div>
                <div class="modal-body">
                    <form id="modelBenchmarkForm">
                        <div class="load-model-layout">
                            <div id="benchmarkBasicParamsContainer">
                                <div class="form-group">
                                    <label class="form-label">${t('modal.model_benchmark.form.model', '模型')}</label>
                                    <div class="form-control" id="benchmarkModelName" style="background-color: #f3f4f6;"></div>
                                </div>

                                <div class="form-group">
                                    <label class="form-label" for="benchmarkLlamaBinPathSelect">${t('modal.model_benchmark.form.llamacpp_version', 'Llama.cpp 版本')}</label>
                                    <select class="form-control" id="benchmarkLlamaBinPathSelect"></select>
                                </div>

                                <div class="form-group">
                                    <label class="form-label">${t('modal.model_benchmark.form.devices', '可用计算设备 (-dev)')}</label>
                                    <small class="form-text">${t('modal.model_benchmark.form.devices_help', '默认已勾选全部设备；取消勾选可排除设备；未选择设备时，使用 auto')}</small>
                                    <div id="benchmarkDeviceChecklist" style="border: 1px solid var(--border-color); border-radius: 0.75rem; padding: 0.75rem; max-height: 260px; overflow: auto;">
                                        <div class="settings-empty">${t('common.select_llamacpp_first', '请先选择 Llama.cpp 版本')}</div>
                                    </div>
                                </div>
                            </div>

                            <div id="benchmarkParamsContainer">
                                <div class="settings-empty">${t('common.loading', '加载中...')}</div>
                            </div>
                        </div>
                    </form>
                </div>
                <div class="modal-footer">
                    <button class="btn btn-secondary" onclick="closeModal('${modalId}')">${t('common.cancel', '取消')}</button>
                    <button class="btn btn-secondary" id="benchmarkResetBtn" onclick="resetModelBenchmarkForm()">${t('common.reset', '重置')}</button>
                    <button class="btn btn-primary" id="benchmarkRunBtn" onclick="submitModelBenchmark()">${t('modal.model_action.submit.benchmark', '开始测试')}</button>
                </div>
            </div>
        `;
        const root = document.getElementById('dynamicModalRoot') || document.body;
        root.appendChild(modal);
    }

    const nameEl = document.getElementById('benchmarkModelName');
    if (nameEl) nameEl.textContent = modelName || modelId;

    resetModelBenchmarkForm();
    if (typeof ensureBenchmarkParamsReady === 'function') {
        try { ensureBenchmarkParamsReady(); } catch (e) {}
    }

    const binSelect = document.getElementById('benchmarkLlamaBinPathSelect');
    if (!binSelect) {
        modal.classList.add('show');
        return;
    }

    binSelect.onchange = () => loadBenchmarkDevices(binSelect.value);

    binSelect.innerHTML = `<option value="">${t('common.loading', '加载中...')}</option>`;
    fetch('/api/llamacpp/list')
        .then(r => r.json())
        .then(listData => {
            const items = (listData && listData.success && listData.data) ? (listData.data.items || []) : [];
            const paths = (Array.isArray(items) ? items : [])
                .map(i => i && i.path !== undefined && i.path !== null ? String(i.path).trim() : '')
                .filter(p => p.length > 0);
            if (!paths.length) {
                binSelect.innerHTML = `<option value="">${t('common.not_configured_path', '未配置路径')}</option>`;
                loadBenchmarkDevices('');
                return;
            }
            binSelect.innerHTML = (Array.isArray(items) ? items : [])
                .map(i => {
                    const p = i && i.path !== undefined && i.path !== null ? String(i.path).trim() : '';
                    if (!p) return '';
                    const name = i && i.name !== undefined && i.name !== null ? String(i.name).trim() : '';
                    const desc = i && i.description !== undefined && i.description !== null ? String(i.description).trim() : '';
                    const text = name ? `${name} (${p})` : p;
                    const title = [name, p, desc].filter(Boolean).join('\n');
                    return `<option value="${escapeAttrCompat(p)}" title="${escapeAttrCompat(title)}">${escapeHtmlCompat(text)}</option>`;
                })
                .filter(Boolean)
                .join('');
            binSelect.value = paths[0];
            loadBenchmarkDevices(paths[0]);
        })
        .catch(() => {
            binSelect.innerHTML = `<option value="">${t('common.load_failed', '加载失败')}</option>`;
            loadBenchmarkDevices('');
        })
        .finally(() => {
            modal.classList.add('show');
        });
}

let benchmarkParamConfig = null;
let benchmarkParamConfigPromise = null;

function escapeHtmlCompat(str) {
    return String(str).replace(/[&<>"']/g, function(m) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]); });
}

function escapeAttrCompat(str) {
    return escapeHtmlCompat(str).replace(/`/g, '&#96;');
}

function loadBenchmarkParamConfig() {
    if (benchmarkParamConfigPromise) return benchmarkParamConfigPromise;
    benchmarkParamConfigPromise = fetch('/api/models/param/benchmark/list')
        .then(r => r.json())
        .then(d => {
            if (d && d.success && Array.isArray(d.params)) {
                benchmarkParamConfig = d.params;
                window.benchmarkParamConfig = d.params;
            } else {
                benchmarkParamConfig = [];
                window.benchmarkParamConfig = [];
            }
            return benchmarkParamConfig;
        })
        .catch(() => {
            benchmarkParamConfig = [];
            window.benchmarkParamConfig = [];
            return benchmarkParamConfig;
        });
    return benchmarkParamConfigPromise;
}

function getBenchmarkParamByFullName(fullName) {
    const list = Array.isArray(benchmarkParamConfig) ? benchmarkParamConfig : (Array.isArray(window.benchmarkParamConfig) ? window.benchmarkParamConfig : []);
    const key = fullName == null ? '' : String(fullName);
    if (!key) return null;
    for (let i = 0; i < list.length; i++) {
        const p = list[i];
        if (!p) continue;
        if (String(p.fullName || '') === key) return p;
    }
    return null;
}

function getBenchmarkDefault(fullName, fallback) {
    const p = getBenchmarkParamByFullName(fullName);
    const v = p && p.defaultValue !== undefined && p.defaultValue !== null ? String(p.defaultValue) : '';
    const trimmed = v.trim();
    return trimmed.length ? trimmed : (fallback == null ? '' : String(fallback));
}

function renderBenchmarkFieldFromParam(param, opts) {
    const p = param || {};
    const id = opts && opts.id ? String(opts.id) : '';
    const type = opts && opts.type ? String(opts.type) : String(p.type || 'STRING');
    const values = (opts && Array.isArray(opts.values)) ? opts.values : (Array.isArray(p.values) ? p.values : []);
    const defaultValue = (opts && opts.defaultValue !== undefined) ? String(opts.defaultValue || '') : String(p.defaultValue || '');
    const name = p.name != null ? String(p.name) : '';
    const desc = p.description != null ? String(p.description) : '';
    const fullName = p.fullName != null ? String(p.fullName) : '';
    const abbr = p.abbreviation != null ? String(p.abbreviation) : '';
    const displayName = (typeof t === 'function') ? t(name, abbr || fullName) : name;
    const description = (typeof t === 'function') ? t(desc, '') : desc;
    const dataFullNameAttr = fullName ? ` data-benchmark-fullname="${escapeAttrCompat(fullName)}"` : '';
    const labelSuffix = (abbr || fullName) ? ` (${escapeHtmlCompat(abbr || fullName)})` : '';
    const labelHtml = description
        ? `<label class="form-label" for="${escapeAttrCompat(id)}">${escapeHtmlCompat(displayName)}${labelSuffix} <i class="fas fa-question-circle" style="color: #DCDCDC; cursor: help; margin-left: 4px;" title="${escapeAttrCompat(description)}"></i></label>`
        : `<label class="form-label" for="${escapeAttrCompat(id)}">${escapeHtmlCompat(displayName)}${labelSuffix}</label>`;

    const groupStyle = opts && opts.groupStyle ? ` style="${opts.groupStyle}"` : '';
    let html = `<div class="form-group"${groupStyle}>${labelHtml}`;

    const kind = String(type || '').toUpperCase();
    if (kind === 'LOGIC') {
        const normalize = (raw) => {
            const v = raw == null ? '' : String(raw).trim().toLowerCase();
            if (v === '1' || v === 'true' || v === 'on' || v === 'yes') return 'true';
            return 'false';
        };
        const selected = normalize(defaultValue);
        html += `<select class="form-control" id="${escapeAttrCompat(id)}"${dataFullNameAttr}>`;
        html += `<option value="true"${selected === 'true' ? ' selected' : ''}>true</option>`;
        html += `<option value="false"${selected === 'false' ? ' selected' : ''}>false</option>`;
        html += `</select>`;
        if (opts && opts.helpText) html += `<small class="form-text">${escapeHtmlCompat(opts.helpText)}</small>`;
        html += `</div>`;
        return html;
    }

    if (values && values.length > 0) {
        html += `<select class="form-control" id="${escapeAttrCompat(id)}"${dataFullNameAttr}>`;
        for (let i = 0; i < values.length; i++) {
            const v = values[i] == null ? '' : String(values[i]);
            const selected = String(v) === String(defaultValue) ? ' selected' : '';
            html += `<option value="${escapeAttrCompat(v)}"${selected}>${escapeHtmlCompat(v)}</option>`;
        }
        html += `</select>`;
        if (opts && opts.helpText) html += `<small class="form-text">${escapeHtmlCompat(opts.helpText)}</small>`;
        html += `</div>`;
        return html;
    }

    if (kind === 'INTEGER') {
        const min = opts && opts.min !== undefined ? ` min="${escapeAttrCompat(opts.min)}"` : '';
        const step = opts && opts.step !== undefined ? ` step="${escapeAttrCompat(opts.step)}"` : '';
        const placeholder = opts && opts.placeholder ? ` placeholder="${escapeAttrCompat(opts.placeholder)}"` : '';
        html += `<input type="number" class="form-control" id="${escapeAttrCompat(id)}"${dataFullNameAttr}${min}${step} value="${escapeAttrCompat(defaultValue)}"${placeholder}>`;
        if (opts && opts.helpText) html += `<small class="form-text">${escapeHtmlCompat(opts.helpText)}</small>`;
        html += `</div>`;
        return html;
    }

    if (kind === 'FLOAT') {
        const step = opts && opts.step !== undefined ? ` step="${escapeAttrCompat(opts.step)}"` : ' step="0.01"';
        const placeholder = opts && opts.placeholder ? ` placeholder="${escapeAttrCompat(opts.placeholder)}"` : '';
        html += `<input type="number" class="form-control" id="${escapeAttrCompat(id)}"${dataFullNameAttr}${step} value="${escapeAttrCompat(defaultValue)}"${placeholder}>`;
        if (opts && opts.helpText) html += `<small class="form-text">${escapeHtmlCompat(opts.helpText)}</small>`;
        html += `</div>`;
        return html;
    }

    const placeholder = opts && opts.placeholder ? ` placeholder="${escapeAttrCompat(opts.placeholder)}"` : '';
    html += `<input type="text" class="form-control" id="${escapeAttrCompat(id)}"${dataFullNameAttr} value="${escapeAttrCompat(defaultValue)}"${placeholder}>`;
    if (opts && opts.helpText) html += `<small class="form-text">${escapeHtmlCompat(opts.helpText)}</small>`;
    html += `</div>`;
    return html;
}

function renderBenchmarkParamsContainer() {
    const unifiedModal = document.getElementById('loadModelModal');
    const inUnified = !!(unifiedModal && unifiedModal.classList && unifiedModal.classList.contains('show') && window.__modelActionMode === 'benchmark');
    const standaloneModal = document.getElementById('modelBenchmarkModal');
    const root = inUnified ? unifiedModal : standaloneModal;
    const container = root ? root.querySelector('#benchmarkParamsContainer') : document.getElementById('benchmarkParamsContainer');
    if (!container) return;

    const sorted = (Array.isArray(benchmarkParamConfig) ? benchmarkParamConfig : []).slice().sort((a, b) => (a && a.sort ? a.sort : 0) - (b && b.sort ? b.sort : 0));
    const fields = [];
    const makeId = (fullName) => {
        const s = fullName == null ? '' : String(fullName);
        if (!s) return '';
        return 'benchmark_param_' + s.replace(/[^a-zA-Z0-9]+/g, '_').replace(/^_+|_+$/g, '');
    };

    for (let i = 0; i < sorted.length; i++) {
        const p = sorted[i];
        if (!p) continue;
        const fullName = p.fullName == null ? '' : String(p.fullName);
        if (!fullName) continue;
        const id = makeId(fullName);
        const type = p.type == null ? '' : String(p.type).toUpperCase();
        const opts = { id: id };
        fields.push(renderBenchmarkFieldFromParam(p, opts));
    }

    if (!fields.length) {
        container.innerHTML = `<div class="settings-empty">${t('modal.model_benchmark.params.empty', '无可用参数')}</div>`;
        return;
    }

    container.innerHTML = `<div style="display: grid; grid-template-columns: 1fr 1fr; gap: 1rem;">${fields.join('')}</div>`;
}

function ensureBenchmarkParamsReady() {
    return loadBenchmarkParamConfig().then(() => {
        renderBenchmarkParamsContainer();
        resetModelBenchmarkForm();
    });
}

function resetModelBenchmarkForm() {
    const unifiedModal = document.getElementById('loadModelModal');
    const inUnified = !!(unifiedModal && unifiedModal.classList && unifiedModal.classList.contains('show') && window.__modelActionMode === 'benchmark');
    const form = inUnified ? (unifiedModal ? unifiedModal.querySelector('form') : null) : document.getElementById('modelBenchmarkForm');
    if (form && !inUnified) form.reset();
    const root = inUnified ? unifiedModal : (document.getElementById('modelBenchmarkModal') || document);

    const cfgList = Array.isArray(benchmarkParamConfig) ? benchmarkParamConfig : (Array.isArray(window.benchmarkParamConfig) ? window.benchmarkParamConfig : []);
    for (let i = 0; i < cfgList.length; i++) {
        const p = cfgList[i];
        if (!p) continue;
        const fullName = p.fullName == null ? '' : String(p.fullName);
        if (!fullName) continue;
        const selector = '[data-benchmark-fullname="' + fullName.replace(/"/g, '\\"') + '"]';
        const el = (root && root.querySelector) ? root.querySelector(selector) : document.querySelector(selector);
        if (!el) continue;
        const type = p.type == null ? '' : String(p.type).toUpperCase();
        const def = p.defaultValue == null ? '' : String(p.defaultValue);
        if (type === 'LOGIC') {
            const v = def.trim().toLowerCase();
            el.value = (v === '1' || v === 'true' || v === 'on' || v === 'yes') ? 'true' : 'false';
        } else if ('value' in el) {
            el.value = def;
        }
    }

    const deviceListEl = document.getElementById('benchmarkDeviceChecklist');
    if (deviceListEl) {
        Array.from(deviceListEl.querySelectorAll('input[type="checkbox"][data-device-value]')).forEach(cb => {
            cb.checked = true;
            cb.disabled = false;
        });
    }

    const deviceChecklistEl = document.getElementById('deviceChecklist');
    if (deviceChecklistEl) {
        Array.from(deviceChecklistEl.querySelectorAll('input[type="checkbox"][data-device-key]')).forEach(cb => {
            cb.checked = true;
            cb.disabled = false;
        });
    }
}

function quoteArgIfNeeded(value) {
    const v = value === null || value === undefined ? '' : String(value);
    if (!v) return '';
    if (!/\s|"/.test(v)) return v;
    return '"' + v.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
}

function isTruthyLogicValue(value) {
    const v = value === null || value === undefined ? '' : String(value).trim().toLowerCase();
    return v === '1' || v === 'true' || v === 'on' || v === 'yes';
}

function getBenchmarkParamFieldEl(root, fullName) {
    const selector = '[data-benchmark-fullname="' + String(fullName).replace(/"/g, '\\"') + '"]';
    if (root && root.querySelector) {
        const el = root.querySelector(selector);
        if (el) return el;
    }
    return document.querySelector(selector);
}

function buildBenchmarkCmdFromDynamicFields(root, cfgList) {
    const list = (Array.isArray(cfgList) ? cfgList : []).slice().sort((a, b) => (a && a.sort ? a.sort : 0) - (b && b.sort ? b.sort : 0));
    const cmdParts = [];
    for (let i = 0; i < list.length; i++) {
        const p = list[i];
        if (!p) continue;
        const fullName = p.fullName == null ? '' : String(p.fullName);
        if (!fullName) continue;
        const el = getBenchmarkParamFieldEl(root, fullName);
        if (!el || !('value' in el)) continue;
        const type = p.type == null ? '' : String(p.type).toUpperCase();
        const rawValue = String(el.value || '');
        if (type === 'LOGIC') {
            if (isTruthyLogicValue(rawValue)) cmdParts.push(fullName);
            continue;
        }
        const trimmed = rawValue.trim();
        if (!trimmed) continue;
        cmdParts.push(fullName, quoteArgIfNeeded(trimmed));
    }
    return cmdParts.join(' ').trim();
}

function appendBenchmarkDeviceArgs(cmdParts, listEl) {
    if (!listEl) return;
    const hasDeviceValue = !!listEl.querySelector('input[type="checkbox"][data-device-value]');
    const hasDeviceKey = !!listEl.querySelector('input[type="checkbox"][data-device-key]');
    let values = [];
    let totalCount = 0;
    if (hasDeviceValue) {
        const all = Array.from(listEl.querySelectorAll('input[type="checkbox"][data-device-value]'));
        totalCount = all.length;
        values = all
            .filter(cb => cb.checked)
            .map(cb => cb.getAttribute('data-device-value'))
            .filter(v => v && String(v).trim().length > 0);
    } else if (hasDeviceKey) {
        const all = Array.from(listEl.querySelectorAll('input[type="checkbox"][data-device-key]'));
        totalCount = all.length;
        values = all
            .filter(cb => cb.checked)
            .map(cb => cb.getAttribute('data-device-key'))
            .map(v => {
                if (v === null || v === undefined) return '';
                return String(v).trim().split(':')[0];
            })
            .filter(v => v && String(v).trim().length > 0 && v !== 'All' && v !== '-1');
    }
    if (values.length > 0 && values.length < totalCount) {
        cmdParts.push('-dev', values.join('/'));
    }
}

function loadBenchmarkDevices(llamaBinPath) {
    const listEl = document.getElementById('benchmarkDeviceChecklist');
    if (!listEl) return;

    if (!llamaBinPath) {
        listEl.innerHTML = `<div class="settings-empty">${t('common.select_llamacpp_first', '请先选择 Llama.cpp 版本')}</div>`;
        return;
    }

    listEl.innerHTML = `<div class="settings-empty">${t('common.loading', '加载中...')}</div>`;
    fetch('/api/model/device/list?llamaBinPath=' + encodeURIComponent(llamaBinPath))
        .then(r => r.json())
        .then(d => {
            if (!d || !d.success) {
                listEl.innerHTML = `<div class="settings-empty">${t('common.load_failed', '加载失败')}</div>`;
                return;
            }
            const devices = (d.data && d.data.devices) ? d.data.devices : [];
            if (!devices.length) {
                listEl.innerHTML = `<div class="settings-empty">${t('common.no_devices', '未发现可用设备')}</div>`;
                return;
            }
            const html = devices.map((raw, idx) => {
                const line = (raw == null) ? '' : String(raw);
                const trimmed = line.trim();
                let value = trimmed.split(/\s+/)[0] || '';
                if (value.endsWith(':')) value = value.slice(0, -1);
                if (!value) value = String(idx);
                const safeId = 'benchmarkDevice_' + idx;
                return `
                    <label style="display:flex; align-items:flex-start; gap:10px; padding:6px 4px;">
                        <input type="checkbox" id="${safeId}" data-device-value="${value}" checked>
                        <span style="font-size: 13px; color: var(--text-secondary);">${trimmed || value}</span>
                    </label>
                `;
            }).join('');
            listEl.innerHTML = html;
        })
        .catch(() => {
            listEl.innerHTML = `<div class="settings-empty">${t('common.load_failed', '加载失败')}</div>`;
        });
}

function submitModelBenchmark() {
    const unifiedModal = document.getElementById('loadModelModal');
    const inUnified = !!(unifiedModal && unifiedModal.classList && unifiedModal.classList.contains('show') && window.__modelActionMode === 'benchmark');

    const modelId = inUnified ? ((document.getElementById('modelId') && document.getElementById('modelId').value) ? String(document.getElementById('modelId').value).trim() : '') : window.__benchmarkModelId;
    const modelName = inUnified ? ((document.getElementById('modelName') && document.getElementById('modelName').value) ? String(document.getElementById('modelName').value).trim() : '') : window.__benchmarkModelName;
    if (!modelId) {
        showToast(t('toast.error', '错误'), t('modal.model_benchmark.missing_model', '未选择模型'), 'error');
        return;
    }

    const btn = inUnified ? document.getElementById('modelActionSubmitBtn') : document.getElementById('benchmarkRunBtn');
    const binSelect = (inUnified ? document.getElementById('llamaBinPathSelect') : document.getElementById('benchmarkLlamaBinPathSelect')) || document.getElementById('benchmarkLlamaBinPathSelect') || document.getElementById('llamaBinPathSelect');
    const llamaBinPath = binSelect ? (binSelect.value || '').trim() : '';
    if (!llamaBinPath) {
        showToast(t('toast.error', '错误'), t('modal.model_benchmark.missing_llamacpp_path', '请先选择 Llama.cpp 版本路径'), 'error');
        return;
    }

    const cfgList = Array.isArray(benchmarkParamConfig) ? benchmarkParamConfig : (Array.isArray(window.benchmarkParamConfig) ? window.benchmarkParamConfig : []);
    const root = inUnified ? unifiedModal : (document.getElementById('modelBenchmarkModal') || document);
    const cmdParts = [];
    const dynamicCmd = buildBenchmarkCmdFromDynamicFields(root, cfgList);
    if (dynamicCmd) cmdParts.push(dynamicCmd);
    const listEl = document.getElementById('benchmarkDeviceChecklist') || document.getElementById('deviceChecklist');
    appendBenchmarkDeviceArgs(cmdParts, listEl);
    const cmd = cmdParts.join(' ').trim();
    if (!cmd) {
        showToast(t('toast.error', '错误'), t('modal.model_benchmark.missing_cmd', '缺少必需的cmd参数'), 'error');
        return;
    }
    const payload = { modelId: modelId, llamaBinPath: llamaBinPath, cmd: cmd };

    if (btn) {
        btn.disabled = true;
        btn.innerHTML = t('modal.model_benchmark.running', '测试中...');
    }
    showToast(t('toast.info', '提示'), t('modal.model_benchmark.started', '已开始模型测试'), 'info');

    fetch('/api/models/benchmark', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    })
        .then(r => r.json())
        .then(res => {
            if (!res || !res.success) {
                const message = res && res.error ? res.error : t('modal.model_benchmark.failed', '模型测试失败');
                showToast(t('toast.error', '错误'), message, 'error');
            } else {
                const data = res.data || {};
                closeModal(inUnified ? 'loadModelModal' : 'modelBenchmarkModal');
                showModelBenchmarkResultModal(modelId, modelName, data);
            }
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        })
        .then(() => {
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = t('modal.model_action.submit.benchmark', '开始测试');
            }
        });
}

function openModelBenchmarkList(modelId, modelName) {
    const modalId = 'modelBenchmarkCompareModal';
    let modal = document.getElementById(modalId);
    if (!modal) {
        const isMobileView = !!document.getElementById('mobileMainModels') || /index-mobile\.html$/i.test((location && location.pathname) ? location.pathname : '');
        const layoutStyle = isMobileView
            ? 'display:flex; flex-direction:column; gap:12px; flex:1; min-height:0;'
            : 'display:flex; gap:16px; height:60vh;';
        const sidebarStyle = isMobileView
            ? 'border:1px solid #e5e7eb; border-radius:0.75rem; overflow:hidden; background:#f9fafb; flex:1; min-height:0;'
            : 'width:25%; border:1px solid #e5e7eb; border-radius:0.75rem; overflow:hidden; background:#f9fafb;';
        const listStyle = isMobileView
            ? 'flex:1; min-height:0; overflow:auto; font-size:13px; color:#374151;'
            : 'max-height:calc(60vh - 36px); overflow:auto; font-size:13px; color:#374151;';
        const preStyle = isMobileView
            ? 'flex:1; min-height:0; overflow:auto; font-size:13px; background:#111827; color:#e5e7eb; padding:10px; border-radius:0.75rem;'
            : 'flex:1; max-height:calc(60vh - 36px); overflow:auto; font-size:13px; background:#111827; color:#e5e7eb; padding:10px; border-radius:0.75rem;';

        modal = document.createElement('div');
        modal.id = modalId;
        modal.className = 'modal';
        modal.innerHTML = `
            <div class="modal-content" style="min-width: 100%; max-width: 100%;">
                <div class="modal-header">
                    <h3 class="modal-title"><i class="fas fa-file-alt"></i> ${t('modal.model_benchmark.results_compare.title', '模型测试结果对比')}</h3>
                    <button class="modal-close" onclick="closeModal('${modalId}')">&times;</button>
                </div>
                <div class="modal-body">
                    <div style="${layoutStyle}">
                        <div style="${sidebarStyle}">
                            <div style="padding:8px 10px; border-bottom:1px solid #e5e7eb; font-size:13px; color:#374151;">${t('modal.model_benchmark.results_compare.files', '测试结果文件')}</div>
                            <div id="${modalId}List" style="${listStyle}">${t('common.loading', '加载中...')}</div>
                        </div>
                        <div style="flex:1; display:flex; flex-direction:column; min-width:0;">
                            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
                                <div id="${modalId}ModelInfo" style="font-size:14px; color:#374151;"></div>
                                <div>
                                    <button class="btn btn-secondary" style="padding:4px 10px; font-size:12px;" onclick="clearBenchmarkResultContent()">${t('modal.model_benchmark.results_compare.clear', '清空内容')}</button>
                                </div>
                            </div>
                            <pre id="${modalId}Content" style="${preStyle}"></pre>
                        </div>
                    </div>
                </div>
                <div class="modal-footer">
                    <button class="btn btn-secondary" onclick="closeModal('${modalId}')">${t('common.close', '关闭')}</button>
                </div>
            </div>`;
        document.body.appendChild(modal);
    }
    window.__benchmarkModelId = modelId;
    window.__benchmarkModelName = modelName;
    const listEl = document.getElementById(modalId + 'List');
    const modelInfoEl = document.getElementById(modalId + 'ModelInfo');
    if (modelInfoEl) {
        const name = modelName || modelId;
        modelInfoEl.textContent = name ? (t('modal.model_benchmark.results_compare.current_model', '当前模型: ') + name) : '';
    }
    if (listEl) listEl.innerHTML = t('common.loading', '加载中...');
    modal.classList.add('show');
    fetch('/api/models/benchmark/list?modelId=' + encodeURIComponent(modelId))
        .then(r => r.json())
        .then(d => {
            if (!d.success) {
                showToast(t('toast.error', '错误'), d.error || t('modal.model_benchmark.results_compare.list_failed', '获取测试结果列表失败'), 'error');
                return;
            }
            const files = (d.data && d.data.files) ? d.data.files : [];
            if (!files.length) {
                listEl.innerHTML = `<div style="color:#666; padding:8px 10px;">${t('modal.model_benchmark.results_compare.no_files', '未找到测试结果文件')}</div>`;
                return;
            }
            let html = '<div style="border-top:1px solid #e5e7eb;">';
            files.forEach(item => {
                const fn = typeof item === 'string' ? item : (item && item.name) ? item.name : '';
                const size = (item && typeof item === 'object' && item.size != null) ? item.size : null;
                const modified = (item && typeof item === 'object' && item.modified) ? item.modified : '';
                const sizeText = size != null ? (typeof formatFileSize === 'function' ? formatFileSize(size) : (size + ' B')) : '';
                html += `
                    <div class="list-row" style="display:flex; justify-content:space-between; align-items:center; padding:8px 10px; border-bottom:1px solid #e5e7eb; background:#f9fafb;">
                        <div style="display:flex; flex-direction:column; gap:4px; max-width:65%;">
                            <span style="word-break:break-all;"><i class="fas fa-file-alt" style="margin-right:6px;"></i>${fn}</span>
                            <span style="color:#6b7280; font-size:12px;">${t('modal.model_benchmark.results_compare.modified_time', '修改时间: ')}${modified || '-'}</span>
                            <span style="color:#6b7280; font-size:12px;">${t('modal.model_benchmark.results_compare.size', '大小: ')}${sizeText || '-'}</span>
                        </div>
                        <div style="display:flex; flex-direction:column; gap:4px; align-items:flex-end;">
                            <button class="btn btn-primary" style="padding:2px 10px; font-size:12px;" onclick="loadBenchmarkResult(this.dataset.fn)" data-fn="${fn}">${t('modal.model_benchmark.results_compare.append', '追加')}</button>
                            <button class="btn btn-secondary" style="padding:2px 10px; font-size:12px;" onclick="deleteBenchmarkResult(this.dataset.fn, this)" data-fn="${fn}">${t('common.delete', '删除')}</button>
                        </div>
                    </div>`;
            });
            html += '</div>';
            listEl.innerHTML = html;
        }).catch(() => {
            showToast(t('toast.error', '错误'), t('modal.model_benchmark.results_compare.list_network_failed', '网络错误，获取测试结果列表失败'), 'error');
        });
}

function deleteBenchmarkResult(fileName, btn) {
    if (!fileName) {
        showToast(t('toast.error', '错误'), t('modal.model_benchmark.results_compare.invalid_filename', '无效的文件名'), 'error');
        return;
    }
    if (!confirm(t('confirm.benchmark_result.delete', '确定要删除该测试结果文件吗？'))) {
        return;
    }
    btn.disabled = true;
    fetch('/api/models/benchmark/delete?fileName=' + encodeURIComponent(fileName), {
        method: 'POST'
    }).then(r => r.json()).then(d => {
        if (!d.success) {
            showToast(t('toast.error', '错误'), d.error || t('modal.model_benchmark.results_compare.delete_failed', '删除测试结果失败'), 'error');
            btn.disabled = false;
            return;
        }
        const row = btn.closest('.list-row');
        if (row && row.parentElement) {
            row.parentElement.removeChild(row);
        }
        showToast(t('toast.success', '成功'), t('modal.model_benchmark.results_compare.deleted', '测试结果已删除'), 'success');
    }).catch(() => {
        showToast(t('toast.error', '错误'), t('modal.model_benchmark.results_compare.delete_network_failed', '网络错误，删除测试结果失败'), 'error');
        btn.disabled = false;
    });
}

function clearBenchmarkResultContent() {
    const modalId = 'modelBenchmarkCompareModal';
    const contentEl = document.getElementById(modalId + 'Content');
    if (contentEl) {
        contentEl.textContent = '';
    }
}

function appendBenchmarkResultBlock(modelId, modelName, data) {
    const modalId = 'modelBenchmarkCompareModal';
    const contentEl = document.getElementById(modalId + 'Content');
    if (!contentEl) {
        return;
    }
    const name = modelName || modelId || '';
    const d = data || {};
    let text = '';
    const existing = contentEl.textContent || '';
    if (existing.trim().length > 0) {
        text += '\n\n';
    }
    const fileName = d.fileName || '';
    text += '==============================\n';
    if (fileName) {
        text += t('modal.model_benchmark.results_compare.label.file', '文件: ') + fileName + '\n';
    }
    if (name) {
        text += t('modal.model_benchmark.results_compare.label.model', '模型: ') + name + '\n';
    }
    if (d.modelId || modelId) {
        text += t('modal.model_benchmark.results_compare.label.model_id', '模型ID: ') + (d.modelId || modelId || '') + '\n';
    }
    if (d.commandStr) {
        text += '\n' + t('modal.model_benchmark.results_compare.label.command', '命令:') + '\n' + d.commandStr + '\n';
    } else if (d.command && d.command.length) {
        text += '\n' + t('modal.model_benchmark.results_compare.label.command', '命令:') + '\n' + d.command.join(' ') + '\n';
    }
    if (d.exitCode != null) {
        text += '\n' + t('modal.model_benchmark.results_compare.label.exit_code', '退出码: ') + d.exitCode + '\n';
    }
    if (d.savedPath) {
        text += '\n' + t('modal.model_benchmark.results_compare.label.saved_path', '保存文件: ') + d.savedPath + '\n';
    }
    if (d.rawOutput) {
        text += '\n' + t('modal.model_benchmark.results_compare.label.raw_output', '原始输出:') + '\n' + d.rawOutput + '\n';
    }
    contentEl.textContent += text;
}

function loadBenchmarkResult(fileName) {
    const modelId = window.__benchmarkModelId;
    const modelName = window.__benchmarkModelName;
    if (!fileName) {
        showToast(t('toast.error', '错误'), t('modal.model_benchmark.results_compare.invalid_filename', '无效的文件名'), 'error');
        return;
    }
    fetch('/api/models/benchmark/get?fileName=' + encodeURIComponent(fileName))
        .then(r => r.json())
        .then(d => {
            if (!d.success) {
                showToast(t('toast.error', '错误'), d.error || t('modal.model_benchmark.results_compare.read_failed', '读取测试结果失败'), 'error');
                return;
            }
            const data = d.data || {};
            appendBenchmarkResultBlock(modelId, modelName, data);
        }).catch(() => {
            showToast(t('toast.error', '错误'), t('modal.model_benchmark.results_compare.read_network_failed', '网络错误，读取测试结果失败'), 'error');
        });
}

function showModelBenchmarkResultModal(modelId, modelName, data) {
    openModelBenchmarkList(modelId, modelName);
    appendBenchmarkResultBlock(modelId, modelName, data);
}
