/**
 * Platform GEO 级联选择器（H5 / Web 嵌入）
 * 用法：PlatformGeoPicker.open({ clientCode, clientName, lang, onConfirm, onCancel })
 */
(function (global) {
  'use strict';

  const MAX_LEVEL = 5;
  const STYLE_ID = 'platform-geo-picker-style';
  const PICKER_VERSION = '1.1.6';

  const REPORT_PASS = {
    AUTO_CREATED: true,
    MANUAL_CREATED: true,
    ALREADY_EXISTS: true
  };

  const REPORT_REASON = {
    AUTO_CREATED: '已通过地图校验并自动创建',
    MANUAL_CREATED: '已人工创建',
    ALREADY_EXISTS: '该地址在当前上级下已存在',
    GEOCODE_FAIL: '地图编码失败，未能定位该地址',
    DISTANCE_REJECT: '未通过地图归属/距离校验（不在上级范围内或超出允许距离）',
    PARENT_NO_COORD: '上级区划缺少坐标，无法完成地图校验',
    REJECTED: '上报已被驳回'
  };

  function injectStyles() {
    const existing = document.getElementById(STYLE_ID);
    if (existing) existing.parentNode.removeChild(existing);
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
.pgp-search-wrap{padding:8px 12px 4px;flex-shrink:0;border-bottom:1px solid #f5f5f5}
.pgp-search{width:100%;box-sizing:border-box;padding:8px 10px;border:1px solid #e5e5e5;border-radius:8px;font-size:14px;outline:none}
.pgp-search:focus{border-color:var(--pgp-primary,#e1251b)}
.pgp-body{flex:1;overflow-y:auto;-webkit-overflow-scrolling:touch;min-height:120px}
.pgp-item{display:flex;align-items:center;gap:8px;padding:14px 12px 14px 16px;font-size:15px;color:#333;border-bottom:1px solid #f5f5f5;cursor:pointer}
.pgp-item:active{background:#fafafa}
.pgp-item.pgp-picked{color:var(--pgp-primary,#e1251b)}
.pgp-item .pgp-label{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.pgp-item .pgp-arrow{flex-shrink:0;color:#ccc;font-size:16px;line-height:1;padding-right:2px}
.pgp-loading,.pgp-error,.pgp-empty{padding:32px 16px;text-align:center;color:#999;font-size:14px}
.pgp-error{color:#e1251b}
.pgp-report-bar{flex-shrink:0;padding:10px 16px 12px;border-top:1px solid #f0f0f0;text-align:left}
.pgp-report-link{border:none;background:0 0;padding:0;color:var(--pgp-primary,#e1251b);font-size:13px;cursor:pointer;text-decoration:underline}
.pgp-report-link:disabled{opacity:.5;cursor:not-allowed}
.pgp-status{padding:8px 16px;font-size:12px;color:#999;border-top:1px solid #f0f0f0;flex-shrink:0}
.pgp-modal{position:absolute;inset:0;z-index:2;display:none;align-items:center;justify-content:center;background:rgba(0,0,0,.35);padding:16px;box-sizing:border-box}
.pgp-modal.pgp-show{display:flex}
.pgp-modal-box{width:100%;max-width:360px;background:#fff;border-radius:10px;padding:16px;box-shadow:0 8px 28px rgba(0,0,0,.18)}
.pgp-modal-box h3{margin:0 0 12px;font-size:16px;color:#333}
.pgp-modal-box .pgp-form-row{margin-bottom:10px}
.pgp-modal-box label{display:block;font-size:12px;color:#666;margin-bottom:4px}
.pgp-modal-box input,.pgp-modal-box textarea{width:100%;box-sizing:border-box;padding:8px 10px;border:1px solid #d9d9d9;border-radius:6px;font-size:14px}
.pgp-modal-box textarea{min-height:64px;resize:vertical}
.pgp-modal-actions{display:flex;gap:8px;justify-content:flex-end;margin-top:14px;flex-wrap:wrap}
.pgp-btn{padding:7px 14px;border-radius:6px;border:1px solid #d9d9d9;background:#fff;font-size:13px;cursor:pointer}
.pgp-btn-primary{background:var(--pgp-primary,#e1251b);border-color:var(--pgp-primary,#e1251b);color:#fff}
.pgp-btn:disabled{opacity:.55;cursor:not-allowed}
.pgp-modal-msg{margin-top:10px;font-size:13px;line-height:1.5;display:none}
.pgp-modal-msg.pgp-show{display:block}
.pgp-modal-msg.pgp-ok{color:#389e0d;background:#f6ffed;border:1px solid #b7eb8f;padding:8px 10px;border-radius:6px}
.pgp-modal-msg.pgp-fail{color:#cf1322;background:#fff2f0;border:1px solid #ffccc7;padding:8px 10px;border-radius:6px}
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

  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
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
    async function request(path, options) {
      options = options || {};
      const sep = path.indexOf('?') >= 0 ? '&' : '?';
      const url = apiBase + path + sep + 'lang=' + encodeURIComponent(lang || '');
      const headers = Object.assign(
        { Accept: 'application/json', 'X-Platform-Token': token },
        options.headers || {}
      );
      const res = await fetch(url, Object.assign({}, options, { headers: headers }));
      const json = await res.json();
      if (!res.ok || (json.code !== undefined && json.code !== 0)) {
        throw new Error(json.message || 'HTTP ' + res.status);
      }
      return json.data !== undefined ? json.data : json;
    }
    return {
      countries: function () { return request('/api/geo/v1/countries'); },
      children: function (parentId) {
        return request('/api/geo/v1/regions/children?parentId=' + parentId);
      },
      path: function (id) {
        return request('/api/geo/v1/regions/' + id + '/path');
      },
      reportMissing: function (body) {
        return request('/api/geo/v1/report/missing', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });
      }
    };
  }

  function postToParent(event, data) {
    if (global.parent && global.parent !== global) {
      global.parent.postMessage({ source: 'platform-geo-picker', event: event, data: data }, '*');
    }
  }

  function nodeName(node) {
    if (!node) return '';
    return displayName(node);
  }

  function numOrNull(v) {
    if (v === null || v === undefined || v === '') return null;
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }

  /** 组装末级地址详情（英文字段，避免中英键重复） */
  function buildAddressDetail(stack, pathNodes) {
    const byLevel = {};
    const chain = (pathNodes && pathNodes.length)
      ? pathNodes
      : stack.map(function (s) { return s.node; });
    chain.forEach(function (n, idx) {
      if (!n) return;
      const lvl = n.level != null ? Number(n.level) : (idx + 1);
      byLevel[lvl] = n;
    });
    // 国家若仅在 stack[0] 且 path 从 L2 起，补上
    if (!byLevel[1] && stack[0] && stack[0].node) {
      byLevel[1] = stack[0].node;
    }
    const leaf = chain[chain.length - 1] || (stack.length ? stack[stack.length - 1].node : null);
    const l1 = nodeName(byLevel[1]);
    const l2 = nodeName(byLevel[2]);
    const l3 = nodeName(byLevel[3]);
    const l4 = nodeName(byLevel[4]);
    const l5 = nodeName(byLevel[5]);
    const fullName = [l1, l2, l3, l4, l5].filter(function (x) { return !!x; }).join('/');
    return {
      path: (leaf && leaf.path) || '',
      code: (leaf && (leaf.code || leaf.iso2)) || '',
      l1Country: l1 || '',
      l2Province: l2 || '',
      l3City: l3 || '',
      l4District: l4 || '',
      l5Street: l5 || '',
      fullName: fullName,
      name: leaf ? nodeName(leaf) : '',
      longitude: numOrNull(leaf && leaf.longitude),
      latitude: numOrNull(leaf && leaf.latitude),
      id: leaf && leaf.id != null ? leaf.id : null,
      countryCode: (leaf && leaf.countryCode) || (byLevel[1] && (byLevel[1].countryCode || byLevel[1].iso2)) || ''
    };
  }

  function formatDetailText(detail) {
    return [
      'l1Country: ' + (detail.l1Country || ''),
      'l2Province: ' + (detail.l2Province || ''),
      'l3City: ' + (detail.l3City || ''),
      'l4District: ' + (detail.l4District || ''),
      'l5Street: ' + (detail.l5Street || ''),
      'path: ' + (detail.path || ''),
      'fullName: ' + (detail.fullName || ''),
      'name: ' + (detail.name || ''),
      'code: ' + (detail.code || ''),
      'longitude: ' + (detail.longitude == null ? '' : detail.longitude),
      'latitude: ' + (detail.latitude == null ? '' : detail.latitude),
      'id: ' + (detail.id == null ? '' : detail.id),
      'countryCode: ' + (detail.countryCode || '')
    ].join('\n');
  }

  function buildResult(stack, detail) {
    const text = stack.map(function (s) { return s.label; }).join(' / ');
    const leaf = stack[stack.length - 1];
    return {
      text: text,
      fullPathName: (detail && detail.fullName) || text,
      selections: stack.map(function (s) { return s.node; }),
      leaf: leaf ? leaf.node : null,
      detail: detail || null,
      detailText: detail ? formatDetailText(detail) : '',
      detailJson: detail || null
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

    // 始终重建壳层，避免旧版本缓存 DOM 缺少搜索/上报控件
    let root = document.getElementById('platform-geo-picker-root');
    if (root && root.parentNode) root.parentNode.removeChild(root);
    root = document.createElement('div');
    root.id = 'platform-geo-picker-root';
    root.className = 'pgp-root';
    root.innerHTML =
      '<div class="pgp-mask" data-action="cancel"></div>' +
      '<div class="pgp-sheet" style="--pgp-primary:' + primary + '">' +
      '<div class="pgp-head"><div class="pgp-tabs" id="pgp-tabs"></div>' +
      '<button type="button" class="pgp-close" data-action="cancel" aria-label="关闭">×</button></div>' +
      '<div class="pgp-search-wrap"><input type="search" class="pgp-search" id="pgp-search" placeholder="搜索当前层级" autocomplete="off"/></div>' +
      '<div class="pgp-body" id="pgp-body"></div>' +
      '<div class="pgp-report-bar" id="pgp-report-bar" style="display:none">' +
      '<button type="button" class="pgp-report-link" id="pgp-report-link">无地址? 我要上报</button></div>' +
      '<div class="pgp-status" id="pgp-status"></div>' +
      '<div class="pgp-modal" id="pgp-modal">' +
      '<div class="pgp-modal-box">' +
      '<h3>上报缺失地址</h3>' +
      '<div class="pgp-form-row"><label>地址名称 *</label><input id="pgp-report-name" maxlength="128" placeholder="请输入缺失的地址名称"/></div>' +
      '<div class="pgp-form-row"><label>备注（可选）</label><textarea id="pgp-report-remark" maxlength="256" placeholder="补充说明"></textarea></div>' +
      '<div class="pgp-modal-msg" id="pgp-report-msg"></div>' +
      '<div class="pgp-modal-actions">' +
      '<button type="button" class="pgp-btn" id="pgp-report-cancel">取消</button>' +
      '<button type="button" class="pgp-btn pgp-btn-primary" id="pgp-report-back" style="display:none">返回上级重新选择</button>' +
      '<button type="button" class="pgp-btn pgp-btn-primary" id="pgp-report-submit">提交</button>' +
      '</div></div></div></div>';
    document.body.appendChild(root);

    root.querySelector('.pgp-sheet').style.setProperty('--pgp-primary', primary);

    const tabsEl = root.querySelector('#pgp-tabs');
    const bodyEl = root.querySelector('#pgp-body');
    const statusEl = root.querySelector('#pgp-status');
    const searchEl = root.querySelector('#pgp-search');
    const reportBar = root.querySelector('#pgp-report-bar');
    const reportLink = root.querySelector('#pgp-report-link');
    const modal = root.querySelector('#pgp-modal');
    const reportNameEl = root.querySelector('#pgp-report-name');
    const reportRemarkEl = root.querySelector('#pgp-report-remark');
    const reportMsgEl = root.querySelector('#pgp-report-msg');
    const reportSubmitBtn = root.querySelector('#pgp-report-submit');
    const reportCancelBtn = root.querySelector('#pgp-report-cancel');
    const reportBackBtn = root.querySelector('#pgp-report-back');

    let stack = [];
    let viewIndex = 0;
    let api = null;
    let listCache = { '-1': null };
    let currentRows = [];
    let searchKeyword = '';
    let searchByLevel = {};

    function setStatus(msg) {
      statusEl.textContent = msg || '';
    }

    function close() {
      hideReportModal();
      root.classList.remove('pgp-open');
      if (typeof config.onCancel === 'function') config.onCancel();
      postToParent('cancel', null);
    }

    async function confirmLeaf() {
      hideReportModal();
      setStatus(clientCode + ' · v' + PICKER_VERSION + ' · 加载详情…');
      let pathNodes = null;
      const leafEntry = stack[stack.length - 1];
      const leafId = leafEntry && leafEntry.node && leafEntry.node.id;
      try {
        if (leafId && api) {
          pathNodes = await api.path(leafId);
        }
      } catch (e) {
        pathNodes = null;
      }
      const detail = buildAddressDetail(stack, pathNodes);
      const result = buildResult(stack, detail);
      setStatus(clientCode + ' · v' + PICKER_VERSION + ' · 已选中，见右侧结果');
      if (!config.keepOpen) {
        root.classList.remove('pgp-open');
      } else {
        // 预览模式：保持弹层，高亮当前选中项
        renderTabs();
        renderList();
      }
      if (typeof config.onConfirm === 'function') config.onConfirm(result);
      postToParent('confirm', result);
    }

    function confirm() {
      confirmLeaf();
    }

    root.querySelector('.pgp-mask').onclick = close;
    root.querySelector('.pgp-close').onclick = close;

    function listLevel(index) {
      if (index <= 0) return 1;
      const parent = stack[index - 1];
      if (parent && parent.level) return parent.level + 1;
      return index + 1;
    }

    function reportParent() {
      if (viewIndex <= 0) return null;
      return stack[viewIndex - 1] || null;
    }

    function updateReportBar() {
      const lvl = listLevel(viewIndex);
      const show = lvl >= 4 && lvl <= 5 && !!reportParent();
      reportBar.style.display = show ? 'block' : 'none';
    }

    function renderTabs() {
      tabsEl.innerHTML = '';
      stack.forEach(function (item, idx) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'pgp-tab' + (viewIndex === idx ? ' pgp-active' : '');
        btn.textContent = item.label;
        btn.title = item.label;
        btn.addEventListener('click', function () {
          viewIndex = idx;
          stack = stack.slice(0, idx + 1);
          searchKeyword = searchByLevel[viewIndex] || '';
          searchEl.value = searchKeyword;
          renderTabs();
          renderList();
        });
        tabsEl.appendChild(btn);
      });
      const placeholder = document.createElement('button');
      placeholder.type = 'button';
      placeholder.className = 'pgp-tab' + (viewIndex === stack.length ? ' pgp-active' : '');
      placeholder.textContent = stack.length ? '请选择' : '选择国家';
      placeholder.addEventListener('click', function () {
        viewIndex = stack.length;
        searchKeyword = searchByLevel[viewIndex] || '';
        searchEl.value = searchKeyword;
        renderList();
        renderTabs();
      });
      tabsEl.appendChild(placeholder);
    }

    function showLoading() {
      bodyEl.innerHTML = '<div class="pgp-loading">加载中…</div>';
    }

    function showError(msg) {
      bodyEl.innerHTML = '<div class="pgp-error">' + escapeHtml(msg) + '</div>';
    }

    async function loadListForIndex(index) {
      if (index === 0) {
        if (!listCache['-1']) listCache['-1'] = await api.countries();
        return listCache['-1'] || [];
      }
      const parent = stack[index - 1];
      if (!parent) return [];
      const key = String(parent.node.id);
      if (!listCache[key]) listCache[key] = await api.children(parent.node.id);
      return listCache[key] || [];
    }

    function filterRows(rows, keyword) {
      const kw = (keyword || '').trim().toLowerCase();
      if (!kw) return rows;
      return rows.filter(function (row) {
        const label = displayName(row).toLowerCase();
        const en = (row.nameEn || '').toLowerCase();
        const ch = (row.nameCh || '').toLowerCase();
        const code = (row.code || row.iso2 || '').toLowerCase();
        return label.indexOf(kw) >= 0 || en.indexOf(kw) >= 0 || ch.indexOf(kw) >= 0 || code.indexOf(kw) >= 0;
      });
    }

    function paintRows(rows) {
      const index = viewIndex;
      const filtered = filterRows(rows, searchKeyword);
      bodyEl.innerHTML = '';
      if (!rows.length) {
        bodyEl.innerHTML = '<div class="pgp-empty">暂无下级区划</div>';
        return;
      }
      if (!filtered.length) {
        bodyEl.innerHTML = '<div class="pgp-empty">无匹配结果</div>';
        return;
      }
      const pickedId = stack[index] ? stack[index].node.id : null;
      filtered.forEach(function (row) {
        const div = document.createElement('div');
        div.className = 'pgp-item' + (pickedId === row.id ? ' pgp-picked' : '');
        const isCountry = index === 0;
        const node = isCountry
          ? { id: row.id, iso2: row.iso2, displayName: displayName(row), level: 1, regionType: 'COUNTRY', isLeaf: false }
          : row;
        const label = displayName(row);
        const lvl = node.level || index + 1;
        const hasMore = isCountry || (row.isLeaf === false && lvl < MAX_LEVEL);
        div.innerHTML = '<span class="pgp-label">' + escapeHtml(label) + '</span>' +
          (hasMore ? '<span class="pgp-arrow">›</span>' : '');
        div.addEventListener('click', function () {
          onPick(index, { node: node, label: label, level: node.level || index + 1 }, row, isCountry);
        });
        bodyEl.appendChild(div);
      });
    }

    async function renderList() {
      showLoading();
      updateReportBar();
      try {
        const index = viewIndex;
        currentRows = await loadListForIndex(index);
        paintRows(currentRows);
      } catch (e) {
        showError(e.message || String(e));
      }
    }

    function onPick(index, entry, raw, isCountry) {
      stack = stack.slice(0, index);
      stack.push(entry);
      viewIndex = stack.length;
      searchByLevel[viewIndex] = '';
      searchKeyword = '';
      searchEl.value = '';

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

    searchEl.addEventListener('input', function () {
      searchKeyword = searchEl.value || '';
      searchByLevel[viewIndex] = searchKeyword;
      paintRows(currentRows || []);
    });

    function hideReportModal() {
      modal.classList.remove('pgp-show');
      reportMsgEl.className = 'pgp-modal-msg';
      reportMsgEl.textContent = '';
      reportBackBtn.style.display = 'none';
      reportSubmitBtn.style.display = '';
      reportSubmitBtn.disabled = false;
    }

    function openReportModal() {
      const parent = reportParent();
      if (!parent) return;
      reportNameEl.value = '';
      reportRemarkEl.value = '';
      reportMsgEl.className = 'pgp-modal-msg';
      reportMsgEl.textContent = '';
      reportBackBtn.style.display = 'none';
      reportSubmitBtn.style.display = '';
      reportSubmitBtn.disabled = false;
      modal.classList.add('pgp-show');
      setTimeout(function () { reportNameEl.focus(); }, 50);
    }

    function goBackAfterReport() {
      const parent = reportParent();
      if (!parent) {
        hideReportModal();
        return;
      }
      const parentKey = String(parent.node.id);
      delete listCache[parentKey];
      // 退到上级「请选择」态，便于重新选
      viewIndex = stack.length;
      // 若当前已在选下一级，stack 已含上级；保持上级，刷新其子列表
      searchKeyword = '';
      searchEl.value = '';
      searchByLevel[viewIndex] = '';
      hideReportModal();
      renderTabs();
      renderList();
    }

    reportLink.addEventListener('click', openReportModal);
    reportCancelBtn.addEventListener('click', hideReportModal);
    reportBackBtn.addEventListener('click', goBackAfterReport);
    modal.addEventListener('click', function (e) {
      if (e.target === modal) hideReportModal();
    });

    reportSubmitBtn.addEventListener('click', async function () {
      const parent = reportParent();
      const name = (reportNameEl.value || '').trim();
      if (!parent || !parent.node || !parent.node.id) {
        reportMsgEl.className = 'pgp-modal-msg pgp-show pgp-fail';
        reportMsgEl.textContent = '缺少上级区划，无法上报';
        return;
      }
      if (!name) {
        reportMsgEl.className = 'pgp-modal-msg pgp-show pgp-fail';
        reportMsgEl.textContent = '请输入地址名称';
        return;
      }
      reportSubmitBtn.disabled = true;
      reportMsgEl.className = 'pgp-modal-msg pgp-show';
      reportMsgEl.textContent = '提交中…';
      try {
        const data = await api.reportMissing({
          parentId: parent.node.id,
          missingName: name,
          remark: (reportRemarkEl.value || '').trim() || null
        });
        const status = data && data.resultStatus;
        const pass = !!(status && REPORT_PASS[status]);
        const reason = REPORT_REASON[status] || (data && data.message) || status || '未知结果';
        if (pass) {
          reportMsgEl.className = 'pgp-modal-msg pgp-show pgp-ok';
          reportMsgEl.textContent = '上报已通过：系统已完成地图校验。' +
            reason + '。可返回上级重新选择该地址。';
          reportSubmitBtn.style.display = 'none';
          reportBackBtn.style.display = '';
          postToParent('report', data);
        } else {
          reportMsgEl.className = 'pgp-modal-msg pgp-show pgp-fail';
          reportMsgEl.textContent = '上报未通过：' + reason;
          reportSubmitBtn.disabled = false;
          postToParent('report', data);
        }
      } catch (e) {
        reportMsgEl.className = 'pgp-modal-msg pgp-show pgp-fail';
        reportMsgEl.textContent = '上报失败：' + (e.message || String(e));
        reportSubmitBtn.disabled = false;
      }
    });

    async function start() {
      root.classList.add('pgp-open');
      stack = [];
      viewIndex = 0;
      listCache = { '-1': null };
      currentRows = [];
      searchKeyword = '';
      searchByLevel = {};
      searchEl.value = '';
      hideReportModal();
      setStatus(clientCode + ' · v' + PICKER_VERSION + ' · 正在连接…');
      showLoading();
      renderTabs();
      updateReportBar();
      try {
        const token = await ensureToken(apiBase, clientCode, clientName, issueSecret);
        api = createApi(apiBase, token, lang);
        setStatus(clientCode + ' · v' + PICKER_VERSION);
        await renderList();
      } catch (e) {
        setStatus('');
        showError(e.message || String(e));
      }
    }

    start();
    return { close: close };
  }

  const PlatformGeoPicker = { open: open, version: PICKER_VERSION };
  global.PlatformGeoPicker = PlatformGeoPicker;
})(typeof window !== 'undefined' ? window : this);
