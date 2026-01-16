function viewModelDetails(modelId) {
    fetch(`/api/models/details?modelId=${modelId}`).then(r => r.json()).then(data => {
        if (data.success) {
            const model = data.model;
            window.__modelDetailModelId = modelId;
            showModelDetailModal(model);
        } else { showToast('错误', data.error, 'error'); }
    });
}

function showModelDetailModal(model) {
    const modalId = 'modelDetailModal';
    let modal = document.getElementById(modalId);
    if (!modal) {
        modal = document.createElement('div'); modal.id = modalId; modal.className = 'modal';
        modal.innerHTML = `<div class="modal-content model-detail"><div class="modal-header"><h3 class="modal-title">模型详情</h3><button class="modal-close" onclick="closeModal('${modalId}')">&times;</button></div><div class="modal-body" id="${modalId}Content"></div><div class="modal-footer"><button class="btn btn-secondary" onclick="closeModal('${modalId}')">关闭</button></div></div>`;
        document.body.appendChild(modal);
    }
    const content = document.getElementById(modalId + 'Content');
    let tabs = `<div style="display:flex; gap:8px; margin-bottom:12px;">` +
                `<button class="btn btn-secondary" id="${modalId}TabInfo">概览</button>` +
                `<button class="btn btn-secondary" id="${modalId}TabMetrics">metrics</button>` +
                `<button class="btn btn-secondary" id="${modalId}TabProps">props</button>` +
                `<button class="btn btn-secondary" id="${modalId}TabChatTemplate">聊天模板</button>` +
                `</div>`;
    let wrapperStart = `<div style="display:flex; flex-direction:column; height:60vh; min-height:60vh;">`;
    let bodyStart = `<div style="flex:1; min-height:0;">`;
    let infoPanel = `<div id="${modalId}InfoPanel" style="height:100%;">` +
                    `<div style="display:grid; grid-template-columns: 1fr 2fr; gap: 10px; height:100%; overflow:auto;">` +
                    `<div><strong>名称:</strong></div><div>${model.name}</div>` +
                    `<div><strong>路径:</strong></div><div style="word-break:break-all;">${model.path}</div>` +
                    `<div><strong>大小:</strong></div><div>${formatFileSize(model.size)}</div>` +
                    `${model.isLoaded ? `<div><strong>状态:</strong></div><div>已启动${model.port ? `（端口 ${model.port}）` : ''}</div>` : `<div><strong>状态:</strong></div><div>未启动</div>`}` +
                    `${model.startCmd ? `<div><strong>启动命令:</strong></div><div style="word-break:break-all; font-family: monospace;">${model.startCmd}</div>` : ``}` +
                    `${(() => { let s=''; if (model.metadata) { for (const [k,v] of Object.entries(model.metadata)) { s += `<div><strong>${k}:</strong></div><div style="word-break:break-all;">${v}</div>`; } } return s; })()}` +
                    `</div>` +
                    `</div>`;
    let metricsPanel = `<div id="${modalId}MetricsPanel" style="display:none; height:100%;">` +
                       `<div style="display:flex; gap:8px; margin-bottom:8px;">` +
                       `<button class="btn btn-primary" id="${modalId}MetricsFetchBtn">请求 metrics</button>` +
                       `</div>` +
                       `<pre id="${modalId}MetricsViewer" style="height:calc(100% - 48px); overflow:auto; font-size:13px; background:#111827; color:#e5e7eb; padding:10px; border-radius:0.75rem;"></pre>` +
                       `</div>`;
    let propsPanel = `<div id="${modalId}PropsPanel" style="display:none; height:100%;">` +
                       `<div style="display:flex; gap:8px; margin-bottom:8px;">` +
                       `<button class="btn btn-primary" id="${modalId}PropsFetchBtn">请求 props</button>` +
                       `</div>` +
                       `<pre id="${modalId}PropsViewer" style="height:calc(100% - 48px); overflow:auto; font-size:13px; background:#111827; color:#e5e7eb; padding:10px; border-radius:0.75rem;"></pre>` +
                       `</div>`;
    let chatTemplatePanel = `<div id="${modalId}ChatTemplatePanel" style="display:none; height:100%;">` +
                        `<div style="display:flex; gap:8px; margin-bottom:8px;">` +
                        `<button class="btn btn-primary" id="${modalId}ChatTemplateDefaultBtn">默认</button>` +
                        `<button class="btn btn-primary" id="${modalId}ChatTemplateReloadBtn">刷新</button>` +
                        `<button class="btn btn-primary" id="${modalId}ChatTemplateSaveBtn">保存</button>` +
                        `</div>` +
                        `<textarea class="form-control" id="${modalId}ChatTemplateTextarea" rows="18" placeholder="(可选)" style="height:calc(100% - 48px); resize: vertical;"></textarea>` +
                        `</div>`;
    let bodyEnd = `</div>`;
    let wrapperEnd = `</div>`;
    content.innerHTML = wrapperStart + tabs + bodyStart + infoPanel + metricsPanel + propsPanel + chatTemplatePanel + bodyEnd + wrapperEnd;
    modal.classList.add('show');
    const tabInfo = document.getElementById(modalId + 'TabInfo');
    const tabMetrics = document.getElementById(modalId + 'TabMetrics');
    const tabProps = document.getElementById(modalId + 'TabProps');
    const tabChatTemplate = document.getElementById(modalId + 'TabChatTemplate');
    const fetchBtn = document.getElementById(modalId + 'MetricsFetchBtn');
    const fetchPropsBtn = document.getElementById(modalId + 'PropsFetchBtn');
    const tplReloadBtn = document.getElementById(modalId + 'ChatTemplateReloadBtn');
    const tplDefaultBtn = document.getElementById(modalId + 'ChatTemplateDefaultBtn');
    const tplSaveBtn = document.getElementById(modalId + 'ChatTemplateSaveBtn');
    if (tabInfo) tabInfo.onclick = () => openModelDetailTab('info');
    if (tabMetrics) tabMetrics.onclick = () => { openModelDetailTab('metrics'); loadModelMetrics(); };
    if (tabProps) tabProps.onclick = () => { openModelDetailTab('props'); loadModelProps(); };
    if (tabChatTemplate) tabChatTemplate.onclick = () => { openModelDetailTab('chatTemplate'); loadModelChatTemplate(false); };
    if (fetchBtn) fetchBtn.onclick = () => loadModelMetrics();
    if (fetchPropsBtn) fetchPropsBtn.onclick = () => loadModelProps();
    if (tplReloadBtn) tplReloadBtn.onclick = () => loadModelChatTemplate(true);
    if (tplDefaultBtn) tplDefaultBtn.onclick = () => loadModelDefaultChatTemplate();
    if (tplSaveBtn) tplSaveBtn.onclick = () => saveModelChatTemplate();
    openModelDetailTab('info');
}

function openModelDetailTab(tab) {
    const modalId = 'modelDetailModal';
    const info = document.getElementById(modalId + 'InfoPanel');
    const metrics = document.getElementById(modalId + 'MetricsPanel');
    const props = document.getElementById(modalId + 'PropsPanel');
    const chatTemplate = document.getElementById(modalId + 'ChatTemplatePanel');
    const btnInfo = document.getElementById(modalId + 'TabInfo');
    const btnMetrics = document.getElementById(modalId + 'TabMetrics');
    const btnProps = document.getElementById(modalId + 'TabProps');
    const btnChatTemplate = document.getElementById(modalId + 'TabChatTemplate');
    if (info) info.style.display = tab === 'info' ? '' : 'none';
    if (metrics) metrics.style.display = tab === 'metrics' ? '' : 'none';
    if (props) props.style.display = tab === 'props' ? '' : 'none';
    if (chatTemplate) chatTemplate.style.display = tab === 'chatTemplate' ? '' : 'none';
    if (btnInfo) { btnInfo.classList.remove('btn-primary'); btnInfo.classList.add(tab === 'info' ? 'btn-primary' : 'btn-secondary'); }
    if (btnMetrics) { btnMetrics.classList.remove('btn-primary'); btnMetrics.classList.add(tab === 'metrics' ? 'btn-primary' : 'btn-secondary'); }
    if (btnProps) { btnProps.classList.remove('btn-primary'); btnProps.classList.add(tab === 'props' ? 'btn-primary' : 'btn-secondary'); }
    if (btnChatTemplate) { btnChatTemplate.classList.remove('btn-primary'); btnChatTemplate.classList.add(tab === 'chatTemplate' ? 'btn-primary' : 'btn-secondary'); }
}

function loadModelMetrics() {
    const modelId = window.__modelDetailModelId;
    const viewer = document.getElementById('modelDetailModalMetricsViewer');
    if (!modelId || !viewer) return;
    viewer.textContent = '加载中...';
    fetch('/api/models/metrics?modelId=' + encodeURIComponent(modelId))
        .then(r => r.json())
        .then(res => {
            const d = res && res.success ? res.data : null;
            const metrics = d && d.metrics ? d.metrics : null;
            viewer.textContent = metrics ? JSON.stringify(metrics, null, 2) : JSON.stringify(res, null, 2);
        })
        .catch(() => {
            viewer.textContent = '请求失败';
        });
}

function loadModelProps() {
    const modelId = window.__modelDetailModelId;
    const viewer = document.getElementById('modelDetailModalPropsViewer');
    if (!modelId || !viewer) return;
    viewer.textContent = '加载中...';
    fetch('/api/models/props?modelId=' + encodeURIComponent(modelId))
        .then(r => r.json())
        .then(res => {
            const d = res && res.success ? res.data : null;
            const props = d && d.props ? d.props : null;
            viewer.textContent = props ? JSON.stringify(props, null, 2) : JSON.stringify(res, null, 2);
        })
        .catch(() => {
            viewer.textContent = '请求失败';
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

function loadModelChatTemplate(showEmptyTip = false) {
    const modelId = window.__modelDetailModelId;
    const el = document.getElementById('modelDetailModalChatTemplateTextarea');
    if (!modelId || !el) return;
    el.value = '';
    fetch(`/api/model/template/get?modelId=${encodeURIComponent(modelId)}`)
        .then(r => r.json())
        .then(res => {
            if (!(res && res.success)) {
                showToast('错误', (res && res.error) ? res.error : '请求失败', 'error');
                return;
            }
            const d = res.data || {};
            if (showEmptyTip && d.exists === false) {
                showToast('提示', '该模型暂无已保存的聊天模板', 'info');
            }
            const tpl = d.chatTemplate !== undefined && d.chatTemplate !== null ? String(d.chatTemplate) : '';
            el.value = tpl;
        })
        .catch(() => {
            showToast('错误', '网络请求失败', 'error');
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
                showToast('错误', (res && res.error) ? res.error : '请求失败', 'error');
                return;
            }
            const d = res.data || {};
            const tpl = d.chatTemplate !== undefined && d.chatTemplate !== null ? String(d.chatTemplate) : '';
            el.value = tpl;
            if (d.exists) showToast('成功', '已加载默认模板', 'success');
            else showToast('提示', '该模型未提供默认模板', 'info');
        })
        .catch(() => {
            showToast('错误', '网络请求失败', 'error');
        });
}

function saveModelChatTemplate() {
    const modelId = window.__modelDetailModelId;
    const el = document.getElementById('modelDetailModalChatTemplateTextarea');
    if (!modelId || !el) return;
    const text = el.value == null ? '' : String(el.value);
    fetch('/api/model/template/set', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ modelId, chatTemplate: text })
    })
        .then(r => r.json())
        .then(res => {
            if (res && res.success) {
                showToast('成功', '聊天模板已保存', 'success');
            } else {
                showToast('错误', (res && res.error) ? res.error : '保存失败', 'error');
            }
        })
        .catch(() => {
            showToast('错误', '网络请求失败', 'error');
        });
}

