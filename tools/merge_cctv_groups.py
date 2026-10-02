"""合并直播频道表里的「央视」与「央视源2」两组。

需求：同一家电视台（CCTV-1…CCTV-17）在两组里各有一条来源，
合并成一组、每个台只留一条，另一条作为备用源；
央视频独有的台（CCTV-14 少儿、CCTV-4K/8K、CGTN、风云/第一/怀旧剧场）单独保留。

做法：
  1. 读 cctv.m3u，按分组切开；
  2. 「央视」与「央视源2」按频道名归一化后合并（央视组在前 = 主源）；
  3. 清掉 ivi.bupt.edu.cn 那批已 502 下线的直连流；
  4. 两大组合并后写回一个「央视」组，其余分组顺序不变。

用法：
  python tools/merge_cctv_groups.py            # 生成 cctv_merged.m3u（不覆盖原文件）
  python tools/merge_cctv_groups.py --apply    # 直接覆盖 cctv.m3u
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "live", "cctv.m3u")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "live", "cctv_merged.m3u")

PRIMARY_GROUP = "央视"
SECOND_GROUP = "央视源2"

# 已实测 502 下线的直连流，合并时清掉
DEAD_HOSTS = ("ivi.bupt.edu.cn",)

# 来源优先级（宿主关键词按顺序匹配，越靠前越优先作为主源）
# 默认：央视网网页画质稳、音量正常 → 央视频兜底
DEFAULT_SOURCE_ORDER = ("tv.cctv.com", "yangshipin")

# 个别台的来源顺序覆写。
# CCTV-1：实测央视网页面自己的播放器不工作（提示「由于播出安排变更，暂不支持播放该时段内容」），
# 央视频能正常出画面，所以把它排到第一位，避免用户点进去先看到黑屏再等自动切源。
SOURCE_ORDER_OVERRIDE = {
    "cctv1综合": ("yangshipin", "tv.cctv.com"),
}


def rank_url(url: str, order: tuple[str, ...]) -> int:
    """给来源地址排优先级：越小越优先，未匹配到的排最后。"""
    for i, host in enumerate(order):
        if host in url:
            return i
    return len(order)


def norm(name: str) -> str:
    """频道名归一化：CCTV-13 新闻 / CCTV13 新闻 → cctv13新闻。"""
    s = name.lower()
    s = re.sub(r"[\s\-_·、,，.。:：()（）\[\]【】]", "", s)
    for junk in ("高清", "标清", "超清", "频道"):
        s = s.replace(junk, "")
    return s.strip()


def pretty(name: str) -> str:
    """显示名统一成 CCTV-XX 形式（央视源2 里写的是 CCTV1 / CCTV13）。

    注意 CCTV4K / CCTV8K 这类不能拆成「CCTV-4 K」，4K/8K 要整体保留。
    """
    s = name.strip()
    m = re.match(r"^CCTV\s*(\d+)\s*(4K|8K)?\s*(.*)$", s, re.IGNORECASE)
    if m:
        num, quality, rest = m.group(1), m.group(2) or "", m.group(3).strip()
        tail = " ".join(x for x in (quality, rest) if x)
        return f"CCTV-{num}" + (f" {tail}" if tail else "")
    return s


def parse(path: str):
    """解析 m3u，返回 [(group, name, url), ...] 保持顺序。"""
    rows = []
    group = None
    name = None
    with open(path, "r", encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line:
                continue
            m = re.match(r'^#EXTINF:.*group-title="([^"]*)",(.*)$', line)
            if m:
                group, name = m.group(1), m.group(2).strip()
                continue
            if line.startswith("#"):
                continue
            if line.startswith("http"):
                rows.append((group or "默认", name or "未命名", line))
                name = None
    return rows


def main() -> None:
    apply = "--apply" in sys.argv
    rows = parse(ASSET)
    print(f"读入 {len(rows)} 条")

    primary = [r for r in rows if r[0] == PRIMARY_GROUP]
    second = [r for r in rows if r[0] == SECOND_GROUP]
    others = [r for r in rows if r[0] not in (PRIMARY_GROUP, SECOND_GROUP)]

    # 合并：以「央视」组的名字为准（更规范），央视频独有的台补在后面
    merged: dict[str, dict] = {}
    order: list[str] = []
    for _, name, url in primary + second:
        if any(h in url for h in DEAD_HOSTS):
            continue
        key = norm(name)
        if key not in merged:
            merged[key] = {"name": pretty(name), "urls": []}
            order.append(key)
        if url not in merged[key]["urls"]:
            merged[key]["urls"].append(url)

    cctv_lines = []
    for key in order:
        item = merged[key]
        # 排定来源优先级：默认 cctv.com 优先，个别台按 SOURCE_ORDER_OVERRIDE 覆写
        pref = SOURCE_ORDER_OVERRIDE.get(key, DEFAULT_SOURCE_ORDER)
        urls = sorted(item["urls"], key=lambda u: (rank_url(u, pref), item["urls"].index(u)))
        # 主源 = 第一条；其余作为备用源（同名频道由 LiveCatalog 自动合并成 alternates，
        # 播放页按「← →」在这些源之间切换，网页播不出画面时也会自动往后切）
        for url in urls:
            cctv_lines.append(f'#EXTINF:-1 group-title="{PRIMARY_GROUP}",{item["name"]}')
            cctv_lines.append(url)
        mark = "  ← 已调整来源顺序" if pref != DEFAULT_SOURCE_ORDER else ""
        print(f'  {item["name"]:<20} {len(urls)} 个源  {urls[0].split("//")[-1].split("/")[0]}{mark}')

    print(f"\n合并后：{len(order)} 个台、{len(cctv_lines)//2} 条来源（原 {len(primary)+len(second)} 条）")

    # 写回：央视组放最前，其余分组顺序不变
    out = ["#EXTM3U"]
    out.extend(cctv_lines)
    cur_group = None
    for group, name, url in others:
        if group != cur_group:
            cur_group = group
        out.append(f'#EXTINF:-1 group-title="{group}",{name}')
        out.append(url)

    target = ASSET if apply else OUT
    with open(target, "w", encoding="utf-8", newline="\r\n") as f:
        f.write("\n".join(out) + "\n")
    print(f"\n已写入 {target}（{'覆盖原文件' if apply else '未覆盖，确认后用 --apply'}）")


if __name__ == "__main__":
    main()
