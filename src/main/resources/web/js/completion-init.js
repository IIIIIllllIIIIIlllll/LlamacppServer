function bindEvents() {
  function isDesktopCompletionHtml() {
    const links = Array.from(document.querySelectorAll('link[rel="stylesheet"]'));
    const hrefs = links.map(l => (l.getAttribute('href') || '').toLowerCase());
    const hasDesktop = hrefs.some(h => h.includes('completion.css'));
    const hasMobile = hrefs.some(h => h.includes('completion-mobile.css'));
    return hasDesktop && !hasMobile;
  }

  function isMobileCompletionHtml() {
    const links = Array.from(document.querySelectorAll('link[rel="stylesheet"]'));
    const hrefs = links.map(l => (l.getAttribute('href') || '').toLowerCase());
    const hasDesktop = hrefs.some(h => h.includes('completion.css'));
    const hasMobile = hrefs.some(h => h.includes('completion-mobile.css'));
    return hasMobile && !hasDesktop;
  }

  function getFilesFromClipboardEvent(e) {
    const out = [];
    const dt = e && e.clipboardData ? e.clipboardData : null;
    if (!dt) return out;

    const files = dt.files;
    if (files && files.length) {
      for (const f of Array.from(files)) {
        if (f) out.push(f);
      }
    }

    const items = dt.items;
    if (items && items.length) {
      for (const it of Array.from(items)) {
        if (!it || it.kind !== 'file') continue;
        const f = it.getAsFile ? it.getAsFile() : null;
        if (f) out.push(f);
      }
    }

    return out;
  }

  const isMobilePage = isMobileCompletionHtml();
  let lastTopicFocus = null;

  function setTopicOpen(open) {
    const isOpen = !!open;

    if (isMobilePage) {
      if (!els.topicOverlay) return;
      if (isOpen) lastTopicFocus = document.activeElement;
      if (!isOpen) {
        const active = document.activeElement;
        if (active && els.topicOverlay.contains(active)) {
          const focusTarget = (els.topicToggle && typeof els.topicToggle.focus === 'function') ? els.topicToggle : null;
          if (focusTarget) focusTarget.focus({ preventScroll: true });
          else if (active && typeof active.blur === 'function') active.blur();
        }
      }
      els.topicOverlay.classList.toggle('open', isOpen);
      if (els.topicBackdrop) els.topicBackdrop.classList.toggle('show', isOpen);
      els.topicOverlay.setAttribute('aria-hidden', isOpen ? 'false' : 'true');
      if (isOpen) els.topicOverlay.removeAttribute('inert');
      else els.topicOverlay.setAttribute('inert', '');
      document.documentElement.style.overflow = isOpen ? 'hidden' : '';
      if (isOpen) {
        renderTopics();
        if (els.topicOverlayClose && typeof els.topicOverlayClose.focus === 'function') {
          els.topicOverlayClose.focus({ preventScroll: true });
        }
      } else {
        const focusTarget = (lastTopicFocus && document.contains(lastTopicFocus)) ? lastTopicFocus : els.topicToggle;
        if (focusTarget && typeof focusTarget.focus === 'function') focusTarget.focus({ preventScroll: true });
        lastTopicFocus = null;
      }
      return;
    }

    if (!els.topicModal) return;
    if (isOpen) lastTopicFocus = document.activeElement;
    els.topicModal.classList.toggle('show', isOpen);
    els.topicModal.setAttribute('aria-hidden', isOpen ? 'false' : 'true');
    if (isOpen) {
      els.topicModal.removeAttribute('inert');
      renderTopics();
      if (els.topicModalClose && typeof els.topicModalClose.focus === 'function') {
        els.topicModalClose.focus({ preventScroll: true });
      }
    } else {
      els.topicModal.setAttribute('inert', '');
      const focusTarget = (lastTopicFocus && document.contains(lastTopicFocus)) ? lastTopicFocus : els.topicToggle;
      if (focusTarget && typeof focusTarget.focus === 'function') focusTarget.focus({ preventScroll: true });
      lastTopicFocus = null;
    }
  }

  els.sessionsToggle.addEventListener('click', () => {
    if (els.topicOverlay && els.topicOverlay.classList.contains('open')) setTopicOpen(false);
    if (els.topicModal && els.topicModal.classList.contains('show')) setTopicOpen(false);
    if (els.modelRow && els.modelRow.classList.contains('visible')) {
      els.modelRow.classList.remove('visible');
      if (els.modelRowBackdrop) els.modelRowBackdrop.classList.remove('show');
      els.modelRowToggle.textContent = '☰';
      els.modelRowToggle.title = '显示模型栏';
      els.modelRowToggle.setAttribute('aria-label', '显示模型栏');
      els.modelRowToggle.setAttribute('aria-expanded', 'false');
    }
    openDrawer();
    loadCompletions(false);
  });
  els.drawerClose.addEventListener('click', closeDrawer);
  els.backdrop.addEventListener('click', closeDrawer);

  function setModelRowOpen(open) {
    const isOpen = !!open;
    els.modelRow.classList.toggle('visible', isOpen);
    if (els.modelRowBackdrop) els.modelRowBackdrop.classList.toggle('show', isOpen);
    els.modelRowToggle.textContent = isOpen ? '×' : '☰';
    els.modelRowToggle.title = isOpen ? '隐藏模型栏' : '显示模型栏';
    els.modelRowToggle.setAttribute('aria-label', isOpen ? '隐藏模型栏' : '显示模型栏');
    els.modelRowToggle.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
    if (isOpen) renderTopics();
  }

  if (els.topicToggle) {
    els.topicToggle.addEventListener('click', () => {
      if (els.drawer && els.drawer.classList.contains('open')) closeDrawer();
      if (els.settingsPanel && els.settingsPanel.classList.contains('open')) setSettingsOpen(false);
      if (els.modelRow && els.modelRow.classList.contains('visible')) setModelRowOpen(false);
      const open = isMobilePage
        ? !(els.topicOverlay && els.topicOverlay.classList.contains('open'))
        : !(els.topicModal && els.topicModal.classList.contains('show'));
      setTopicOpen(open);
    });
  }
  if (els.topicOverlayClose) {
    els.topicOverlayClose.addEventListener('click', () => setTopicOpen(false));
  }
  if (els.topicModalClose) {
    els.topicModalClose.addEventListener('click', () => setTopicOpen(false));
  }
  if (els.topicBackdrop) {
    els.topicBackdrop.addEventListener('click', () => {
      if (isMobilePage && els.topicOverlay && els.topicOverlay.classList.contains('open')) setTopicOpen(false);
    });
  }
  if (els.topicModal) {
    els.topicModal.addEventListener('click', (e) => {
      if (!isMobilePage && e.target === els.topicModal) setTopicOpen(false);
    });
  }

  els.modelRowToggle.addEventListener('click', () => {
    if (els.topicOverlay && els.topicOverlay.classList.contains('open')) setTopicOpen(false);
    if (els.topicModal && els.topicModal.classList.contains('show')) setTopicOpen(false);
    const open = !els.modelRow.classList.contains('visible');
    setModelRowOpen(open);
  });

  if (els.modelRowBackdrop) {
    els.modelRowBackdrop.addEventListener('click', () => {
      if (els.modelRow.classList.contains('visible')) setModelRowOpen(false);
    });
  }

  els.refreshModels.addEventListener('click', () => loadModels());

  if (els.newTopicBtn) {
    els.newTopicBtn.addEventListener('click', () => {
      if (state.abortController) return;
      if (!state.currentCompletionId) return;
      const name = prompt('请输入话题名称', '新话题');
      if (name == null) return;
      const title = normalizeTopicTitle(name);
      const id = topicId();
      persistActiveTopic();
      if (!Array.isArray(state.topics)) state.topics = [];
      state.topics.unshift({ id, title, createdAt: Date.now(), updatedAt: Date.now() });
      if (!state.topicData || typeof state.topicData !== 'object') state.topicData = {};
      state.topicData[id] = { history: [], systemLogs: [], timingsLog: [] };
      state.activeTopicId = id;
      state.messages = [];
      state.systemLogs = [];
      state.timingsLog = [];
      syncMessageSequence();
      rerenderAll();
      renderTopics();
      scheduleSave('新建话题');
    });
  }

  els.attachBtn.addEventListener('click', () => {
    if (els.attachInput) els.attachInput.click();
  });
  els.attachInput.addEventListener('change', (e) => {
    const file = e && e.target && e.target.files ? e.target.files[0] : null;
    setAttachment(file);
  });
  els.attachClear.addEventListener('click', () => clearAttachment());

  if (els.promptInput && isDesktopCompletionHtml()) {
    els.promptInput.addEventListener('paste', (e) => {
      const files = getFilesFromClipboardEvent(e);
      const f = files && files.length ? files[0] : null;
      if (!f) return;
      setAttachment(f);
      if (typeof setStatus === 'function') setStatus('已从剪贴板添加附件：' + (f.name || 'file'));
    });
  }

  els.chatList.addEventListener('click', (e) => {
    const avatar = e && e.target ? e.target.closest('.avatar.clickable') : null;
    if (!avatar) return;
    if (!state.currentCompletionId) return;
    viewAvatar(state.currentCompletionId);
  });

  els.sendBtn.addEventListener('click', () => runCompletion());
  els.stopBtn.addEventListener('click', () => {
    if (state.abortController) state.abortController.abort();
  });

  els.clearChat.addEventListener('click', () => {
    if (!confirm('确认清空当前角色的对话内容？')) return;
    state.messages = [];
    state.timingsLog = [];
    rerenderAll();
    scheduleSave('清空对话');
  });

  els.toggleSettings.addEventListener('click', () => {
    const open = !els.settingsPanel.classList.contains('open');
    setSettingsOpen(open);
    if (open) {
      if (els.avatarSettingPreview) applyAssistantAvatar(els.avatarSettingPreview);
    }
  });
  els.closeSettings.addEventListener('click', () => {
    setSettingsOpen(false);
  });

  if (els.refreshMcpTools) {
    els.refreshMcpTools.addEventListener('click', () => refreshMcpTools());
  }

  if (els.avatarSettingPreview) {
    els.avatarSettingPreview.addEventListener('click', () => {
      if (!state.currentCompletionId) return;
      viewAvatar(state.currentCompletionId);
    });
  }
  if (els.avatarSettingUpload) {
    els.avatarSettingUpload.addEventListener('click', () => {
      if (!state.currentCompletionId) return;
      getAvatarUploadInput().click();
    });
  }

  els.newSessionBtn.addEventListener('click', () => createCompletion());
  els.reloadSessionsBtn.addEventListener('click', () => loadCompletions(false));

  els.sessionsList.addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-action="delete"]');
    const item = e.target.closest('.session-item');
    const id = item?.dataset?.id;
    if (btn && id) {
      e.stopPropagation();
      deleteCompletion(id);
      return;
    }
    if (id) switchCompletion(id);
  });

  els.titleInput.addEventListener('input', () => {
    refreshCompletionTitleInMessages();
    scheduleSave('标题');
  });
  els.modelSelect.addEventListener('change', () => scheduleSave('模型'));
  els.apiModelSelect.addEventListener('change', () => {
    state.apiModel = Number(els.apiModelSelect.value) === 0 ? 0 : 1;
    scheduleSave('端点');
  });
  els.systemPrompt.addEventListener('input', () => scheduleSave('系统描述'));
  els.rolePrompt.addEventListener('input', () => scheduleSave('角色设定'));
  els.userName.addEventListener('input', () => scheduleSave('用户名'));
  els.userPrefix.addEventListener('input', () => scheduleSave('用户前缀'));
  els.userSuffix.addEventListener('input', () => scheduleSave('用户后缀'));
  els.assistantPrefix.addEventListener('input', () => scheduleSave('助手前缀'));
  els.assistantSuffix.addEventListener('input', () => scheduleSave('助手后缀'));
  els.maxTokens.addEventListener('input', () => scheduleSave('参数'));
  els.temperature.addEventListener('input', () => scheduleSave('参数'));
  els.topP.addEventListener('input', () => scheduleSave('参数'));
  els.minP.addEventListener('input', () => scheduleSave('参数'));
  els.repeatPenalty.addEventListener('input', () => scheduleSave('参数'));
  els.stopSequences.addEventListener('input', () => scheduleSave('参数'));
  els.thinkingToggle.addEventListener('change', () => scheduleSave('思考开关'));
  els.webSearchToggle.addEventListener('change', () => scheduleSave('联网搜索开关'));

  els.kvCacheToggle.addEventListener('click', openKVCacheModal);
  els.kvCacheClose.addEventListener('click', closeKVCacheModal);
  els.kvCacheCancel.addEventListener('click', closeKVCacheModal);
  els.kvCacheModal.addEventListener('click', (e) => {
    if (e.target === els.kvCacheModal) closeKVCacheModal();
  });
  els.saveKV.addEventListener('click', () => kvCacheAction('save'));
  els.loadKV.addEventListener('click', () => kvCacheAction('load'));

  els.mcpToolsToggle.addEventListener('click', openMcpToolsModal);
  els.mcpToolsClose.addEventListener('click', closeMcpToolsModal);
  els.mcpToolsDone.addEventListener('click', closeMcpToolsModal);
  els.mcpToolsModal.addEventListener('click', (e) => {
    if (e.target === els.mcpToolsModal) closeMcpToolsModal();
  });

  els.editModal.addEventListener('click', (e) => {
    if (e.target === els.editModal) closeEditModal();
  });
  els.editClose.addEventListener('click', closeEditModal);
  els.editCancel.addEventListener('click', closeEditModal);
  els.editSave.addEventListener('click', saveEditModal);

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      if (els.editModal.classList.contains('show')) {
        closeEditModal();
        return;
      }
      if (els.kvCacheModal.classList.contains('show')) {
        closeKVCacheModal();
        return;
      }
      if (els.mcpToolsModal.classList.contains('show')) {
        closeMcpToolsModal();
        return;
      }
      if (els.topicOverlay && els.topicOverlay.classList.contains('open')) {
        setTopicOpen(false);
        return;
      }
      if (els.topicModal && els.topicModal.classList.contains('show')) {
        setTopicOpen(false);
        return;
      }
      if (els.settingsPanel.classList.contains('open')) {
        setSettingsOpen(false);
        return;
      }
      if (els.modelRow.classList.contains('visible')) {
        els.modelRow.classList.remove('visible');
        if (els.modelRowBackdrop) els.modelRowBackdrop.classList.remove('show');
        els.modelRowToggle.textContent = '☰';
        els.modelRowToggle.title = '显示模型栏';
        els.modelRowToggle.setAttribute('aria-label', '显示模型栏');
        els.modelRowToggle.setAttribute('aria-expanded', 'false');
        return;
      }
      if (els.drawer.classList.contains('open')) {
        closeDrawer();
        return;
      }
    }
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      if (els.editModal.classList.contains('show')) {
        saveEditModal();
        return;
      }
      runCompletion();
    }
  });
}

async function init() {
  bindEvents();
  renderAttachment();
  els.apiModelSelect.value = String(state.apiModel);
  await loadModels();
  await loadCompletions(true);
}

init();

