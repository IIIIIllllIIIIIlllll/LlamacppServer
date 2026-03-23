(function() {
    const SPEED_SAMPLE_MIN_MS = 1000;
    const SPEED_EMA_ALPHA = 0.25;
    const isDownloadHtml = /(^|\/)download\.html$/i.test(String(window.location.pathname || ''));

    const state = {
        started: false,
        downloads: [],
        websocket: null,
        reconnectAttempts: 0,
        maxReconnectAttempts: 5,
        reconnectInterval: 5000,
        speedByTaskId: {}
    };

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

    function closeModal(id) {
        if (typeof window.closeModal === 'function') {
            window.closeModal(id);
            return;
        }
        const el = document.getElementById(id);
        if (el) el.classList.remove('show');
        if (id === 'createDownloadModal') {
            const form = document.getElementById('createDownloadForm');
            if (form) form.reset();
        } else if (id === 'settingsModal') {
            const form = document.getElementById('settingsForm');
            if (form) form.reset();
        }
    }

    function showToast(title, msg, type = 'info') {
        if (typeof window.showToast === 'function') {
            window.showToast(title, msg, type);
            return;
        }
        const container = document.getElementById('toastContainer');
        if (!container) return;
        const id = 'toast-' + Date.now();
        const html = `
            <div class="toast ${type}" id="${id}">
                <div class="toast-icon"><i class="fas ${type === 'success' ? 'fa-check-circle' : type === 'error' ? 'fa-exclamation-circle' : 'fa-info-circle'}"></i></div>
                <div class="toast-content"><div class="toast-title">${title}</div><div class="toast-message">${msg}</div></div>
                <button class="toast-close" onclick="document.getElementById('${id}').remove()">&times;</button>
            </div>`;
        container.insertAdjacentHTML('beforeend', html);
        setTimeout(() => { const el = document.getElementById(id); if (el) el.remove(); }, 5000);
    }

    function formatFileSize(size) {
        if (size < 1024) return size + ' B';
        if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB';
        if (size < 1024 * 1024 * 1024) return (size / (1024 * 1024)).toFixed(1) + ' MB';
        return (size / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
    }

    function formatSpeed(bytesPerSecond) {
        const n = typeof bytesPerSecond === 'number' && Number.isFinite(bytesPerSecond) ? bytesPerSecond : 0;
        if (n <= 0) return '-';
        return `${formatFileSize(Math.round(n))}/s`;
    }

    function normalizeTimestampMs(value) {
        const n = typeof value === 'number' && Number.isFinite(value) ? value : Number(value);
        if (!Number.isFinite(n)) return Date.now();
        return n < 1e12 ? n * 1000 : n;
    }

    function escapeHtml(str) {
        const s = str == null ? '' : String(str);
        return s
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function joinPath(base, name) {
        const b = base == null ? '' : String(base);
        const n = name == null ? '' : String(name);
        if (!b) return n;
        if (!n) return b;
        const sep = b.includes('\\') ? '\\' : '/';
        if (b.endsWith(sep)) return b + n;
        return b + sep + n;
    }

    function initDownloadWebSocket() {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${window.location.host}/ws`;

        try {
            state.websocket = new WebSocket(wsUrl);
            state.websocket.onopen = function() {
                state.reconnectAttempts = 0;
                state.websocket.send(JSON.stringify({ type: 'connect', message: 'Connected', timestamp: new Date().toISOString() }));
            };
            state.websocket.onmessage = function(event) {
                handleWebSocketMessage(event.data);
            };
            state.websocket.onclose = function() {
                if (state.started && state.reconnectAttempts < state.maxReconnectAttempts) {
                    state.reconnectAttempts++;
                    setTimeout(initDownloadWebSocket, state.reconnectInterval);
                }
            };
            state.websocket.onerror = function(error) {
                console.error('WebSocket Error:', error);
            };
        } catch (error) {
            console.error('WebSocket Init Failed:', error);
        }
    }

    function handleWebSocketMessage(message) {
        try {
            const data = JSON.parse(message);
            if (!data || !data.type) return;
            switch (data.type) {
                case 'notification':
                    showToast(data.title || t('toast.info', '提示'), data.message || '', data.level || 'info');
                    break;
                case 'download_update':
                    if (data.taskId) {
                        updateDownloadItem(data.taskId, data);
                        if (data.state) {
                            if (data.state === 'COMPLETED') {
                                showToast(
                                    t('download.toast.completed.title', '下载完成'),
                                    tf('download.toast.completed.message', { name: data.fileName || data.taskId }, '下载任务 {name} 已完成'),
                                    'success'
                                );
                            } else if (data.state === 'FAILED') {
                                showToast(
                                    t('download.toast.failed.title', '下载失败'),
                                    tf(
                                        'download.toast.failed.message',
                                        { name: data.fileName || data.taskId, error: data.errorMessage || t('common.unknown_error', '未知错误') },
                                        '下载任务 {name} 失败: {error}'
                                    ),
                                    'error'
                                );
                            }
                        }
                    }
                    break;
                case 'download_progress':
                    if (data.taskId) updateDownloadProgress(data.taskId, data);
                    break;
            }
        } catch (error) {
            console.error(t('log.ws_message_handle_failed', '处理WebSocket消息失败:'), error);
        }
    }

    function refreshDownloads() {
        const downloadsList = document.getElementById('downloadsList');
        if (!downloadsList) return;
        downloadsList.innerHTML = `<div class="loading-spinner"><div class="spinner"></div></div>`;

        fetch('/api/downloads/list')
            .then(response => response.json())
            .then(data => {
                if (data && data.success) {
                    state.downloads = data.downloads || [];
                    renderDownloadsList();
                    updateStats();
                } else {
                    throw new Error((data && data.error) ? data.error : t('download.list_fetch_failed', '获取下载列表失败'));
                }
            })
            .catch(error => {
                console.error('Error:', error);
                downloadsList.innerHTML = `
                    <div class="empty-state">
                        <div class="empty-state-icon"><i class="fas fa-exclamation-triangle"></i></div>
                        <div class="empty-state-title">${t('common.load_failed', '加载失败')}</div>
                        <div class="empty-state-text">${error.message || t('common.network_error', '网络错误')}</div>
                        <button class="btn btn-primary" onclick="DownloadManager.refreshDownloads()">${t('common.retry', '重试')}</button>
                    </div>
                `;
            });
    }

    function renderDownloadsList() {
        const downloadsList = document.getElementById('downloadsList');
        if (!downloadsList) return;
        const downloads = state.downloads || [];
        if (downloads.length === 0) {
            downloadsList.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon"><i class="fas fa-download"></i></div>
                    <div class="empty-state-title">${t('download.empty.title', '没有下载任务')}</div>
                    <div class="empty-state-text">${t('download.empty.desc', '点击“创建下载任务”按钮添加新的下载任务')}</div>
                    <button class="btn btn-primary" onclick="DownloadManager.openCreateDownloadModal()">${t('page.download.create_task', '创建下载任务')}</button>
                </div>
            `;
            return;
        }

        const getDownloadCreatedAtMs = (download) => {
            const value = download && download.createdAt != null ? download.createdAt : null;
            if (value === null) return 0;
            if (typeof value === 'number' && Number.isFinite(value)) return value < 1e12 ? value * 1000 : value;
            if (typeof value === 'string') {
                const trimmed = value.trim();
                if (!trimmed) return 0;
                if (/^\d+$/.test(trimmed)) {
                    const num = Number(trimmed);
                    if (!Number.isFinite(num)) return 0;
                    return num < 1e12 ? num * 1000 : num;
                }
                const ms = new Date(trimmed).getTime();
                return Number.isFinite(ms) ? ms : 0;
            }
            const ms = new Date(value).getTime();
            return Number.isFinite(ms) ? ms : 0;
        };

        const sortedDownloads = [...downloads].sort((a, b) => {
            const diff = getDownloadCreatedAtMs(b) - getDownloadCreatedAtMs(a);
            if (diff !== 0) return diff;
            return String(b && b.taskId ? b.taskId : '').localeCompare(String(a && a.taskId ? a.taskId : ''));
        });

        let html = '';
        sortedDownloads.forEach(download => {
            const status = download && download.state ? download.state : 'IDLE';
            let statusText = t('download.status.starting', '正在开始……');
            let statusIcon = 'fa-question-circle';
            let statusClass = 'status-idle';

            switch (status) {
                case 'DOWNLOADING':
                    statusText = t('download.status.downloading', '下载中'); statusIcon = 'fa-spinner fa-spin'; statusClass = 'status-downloading';
                    break;
                case 'IDLE':
                    statusText = t('download.status.pending', '等待中'); statusIcon = 'fa-clock'; statusClass = 'status-idle';
                    break;
                case 'COMPLETED':
                    statusText = t('download.status.completed', '已完成'); statusIcon = 'fa-check-circle'; statusClass = 'status-completed';
                    break;
                case 'FAILED':
                    statusText = t('download.status.failed', '失败'); statusIcon = 'fa-exclamation-circle'; statusClass = 'status-failed';
                    break;
                case 'PAUSED':
                    statusText = t('download.status.paused', '已暂停'); statusIcon = 'fa-pause-circle'; statusClass = 'status-paused';
                    break;
                default:
                    statusText = t('download.status.unknown', '未知');
                    break;
            }

            let actionButtons = '';
            const taskId = download && download.taskId ? String(download.taskId) : '';
            if (taskId) {
                if (status === 'DOWNLOADING') {
                    actionButtons = `
                        <button class="btn-icon" onclick="DownloadManager.pauseDownload('${taskId}')" title="${escapeHtml(t('download.action.pause', '暂停'))}">
                            <i class="fas fa-pause"></i>
                        </button>
                        <button class="btn-icon danger" onclick="DownloadManager.deleteDownload('${taskId}')" title="${escapeHtml(t('common.delete', '删除'))}">
                            <i class="fas fa-trash"></i>
                        </button>
                    `;
                } else if (status === 'IDLE' || status === 'FAILED' || status === 'PAUSED') {
                    actionButtons = `
                        <button class="btn-icon primary" onclick="DownloadManager.resumeDownload('${taskId}')" title="${escapeHtml(t('download.action.resume', '恢复'))}">
                            <i class="fas fa-play"></i>
                        </button>
                        <button class="btn-icon danger" onclick="DownloadManager.deleteDownload('${taskId}')" title="${escapeHtml(t('common.delete', '删除'))}">
                            <i class="fas fa-trash"></i>
                        </button>
                    `;
                } else if (status === 'COMPLETED') {
                    actionButtons = `
                        <button class="btn-icon danger" onclick="DownloadManager.deleteDownload('${taskId}')" title="${escapeHtml(t('common.delete', '删除'))}">
                            <i class="fas fa-trash"></i>
                        </button>
                    `;
                }
            }

            const totalBytes = download && download.totalBytes ? Number(download.totalBytes) : 0;
            const downloadedBytes = download && download.downloadedBytes ? Number(download.downloadedBytes) : 0;
            const progressRatio = totalBytes > 0 ? (downloadedBytes / totalBytes) : 0;
            const progressPercent = Math.round(progressRatio * 100);
            const speedInfo = taskId && state.speedByTaskId ? state.speedByTaskId[taskId] : null;
            const speedText = status === 'DOWNLOADING' ? formatSpeed(speedInfo && typeof speedInfo.speedBps === 'number' ? speedInfo.speedBps : 0) : '-';

            const fileNameText = download && download.fileName ? String(download.fileName) : t('download.unknown_file', '未知文件');
            const targetPathText = download && download.targetPath ? String(download.targetPath) : '';
            const urlText = download && download.url ? String(download.url) : '';
            const fullPathText = joinPath(targetPathText, download && download.fileName ? String(download.fileName) : '');

            html += `
                <div class="download-item" id="download-${taskId}">
                    <div class="download-icon-wrapper">
                        <i class="fas fa-file-download"></i>
                    </div>
                    <div class="download-details">
                        <div class="download-name" title="${escapeHtml((download && (download.fileName || download.url)) ? String(download.fileName || download.url) : '')}">
                            ${escapeHtml(fileNameText)}
                        </div>
                        <div class="download-meta">
                            ${totalBytes ? `<span><i class="fas fa-hdd"></i> ${formatFileSize(totalBytes)}</span>` : ''}
                        </div>
                        ${(fullPathText || urlText) ? `
                            <div class="download-meta" style="margin-top: 0.25rem; flex-direction: column; gap: 0.25rem; align-items: flex-start;">
                                ${fullPathText ? `<span style="width: 100%; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${escapeHtml(fullPathText)}"><i class="fas fa-folder-open"></i> ${escapeHtml(fullPathText)}</span>` : ''}
                                ${urlText ? `<span style="width: 100%; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${escapeHtml(urlText)}"><i class="fas fa-link"></i> ${escapeHtml(urlText)}</span>` : ''}
                            </div>
                        ` : ''}
                        ${(status === 'DOWNLOADING' || status === 'PAUSED' || status === 'IDLE') ? `
                            <div class="progress-bar-container">
                                <div class="progress-bar">
                                    <div class="progress-fill" style="width: ${progressPercent}%"></div>
                                </div>
                                <div class="download-meta" style="margin-top: 0.25rem;">
                                    <span><i class="fas fa-percentage"></i> ${progressPercent}%</span>
                                    <span><i class="fas fa-gauge-high"></i> ${speedText}</span>
                                    ${downloadedBytes ? `<span><i class="fas fa-download"></i> ${formatFileSize(downloadedBytes)}</span>` : ''}
                                    ${totalBytes ? `<span><i class="fas fa-hdd"></i> ${formatFileSize(totalBytes)}</span>` : ''}
                                </div>
                            </div>
                        ` : ''}
                    </div>
                    <div class="download-status-badge ${statusClass}">
                        <i class="fas ${statusIcon}"></i> <span>${statusText}</span>
                    </div>
                    <div class="download-actions">${actionButtons}</div>
                </div>
            `;
        });

        downloadsList.innerHTML = html;
    }

    function updateDownloadItem(taskId, data) {
        const idx = (state.downloads || []).findIndex(d => d && d.taskId === taskId);
        if (idx < 0) return;
        state.downloads[idx] = { ...state.downloads[idx], ...data };
        renderDownloadsList();
        updateStats();
    }

    function updateDownloadProgress(taskId, data) {
        const idx = (state.downloads || []).findIndex(d => d && d.taskId === taskId);
        if (idx < 0) return;

        const nowMs = normalizeTimestampMs(data && data.timestamp);
        const downloadedBytesNumber = data && data.downloadedBytes > 0 ? Number(data.downloadedBytes) : 0;
        const prevSpeed = state.speedByTaskId && state.speedByTaskId[taskId] ? state.speedByTaskId[taskId] : null;
        if (!state.speedByTaskId) state.speedByTaskId = {};
        if (prevSpeed && typeof prevSpeed.atMs === 'number' && typeof prevSpeed.bytes === 'number') {
            const dtMs = nowMs - prevSpeed.atMs;
            const deltaBytes = downloadedBytesNumber - prevSpeed.bytes;
            if (dtMs >= SPEED_SAMPLE_MIN_MS && deltaBytes >= 0) {
                const instantSpeed = (deltaBytes * 1000) / dtMs;
                const previousSpeed = typeof prevSpeed.speedBps === 'number' ? prevSpeed.speedBps : 0;
                const smoothedSpeed = previousSpeed > 0
                    ? (previousSpeed * (1 - SPEED_EMA_ALPHA)) + (instantSpeed * SPEED_EMA_ALPHA)
                    : instantSpeed;
                state.speedByTaskId[taskId] = { atMs: nowMs, bytes: downloadedBytesNumber, speedBps: smoothedSpeed };
            } else {
                state.speedByTaskId[taskId] = { atMs: prevSpeed.atMs, bytes: prevSpeed.bytes, speedBps: prevSpeed.speedBps };
            }
        } else {
            state.speedByTaskId[taskId] = { atMs: nowMs, bytes: downloadedBytesNumber, speedBps: 0 };
        }

        state.downloads[idx] = {
            ...state.downloads[idx],
            downloadedBytes: data.downloadedBytes,
            totalBytes: data.totalBytes,
            partsCompleted: data.partsCompleted,
            partsTotal: data.partsTotal,
            progressRatio: data.progressRatio
        };

        const downloadElement = document.getElementById(`download-${taskId}`);
        if (downloadElement) {
            const totalBytes = data.totalBytes > 0 ? Number(data.totalBytes) : 0;
            const downloadedBytes = downloadedBytesNumber;
            const progressRatio = totalBytes > 0 ? (downloadedBytes / totalBytes) : 0;
            const progressPercent = Math.round(progressRatio * 100);
            const current = state.downloads[idx] || {};
            const speedText = current && current.state === 'DOWNLOADING'
                ? formatSpeed(state.speedByTaskId && state.speedByTaskId[taskId] ? state.speedByTaskId[taskId].speedBps : 0)
                : '-';

            let progressBarContainer = downloadElement.querySelector('.progress-bar-container');
            if (!progressBarContainer) {
                const detailsDiv = downloadElement.querySelector('.download-details');
                progressBarContainer = document.createElement('div');
                progressBarContainer.className = 'progress-bar-container';
                if (detailsDiv) detailsDiv.appendChild(progressBarContainer);
            }

            let progressBar = progressBarContainer ? progressBarContainer.querySelector('.progress-bar') : null;
            if (!progressBar && progressBarContainer) {
                progressBar = document.createElement('div');
                progressBar.className = 'progress-bar';
                progressBarContainer.appendChild(progressBar);
            }

            let progressFill = progressBar ? progressBar.querySelector('.progress-fill') : null;
            if (!progressFill && progressBar) {
                progressFill = document.createElement('div');
                progressFill.className = 'progress-fill';
                progressBar.appendChild(progressFill);
            }

            if (progressFill) progressFill.style.width = `${progressPercent}%`;

            let progressMeta = progressBarContainer ? progressBarContainer.querySelector('.download-meta') : null;
            if (!progressMeta && progressBarContainer) {
                progressMeta = document.createElement('div');
                progressMeta.className = 'download-meta';
                progressMeta.style.marginTop = '0.25rem';
                progressBarContainer.appendChild(progressMeta);
            }

            if (progressMeta) {
                progressMeta.innerHTML = `
                    <span><i class="fas fa-percentage"></i> ${progressPercent}%</span>
                    <span><i class="fas fa-gauge-high"></i> ${speedText}</span>
                    <span><i class="fas fa-download"></i> ${formatFileSize(downloadedBytes)}</span>
                    ${totalBytes > 0 ? `<span><i class="fas fa-hdd"></i> ${formatFileSize(totalBytes)}</span>` : ''}
                `;
            }
        }

        updateStats();
    }

    function updateStats() {
        const downloads = state.downloads || [];
        const activeCount = downloads.filter(d => d && d.state === 'DOWNLOADING').length;
        const pendingCount = downloads.filter(d => d && d.state === 'IDLE').length;
        const completedCount = downloads.filter(d => d && d.state === 'COMPLETED').length;
        const totalCount = downloads.length;

        const activeEl = document.getElementById('activeDownloadsCount');
        const pendingEl = document.getElementById('pendingDownloadsCount');
        const completedEl = document.getElementById('completedDownloadsCount');
        const totalEl = document.getElementById('totalDownloadsCount');
        if (activeEl) activeEl.textContent = String(activeCount);
        if (pendingEl) pendingEl.textContent = String(pendingCount);
        if (completedEl) completedEl.textContent = String(completedCount);
        if (totalEl) totalEl.textContent = String(totalCount);
    }

    async function getDownloadPath() {
        return fetch('/api/downloads/path/get')
            .then(response => response.json())
            .then(data => {
                return (data && data.path) ? data.path : '';
            })
            .catch(error => {
                console.error(t('log.download_path_get_failed', '获取下载路径失败:'), error);
                return '';
            });
    }

    function openCreateDownloadModal() {
        getDownloadPath().then(path => {
            if (path) {
                const el = document.getElementById('downloadPath');
                if (el) el.value = path;
            }
        });
        const modal = document.getElementById('createDownloadModal');
        if (modal) modal.classList.add('show');
    }

    function submitCreateDownload() {
        const urlEl = document.getElementById('downloadUrl');
        const pathEl = document.getElementById('downloadPath');
        const fileNameEl = document.getElementById('downloadFileName');
        const url = urlEl ? String(urlEl.value || '').trim() : '';
        const path = pathEl ? String(pathEl.value || '').trim() : '';
        const fileName = fileNameEl ? String(fileNameEl.value || '').trim() : '';

        if (!url) {
            showToast(t('toast.error', '错误'), t('download.validation.url_required', '请输入下载URL'), 'error');
            return;
        }
        if (!path) {
            showToast(t('toast.error', '错误'), t('download.validation.path_required', '请输入保存路径'), 'error');
            return;
        }

        const payload = { url, path };
        if (fileName) payload.fileName = fileName;

        fetch('/api/downloads/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        .then(response => response.json())
        .then(data => {
            if (data && data.success) {
                showToast(t('toast.success', '成功'), t('download.toast.task_created', '任务已创建'), 'success');
                closeModal('createDownloadModal');
                refreshDownloads();
            } else {
                showToast(t('toast.error', '错误'), (data && data.error) ? data.error : t('download.toast.create_failed', '创建下载任务失败'), 'error');
            }
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
    }

    function pauseDownload(taskId) {
        fetch('/api/downloads/pause', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ taskId })
        })
        .then(response => response.json())
        .then(data => {
            if (data && data.success) showToast(t('toast.success', '成功'), t('download.toast.paused', '已暂停'), 'success');
            else showToast(t('toast.error', '错误'), (data && data.error) ? data.error : t('download.toast.pause_failed', '暂停失败'), 'error');
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
    }

    function resumeDownload(taskId) {
        fetch('/api/downloads/resume', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ taskId })
        })
        .then(response => response.json())
        .then(data => {
            if (data && data.success) showToast(t('toast.success', '成功'), t('download.toast.resumed', '已恢复'), 'success');
            else showToast(t('toast.error', '错误'), (data && data.error) ? data.error : t('download.toast.resume_failed', '恢复失败'), 'error');
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
    }

    function deleteDownload(taskId) {
        if (!confirm(t('confirm.download.delete', '确定要删除这个下载任务吗？'))) return;

        fetch('/api/downloads/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ taskId })
        })
        .then(response => response.json())
        .then(data => {
            if (data && data.success) {
                showToast(t('toast.success', '成功'), t('download.toast.deleted', '已删除'), 'success');
                refreshDownloads();
            } else {
                showToast(t('toast.error', '错误'), (data && data.error) ? data.error : t('download.toast.delete_failed', '删除失败'), 'error');
            }
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
    }

    function openSettingsModal() {
        getDownloadPath().then(path => {
            if (path) {
                const el = document.getElementById('defaultDownloadPath');
                if (el) el.value = path;
            }
        });
        const modal = document.getElementById('settingsModal');
        if (modal) modal.classList.add('show');
    }

    function saveSettings() {
        const el = document.getElementById('defaultDownloadPath');
        const path = el ? String(el.value || '').trim() : '';
        if (!path) {
            showToast(t('toast.error', '错误'), t('download.validation.download_dir_required', '请输入下载路径'), 'error');
            return;
        }

        fetch('/api/downloads/path/set', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path })
        })
        .then(response => response.json())
        .then(data => {
            if (data && data.path) {
                showToast(t('toast.success', '成功'), t('toast.saved', '已保存'), 'success');
                closeModal('settingsModal');
            } else {
                showToast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
            }
        })
        .catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
    }

    function start() {
        if (state.started) return;
        state.started = true;
        refreshDownloads();
        initDownloadWebSocket();
    }

    function stop() {
        state.started = false;
        state.reconnectAttempts = 0;
        try {
            if (state.websocket) state.websocket.close();
        } catch (e) {
        }
        state.websocket = null;
    }

    window.DownloadManager = {
        start,
        stop,
        refreshDownloads,
        openCreateDownloadModal,
        submitCreateDownload,
        pauseDownload,
        resumeDownload,
        deleteDownload,
        openSettingsModal,
        saveSettings
    };

    if (isDownloadHtml) {
        window.refreshDownloads = refreshDownloads;
        window.openCreateDownloadModal = openCreateDownloadModal;
        window.submitCreateDownload = submitCreateDownload;
        window.pauseDownload = pauseDownload;
        window.resumeDownload = resumeDownload;
        window.deleteDownload = deleteDownload;
        window.openSettingsModal = openSettingsModal;
        window.saveSettings = saveSettings;
        window.getDownloadPath = getDownloadPath;
        window.closeModal = closeModal;
        window.showToast = showToast;

        window.addEventListener('click', function(e) {
            if (e && e.target && e.target.classList && e.target.classList.contains('modal')) closeModal(e.target.id);
        });

        document.addEventListener('DOMContentLoaded', function() {
            start();
        });
    }
})();

