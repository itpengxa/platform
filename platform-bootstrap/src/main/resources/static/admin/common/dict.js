window.AdminDict = {
  LEVEL_MAP: { 1: '国家', 2: '省/州', 3: '市', 4: '区/县', 5: '街/镇' },
  SOURCE_MAP: {
    user_report: '用户上报',
    admin: '后台新增',
    OSM: 'OSM',
    OSM_STREET: 'OSM街道',
    GEONAMES: 'GeoNames',
    GSO: 'GSO',
    CSC: 'CSC'
  },
  sourceTag: function (s) {
    const label = this.SOURCE_MAP[s] || s || '-';
    const cls = s === 'user_report' ? 'tag-orange' : (s === 'admin' ? 'tag-green' : 'tag-gray');
    return '<span class="tag ' + cls + '" title="' + (s || '') + '">' + label + '</span>';
  },
  statusTag: function (s) {
    return s === 1
      ? '<span class="tag tag-green">启用</span>'
      : '<span class="tag tag-orange">停用</span>';
  },
  allowIssueTag: function (v) {
    return v === 1
      ? '<span class="tag tag-green">允许</span>'
      : '<span class="tag tag-orange">禁止</span>';
  },
  REPORT_STATUS_MAP: {
    AUTO_CREATED: '自动创建',
    MANUAL_CREATED: '人工通过创建',
    GEOCODE_FAIL: '地理编码失败',
    DISTANCE_REJECT: '距离超限待审',
    ALREADY_EXISTS: '已存在',
    PARENT_NO_COORD: '父节点无坐标',
    REJECTED: '已驳回'
  },
  reportStatusTag: function (s) {
    const clsMap = {
      AUTO_CREATED: 'tag-green', MANUAL_CREATED: 'tag-green',
      GEOCODE_FAIL: 'tag-gray', DISTANCE_REJECT: 'tag-orange',
      ALREADY_EXISTS: 'tag-orange', PARENT_NO_COORD: 'tag-orange', REJECTED: 'tag-gray'
    };
    const cls = clsMap[s] || 'tag-gray';
    const label = this.REPORT_STATUS_MAP[s] || s || '-';
    return '<span class="tag ' + cls + '" title="' + (s || '') + '">' + label + '</span>';
  }
};
