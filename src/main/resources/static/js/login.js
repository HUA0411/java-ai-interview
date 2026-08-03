/* 登录 / 注册页逻辑 */
(function () {
  'use strict';

  // 已登录用户访问登录页时直接进入面试页
  if (localStorage.getItem('token')) {
    location.href = '/interview.html';
    return;
  }

  const tabLogin = document.getElementById('tab-login');
  const tabRegister = document.getElementById('tab-register');
  const formTitle = document.getElementById('form-title');
  const submitBtn = document.getElementById('submit-btn');
  const errorBar = document.getElementById('error-bar');
  const username = document.getElementById('username');
  const password = document.getElementById('password');

  let mode = 'login'; // login | register

  function switchMode(m) {
    mode = m;
    tabLogin.classList.toggle('active', m === 'login');
    tabRegister.classList.toggle('active', m === 'register');
    formTitle.textContent = m === 'login' ? '登录' : '注册';
    submitBtn.textContent = m === 'login' ? '登 录' : '注 册';
    errorBar.classList.remove('show');
  }

  function showError(msg) {
    errorBar.textContent = msg;
    errorBar.classList.add('show');
  }

  tabLogin.addEventListener('click', () => switchMode('login'));
  tabRegister.addEventListener('click', () => switchMode('register'));

  submitBtn.addEventListener('click', async () => {
    const name = username.value.trim();
    const pwd = password.value;

    if (!name || !pwd) return showError('请输入用户名和密码');
    if (mode === 'register' && pwd.length < 6) return showError('密码至少 6 位');

    try {
      submitBtn.disabled = true;
      const data = await API.post('/api/auth/' + mode, { username: name, password: pwd });
      localStorage.setItem('token', data.token);
      localStorage.setItem('username', data.username);
      location.href = '/interview.html';
    } catch (e) {
      showError(e.message);
    } finally {
      submitBtn.disabled = false;
    }
  });

  // 回车提交
  password.addEventListener('keydown', (e) => { if (e.key === 'Enter') submitBtn.click(); });
  username.addEventListener('keydown', (e) => { if (e.key === 'Enter') password.focus(); });
})();
