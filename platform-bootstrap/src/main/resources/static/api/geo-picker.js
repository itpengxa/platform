/**
 * Platform GEO 级联选择器（H5 / Web 嵌入）
 * 用法：PlatformGeoPicker.open({ clientCode, clientName, lang, onConfirm, onCancel })
 */
(function (global) {
  'use strict';

  const MAX_LEVEL = 5;
  const STYLE_ID = 'platform-geo-picker-style';

  function injectStyles() {
    if (document.getElementById(STYLE_ID)) return;
    const css = `
.pgp-root{position:fixed;inset:0;z-index:2147483000;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif}
.pgp-mask{position:absolute;inset:0;background:rgba(0,0,0,.45);opacity:0;transition:opacity .2s}
.pgp-root.pgp-open .pgp-mask{opacity:1}
.pgp-sheet{position:absolute;left:0;right:0;bottom:0;max-height:min(78vh,640px);background:#fff;border-radius:12px 12px 0 0;display:flex;flex-direction:column;transform:translateY(100%);transition:transform .25s ease;box-shadow:0 -4px 24px rgba(0,0,0,.12)}
.pgp-root.pgp-open .pgp-sheet{transform:translateY(0)}
@media(min-width:768px){.pgp-sheet{left:50%;right:auto;bottom:auto;top:50%;width:min(420px,92vw);max-height:min(70vh,560px);border-radius:12px;transform:translate(-50%,-50%) scale(.96);opacity:0}
.pgp-root.pgp-open .pgp-sheet{transform:translate(-50%,-50%) scale(1);opacity:1}}
.pgp-head{display:flex;align-items:center;border-bottom:1px solid #eee;min-height:44px;padding:0 40px 0 8px;position:relative;flex-shrink:0}
.pgp-tabs{display:flex;overflow-x:auto;gap:4px;flex:1;-webkit-overflow-scrolling:touch;scrollbar-width:none}
.pgp-tabs::-webkit-scrollbar{display:none}
.pgp-tab{flex-shrink:0;padding:10px 8px;font-size:14px;color:#666;border:none;background:0 0;cursor:pointer;border-bottom:2px solid transparent;max-width:120px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.pgp-tab.pgp-active{color:var(--pgp-primary,#e1251b);border-bottom-color:var(--pgp-primary,#e1251b);font-weight:500}
.pgp-close{position:absolute;right:8px;top:50%;transform:translateY(-50%);width:32px;height:32px;border:none;background:0 0;font-size:22px;line-height:1;color:#999;cursor:pointer}
.pgp-body{flex:1;overflow-y:auto;-webkit-overflow-scrolling:touch}
.pgp-item{display:flex;align-items:center;gap:8px;padding:14px 12px 14px 16px;font-size:15px;color:#333;border-bottom:1px solid #f5f5f5;cursor:pointer}
.pgp-item:active{background:#fafafa}
.pgp-item.pgp-picked{color:var(--pgp-primary,#e1251b)}
.pgp-item .pgp-label{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.pgp-item .pgp-arrow{flex-shrink:0;color:#ccc;font-size:16px;line-height:1;padding-right:2px}
.pgp-loading,.pgp-error,.pgp-empty{padding:32px 16px;text-align:center;color:#999;font-size:14px}
.pgp-error{color:#e1251b}
.pgp-status{padding:8px 16px;font-size:12px;color:#999;border-top:1px solid #f0f0f0;flex-shrink:0}
`;
    const el = document.createElement('style');
    el.id = STYLE_ID;
    el.textContent = css;
    document.head.appendChild(el);
  }

  function displayName(node) {
    return node.displayName || node.nameCh || node.name || node.nameEn || ('#' + node.id);
  }

  function tokenStorageKey(clientCode) {
    return 'platform_geo_token_' + clientCode;
  }

  async function ensureToken(apiBase, clientCode, clientName, issueSecret) {
    const key = tokenStorageKey(clientCode);
    try {
      const cached = sessionStorage.getItem(key);
      if (cached) return cached;
    } catch (e) {}
    const headers = {
      'Content-Type': 'application/json',
      Accept: 'application/json'
    };
    if (issueSecret) {
      headers['X-Platform-Issue-Secret'] = issueSecret;
    }
    const res = await fetch(apiBase + '/api/platform/v1/auth/token/issue', {
      method: 'POST',
      headers: headers,
      body: JSON.stringify({ clientCode: clientCode, clientName: clientName || clientCode }),
    });
    const json = await res.json();
    if (!res.ok || json.code !== 0) {
      throw new Error(json.message || 'Token 签发失败 HTTP ' + res.status);
    }
    const token = json.data && json.data.token;
    if (!token) throw new Error('签发响应无 token');
    try {
      sessionStorage.setItem(key, token);
    } catch (e) {}
    return token;
  }

  function createApi(apiBase, token, lang) {
    async function request(path) {
      const sep = path.indexOf('?') >= 0 ? '&' : '?';
      const url = apiBase + path + sep + 'lang=' + encodeURIComponent(lang || '');
      const res = await fetch(url, {
        headers: { Accept: 'application/json', 'X-Platform-Token': token },
      });
      const json = await res.json();
      if (!res.ok || (json.code !== undefined && json.code !== 0)) {
        throw new Error(json.message || 'HTTP ' + res.status);
      }
      return json.data !== undefined ? json.data : json;
    }
    return {
      countries: () => request('/api/geo/v1/countries'),
      children: (parentId) => request('/api/geo/v1/regions/children?parentId=' + parentId),
    };
  }

  function postToParent(event, data) {
    if (global.parent && global.parent !== global) {
      global.parent.postMessage({ source: 'platform-geo-picker', event: event, data: data }, '*');
    }
  }

  function buildResult(stack) {
    const text = stack.map(function (s) { return s.label; }).join(' / ');
    const leaf = stack[stack.length - 1];
    return {
      text: text,
      fullPathName: text,
      selections: stack.map(function (s) { return s.node; }),
      leaf: leaf ? leaf.node : null,
    };
  }

  function open(config) {
    config = config || {};
    const clientCode = (config.clientCode || 'embed').trim();
    const clientName = (config.clientName || clientCode).trim();
    const lang = config.lang || 'en';
    const apiBase = (config.apiBase != null ? config.apiBase : '').replace(/\/$/, '');
    const primary = config.primaryColor || '#e1251b';
    const issueSecret = config.issueSecret || '';

    injectStyles();

    let root = document.getElementById('platform-geo-picker-root');
    if (!root) {
      root = document.createElement('div');
      root.id = 'platform-geo-picker-root';
      root.className = 'pgp-root';
      root.innerHTML =
        '<div class="pgp-mask" data-action="cancel"></div>' +
        '<div class="pgp-sheet" style="--pgp-primary:' +
        primary +
        '">' +
        '<div class="pgp-head"><div class="pgp-tabs" id="pgp-tabs"></div>' +
        '<button type="button" class="pgp-close" data-action="cancel" aria-label="关闭">×</button></div>' +
        '<div class="pgp-body" id="pgp-body"></div>' +
        '<div class="pgp-status" id="pgp-status"></div></div>';
      document.body.appendChild(root);
    }

    root.querySelector('.pgp-sheet').style.setProperty('--pgp-primary', primary);

    const tabsEl = root.querySelector('#pgp-tabs');
    const bodyEl = root.querySelector('#pgp-body');
    const statusEl = root.querySelector('#pgp-status');

    let stack = [];
    let viewIndex = 0;
    let api = null;
    let listCache = { '-1': null };

    function setStatus(msg) {
      statusEl.textContent = msg || '';
    }

    function close() {
      root.classList.remove('pgp-open');
      if (typeof config.onCancel === 'function') config.onCancel();
      postToParent('cancel', null);
    }

    function confirm() {
      const result = buildResult(stack);
      root.classList.remove('pgp-open');
      if (typeof config.onConfirm === 'function') config.onConfirm(result);
      postToParent('confirm', result);
    }

    root.querySelector('.pgp-mask').onclick = close;
    root.querySelector('.pgp-close').onclick = close;

    function renderTabs() {
      tabsEl.innerHTML = '';
      const placeholder = document.createElement('button');
      placeholder.type = 'button';
      placeholder.className = 'pgp-tab' + (viewIndex === stack.length ? ' pgp-active' : '');
      placeholder.textContent = stack.length ? '请选择' : '选择国家';
      placeholder.addEventListener('click', function () {
        viewIndex = stack.length;
        renderList();
        renderTabs();
      });
      stack.forEach(function (item, idx) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'pgp-tab' + (viewIndex === idx ? ' pgp-active' : '');
        btn.textContent = item.label;
        btn.title = item.label;
        btn.addEventListener('click', function () {
          viewIndex = idx;
          stack = stack.slice(0, idx + 1);
          renderTabs();
          renderList();
        });
        tabsEl.appendChild(btn);
      });
      tabsEl.appendChild(placeholder);
    }

    function showLoading() {
      bodyEl.innerHTML = '<div class="pgp-loading">加载中…</div>';
    }

    function showError(msg) {
      bodyEl.innerHTML = '<div class="pgp-error">' + msg + '</div>';
    }

    async function loadListForIndex(index) {
      if (index === 0) {
        if (!listCache['-1']) listCache['-1'] = await api.countries();
        return listCache['-1'];
      }
      const parent = stack[index - 1];
      if (!parent) return [];
      const key = String(parent.node.id);
      if (!listCache[key]) listCache[key] = await api.children(parent.node.id);
      return listCache[key];
    }

    async function renderList() {
      showLoading();
      try {
        const index = viewIndex;
        const rows = await loadListForIndex(index);
        if (!rows.length) {
          bodyEl.innerHTML = '<div class="pgp-empty">暂无下级区划</div>';
          return;
        }
        const pickedId = stack[index] ? stack[index].node.id : null;
        bodyEl.innerHTML = '';
        rows.forEach(function (row) {
          const div = document.createElement('div');
          div.className = 'pgp-item' + (pickedId === row.id ? ' pgp-picked' : '');
          const isCountry = index === 0;
          const node = isCountry
            ? { id: row.id, iso2: row.iso2, displayName: displayName(row), level: 1, regionType: 'COUNTRY', isLeaf: false }
            : row;
          const label = displayName(row);
          const lvl = node.level || index + 1;
          const hasMore = isCountry || (row.isLeaf === false && lvl < MAX_LEVEL);
          div.innerHTML = '<span class="pgp-label">' + label + '</span>' + (hasMore ? '<span class="pgp-arrow">›</span>' : '');
          div.addEventListener('click', function () {
            onPick(index, { node: node, label: label, level: node.level || index + 1 }, row, isCountry);
          });
          bodyEl.appendChild(div);
        });
      } catch (e) {
        showError(e.message || String(e));
      }
    }

    function onPick(index, entry, raw, isCountry) {
      stack = stack.slice(0, index);
      stack.push(entry);
      viewIndex = stack.length;

      const level = entry.level || stack.length;
      const isLeaf = !isCountry && raw.isLeaf === true;
      const atMax = level >= MAX_LEVEL;

      if ((!isCountry && isLeaf) || (isCountry && raw.maxLevel === 1) || atMax) {
        confirm();
        return;
      }

      renderTabs();
      renderList();
    }

    async function start() {
      root.classList.add('pgp-open');
      stack = [];
      viewIndex = 0;
      listCache = { '-1': null };
      setStatus(clientCode + ' · 正在连接…');
      showLoading();
      renderTabs();
      try {
        const token = await ensureToken(apiBase, clientCode, clientName, issueSecret);
        api = createApi(apiBase, token, lang);
        setStatus(clientCode);
        await renderList();
      } catch (e) {
        setStatus('');
        showError(e.message || String(e));
      }
    }

    start();
    return { close: close };
  }

  const PlatformGeoPicker = { open: open, version: '1.0.0' };
  global.PlatformGeoPicker = PlatformGeoPicker;
})(typeof window !== 'undefined' ? window : this);
