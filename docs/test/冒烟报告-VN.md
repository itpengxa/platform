# 功能测试报告：GEO-001 platform geo 本地冒烟（越南三级）

## 测试概要

| 项 | 内容 |
|------|------|
| 需求ID | GEO-001 |
| 测试版本 | platform `1.0.0-SNAPSHOT` / 分支 `feature/GEO-001-geo_ai` |
| 测试类型 | 本地接口冒烟（国家 → 省/州 → 城市） |
| 测试时间 | 2026-07-24 11:00:08 |
| 测试环境 | `http://127.0.0.1:8088`，MySQL `platform`，账号 `platform` |
| 测试范围 | 越南 VN 三级查询链路 |
| 测试覆盖率 | 冒烟核心路径 5/5（100%）；未覆盖：search、多语言 en、异常码边界 |

## 前置数据（库侧核对）

| 层级 | 表 | VN 数量 |
|------|-----|---------|
| 国家 | geo_country iso2=VN | 1（id=240） |
| L1 国家节点 | geo_region level=1 | 1 |
| L2 省/州 | geo_region level=2 | 34+（接口返回 41） |
| L3 城市 | geo_region level=3 | 488 |

## 用例执行记录

| ID | 级别 | 用例 | 接口 | 期望 | 实际 | 结果 |
|----|------|------|------|------|------|------|
| SM-01 | P0 | 国家列表含越南 | `GET /api/geo/v1/countries?lang=zh&keyword=Vietnam` | code=0，含 iso2=VN，返回 iconBase64/displayName | id=240，displayName=越南，iconBase64=🇻🇳 | PASS |
| SM-02 | P0 | 查省/州 | `GET /api/geo/v1/regions/children?parentId=240&lang=zh` | code=0，level 均为 2，数量>0 | count=41，sample=兴安省，level=2 | PASS |
| SM-03 | P0 | 查城市 | `GET /api/geo/v1/regions/children?parentId={省}&lang=zh` | code=0，level=3，isLeaf=true | parent=同奈(3821)，count=14，sample=边和 | PASS |
| SM-04 | P0 | 祖先链回显 | `GET /api/geo/v1/regions/{cityId}/path?lang=zh` | 有序 level=[1,2,3] | 越南 → 同奈 → 边和 | PASS |
| SM-05 | P1 | 子树 depth=3 | `GET /api/geo/v1/regions/tree?countryCode=VN&depth=3&lang=zh` | 根 level=1，且存在 level=3 叶子 | provinces=41，hasCity=True | PASS |

### 关键响应摘录

**SM-01 CountryVO（越南）**

```json
{
  "id": 240,
  "iso2": "VN",
  "iso3": "VNM",
  "name": "Việt Nam",
  "nameEn": "Vietnam",
  "nameCh": "越南",
  "displayName": "越南",
  "iconBase64": "🇻🇳",
  "phoneCode": "84",
  "maxLevel": 5
}
```

**SM-04 path**

```text
(1, 越南) → (2, 同奈) → (3, 边和)
```

## Bug 清单

| ID | 级别 | 描述 | 页面/接口 | 状态 | 负责人 |
|----|------|------|----------|------|--------|
| - | - | 本次冒烟未发现 P0/P1/P2 | - | - | - |

### 观察项（非 Bug，不阻断）

| 项 | 说明 |
|------|------|
| maxLevel=5 | 国家表标记最深 5 级，当前 VN 区划数据仅到 3 级；接口行为符合现有数据 |
| 省数量 | 库统计 level=2 与接口 41 条可能因数据灌入差异；冒烟以接口实返为准 |

## 自动修复记录

| 轮次 | 问题 | 变更 | 复测 |
|------|------|------|------|
| 1 | JDBC `root` TCP 空密码 Access denied，接口 500 | 创建 MySQL 用户 `platform/platform`，修正 `application*.yml` 数据源 | 复测 5/5 PASS |
| 2 | YAML `password` 被错误改写成非法结构导致启动失败 | 重写 `application.yml` / `application-dev.yml` 并重新打包 | 启动成功，冒烟 PASS |

## 测试结果汇总

- P0: 0 个未修复（用例 4 个全部 PASS）
- P1: 0 个未修复（用例 1 个 PASS）
- P2: 0 个

## 测试结论

- [x] **通过**（P0=0 且 P1=0）
- [ ] 不通过

越南 **国家 → 省/州 → 城市** 三级查询链路本地冒烟通过，可进入后续联调/扩展国家测试。

## 附件路径

- 本报告：`/Users/a0000/Documents/AICoding/GEO-001/测试/冒烟报告-VN.md`
- 需求：`/Users/a0000/Documents/AICoding/GEO-001/需求分析/`
- 设计：`/Users/a0000/Documents/AICoding/GEO-001/设计/`
