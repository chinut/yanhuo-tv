#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
清空两个平台的所有已发布版本（用户明确要求）。

    「删除之前所有的已发布版本，都不要了 双发布平台数据清空，
      只留这一个版本，作为原始和基础版本」

## 复用什么

直接 import `publish_release.py` 里的 `read_cred` / `http` / `jbody` ——
它们已经处理好了 Windows 凭据管理器和重试逻辑，不重复造。

## 两个平台的接口差异（踩过的坑都写在 publish_release.py 里）

| | GitHub | Gitee |
|---|---|---|
| 列 release | `GET /repos/{r}/releases` | `GET /repos/{r}/releases?access_token=` |
| 删 release | `DELETE /repos/{r}/releases/{id}` | `DELETE /repos/{r}/releases/{id}?access_token=` |
| 删 tag | `DELETE /repos/{r}/git/refs/tags/{t}` | `DELETE /repos/{r}/tags/{t}?access_token=` |

## 用法

    python tools/wipe_releases.py --dry-run     # 只看会删什么
    python tools/wipe_releases.py               # 真删
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from publish_release import read_cred, http   # noqa: E402

REPO = 'chinut/yanhuo-tv'


def list_github(token):
    hh = {'Authorization': 'token ' + token, 'User-Agent': 'yanhuo',
          'Accept': 'application/vnd.github+json'}
    out, page = [], 1
    while page <= 5:
        st, r = http('GET',
                     f'https://api.github.com/repos/{REPO}/releases'
                     f'?per_page=100&page={page}', hh)
        if st != 200 or not isinstance(r, list) or not r:
            break
        out += r
        page += 1
    return hh, out


def list_gitee(token):
    base = f'https://gitee.com/api/v5/repos/{REPO}'
    out, page = [], 1
    while page <= 5:
        st, r = http('GET', f'{base}/releases?per_page=100&page={page}'
                            f'&access_token={token}')
        if not isinstance(r, list) or not r:
            break
        out += r
        page += 1
    return base, out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry-run', action='store_true',
                    help='只列出会删除什么，不真的删')
    a = ap.parse_args()

    print('仓库:', REPO)
    print('模式:', 'DRY-RUN（不会删）' if a.dry_run else '**真删**')
    print()

    # ================= GitHub =================
    print('=' * 58)
    print('GitHub')
    print('=' * 58)
    ght = read_cred('git:https://github.com')
    if not ght:
        print('  读不到令牌，跳过')
    else:
        hh, rels = list_github(ght)
        print('  已发布版本 %d 个' % len(rels))
        for r in rels:
            tag = r.get('tag_name')
            assets = r.get('assets') or []
            print('     %-10s %s  (%d 个附件)' % (tag, r.get('name'), len(assets)))
        if not a.dry_run:
            for r in rels:
                st, _ = http('DELETE',
                             f"https://api.github.com/repos/{REPO}/releases/{r['id']}",
                             hh)
                print('     删 release %-10s → %s' % (r.get('tag_name'), st))
                if r.get('tag_name'):
                    st2, _ = http('DELETE',
                                  f"https://api.github.com/repos/{REPO}"
                                  f"/git/refs/tags/{r['tag_name']}", hh)
                    print('     删 tag     %-10s → %s' % (r.get('tag_name'), st2))

        # 再扫一遍所有 tag（可能有 release 之外的 tag）
        st, tags = http('GET',
                        f'https://api.github.com/repos/{REPO}/git/refs/tags'
                        f'?per_page=100', hh)
        if isinstance(tags, list) and tags:
            print('  仍有 tag %d 个' % len(tags))
            if not a.dry_run:
                for t in tags:
                    ref = t.get('ref', '')
                    st2, _ = http('DELETE',
                                  f'https://api.github.com/repos/{REPO}/{ref}', hh)
                    print('     删 tag %-14s → %s' % (ref.replace('refs/tags/', ''), st2))

    print()

    # ================= Gitee =================
    print('=' * 58)
    print('Gitee')
    print('=' * 58)
    gtt = read_cred('git:https://gitee.com')
    if not gtt:
        print('  读不到令牌，跳过')
    else:
        base, rels = list_gitee(gtt)
        print('  已发布版本 %d 个' % len(rels))
        for r in rels[:5]:
            print('     %-10s %s' % (r.get('tag_name'), r.get('name')))
        if len(rels) > 5:
            print('     … 其余 %d 个' % (len(rels) - 5))
        if not a.dry_run:
            for r in rels:
                st, _ = http('DELETE',
                             f"{base}/releases/{r['id']}?access_token={gtt}")
                tag = r.get('tag_name')
                print('     删 release %-10s → %s' % (tag, st))
                if tag:
                    st2, _ = http('DELETE',
                                  f'{base}/tags/{tag}?access_token={gtt}')
                    print('     删 tag     %-10s → %s' % (tag, st2))

    print()
    print('完成。' if not a.dry_run else '（DRY-RUN 结束，什么都没删）')


if __name__ == '__main__':
    main()
