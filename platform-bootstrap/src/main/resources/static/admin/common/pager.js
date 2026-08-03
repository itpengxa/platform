/**
 * 管理端列表分页控件。
 * 用法：
 *   const pager = AdminPager.mount('pager', { pageSize: 20, onChange: () => load() });
 *   // load 内：pager.apply(data)  // data = { total, pageNum, pageSize, list }
 *   // 请求参数：pager.pageNum / pager.pageSize
 *   // 搜索时：pager.reset() 再 load()
 */
(function () {
  const STYLE = `
    .admin-pager {
      display: flex; flex-wrap: wrap; gap: 10px; align-items: center;
      margin-top: 12px; padding: 10px 12px; background: #fff; border-radius: 6px;
      font-size: 13px; color: #555;
    }
    .admin-pager .pg-info { color: #666; }
    .admin-pager select, .admin-pager input[type=number] {
      padding: 4px 8px; border: 1px solid #d9d9d9; border-radius: 4px;
    }
    .admin-pager input[type=number] { width: 64px; }
    .admin-pager .pg-btn {
      padding: 4px 12px; border: 1px solid #d9d9d9; border-radius: 4px;
      background: #fff; color: #333; cursor: pointer;
    }
    .admin-pager .pg-btn:disabled { color: #bbb; cursor: not-allowed; background: #fafafa; }
    .admin-pager .pg-btn:not(:disabled):hover { border-color: #1677ff; color: #1677ff; }
  `;

  function ensureStyle() {
    if (document.getElementById('admin-pager-style')) return;
    const s = document.createElement('style');
    s.id = 'admin-pager-style';
    s.textContent = STYLE;
    document.head.appendChild(s);
  }

  function mount(elOrId, options) {
    ensureStyle();
    options = options || {};
    const el = typeof elOrId === 'string' ? document.getElementById(elOrId) : elOrId;
    if (!el) throw new Error('AdminPager: element not found');

    const state = {
      pageNum: 1,
      pageSize: options.pageSize || 20,
      total: 0,
      pages: 0
    };
    const onChange = typeof options.onChange === 'function' ? options.onChange : function () {};
    const sizeOptions = options.sizeOptions || [20, 50, 100];

    el.classList.add('admin-pager');
    el.innerHTML =
      '<span class="pg-info" data-role="info">共 0 条</span>' +
      '<span>每页</span>' +
      '<select data-role="size"></select>' +
      '<span data-role="pageText">第 0 / 0 页</span>' +
      '<button type="button" class="pg-btn" data-role="prev">上一页</button>' +
      '<button type="button" class="pg-btn" data-role="next">下一页</button>' +
      '<span>跳至</span>' +
      '<input type="number" min="1" data-role="jump" value="1"/>' +
      '<button type="button" class="pg-btn" data-role="go">确定</button>';

    const sizeSel = el.querySelector('[data-role=size]');
    sizeOptions.forEach(function (n) {
      const opt = document.createElement('option');
      opt.value = String(n);
      opt.textContent = String(n);
      if (n === state.pageSize) opt.selected = true;
      sizeSel.appendChild(opt);
    });

    function render() {
      const pages = state.pageSize > 0 ? Math.max(1, Math.ceil(state.total / state.pageSize)) : 1;
      state.pages = state.total === 0 ? 0 : pages;
      if (state.total > 0 && state.pageNum > state.pages) {
        state.pageNum = state.pages;
      }
      el.querySelector('[data-role=info]').textContent = '共 ' + state.total + ' 条';
      el.querySelector('[data-role=pageText]').textContent =
        state.total === 0
          ? '第 0 / 0 页'
          : '第 ' + state.pageNum + ' / ' + state.pages + ' 页';
      el.querySelector('[data-role=jump]').value = String(state.pageNum || 1);
      el.querySelector('[data-role=prev]').disabled = state.pageNum <= 1 || state.total === 0;
      el.querySelector('[data-role=next]').disabled =
        state.total === 0 || state.pageNum >= state.pages;
      sizeSel.value = String(state.pageSize);
    }

    function go(pn) {
      const next = Math.max(1, pn | 0);
      if (next === state.pageNum && state.total > 0) return;
      state.pageNum = next;
      render();
      onChange(state.pageNum, state.pageSize);
    }

    el.querySelector('[data-role=prev]').addEventListener('click', function () {
      if (state.pageNum > 1) go(state.pageNum - 1);
    });
    el.querySelector('[data-role=next]').addEventListener('click', function () {
      if (state.pages > 0 && state.pageNum < state.pages) go(state.pageNum + 1);
    });
    el.querySelector('[data-role=go]').addEventListener('click', function () {
      const v = parseInt(el.querySelector('[data-role=jump]').value, 10);
      if (!v || v < 1) return;
      const max = state.pages > 0 ? state.pages : 1;
      go(Math.min(v, max));
    });
    el.querySelector('[data-role=jump]').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') el.querySelector('[data-role=go]').click();
    });
    sizeSel.addEventListener('change', function () {
      state.pageSize = parseInt(sizeSel.value, 10) || 20;
      state.pageNum = 1;
      render();
      onChange(state.pageNum, state.pageSize);
    });

    render();

    return {
      get pageNum() { return state.pageNum; },
      get pageSize() { return state.pageSize; },
      get total() { return state.total; },
      reset: function () {
        state.pageNum = 1;
        render();
      },
      apply: function (page) {
        if (!page) {
          state.total = 0;
          render();
          return;
        }
        state.total = Number(page.total) || 0;
        if (page.pageNum) state.pageNum = page.pageNum;
        if (page.pageSize) state.pageSize = page.pageSize;
        render();
      }
    };
  }

  window.AdminPager = { mount: mount };
})();
