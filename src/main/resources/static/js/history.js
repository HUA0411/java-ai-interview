/* 历史记录页：会话列表 + 报告展示（报告异步生成中则轮询） */
(function () {
  'use strict';

  API.requireLogin();

  const els = {
    listSection: document.getElementById('list-section'),
    reportSection: document.getElementById('report-section'),
    sessionList: document.getElementById('session-list'),
    reportBody: document.getElementById('report-body'),
    backBtn: document.getElementById('back-btn'),
    errorBar: document.getElementById('error-bar'),
  };

  const DIR_NAMES = { JAVA: 'Java 后端', FRONTEND: '前端开发', AI: 'AI 应用' };

  init();

  async function init() {
    try {
      const sessions = await API.get('/api/session/list');
      renderList(sessions);
    } catch (e) {
      showError(e.message);
    }
  }

  function renderList(sessions) {
    els.sessionList.innerHTML = '';
    if (!sessions.length) {
      els.sessionList.innerHTML = '<p class="placeholder-line" style="padding:18px;border:1px dashed var(--line-strong);border-radius:12px;color:var(--ink-faint)">还没有面试记录，<a href="/interview.html">去开始第一场面试</a> →</p>';
      return;
    }
    sessions.forEach((s) => {
      const item = document.createElement('div');
      item.className = 'card session-item';
      const time = (s.createdAt || '').replace('T', ' ').slice(0, 16);
      item.innerHTML =
        '<div>' +
        '  <span class="dir">' + DIR_NAMES[s.direction] + '</span>' +
        '  <div class="time">' + time + ' · ' + s.questionCount + '/' + 5 + ' 题</div>' +
        '</div>' +
        '<span class="status-tag status-' + s.status + '">' +
        (s.status === 'FINISHED' ? '已结束 · 查看报告' : '进行中 · 继续面试') + '</span>';
      item.addEventListener('click', () => s.status === 'FINISHED' ? openReport(s) : location.href = '/interview.html?id=' + s.id);
      els.sessionList.appendChild(item);
    });
  }

  async function openReport(session) {
    els.listSection.style.display = 'none';
    els.reportSection.style.display = 'block';
    els.reportBody.innerHTML = '<p style="color:var(--ink-faint)">报告生成中，请稍候…</p>';

    // 报告异步生成中：轮询直到拿到
    for (let i = 0; i < 12; i++) {
      try {
        const report = await API.get('/api/session/' + session.id + '/report');
        renderReport(report);
        return;
      } catch (e) {
        if (e.message.includes('生成中')) {
          await new Promise((r) => setTimeout(r, 3000)); // 3s 后重试
        } else {
          showError(e.message);
          els.reportBody.innerHTML = '<p style="color:var(--accent)">报告加载失败：' + e.message + '</p>';
          return;
        }
      }
    }
    els.reportBody.innerHTML = '<p style="color:var(--accent)">报告生成超时，请稍后重新打开</p>';
  }

  function renderReport(report) {
    let qaHtml = '';
    try {
      const reviews = JSON.parse(report.qaReviews || '[]');
      qaHtml = reviews.map((r, i) =>
        '<div class="qa-item" style="margin-bottom:14px;padding:14px;border:1px solid var(--line);border-radius:10px">' +
        '<div style="display:flex;justify-content:space-between;gap:10px;flex-wrap:wrap">' +
        '<strong style="font-size:14px">第 ' + (i + 1) + ' 题 · ' + (r.score || '-') + ' 分</strong>' +
        '</div>' +
        '<p style="font-size:13.5px;color:var(--ink-soft);margin-top:4px">' + md2html(r.question || '') + '</p>' +
        '<p style="font-size:13.5px;margin-top:6px">' + md2html(r.comment || '') + '</p>' +
        '</div>'
      ).join('');
    } catch (e) { qaHtml = '<p style="color:var(--ink-faint)">（逐题点评解析失败）</p>'; }

    els.reportBody.innerHTML =
      '<div class="card report-card">' +
      '  <h2 class="section-title" style="margin-bottom:8px"><span class="num">02</span>面试评估报告</h2>' +
      '  <div class="report-score">' + report.overallScore + '<span style="font-size:1rem;color:var(--ink-faint)"> / 100</span></div>' +
      '  <div class="report-section"><h4>逐题点评</h4>' + qaHtml + '</div>' +
      '  <div class="report-section"><h4>综合评语</h4>' + md2html(report.summary || '') + '</div>' +
      '  <div class="report-section"><h4>改进建议</h4>' + md2html(report.suggestions || '') + '</div>' +
      '</div>';
  }

  els.backBtn.addEventListener('click', () => {
    els.reportSection.style.display = 'none';
    els.listSection.style.display = 'block';
    init();
  });

  function showError(msg) {
    els.errorBar.textContent = msg;
    els.errorBar.classList.add('show');
  }
})();
