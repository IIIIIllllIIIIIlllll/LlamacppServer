(function () {
    const SPEED_SAMPLE_MIN_MS = 1000;
    const SPEED_EMA_ALPHA = 0.25;
    const state = {
        started: false,
        downloads: [],
        websocket: null,
        reconnectAttempts: 0,
        maxReconnectAttempts: 5,
        reconnectIntervalMs: 5000,
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

    function byId(id) {
        return document.getElementById(id);
    }

    function showToast(title, msg, type) {
        if (typeof window.showToast === 'function') {
            window.showToast(title, msg, type);
        }
    }

    function escapeHtml(v) {
        const s = v == null ? '' : String(v);
        return s.replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function formatFileSize(bytes) {
        const n = Number(bytes);
        if (!Number.isFinite(n) || n < 0) return '-';
        if (n < 1024) return `${Math.round(n)} B`;
        if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
        if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
        return `${(n / (1024 * 1024 * 1024)).toFixed(1)} GB`;
    }

    function formatSpeed(bps) {
        const n = Number(bps);
        if (!Number.isFinite(n) || n <= 0) return '-';
        return `${formatFileSize(n)}/s`;
    }

    function normalizeTimestampMs(value) {
        const n = typeof value === 'number' && Number.isFinite(value) ? value : Number(value);
        if (!Number.isFinite(n)) return Date.now();
        return n < 1e12 ? n * 1000 : n;
    }

    function statusMeta(stateName) {
        const s = stateName == null ? '' : String(stateName).toUpperCase();
        if (s === 'DOWNLOADING') return { code: s, text: t('download.status.downloading', '下载中'), icon: 'fa-spinner fa-spin', cls: 'status-downloading' };
        if (s === 'IDLE') return { code: s, text: t('download.status.pending', '等待中'), icon: 'fa-clock', cls: 'status-idle' };
        if (s === 'COMPLETED') return { code: s, text: t('download.status.completed', '已完成'), icon: 'fa-check-circle', cls: 'status-completed' };
        if (s === 'FAILED') return { code: s, text: t('download.status.failed', '失败'), icon: 'fa-exclamation-circle', cls: 'status-failed' };
        if (s === 'PAUSED') return { code: s, text: t('download.status.paused', '已暂停'), icon: 'fa-pause-circle', cls: 'status-paused' };
        return { code: s || 'UNKNOWN', text: t('download.status.unknown', '未知'), icon: 'fa-question-circle', cls: 'status-idle' };
    }

    function getDownloadCreatedAtMs(download) {
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
    }

    function updateStats() {
        const list = Array.isArray(state.downloads) ? state.downloads : [];
        const active = list.filter((d) => d && String(d.state || '').toUpperCase() === 'DOWNLOADING').length;
        const pending = list.filter((d) => d && String(d.state || '').toUpperCase() === 'IDLE').length;
        const done = list.filter((d) => d && String(d.state || '').toUpperCase() === 'COMPLETED').length;
        const activeEl = byId('mobileDownloadsActiveCount');
        const pendingEl = byId('mobileDownloadsPendingCount');
        const doneEl = byId('mobileDownloadsDoneCount');
        if (activeEl) activeEl.textContent = String(active);
        if (pendingEl) pendingEl.textContent = String(pending);
        if (doneEl) doneEl.textContent = String(done);
    }

    function renderList() {
        const container = byId('mobileDownloadsList');
        if (!container) return;

        const list = Array.isArray(state.downloads) ? state.downloads : [];
        if (!list.length) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon"><i class="fas fa-download"></i></div>
                    <div class="empty-state-title">${t('download.empty.title', '没有下载任务')}</div>
                    <div class="empty-state-text">${t('download.mobile.empty.desc', '点击“新建任务”创建下载')}</div>
                </div>
            `;
            updateStats();
            return;
        }

        const sorted = list.slice().sort((a, b) => {
            const diff = getDownloadCreatedAtMs(b) - getDownloadCreatedAtMs(a);
            if (diff !== 0) return diff;
            return String(b && b.taskId ? b.taskId : '').localeCompare(String(a && a.taskId ? a.taskId : ''));
        });

        container.innerHTML = sorted.map((d) => {
            const taskId = d && d.taskId != null ? String(d.taskId) : '';
            const fileName = d && d.fileName != null ? String(d.fileName) : (d && d.url ? String(d.url).split('/').pop() : '');
            const st = statusMeta(d && d.state);
            const total = d && d.totalBytes != null ? Number(d.totalBytes) : 0;
            const done = d && d.downloadedBytes != null ? Number(d.downloadedBytes) : 0;
            const pct = total > 0 ? Math.min(100, Math.max(0, Math.round(done / total * 100))) : 0;
            const speedEntry = state.speedByTaskId[taskId];
            const speedBps = (typeof speedEntry === 'number')
                ? speedEntry
                : (speedEntry && typeof speedEntry.speedBps === 'number' ? speedEntry.speedBps : 0);
            const speedText = formatSpeed(speedBps);

            const canResume = st.code === 'IDLE' || st.code === 'FAILED' || st.code === 'PAUSED';
            const canPause = st.code === 'DOWNLOADING';
            const actions = `
                <div style="display:flex; gap:0.5rem; margin-left:auto;">
                    ${canPause ? `<button class="btn btn-secondary btn-sm" data-dl-act="pause" data-dl-id="${escapeHtml(taskId)}"><i class="fas fa-pause"></i></button>` : ''}
                    ${canResume ? `<button class="btn btn-primary btn-sm" data-dl-act="resume" data-dl-id="${escapeHtml(taskId)}"><i class="fas fa-play"></i></button>` : ''}
                    <button class="btn btn-danger btn-sm" data-dl-act="delete" data-dl-id="${escapeHtml(taskId)}"><i class="fas fa-trash"></i></button>
                </div>
            `;

            const progressBar = (total > 0 || done > 0) ? `
                <div style="width:100%; margin-top:0.5rem;">
                    <div style="display:flex; gap:0.75rem; font-size:0.8rem; color: var(--text-secondary); flex-wrap: wrap;">
                        <span><i class="fas fa-percentage"></i> ${pct}%</span>
                        <span><i class="fas fa-gauge-high"></i> ${escapeHtml(speedText)}</span>
                        <span><i class="fas fa-download"></i> ${escapeHtml(formatFileSize(done))}</span>
                        ${total > 0 ? `<span><i class="fas fa-hdd"></i> ${escapeHtml(formatFileSize(total))}</span>` : ''}
                    </div>
                    <div class="progress-bar"><div class="progress-fill" style="width:${pct}%;"></div></div>
                </div>
            ` : '';

            return `
                <div class="model-item" data-dl-item="${escapeHtml(taskId)}" style="align-items:flex-start;">
                    <div class="model-details" style="min-width:0;">
                        <div class="model-name" style="margin-bottom:0.35rem; word-break: break-all;">${escapeHtml(fileName || taskId || t('download.task_fallback', '任务'))}</div>
                        <div class="model-meta" style="gap:0.5rem;">
                            <span class="model-status-badge ${escapeHtml(st.cls)}" style="min-width:auto; padding: 0.25rem 0.6rem;">
                                <i class="fas ${escapeHtml(st.icon)}"></i> <span>${escapeHtml(st.text)}</span>
                            </span>
                        </div>
                        ${progressBar}
                    </div>
                    ${actions}
                </div>
            `;
        }).join('');

        updateStats();
    }

    function mergeUpdate(taskId, patch) {
        const id = taskId == null ? '' : String(taskId);
        if (!id) return;
        const list = Array.isArray(state.downloads) ? state.downloads : [];
        let found = false;
        for (let i = 0; i < list.length; i++) {
            if (list[i] && String(list[i].taskId) === id) {
                state.downloads[i] = { ...list[i], ...patch };
                found = true;
                break;
            }
        }
        if (!found) {
            state.downloads.unshift({ taskId: id, ...patch });
        }
    }

    function handleWsMessage(raw) {
        try {
            const data = JSON.parse(raw);
            if (!data || !data.type) return;
            if (data.type === 'notification') {
                showToast(data.title || t('toast.info', '提示'), data.message || '', data.level || 'info');
                return;
            }
            if (data.type === 'download_update' && data.taskId) {
                mergeUpdate(data.taskId, data);
                if (data.state) {
                    const st = String(data.state).toUpperCase();
                    if (st === 'COMPLETED') {
                        showToast(
                            t('download.toast.completed.title', '下载完成'),
                            tf('download.toast.completed.message', { name: data.fileName || data.taskId }, '下载任务 {name} 已完成'),
                            'success'
                        );
                    }
                    if (st === 'FAILED') {
                        showToast(
                            t('download.toast.failed.title', '下载失败'),
                            tf('download.toast.failed.message', { name: data.fileName || data.taskId, error: data.errorMessage || t('common.unknown_error', '未知错误') }, '下载任务 {name} 失败: {error}'),
                            'error'
                        );
                    }
                }
                renderList();
                return;
            }
            if (data.type === 'download_progress' && data.taskId) {
                const id = String(data.taskId);
                const nowMs = normalizeTimestampMs(data && data.timestamp);
                const downloadedBytes = data && data.downloadedBytes != null ? Number(data.downloadedBytes) : 0;
                const serverSpeed = data && data.speedBytesPerSecond != null
                    ? Number(data.speedBytesPerSecond)
                    : (data && data.speed != null ? Number(data.speed) : NaN);

                const prev = state.speedByTaskId ? state.speedByTaskId[id] : null;
                const prevObj = (prev && typeof prev === 'object') ? prev : null;

                let speedBps = 0;
                if (Number.isFinite(serverSpeed) && serverSpeed >= 0) {
                    speedBps = serverSpeed;
                } else if (prevObj && typeof prevObj.atMs === 'number' && typeof prevObj.bytes === 'number') {
                    const dtMs = nowMs - prevObj.atMs;
                    const deltaBytes = downloadedBytes - prevObj.bytes;
                    if (dtMs >= SPEED_SAMPLE_MIN_MS && deltaBytes >= 0) {
                        const instantSpeed = (deltaBytes * 1000) / dtMs;
                        const previousSpeed = typeof prevObj.speedBps === 'number' ? prevObj.speedBps : 0;
                        speedBps = previousSpeed > 0
                            ? (previousSpeed * (1 - SPEED_EMA_ALPHA)) + (instantSpeed * SPEED_EMA_ALPHA)
                            : instantSpeed;
                    } else if (typeof prevObj.speedBps === 'number') {
                        speedBps = prevObj.speedBps;
                    }
                }

                if (!state.speedByTaskId) state.speedByTaskId = {};
                if (prevObj && nowMs - prevObj.atMs < SPEED_SAMPLE_MIN_MS) {
                    state.speedByTaskId[id] = { atMs: prevObj.atMs, bytes: prevObj.bytes, speedBps };
                } else {
                    state.speedByTaskId[id] = { atMs: nowMs, bytes: downloadedBytes, speedBps };
                }
                mergeUpdate(id, {
                    downloadedBytes: data.downloadedBytes,
                    totalBytes: data.totalBytes
                });
                renderList();
                return;
            }
        } catch (e) {
        }
    }

    function connectWs() {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${window.location.host}/ws`;
        try {
            state.websocket = new WebSocket(wsUrl);
            state.websocket.onopen = function () {
                state.reconnectAttempts = 0;
                try {
                    state.websocket.send(JSON.stringify({ type: 'connect', message: 'Connected', timestamp: new Date().toISOString() }));
                } catch (e) {
                }
            };
            state.websocket.onmessage = function (event) {
                handleWsMessage(event.data);
            };
            state.websocket.onclose = function () {
                if (!state.started) return;
                if (state.reconnectAttempts >= state.maxReconnectAttempts) return;
                state.reconnectAttempts += 1;
                setTimeout(connectWs, state.reconnectIntervalMs);
            };
            state.websocket.onerror = function () {
            };
        } catch (e) {
        }
    }

    function refresh() {
        const container = byId('mobileDownloadsList');
        if (container) container.innerHTML = `<div class="loading-spinner"><div class="spinner"></div></div>`;
        fetch('/api/downloads/list')
            .then((r) => r.json())
            .then((data) => {
                if (!data || data.success !== true) throw new Error((data && data.error) ? data.error : t('download.list_fetch_failed', '获取下载列表失败'));
                state.downloads = Array.isArray(data.downloads) ? data.downloads : [];
                renderList();
            })
            .catch((e) => {
                if (container) {
                    container.innerHTML = `
                        <div class="empty-state">
                            <div class="empty-state-icon"><i class="fas fa-exclamation-triangle"></i></div>
                            <div class="empty-state-title">${t('common.load_failed', '加载失败')}</div>
                            <div class="empty-state-text">${escapeHtml(e && e.message ? e.message : t('common.network_error', '网络错误'))}</div>
                            <button class="btn btn-primary" id="mobileDownloadsRetryBtn">${t('common.retry', '重试')}</button>
                        </div>
                    `;
                    const retry = byId('mobileDownloadsRetryBtn');
                    if (retry) retry.onclick = refresh;
                }
            });
    }

    function getDownloadPath() {
        return fetch('/api/downloads/path/get')
            .then((r) => r.json())
            .then((data) => (data && data.path) ? String(data.path) : '')
            .catch(() => '');
    }

    function setDownloadPath(path) {
        return fetch('/api/downloads/path/set', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path })
        })
            .then((r) => r.json());
    }

    function createDownload(payload) {
        return fetch('/api/downloads/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
            .then((r) => r.json());
    }

    function pause(taskId) {
        return fetch('/api/downloads/pause', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ taskId })
        }).then((r) => r.json());
    }

    function resume(taskId) {
        return fetch('/api/downloads/resume', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ taskId })
        }).then((r) => r.json());
    }

    function del(taskId) {
        return fetch('/api/downloads/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ taskId })
        }).then((r) => r.json());
    }

    function openCreateModal() {
        getDownloadPath().then((path) => {
            const el = byId('mobileDownloadPathInput');
            if (el) el.value = path || '';
        });
        const modal = byId('mobileDownloadCreateModal');
        if (modal) modal.classList.add('show');
    }

    function openSettingsModal() {
        getDownloadPath().then((path) => {
            const el = byId('mobileDefaultDownloadPathInput');
            if (el) el.value = path || '';
        });
        const modal = byId('mobileDownloadSettingsModal');
        if (modal) modal.classList.add('show');
    }

    function start() {
        if (state.started) return;
        state.started = true;
        refresh();
        connectWs();
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

    document.addEventListener('DOMContentLoaded', function () {
        const list = byId('mobileDownloadsList');
        if (list) {
            list.addEventListener('click', function (e) {
                const btn = e && e.target ? e.target.closest('[data-dl-act]') : null;
                if (!btn) return;
                const act = btn.getAttribute('data-dl-act');
                const taskId = btn.getAttribute('data-dl-id');
                if (!taskId) return;
                if (act === 'pause') {
                    pause(taskId).then((data) => {
                        if (data && data.success) showToast(t('toast.success', '成功'), t('download.toast.paused', '已暂停'), 'success');
                        else showToast(t('toast.error', '错误'), (data && data.error) ? data.error : t('download.toast.pause_failed', '暂停失败'), 'error');
                    });
                } else if (act === 'resume') {
                    resume(taskId).then((data) => {
                        if (data && data.success) showToast(t('toast.success', '成功'), t('download.toast.resumed', '已恢复'), 'success');
                        else showToast(t('toast.error', '错误'), (data && data.error) ? data.error : t('download.toast.resume_failed', '恢复失败'), 'error');
                    });
                } else if (act === 'delete') {
                    if (!confirm(t('confirm.download.delete', '确定要删除这个下载任务吗？'))) return;
                    del(taskId).then((data) => {
                        if (data && data.success) {
                            showToast(t('toast.success', '成功'), t('download.toast.deleted', '已删除'), 'success');
                            refresh();
                        } else {
                            showToast(t('toast.error', '错误'), (data && data.error) ? data.error : t('download.toast.delete_failed', '删除失败'), 'error');
                        }
                    });
                }
            });
        }

        const refreshBtn = byId('mobileDownloadRefreshBtn');
        const createBtn = byId('mobileDownloadCreateBtn');
        const settingsBtn = byId('mobileDownloadSettingsBtn');
        const createSubmit = byId('mobileDownloadCreateSubmitBtn');
        const settingsSave = byId('mobileDownloadSettingsSaveBtn');

        if (refreshBtn) refreshBtn.addEventListener('click', refresh);
        if (createBtn) createBtn.addEventListener('click', openCreateModal);
        if (settingsBtn) settingsBtn.addEventListener('click', openSettingsModal);

        if (createSubmit) {
            createSubmit.addEventListener('click', function () {
                const urlEl = byId('mobileDownloadUrlInput');
                const pathEl = byId('mobileDownloadPathInput');
                const fileNameEl = byId('mobileDownloadFileNameInput');
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
                createDownload(payload).then((data) => {
                    if (data && data.success) {
                        showToast(t('toast.success', '成功'), t('download.toast.task_created', '任务已创建'), 'success');
                        if (typeof window.closeModal === 'function') window.closeModal('mobileDownloadCreateModal');
                        refresh();
                    } else {
                        showToast(t('toast.error', '错误'), (data && data.error) ? data.error : t('download.toast.create_failed', '创建失败'), 'error');
                    }
                }).catch(() => showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error'));
            });
        }

        if (settingsSave) {
            settingsSave.addEventListener('click', function () {
                const el = byId('mobileDefaultDownloadPathInput');
                const path = el ? String(el.value || '').trim() : '';
                if (!path) {
                    showToast(t('toast.error', '错误'), t('download.validation.download_dir_required', '请输入下载路径'), 'error');
                    return;
                }
                setDownloadPath(path).then((data) => {
                    if (data && data.path) {
                        showToast(t('toast.success', '成功'), t('toast.saved', '已保存'), 'success');
                        if (typeof window.closeModal === 'function') window.closeModal('mobileDownloadSettingsModal');
                    } else {
                        showToast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                    }
                }).catch(() => showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error'));
            });
        }
    });

    window.MobileDownloadManager = {
        start,
        stop,
        refresh,
        openCreateModal,
        openSettingsModal
    };
})();

