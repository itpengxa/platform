(function () {
  const TOKEN_KEY = 'platform_admin_token';
  const USER_KEY = 'platform_admin_user';

  function getToken() {
    return sessionStorage.getItem(TOKEN_KEY) || '';
  }

  function setSession(token, user) {
    if (token) sessionStorage.setItem(TOKEN_KEY, token);
    if (user) sessionStorage.setItem(USER_KEY, JSON.stringify(user));
  }

  function clearSession() {
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(USER_KEY);
  }

  function getUser() {
    try {
      return JSON.parse(sessionStorage.getItem(USER_KEY) || 'null');
    } catch (e) {
      return null;
    }
  }

  function requireLogin() {
    if (!getToken()) {
      location.href = '/admin/login.html';
      throw new Error('NEED_LOGIN');
    }
  }

  async function login(username, password) {
    const resp = await fetch('/admin/platform/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    const data = await resp.json();
    if (data.code !== 0) {
      throw new Error(data.message || '登录失败');
    }
    setSession(data.data.token, {
      username: data.data.username,
      displayName: data.data.displayName,
      expireAt: data.data.expireAt
    });
    return data.data;
  }

  async function logout() {
    const token = getToken();
    try {
      if (token) {
        await fetch('/admin/platform/v1/auth/logout', {
          method: 'POST',
          headers: { 'X-Admin-Token': token, 'Content-Type': 'application/json' }
        });
      }
    } catch (e) { /* ignore */ }
    clearSession();
    location.href = '/admin/login.html';
  }

  async function api(path, options) {
    options = options || {};
    requireLogin();
    const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
    headers['X-Admin-Token'] = getToken();
    const resp = await fetch(path, Object.assign({}, options, { headers }));
    let data;
    try {
      data = await resp.json();
    } catch (e) {
      throw new Error('响应非 JSON');
    }
    if (resp.status === 401 || data.code === 40101) {
      clearSession();
      alert('登录已失效，请重新登录');
      location.href = '/admin/login.html';
      throw new Error('ADMIN_UNAUTHORIZED');
    }
    if (data.code !== 0) {
      throw new Error(data.message || '请求失败');
    }
    return data.data;
  }

  window.AdminHttp = { api, login, logout, getToken, getUser, requireLogin, clearSession, setSession };
})();
