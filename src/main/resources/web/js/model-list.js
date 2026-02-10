 function t(key, fallback) {
    if (window.I18N && typeof window.I18N.t === 'function') {
        return window.I18N.t(key, fallback);
    }
    return fallback == null ? key : fallback;
}

function loadModels() {
    const modelsList = document.getElementById('modelsList');
    fetch('/api/models/list')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const allModels = data.models || [];
                if (data.success) {
                    const totalCount = (allModels || []).length;
                    const el = document.getElementById('totalModelsCount');
                    if (el) el.textContent = totalCount;
                }
                return fetch('/api/models/loaded')
                    .then(response => response.json())
                    .then(loadedData => {
                        const loadedModels = loadedData.success ? (loadedData.models || []) : [];
                        const loadedModelIds = loadedModels.map(m => m.id);
                        const modelsWithStatus = allModels.map(model => {
                            const isLoaded = loadedModelIds.includes(model.id);
                            const loadedModel = loadedModels.find(m => m.id === model.id);
                            return {
                                ...model,
                                isLoading: !!model.isLoading,
                                isLoaded: isLoaded,
                                status: isLoaded ? (loadedModel ? loadedModel.status : 'loaded') : 'stopped',
                                port: isLoaded && loadedModel ? loadedModel.port : null
                            };
                        });
                        currentModelsData = modelsWithStatus;
                        sortAndRenderModels();
                        if (loadedData.success) {
                            const loadedCount = (loadedData.models || []).length;
                            const el = document.getElementById('loadedModelsCount');
                            if (el) el.textContent = loadedCount;
                        }
                    });
            } else {
                throw new Error(data.error);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            modelsList.innerHTML = `
                        <div class="empty-state">
                            <div class="empty-state-icon"><i class="fas fa-exclamation-triangle"></i></div>
                            <div class="empty-state-title">${t('common.load_failed', '加载失败')}</div>
                            <div class="empty-state-text">${error.message || t('common.network_error', '网络错误')}</div>
                            <button class="btn btn-primary" onclick="loadModels()">${t('common.retry', '重试')}</button>
                        </div>
                    `;
        });
}

function getModelIcon(architecture) {
    const archName = (architecture || '').toLowerCase().replace(/[^a-z]/g, '');
    const iconMap = {
        'qwen': 'icon/qwen.png',
        'glm': 'icon/glm.png',
        'hunyuan': 'icon/hunyuan.png',
        'mistral': 'icon/mistral3.png',
        'gpt': 'icon/openai.png',
        'seed': 'icon/seed_oss.png',
        'llama': 'icon/llama.png',
        'kimi': 'icon/kimi.png',
        'minimax': 'icon/minimax.png',
        'gemma': 'icon/gemma.png',
		'deepseek': 'icon/deepseek.png',
		'deepseek2': 'icon/deepseek.png',
		'deepseek3': 'icon/deepseek.png',
        'step': 'icon/step35.png',
        'paddleocr': 'icon/paddleocr.png',
    };
    for (const [key, icon] of Object.entries(iconMap)) {
        if (archName.includes(key)) return icon;
    }
    return null;
}

const _modelIconMemoryCache = new Map();
const _modelIconObjectUrlCache = new Map();
let _modelIconObjectUrlRevokeBound = false;

function getCachedModelIconDataUrl(iconPath) {
    if (!iconPath) return null;
    if (_modelIconMemoryCache.has(iconPath)) return _modelIconMemoryCache.get(iconPath);
    return null;
}

function _bindModelIconObjectUrlRevokeOnce() {
    if (_modelIconObjectUrlRevokeBound) return;
    _modelIconObjectUrlRevokeBound = true;
    try {
        window.addEventListener('beforeunload', () => {
            try {
                for (const url of _modelIconObjectUrlCache.values()) {
                    try { URL.revokeObjectURL(url); } catch (e) {}
                }
                _modelIconObjectUrlCache.clear();
            } catch (e) {}
        }, { once: true });
    } catch (e) {}
}

function _dataUrlToBlob(dataUrl) {
    try {
        const s = String(dataUrl || '');
        const idx = s.indexOf(',');
        if (idx < 0) return null;
        const meta = s.slice(0, idx);
        const b64 = s.slice(idx + 1);
        const m = meta.match(/^data:([^;]+);base64$/i);
        if (!m) return null;
        const mime = m[1] || 'application/octet-stream';
        const bin = atob(b64);
        const len = bin.length;
        const bytes = new Uint8Array(len);
        for (let i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
        return new Blob([bytes], { type: mime });
    } catch (e) {
        return null;
    }
}

function getCachedModelIconObjectUrl(iconPath) {
    if (!iconPath) return null;
    if (_modelIconObjectUrlCache.has(iconPath)) return _modelIconObjectUrlCache.get(iconPath);
    const dataUrl = getCachedModelIconDataUrl(iconPath);
    if (!dataUrl) return null;
    const blob = _dataUrlToBlob(dataUrl);
    if (!blob) return null;
    try {
        const url = URL.createObjectURL(blob);
        _bindModelIconObjectUrlRevokeOnce();
        _modelIconObjectUrlCache.set(iconPath, url);
        return url;
    } catch (e) {
        return null;
    }
}

function _tryCacheModelIconFromImg(img, iconPath) {
    try {
        if (!img || !iconPath) return null;
        const w = img.naturalWidth;
        const h = img.naturalHeight;
        if (!w || !h) return null;

        const canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        if (!ctx) return null;
        ctx.drawImage(img, 0, 0, w, h);

        const dataUrl = canvas.toDataURL('image/png');
        if (typeof dataUrl === 'string' && dataUrl.startsWith('data:')) {
            _modelIconMemoryCache.set(iconPath, dataUrl);
            return getCachedModelIconObjectUrl(iconPath) || dataUrl;
        }
    } catch (e) {}
    return null;
}

function hydrateModelIcons(container) {
    try {
        if (!container) return;
        const imgs = container.querySelectorAll('img[data-model-icon-path]');
        if (!imgs || imgs.length === 0) return;
        imgs.forEach(img => {
            const iconPath = img.getAttribute('data-model-icon-path');
            if (!iconPath) return;
            const cachedUrl = getCachedModelIconObjectUrl(iconPath);
            if (cachedUrl) {
                if (img.src !== cachedUrl) img.src = cachedUrl;
                return;
            }
            if (img.getAttribute('data-model-icon-cache-bound') === '1') return;
            img.setAttribute('data-model-icon-cache-bound', '1');
            const handler = () => {
                const currentPath = img.getAttribute('data-model-icon-path');
                if (currentPath !== iconPath) return;
                const src = _tryCacheModelIconFromImg(img, iconPath);
                if (src && img.getAttribute('data-model-icon-path') === iconPath) {
                    img.src = src;
                }
            };
            img.addEventListener('load', handler, { once: true });
            if (img.complete && img.naturalWidth) handler();
        });
    } catch (e) {}
}

let currentModelsData = [];

function getParamsCount(name) {
    if (!name) return 0;
    name = name.toUpperCase();
    const moeMatch = name.match(/(\d+)X(\d+(?:\.\d+)?)B/);
    if (moeMatch) {
        return parseFloat(moeMatch[1]) * parseFloat(moeMatch[2]);
    }
    const match = name.match(/(\d+(?:\.\d+)?)B/);
    if (match) {
        return parseFloat(match[1]);
    }
    const matchM = name.match(/(\d+(?:\.\d+)?)M/);
    if (matchM) {
        return parseFloat(matchM[1]) / 1000;
    }
    return 0;
}

function sortAndRenderModels() {
    const sortType = document.getElementById('modelSortSelect').value;
    if (!currentModelsData) return;

    const comparator = getModelSortComparator(sortType);
    const all = [...currentModelsData];

    const favourites = [];
    const nonFavourites = [];
    all.forEach(m => {
        if (m && m.favourite) favourites.push(m);
        else nonFavourites.push(m);
    });

    favourites.sort(comparator);
    nonFavourites.sort(comparator);

    renderModelsList([...favourites, ...nonFavourites]);
}

function getModelSortComparator(sortType) {
    return (a, b) => {
        const nameA = (a.alias || a.name || '').toLowerCase();
        const nameB = (b.alias || b.name || '').toLowerCase();
        const sizeA = a.size || 0;
        const sizeB = b.size || 0;
        const paramsA = getParamsCount(a.name);
        const paramsB = getParamsCount(b.name);

        switch (sortType) {
            case 'name_asc': return nameA.localeCompare(nameB);
            case 'name_desc': return nameB.localeCompare(nameA);
            case 'size_asc': return sizeA - sizeB;
            case 'size_desc': return sizeB - sizeA;
            case 'params_asc': return paramsA - paramsB;
            case 'params_desc': return paramsB - paramsA;
            default: return 0;
        }
    };
}

function renderModelsList(models) {
    const modelsList = document.getElementById('modelsList');
    if (!models || models.length === 0) {
        modelsList.innerHTML = `
                    <div class="empty-state">
                        <div class="empty-state-icon"><i class="fas fa-box-open"></i></div>
                        <div class="empty-state-title">${t('page.model.empty_title', '没有模型')}</div>
                        <div class="empty-state-text">${t('page.model.empty_desc', '请先在“模型路径配置”中添加模型目录')}</div>
                        <button class="btn btn-primary" onclick="showModelPathSetting()">${t('page.model.empty_action', '去配置')}</button>
                    </div>
                `;
        return;
    }

    let html = '';
    models.forEach(model => {
        const metadata = model.metadata || {};
        const architecture = metadata.architecture || t('common.unknown', '未知');
        const quantization = metadata.quantization || '';
        const isLoading = !!model.isLoading;

        let status = model.status;
        let statusText = t('page.model.status.stopped', '已停止');
        let statusIcon = 'fa-stop-circle';
        let statusClass = 'status-stopped';

        if (isLoading) {
            statusText = t('page.model.status.loading', '加载中');
            statusIcon = 'fa-spinner fa-spin';
            statusClass = 'status-loading';
        } else if (model.isLoaded) {
            statusText = status === 'running' ? t('page.model.status.running', '运行中') : t('page.model.status.loaded', '已加载');
            statusIcon = status === 'running' ? 'fa-play-circle' : 'fa-check-circle';
            statusClass = status === 'running' ? 'status-running' : 'status-loaded';
        }

        const modelIconPath = getModelIcon(architecture);
        const modelIconSrc = modelIconPath ? (getCachedModelIconObjectUrl(modelIconPath) || modelIconPath) : null;
        const displayName = (model.alias && model.alias.trim()) ? model.alias : model.name;
        const isFavourite = !!model.favourite;

        let actionButtons = '';
        if (isLoading) {
            actionButtons = `<button class="btn-icon danger" onclick="stopModel('${model.id}')" title="${t('page.model.action.cancel_loading', '取消加载')}"><i class="fas fa-stop"></i></button>`;
        } else if (model.isLoaded) {
            if (status === 'running') {
                actionButtons = `
                            <button class="btn-icon danger" onclick="stopModel('${model.id}')" title="${t('page.model.action.stop', '停止')}"><i class="fas fa-stop"></i></button>
                            <button class="btn-icon" onclick="viewModelDetails('${model.id}')" title="${t('page.model.action.details', '详情')}"><i class="fas fa-info-circle"></i></button>
                            <button class="btn-icon" onclick="openModelBenchmarkList(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="${t('page.model.action.view_benchmark_results', '查看测试结果')}"><i class="fas fa-list"></i></button>
                            <button class="btn-icon" onclick="viewModelConfig('${model.id}')" title="${t('page.model.action.view_config', '查看配置')}"><i class="fas fa-cog"></i></button>
                            <button class="btn-icon" onclick="openSlotsModal(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="${t('modal.slots.title', '缓存管理')}"><i class="fas fa-database"></i></button>
                        `;
            } else {
                actionButtons = `
                            <button class="btn-icon" onclick="openModelBenchmarkList(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="${t('page.model.action.view_benchmark_results', '查看测试结果')}"><i class="fas fa-list"></i></button>
                        `;
            }
        } else {
            actionButtons = `
                        <button class="btn-icon primary" onclick="loadModel('${model.id}', '${model.name}')" title="${t('page.model.action.load', '加载')}"><i class="fas fa-play"></i></button>
                        <button class="btn-icon" onclick="viewModelDetails('${model.id}')" title="${t('page.model.action.details', '详情')}"><i class="fas fa-info-circle"></i></button>
                        <button class="btn-icon" onclick="openModelBenchmarkDialog(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="${t('page.model.action.benchmark', '性能测试')}"><i class="fas fa-rocket"></i></button>
                        <button class="btn-icon" onclick="openModelBenchmarkList(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="${t('page.model.action.view_benchmark_results', '查看测试结果')}"><i class="fas fa-list"></i></button>
                        <button class="btn-icon" onclick="viewModelConfig('${model.id}')" title="${t('page.model.action.view_config', '查看配置')}"><i class="fas fa-cog"></i></button>
                    `;
        }

        html += `
                    <div class="model-item">
                        <button class="model-fav-btn ${isFavourite ? 'active' : ''}" onclick="toggleFavouriteModel(event, decodeURIComponent('${encodeURIComponent(model.id)}'))" title="${isFavourite ? t('page.model.fav.remove', '取消喜好') : t('page.model.fav.add', '标记喜好')}">
                            <i class="${isFavourite ? 'fas' : 'far'} fa-star"></i>
                        </button>
                        <div class="model-icon-wrapper">
                            ${modelIconPath ? `<img src="${modelIconSrc}" data-model-icon-path="${modelIconPath}" alt="${architecture}">` : `<i class="fas fa-brain"></i>`}
                        </div>
                        <div class="model-details">
                            <div class="model-name" title="${model.name}" onclick="openAliasModal(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(model.name)}'), decodeURIComponent('${encodeURIComponent(model.alias || '')}'))">
                                ${displayName}
                                ${model.isMultimodal ? '<span class="vision-badge"><i class="fas fa-image"></i></span>' : ''}
                            </div>
                        <div class="model-meta">
                                <span><i class="fas fa-layer-group"></i> ${architecture}</span>
                                ${quantization ? `<span><i class="fas fa-microchip"></i> ${quantization}</span>` : ''}
                                <span><i class="fas fa-hdd"></i> ${formatFileSize(model.size)}</span>
                                ${model.port ? `<span><i class="fas fa-network-wired"></i> ${model.port}</span>` : ''}
                            </div>
						<span class="model-slots" id="slots-${encodeURIComponent(model.id)}" style="visibility:${Array.isArray(model.slots) && model.slots.length > 0 ? 'visible' : 'hidden'};">${renderSlotsSquaresInner(model.slots)}</span>
                        </div>
                        <div class="model-status-badge ${statusClass}">
                            <i class="fas ${statusIcon}"></i> <span>${statusText}</span>
                        </div>
                        <div class="model-actions">${actionButtons}</div>
                    </div>
                `;
    });
    modelsList.innerHTML = html;
    hydrateModelIcons(modelsList);
    const input = document.getElementById('modelSearchInput');
    if (input) filterModels(input.value);
}

function renderSlotsSquaresInner(slots) {
    try {
        if (!Array.isArray(slots) || slots.length === 0) return '';
        let s = '';
        for (let i = 0; i < slots.length; i++) {
            const it = slots[i];
            if (!it || typeof it !== 'object') continue;
            const id = Number.isFinite(it.id) ? it.id : i;
            const busy = !!it.is_processing;
            const speculative = !!it.speculative;
            const title = `slot ${id}${speculative ? ' (speculative)' : ''}`;
            s += `<span class="model-slot-square${busy ? ' busy' : ''}" title="${title}"></span>`;
        }
        return s;
    } catch (e) {
        return '';
    }
}

function updateModelSlotsDom(modelId, slots) {
    try {
        const el = document.getElementById(`slots-${encodeURIComponent(modelId)}`);
        if (!el) return;
        const hasSlots = Array.isArray(slots) && slots.length > 0;
        el.style.visibility = hasSlots ? 'visible' : 'hidden';
        el.innerHTML = renderSlotsSquaresInner(slots);
    } catch (e) {}
}
function toggleFavouriteModel(event, modelId) {
    if (event) {
        event.preventDefault();
        event.stopPropagation();
    }
    const idx = (currentModelsData || []).findIndex(m => m && m.id === modelId);
    if (idx < 0) return;

    const prev = !!currentModelsData[idx].favourite;
    currentModelsData[idx].favourite = !prev;
    sortAndRenderModels();

    fetch('/api/models/favourite', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ modelId })
    })
        .then(r => r.json())
        .then(res => {
            if (!res || !res.success) {
                throw new Error((res && res.error) ? res.error : t('page.model.fav.set_failed', '设置喜好失败'));
            }
            const favourite = !!(res.data && res.data.favourite);
            const i = (currentModelsData || []).findIndex(m => m && m.id === modelId);
            if (i >= 0) {
                currentModelsData[i].favourite = favourite;
                sortAndRenderModels();
            }
        })
        .catch(err => {
            const i = (currentModelsData || []).findIndex(m => m && m.id === modelId);
            if (i >= 0) {
                currentModelsData[i].favourite = prev;
                sortAndRenderModels();
            }
            showToast(t('toast.error', '错误'), err && err.message ? err.message : t('common.network_error', '网络错误'), 'error');
        });
}

function refreshModels() {
    showToast(t('toast.info', '提示'), t('page.model.refreshing', '正在刷新模型列表'), 'info');
    fetch('/api/models/refresh')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                loadModels();
            } else {
                throw new Error(data.error || t('page.model.refresh_failed', '刷新模型列表失败'));
            }
        })
        .catch(error => {
            showToast(t('toast.error', '错误'), error.message || t('page.model.network_retry', '网络错误，请稍后重试'), 'error');
            loadModels();
        });
}

