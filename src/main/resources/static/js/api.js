/* 共享 API 封装：token 管理与请求 */

const API = {
  /** 统一 JSON 请求；失败/业务错误抛异常 */
  async request(path, { method = 'GET', body } = {}) {
    const headers = { 'Content-Type': 'application/json' };
    const token = localStorage.getItem('token');
    if (token) headers['Authorization'] = 'Bearer ' + token;

    const resp = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
    const data = await resp.json().catch(() => null);

    if (resp.status === 401) {
      // 登录态失效 → 回登录页
      localStorage.removeItem('token');
      location.href = '/login.html';
      throw new Error('登录已失效');
    }
    if (!data || data.code !== 0) {
      throw new Error(data?.message || '请求失败');
    }
    return data.data;
  },

  get(path) { return this.request(path); },

  post(path, body) { return this.request(path, { method: 'POST', body }); },

  /** 检查登录态，未登录跳转 */
  requireLogin() {
    if (!localStorage.getItem('token')) location.href = '/login.html';
  },

  logout() {
    localStorage.removeItem('token');
    location.href = '/login.html';
  },
};
