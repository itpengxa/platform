#!/usr/bin/env python3
"""GEO admin + geo API full regression for local test profile."""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Optional

BASE = "http://127.0.0.1:8088"


@dataclass
class Case:
    name: str
    ok: bool
    detail: str = ""
    severity: str = "P2"  # P0/P1/P2


@dataclass
class Suite:
    cases: list[Case] = field(default_factory=list)

    def add(self, name: str, ok: bool, detail: str = "", severity: str = "P2"):
        self.cases.append(Case(name, ok, detail, severity))
        mark = "PASS" if ok else "FAIL"
        print(f"[{mark}] {name}" + (f" — {detail}" if detail else ""))

    def failed(self) -> list[Case]:
        return [c for c in self.cases if not c.ok]


def http(
    method: str,
    path: str,
    *,
    token: Optional[str] = None,
    body: Any = None,
    headers: Optional[dict] = None,
    timeout: float = 15.0,
) -> tuple[int, Any, str]:
    url = path if path.startswith("http") else BASE + path
    data = None
    hdrs = {"Accept": "application/json"}
    if headers:
        hdrs.update(headers)
    if token:
        hdrs["X-Admin-Token"] = token
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        hdrs["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            try:
                return resp.status, json.loads(raw) if raw else None, raw
            except json.JSONDecodeError:
                return resp.status, raw, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw) if raw else None, raw
        except json.JSONDecodeError:
            return e.code, raw, raw
    except Exception as e:
        return 0, None, str(e)


def api_ok(payload: Any) -> bool:
    return isinstance(payload, dict) and payload.get("code") == 0


def main() -> int:
    s = Suite()

    # --- static pages ---
    for path, label in [
        ("/admin/index.html", "index"),
        ("/admin/login.html", "login"),
        ("/admin/pages/settings.html", "settings"),
        ("/admin/pages/cache.html", "cache"),
        ("/admin/pages/regions.html", "regions"),
        ("/admin/pages/reports.html", "reports"),
        ("/admin/pages/admins.html", "admins"),
        ("/admin/pages/clients.html", "clients"),
        ("/admin/pages/tokens.html", "tokens"),
        ("/admin/pages/dashboard.html", "dashboard"),
        ("/admin/pages/debug.html", "debug"),
        ("/admin/pages/picker.html", "picker"),
        ("/admin/common/http.js", "http.js"),
        ("/admin/common/pager.js", "pager.js"),
    ]:
        code, _, raw = http("GET", path)
        s.add(f"static:{label}", code == 200, f"http={code}", "P0" if label in ("index", "login", "settings", "cache") else "P1")

    # IA markers
    code, html, _ = http("GET", "/admin/index.html")
    html = html if isinstance(html, str) else _
    for marker in ["用户与接入", "系统配置", "缓存管理", "开发工具", "settings.html", "cache.html"]:
        s.add(f"ia:{marker}", marker in html, severity="P1")

    # --- auth ---
    code, bad, _ = http("POST", "/admin/platform/v1/auth/login", body={"username": "admin", "password": "wrong"})
    s.add("auth:bad_password", code in (200, 401) and (not api_ok(bad) or code == 401), f"http={code} body={bad}", "P0")

    code, login, _ = http("POST", "/admin/platform/v1/auth/login", body={"username": "admin", "password": "admin"})
    token = login.get("data", {}).get("token") if api_ok(login) else None
    s.add("auth:login", bool(token), f"http={code}", "P0")
    if not token:
        print("ABORT: no admin token")
        return 1

    code, me, _ = http("GET", "/admin/platform/v1/auth/me", token=token)
    s.add("auth:me", api_ok(me) and me.get("data", {}).get("username") == "admin", f"{me}", "P0")

    code, unauth, _ = http("GET", "/admin/platform/v1/configs")
    s.add("auth:configs_without_token", code == 401 or (isinstance(unauth, dict) and unauth.get("code") != 0), f"http={code}", "P0")

    # --- admin list APIs ---
    for path, label in [
        ("/admin/geo/v1/regions/page?pageNum=1&pageSize=5", "regions.page"),
        ("/admin/geo/v1/reports/page?pageNum=1&pageSize=5", "reports.page"),
        ("/admin/platform/v1/admins/page?pageNum=1&pageSize=5", "admins.page"),
        ("/admin/platform/v1/clients/page?pageNum=1&pageSize=5", "clients.page"),
        ("/admin/platform/v1/tokens/page?pageNum=1&pageSize=5", "tokens.page"),
        ("/admin/platform/v1/stats/overview", "stats.overview"),
    ]:
        code, body, _ = http("GET", path, token=token)
        s.add(f"admin_api:{label}", code == 200 and api_ok(body), f"http={code} code={body.get('code') if isinstance(body, dict) else body}", "P0")

    # regions source filter
    code, body, _ = http("GET", "/admin/geo/v1/regions/page?pageNum=1&pageSize=5&source=user_report", token=token)
    s.add("admin_api:regions.source_filter", code == 200 and api_ok(body), f"http={code}", "P1")

    # --- runtime config ---
    code, cfg, _ = http("GET", "/admin/platform/v1/configs", token=token)
    groups = (cfg or {}).get("data", {}).get("groups") if api_ok(cfg) else None
    s.add("config:list", bool(groups) and all(g in groups for g in ("report", "rate-limit", "auth", "cache", "admin", "access-log")), f"groups={list(groups) if groups else None}", "P0")

    # secret masking
    auth_items = (groups or {}).get("auth") or []
    issue = next((i for i in auth_items if i.get("key", "").endswith("issue-secret")), None)
    s.add(
        "config:secret_masked",
        issue is not None and issue.get("masked") is True and (issue.get("value") in ("", "******") or issue.get("value") == "******"),
        f"issue={issue}",
        "P0",
    )
    if issue and issue.get("hasValue") and issue.get("value") not in ("", "******"):
        s.add("config:secret_leak", False, f"plaintext leaked: {issue.get('value')}", "P0")

    # hot change max-parent-distance
    code, save, _ = http(
        "PUT",
        "/admin/platform/v1/configs",
        token=token,
        body={"items": [{"key": "platform.geo.report.max-parent-distance-km", "value": "33.3"}]},
    )
    after = None
    if api_ok(save):
        after = next(
            (i for i in save["data"]["groups"]["report"] if "max-parent" in i["key"]),
            None,
        )
    s.add(
        "config:hot_save_distance",
        api_ok(save) and after and after.get("value") == "33.3" and after.get("source") == "DB",
        f"after={after}",
        "P0",
    )

    # save secret then ensure mask
    code, sec, _ = http(
        "PUT",
        "/admin/platform/v1/configs",
        token=token,
        body={"items": [{"key": "platform.geo.auth.issue-secret", "value": "qa-temp-secret-001"}]},
    )
    sec_item = None
    if api_ok(sec):
        sec_item = next((i for i in sec["data"]["groups"]["auth"] if "issue-secret" in i["key"]), None)
    s.add(
        "config:save_secret_masked",
        api_ok(sec) and sec_item and sec_item.get("value") == "******" and sec_item.get("hasValue"),
        f"sec_item={sec_item}",
        "P0",
    )

    # empty secret should not clear
    code, keep, _ = http(
        "PUT",
        "/admin/platform/v1/configs",
        token=token,
        body={"items": [{"key": "platform.geo.auth.issue-secret", "value": ""}]},
    )
    keep_item = None
    if api_ok(keep):
        keep_item = next((i for i in keep["data"]["groups"]["auth"] if "issue-secret" in i["key"]), None)
    s.add(
        "config:empty_secret_keeps",
        api_ok(keep) and keep_item and keep_item.get("hasValue") and keep_item.get("value") == "******",
        f"keep={keep_item}",
        "P1",
    )

    # reset
    code, rst, _ = http(
        "POST",
        "/admin/platform/v1/configs/reset",
        token=token,
        body={"keys": ["platform.geo.report.max-parent-distance-km", "platform.geo.auth.issue-secret"]},
    )
    dist = None
    if api_ok(rst):
        dist = next((i for i in rst["data"]["groups"]["report"] if "max-parent" in i["key"]), None)
    s.add(
        "config:reset_to_default",
        api_ok(rst) and dist and dist.get("source") == "DEFAULT",
        f"dist={dist}",
        "P0",
    )

    code, reloaded, _ = http("POST", "/admin/platform/v1/configs/reload", token=token, body={})
    s.add("config:reload", api_ok(reloaded), f"http={code}", "P1")

    # invalid range
    code, bad_cfg, _ = http(
        "PUT",
        "/admin/platform/v1/configs",
        token=token,
        body={"items": [{"key": "platform.geo.report.max-parent-distance-km", "value": "99999"}]},
    )
    s.add("config:reject_out_of_range", not api_ok(bad_cfg), f"body={bad_cfg}", "P1")

    # readonly reject
    code, ro, _ = http(
        "PUT",
        "/admin/platform/v1/configs",
        token=token,
        body={"items": [{"key": "platform.geo.admin.enabled", "value": "false"}]},
    )
    s.add("config:reject_readonly", not api_ok(ro), f"body={ro}", "P1")

    # --- cache admin ---
    code, stats, _ = http("GET", "/admin/geo/v1/cache/stats", token=token)
    s.add(
        "cache:stats",
        api_ok(stats) and "redisEnabled" in (stats.get("data") or {}),
        f"data={stats.get('data') if isinstance(stats, dict) else stats}",
        "P0",
    )

    code, dry, _ = http(
        "POST",
        "/admin/geo/v1/cache/clear",
        token=token,
        body={"scope": "ALL", "dryRun": True},
    )
    s.add(
        "cache:dry_run_all",
        api_ok(dry) and dry.get("data", {}).get("dryRun") is True and dry.get("data", {}).get("localL1Cleared") is False,
        f"data={dry.get('data') if isinstance(dry, dict) else dry}",
        "P0",
    )

    code, ctry, _ = http(
        "POST",
        "/admin/geo/v1/cache/clear",
        token=token,
        body={"scope": "COUNTRY", "countryCode": "VN", "dryRun": False},
    )
    s.add(
        "cache:clear_country",
        api_ok(ctry) and ctry.get("data", {}).get("localL1Cleared") is True,
        f"data={ctry.get('data') if isinstance(ctry, dict) else ctry}",
        "P0",
    )

    # warm a key then evict
    code, countries, _ = http("GET", "/api/geo/v1/countries")
    s.add("geo:countries", code == 200 and api_ok(countries), f"http={code}", "P0")

    code, ev, _ = http(
        "POST",
        "/admin/geo/v1/cache/evict",
        token=token,
        body={"keys": ["platform:geo:countries:", "platform:geo:rl:should-ignore"], "dryRun": False},
    )
    s.add(
        "cache:evict_filters_rl",
        api_ok(ev) and ev.get("data", {}).get("deletedRedisKeys", -1) >= 0,
        f"data={ev.get('data') if isinstance(ev, dict) else ev}",
        "P1",
    )

    code, bad_ev, _ = http(
        "POST",
        "/admin/geo/v1/cache/evict",
        token=token,
        body={"keys": ["platform:geo:rl:1.1.1.1:default"], "dryRun": False},
    )
    s.add("cache:evict_only_rl_rejected", not api_ok(bad_ev), f"body={bad_ev}", "P1")

    code, bad_scope, _ = http(
        "POST",
        "/admin/geo/v1/cache/clear",
        token=token,
        body={"scope": "COUNTRY", "dryRun": True},
    )
    s.add("cache:country_requires_code", not api_ok(bad_scope), f"body={bad_scope}", "P1")

    # --- public geo APIs (错开限流窗口) ---
    time.sleep(1.1)
    code, children, _ = http("GET", "/api/geo/v1/regions/children?parentId=1")
    s.add(
        "geo:children",
        code in (200, 404) and isinstance(children, dict) and "code" in children and code != 429,
        f"http={code} code={children.get('code') if isinstance(children, dict) else None}",
        "P1",
    )

    time.sleep(1.1)
    code, tree, _ = http("GET", "/api/geo/v1/regions/tree?countryCode=VN&depth=2")
    s.add("geo:tree_vn", code == 200 and api_ok(tree), f"http={code} msg={tree.get('message') if isinstance(tree, dict) else tree}", "P0")

    time.sleep(1.1)
    code, search, _ = http("GET", "/api/geo/v1/regions/search?keyword=Ha&countryCode=VN&limit=5")
    s.add("geo:search", code == 200 and api_ok(search), f"http={code}", "P1")

    # rate limit smoke：连续两次同桶应出现 429
    time.sleep(1.1)
    code1, _, _ = http("GET", "/api/geo/v1/countries")
    code2, body2, _ = http("GET", "/api/geo/v1/countries")
    s.add(
        "geo:rate_limit_behaves",
        code1 == 200 and code2 == 429,
        f"c1={code1} c2={code2}",
        "P1",
    )

    # --- logout ---
    code, lo, _ = http("POST", "/admin/platform/v1/auth/logout", token=token, body={})
    s.add("auth:logout", api_ok(lo), f"http={code}", "P1")
    code, me2, _ = http("GET", "/admin/platform/v1/auth/me", token=token)
    s.add("auth:token_invalid_after_logout", code == 401 or (isinstance(me2, dict) and me2.get("code") != 0), f"http={code}", "P1")

    failed = s.failed()
    print("\n======== SUMMARY ========")
    print(f"total={len(s.cases)} pass={len(s.cases)-len(failed)} fail={len(failed)}")
    for c in failed:
        print(f"  [{c.severity}] {c.name}: {c.detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
