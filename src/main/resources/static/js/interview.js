/* 面试页逻辑：创建面试 → 对话（SSE 打字机流式输出）→ 结束 */
(function () {
  'use strict';

  API.requireLogin();

  let sessionId = new URLSearchParams(location.search).get('id');
  let questionCount = 0;
  const TOTAL = 5;

  const els = {
    createSection: document.getElementById('create-section'),
    chatSection: document.getElementById('chat-section'),
    directionCards: document.querySelectorAll('.direction-card'),
    startBtn: document.getElementById('start-btn'),
    progress: document.getElementById('progress'),
    chatBox: document.getElementById('chat-box'),
    answerBar: document.getElementById('answer-bar'),
    answerInput: document.getElementById('answer-input'),
    sendBtn: document.getElementById('send-btn'),
    errorBar: document.getElementById('error-bar'),
  };

  // ---------- 创建面试 ----------
  let selectedDirection = 'JAVA';
  els.directionCards.forEach((card) => {
    card.addEventListener('click', () => {
      els.directionCards.forEach((c) => c.classList.remove('selected'));
      card.classList.add('selected');
      selectedDirection = card.dataset.direction;
    });
  });

  els.startBtn.addEventListener('click', async () => {
    try {
      els.startBtn.disabled = true;
      const data = await API.post('/api/session/create', { direction: selectedDirection });
      sessionId = data.sessionId;
      questionCount = data.questionCount;
      history.replaceState(null, '', '/interview.html?id=' + sessionId);
      enterChat(data.question);
    } catch (e) {
      showError(e.message);
      els.startBtn.disabled = false;
    }
  });

  // ---------- 发送回答（SSE 流式） ----------
  els.sendBtn.addEventListener('click', sendAnswer);
  els.answerInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendAnswer(); }
  });

  async function sendAnswer() {
    const content = els.answerInput.value.trim();
    if (!content || !sessionId) return;
    if (els.sendBtn.disabled) return;

    addMessage('USER', content);
    els.answerInput.value = '';
    els.sendBtn.disabled = true;

    try {
      const aiMsg = addMessage('AI', '');
      await streamAnswer(content, aiMsg);

      // 完成后判断是否结束
      if (questionCount >= TOTAL) {
        addSystemLine('— 本场面试结束，前往历史页查看评估报告 —');
        els.answerBar.style.display = 'none';
      }
    } catch (e) {
      showError(e.message);
    } finally {
      els.sendBtn.disabled = false;
    }
  }

  /** fetch POST + 逐行解析 SSE，增量写入 AI 气泡 */
  async function streamAnswer(content, aiMsg) {
    const resp = await fetch('/api/session/' + sessionId + '/answer-stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + localStorage.getItem('token'),
      },
      body: JSON.stringify({ content }),
    });

    if (!resp.ok) {
      const data = await resp.json().catch(() => null);
      throw new Error(data?.message || '请求失败');
    }

    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    const fullText = { text: '' }; // 累计全文，供实时 Markdown 渲染

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      let idx;
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        const dataLine = block.split('\n').find((l) => l.startsWith('data:'));
        if (!dataLine) continue;
        const evt = JSON.parse(dataLine.slice(5).trim());
        handleSseEvent(evt, aiMsg, fullText);
      }
    }
  }

  function handleSseEvent(evt, aiMsg, fullText) {
    if (evt.type === 'delta') {
      // 流式实时渲染 Markdown（每段增量后重渲染全文，效果等同打字机）
      fullText.text += evt.content;
      aiMsg.querySelector('.content').innerHTML = md2html(fullText.text);
      els.chatBox.scrollTop = els.chatBox.scrollHeight;
    } else if (evt.type === 'finished') {
      aiMsg.classList.remove('typing-cursor');
      questionCount = evt.questionCount;
      els.progress.textContent = 'PROGRESS: ' + questionCount + ' / ' + TOTAL;
    } else if (evt.type === 'error') {
      aiMsg.classList.remove('typing-cursor');
      if (!aiMsg.querySelector('.content').textContent) {
        aiMsg.querySelector('.content').textContent = '(AI 回复失败：' + evt.message + ')';
      }
      throw new Error(evt.message);
    }
  }

  // ---------- 页面恢复（带 ?id= 刷新时加载历史消息） ----------
  if (sessionId) {
    (async () => {
      try {
        const messages = await API.get('/api/session/' + sessionId + '/messages');
        els.createSection.style.display = 'none';
        els.chatSection.style.display = 'block';
        messages.forEach((m) => addMessage(m.role === 'AI' ? 'AI' : 'USER', m.content));
        questionCount = messages.filter((m) => m.role === 'AI').length;
        els.progress.textContent = 'PROGRESS: ' + questionCount + ' / ' + TOTAL;
        if (questionCount >= TOTAL) {
          els.answerBar.style.display = 'none';
          addSystemLine('— 本场面试已结束，可前往历史页查看评估报告 —');
        }
      } catch (e) {
        showError(e.message);
      }
    })();
  }

  // ---------- UI 辅助 ----------
  function enterChat(firstQuestion) {
    els.createSection.style.display = 'none';
    els.chatSection.style.display = 'block';
    els.progress.textContent = 'PROGRESS: 1 / ' + TOTAL;
    const aiMsg = addMessage('AI', '');
    aiMsg.classList.remove('typing-cursor'); // 首题已完整显示，无需打字机光标
    aiMsg.querySelector('.content').innerHTML = md2html(firstQuestion);
    els.answerInput.focus();
  }

  function addMessage(role, content) {
    const div = document.createElement('div');
    div.className = 'msg ' + (role === 'AI' ? 'msg-ai typing-cursor' : 'msg-user');
    div.innerHTML = '<span class="msg-role">' + (role === 'AI' ? 'AI 面试官' : '你') + '</span>' +
      '<span class="content"></span>';
    const contentEl = div.querySelector('.content');
    if (content) {
      if (role === 'AI') {
        contentEl.innerHTML = md2html(content); // AI 消息渲染 Markdown
      } else {
        contentEl.textContent = content;        // 用户消息保持纯文本
      }
    }
    els.chatBox.appendChild(div);
    els.chatBox.scrollTop = els.chatBox.scrollHeight;
    return div;
  }

  function addSystemLine(text) {
    const div = document.createElement('div');
    div.style.cssText = 'text-align:center;font-family:var(--font-mono);font-size:12px;color:var(--ink-faint);letter-spacing:.1em;';
    div.textContent = text;
    els.chatBox.appendChild(div);
  }

  function showError(msg) {
    els.errorBar.textContent = msg;
    els.errorBar.classList.add('show');
  }
})();
