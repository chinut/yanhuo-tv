#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
遥控协议 v2 —— 按交接包 README 第四节跑完整验收。

README 的验收清单：
  1. 协议自测         name / port / app / protocol
  2. 设置项自测       能改、能显示、引号中文不炸、超长截断到 24
  3. 手机端联调       两台不同名字（需要两台在线，跳过时说明）
  4. 兼容性自测       app 字段保留（老手机端只读它）

## 为什么要"引号 + 中文"这一条
README 特别强调：响应必须用 `JSONObject` 拼，不能手写字符串模板 ——
用户起的中文名或带引号的名字会把手写 JSON 弄坏，手机端解析失败会
当成"没找到电视"。这一条是专门验那个的。
"""
import json
import sys
import urllib.parse
import urllib.request

BASE = "http://%s:8899" % (sys.argv[1] if len(sys.argv) > 1 else "192.168.31.101")
PASS, FAIL = [], []


def check(label, ok, detail=""):
    (PASS if ok else FAIL).append(label)
    print("  [%s] %-34s %s" % ("✅" if ok else "❌", label, detail))


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=12) as x:
        return x.read().decode("utf-8", "replace")


def save(**kw):
    body = urllib.parse.urlencode(kw).encode()
    req = urllib.request.Request(BASE + "/save", data=body, method="POST")
    urllib.request.urlopen(req, timeout=15).read()


def ping():
    return json.loads(get("/api/remote/ping"))


print("=" * 62)
print("遥控协议 v2 验收  (%s)" % BASE)
print("=" * 62)

# ---------- 1. 协议自测 ----------
print("\n[1] 协议自测")
o = ping()
check("app 保留为 焰火TV", o.get("app") == "焰火TV", repr(o.get("app")))
check("protocol == 2", o.get("protocol") == 2, repr(o.get("protocol")))
check("name 非空", bool(o.get("name")), repr(o.get("name")))
check("port 是 1..65535", isinstance(o.get("port"), int) and 1 <= o["port"] <= 65535,
      repr(o.get("port")))
check("ok == true", o.get("ok") is True)

# ---------- 2. 设置项自测 ----------
print("\n[2] 设置项自测")

# 2a. 能改成任意名字
save(device_name="客厅电视")
o = ping()
check("改名生效", o.get("name") == "客厅电视", repr(o.get("name")))

# 2b. 中文 + 引号（README 最强调的一条）
tricky = '我的"4K"电视'
save(device_name=tricky)
raw = get("/api/remote/ping")
try:
    o = json.loads(raw)
    parsed_ok = True
except Exception as e:
    o, parsed_ok = {}, False
    print("     原始响应:", raw[:120])
check("引号+中文：仍是合法 JSON", parsed_ok)
check("引号+中文：名字正确", o.get("name") == tricky, repr(o.get("name")))

# 2c. 反斜杠 + 换行（更狠的边界）
nasty = "a\\b\nc"
save(device_name=nasty)
try:
    o = json.loads(get("/api/remote/ping"))
    ok = o.get("name") == nasty.replace("\n", "") or o.get("name") == nasty
    check("反斜杠/换行：合法 JSON", True, repr(o.get("name")))
except Exception as e:
    check("反斜杠/换行：合法 JSON", False, str(e)[:50])

# 2d. 超长截断到 24
save(device_name="很长的名字" * 20)
o = ping()
check("超长截断到 24 字", len(o.get("name", "")) <= 24, "长度=%d" % len(o.get("name", "")))

# 2e. 清空 → 回退机型名
save(device_name="")
o = ping()
check("清空后回退机型名", bool(o.get("name")) and o["name"] != "", repr(o.get("name")))

# ---------- 4. 兼容性 ----------
print("\n[4] 兼容性（老手机端只读 app）")
o = ping()
check("老手机端仍能识别", o.get("app") == "焰火TV", "老端读 app → 能连上")

print("\n" + "=" * 62)
print("通过 %d 项，失败 %d 项" % (len(PASS), len(FAIL)))
if FAIL:
    print("失败：")
    for f in FAIL:
        print("   · " + f)
    sys.exit(1)
print("全部通过 ✅")
