/* 轻量 Markdown 渲染器（自研，零外部依赖）
 * 支持 AI 输出常用语法：标题、加粗、行内代码、代码块、引用、无序/有序列表、链接、换行。
 * 安全策略：
 *   1. 先按原始文本做"行类型分类"（标题/引用/列表等依赖行首符号，必须在转义前判断）；
 *   2. 每个片段的文本内容一律先 escapeHtml 转义，再应用行内语法；
 *   因此任何 <script> 等标签都已成为普通文本，天然免疫 XSS。
 */
window.md2html = function (text) {
  'use strict';
  if (!text) return '';

  const escapeHtml = (s) => s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');

  // 行内语法（作用于已转义的文本，安全）
  const inline = (s) => s
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
      '<a href="$2" target="_blank" rel="noopener">$1</a>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>');

  const lines = text.split('\n');
  let html = '';
  let inCode = false, inUl = false, inOl = false;
  let codeBuf = [];

  const closeList = () => {
    if (inUl) { html += '</ul>'; inUl = false; }
    if (inOl) { html += '</ol>'; inOl = false; }
  };

  for (const rawLine of lines) {
    const t = rawLine.trim();

    // 代码块 ``` ... ```
    if (t.startsWith('```')) {
      if (!inCode) {
        closeList();
        inCode = true;
        html += '<pre><code>';
      } else {
        inCode = false;
        html += codeBuf.join('\n') + '</code></pre>';
        codeBuf = [];
      }
      continue;
    }
    if (inCode) { codeBuf.push(escapeHtml(rawLine)); continue; }

    // 标题 ### 标题
    const heading = t.match(/^(#{1,4})\s+(.*)$/);
    if (heading) {
      closeList();
      const level = heading[1].length;
      html += '<h' + level + '>' + inline(escapeHtml(heading[2])) + '</h' + level + '>';
      continue;
    }

    // 引用块 > 内容
    const quote = t.match(/^>\s?(.*)$/);
    if (quote) {
      closeList();
      html += '<blockquote>' + inline(escapeHtml(quote[1])) + '</blockquote>';
      continue;
    }

    // 水平线 --- / ***
    if (/^(-{3,}|\*{3,})$/.test(t)) {
      closeList();
      html += '<hr>';
      continue;
    }

    // 无序列表
    const ul = t.match(/^[-*]\s+(.*)$/);
    if (ul) {
      if (!inUl) { closeList(); inUl = true; html += '<ul>'; }
      html += '<li>' + inline(escapeHtml(ul[1])) + '</li>';
      continue;
    }

    // 有序列表
    const ol = t.match(/^\d+\.\s+(.*)$/);
    if (ol) {
      if (!inOl) { closeList(); inOl = true; html += '<ol>'; }
      html += '<li>' + inline(escapeHtml(ol[1])) + '</li>';
      continue;
    }

    // 空行 → 换行
    if (!t) { closeList(); html += '<br>'; continue; }

    // 普通段落
    closeList();
    html += '<p>' + inline(escapeHtml(t)) + '</p>';
  }

  closeList();
  if (inCode) html += codeBuf.join('\n') + '</code></pre>';
  return html;
};
