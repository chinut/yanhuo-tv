"""
发布焰火TV 到 GitHub + Gitee。

由 tools/release.ps1 调用（那里负责构建和版本校验，这里负责上传）。

## 两个必须记住的坑

1. **JSON 里的中文不能用 PowerShell 传**
   实测 '焰火TV' 经过 PowerShell → 文件 → curl 会变成替换字符
   （GitHub 上真的存成过 '???TV v1.0.22'）。所以这里全程 Python +
   显式 UTF-8 编码。

2. **20MB 二进制不能用 urllib / Invoke-RestMethod 上传**
   会被对端重置连接。改用系统自带的 curl.exe。

3. **正文必须带 versionCode**
   应用检查更新时先读正文里的 versionCode，读不到才退回从 tag 抠数字，
   而 v1.0.22 抠出来是 1022，和真实的 23 差得远。所以正文顶部固定写：
       versionCode: 23
       versionName: 1.0.22
"""
import argparse
import ctypes
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from ctypes import wintypes


# ---------------- Windows 凭据管理器 ----------------
class CREDENTIAL(ctypes.Structure):
    _fields_ = [
        ('Flags', wintypes.DWORD), ('Type', wintypes.DWORD),
        ('TargetName', wintypes.LPWSTR), ('Comment', wintypes.LPWSTR),
        ('LastWritten', ctypes.c_longlong), ('CredentialBlobSize', wintypes.DWORD),
        ('CredentialBlob', ctypes.POINTER(ctypes.c_byte)),
        ('Persist', wintypes.DWORD), ('AttributeCount', wintypes.DWORD),
        ('Attributes', ctypes.c_void_p),
        ('TargetAlias', wintypes.LPWSTR), ('UserName', wintypes.LPWSTR),
    ]


def read_cred(target):
    a = ctypes.windll.advapi32
    p = ctypes.POINTER(CREDENTIAL)()
    if not a.CredReadW(target, 1, 0, ctypes.byref(p)):
        return None
    c = p.contents
    b = ctypes.string_at(c.CredentialBlob, c.CredentialBlobSize)
    a.CredFree(p)
    return b.decode('utf-16-le')


def http(method, url, headers=None, data=None, timeout=180, retries=4,
         raw=False):
    """
    带重试的 HTTP 调用。

    GitHub 的 API 偶尔会瞬断（实测遇到 UNEXPECTED_EOF_WHILE_READING、
    schannel 握手失败等）。这类错误重试一次基本就好，不该让整个发布失败
    —— 否则发布脚本会变成"要盯着重跑"的东西，那就失去意义了。
    """
    import time as _time
    last = None
    for attempt in range(retries):
        # 传 dict/list 时自动编码成 UTF-8 JSON。
        #
        # ⚠️ 必须自己 encode：urlib 对 str/bytes 之外的对象会退回
        # latin-1，而我们的 name/body 里有中文（「焰火TV」），
        # 实测直接抛 UnicodeEncodeError: 'latin-1' codec can't encode。
        hh = dict(headers or {})
        payload = data
        if isinstance(data, (dict, list)):
            payload = json.dumps(data, ensure_ascii=False).encode('utf-8')
            hh.setdefault('Content-Type', 'application/json; charset=utf-8')
        r = urllib.request.Request(url, data=payload, method=method)
        for k, v in hh.items():
            r.add_header(k, v)
        try:
            with urllib.request.urlopen(r, timeout=timeout) as resp:
                body_bytes = resp.read()
                if raw:
                    # 附件上传返回的不是 JSON（或不需要解析），原样给回去
                    return resp.status, body_bytes
                return resp.status, (
                    json.loads(body_bytes.decode('utf-8')) if body_bytes else None)
        except urllib.error.HTTPError as e:
            body = e.read().decode('utf-8', 'replace')
            # 4xx 是请求本身的问题，重试没意义
            if 400 <= e.code < 500 and e.code != 429:
                return e.code, body
            last = f'HTTP {e.code}: {body[:150]}'
        except Exception as e:                      # 网络/SSL 类，可重试
            last = f'{type(e).__name__}: {e}'
        if attempt < retries - 1:
            wait = 2 * (attempt + 1)
            print(f'    （第 {attempt + 1} 次失败：{last}；{wait}s 后重试）')
            _time.sleep(wait)
    print(f'    !! 重试 {retries} 次仍失败：{last}')
    return 0, last


def jbody(o):
    return json.dumps(o, ensure_ascii=False).encode('utf-8')


def build_body(code, version_name, apk, sha, size_mb, repo, tag, notes):
    gh_dl = f'https://github.com/{repo}/releases/download/{tag}/{apk}'
    extra = f'\n### 本次更新\n\n{notes}\n' if notes.strip() else ''
    return f"""versionCode: {code}
versionName: {version_name}

## 焰火TV v{version_name}

为电视大屏与遥控器设计的直播 + 影视播放器。

**minSdk 23**（Android 5.1 及以上）· {size_mb} MB

SHA256: `{sha}`

### 下载

- GitHub：[{apk}]({gh_dl})

> 两个平台的 Release 附件都会自动上传（Gitee 用 multipart 接口，实测可用）。
{extra}
### 说明
本应用只做播放器与界面聚合，不存储、不传播任何影视资源。
直播源与在线影视地址均来自第三方公开接口，仅限个人学习研究使用。
"""


def publish_github(repo, tag, name, body, apk_path, apk_name):
    print('=' * 58)
    print('GitHub')
    print('=' * 58)
    token = read_cred('git:https://github.com')
    if not token:
        print('  读不到令牌，跳过')
        return
    hh = {'Authorization': 'token ' + token, 'User-Agent': 'yanhuo',
          'Accept': 'application/vnd.github+json'}

    st, rel = http('GET', f'https://api.github.com/repos/{repo}/releases/tags/{tag}', hh)
    if st == 200 and rel:
        print('  删除已存在的 Release')
        http('DELETE', f"https://api.github.com/repos/{repo}/releases/{rel['id']}", hh)
    http('DELETE', f'https://api.github.com/repos/{repo}/git/refs/tags/{tag}', hh)

    commit = subprocess.run(['git', 'rev-parse', 'HEAD'],
                            capture_output=True).stdout.decode().strip()
    st, _ = http('POST', f'https://api.github.com/repos/{repo}/git/refs', hh,
                 jbody({'ref': f'refs/tags/{tag}', 'sha': commit}))
    print('  创建 tag:', st)

    st, r = http('POST', f'https://api.github.com/repos/{repo}/releases', hh,
                 jbody({'tag_name': tag, 'target_commitish': 'main', 'name': name,
                        'body': body, 'draft': False, 'prerelease': False}))
    if st not in (200, 201):
        print('  Release 创建失败:', st, str(r)[:200])
        return
    print('  名称:', repr(r['name']))
    print('  页面:', r['html_url'])

    out = subprocess.run(
        ['curl.exe', '-s', '-X', 'POST',
         f"https://uploads.github.com/repos/{repo}/releases/{r['id']}/assets?name={apk_name}",
         '-H', f'Authorization: token {token}', '-H', 'User-Agent: yanhuo',
         '-H', 'Content-Type: application/vnd.android.package-archive',
         '--data-binary', f'@{apk_path}'],
        capture_output=True, timeout=1200)
    try:
        a = json.loads(out.stdout.decode('utf-8', 'replace'))
        print('  已上传:', a.get('name'), round(a.get('size', 0) / 1048576, 1), 'MB')
        print('  直链:', a.get('browser_download_url'))
    except Exception:
        print('  上传结果异常:', out.stdout[:200])


def publish_gitee(repo, tag, name, body, apk_path):
    """
    发布到 Gitee。

    ## 三个踩过的坑（都写在这里，免得以后再撞）

    1. **附件能传！** 之前这里写着"attach_files 对令牌返回 405"，
       实测是**错的** —— 用 multipart 正确调用返回 **201**。
       所以现在真的会把 APK 传上去，不用手动。

    2. **不要 DELETE 再 POST**。Gitee 在 tag 已存在时 POST 会报
       400「验证错误，该标签已经存在发行版」；而且删掉重建会丢掉
       已有的附件。改成：存在就 **PATCH 更新**。

    3. **PATCH 必须同时带 `tag_name` 和 `name`** —— 只带一个会 400
       （报 "tag_name is missing" / "name is missing"）。

    4. **上传前先查同名附件**，避免重复（实测出现过两个一样的 APK）。
    """
    print()
    print('=' * 58)
    print('Gitee')
    print('=' * 58)
    token = read_cred('git:https://gitee.com')
    if not token:
        print('  读不到令牌，跳过')
        return

    base = f'https://gitee.com/api/v5/repos/{repo}'

    # ---------- 找已存在的 release ----------
    rel = None
    for page in (1, 2, 3):
        st, rls = http(
            'GET', f'{base}/releases?per_page=100&page={page}&access_token={token}')
        if not isinstance(rls, list) or not rls:
            break
        for r in rls:
            if r.get('tag_name') == tag:
                rel = r
        if rel:
            break

    if rel:
        # ⚠️ 必须写 data= —— http() 的第 3 个位置参数是 headers，
        # 直接把 dict 传进去会被当成 HTTP 头，而头里不能有中文
        # （实测报 latin-1 codec can't encode）。
        st, r = http('PATCH', f"{base}/releases/{rel['id']}", data={
            'access_token': token, 'tag_name': tag, 'name': name, 'body': body,
        })
        if st == 200:
            print('  已更新 Release id=%s' % rel['id'])
        else:
            print('  更新失败:', st, str(r)[:200])
            return
    else:
        st, r = http('POST', f'{base}/releases', data={
            'access_token': token, 'tag_name': tag, 'name': name,
            'body': body, 'target_commitish': 'main', 'prerelease': False,
        })
        if st not in (200, 201) or not isinstance(r, dict):
            print('  创建失败:', st, str(r)[:200])
            return
        rel = r
        print('  已创建 Release id=%s' % rel.get('id'))

    rel_id = rel['id']
    print('  页面: https://gitee.com/%s/releases/tag/%s' % (repo, tag))

    # ---------- 上传 APK（先查重）----------
    apk_name = os.path.basename(apk_path)
    st, lst = http('GET', f'{base}/releases/{rel_id}/attach_files?access_token={token}')
    existing = [a for a in (lst if isinstance(lst, list) else [])
                if a.get('name') == apk_name]
    if existing:
        print('  附件已存在（%d 个），跳过上传' % len(existing))
        # 顺手清掉重复的
        for a in existing[1:]:
            http('DELETE',
                 f"{base}/releases/{rel_id}/attach_files/{a.get('id')}"
                 f"?access_token={token}")
            print('    清理重复附件 id=%s' % a.get('id'))
        return

    import uuid
    bd = '----dsh' + uuid.uuid4().hex
    with io.open(apk_path, 'rb') as f:
        apk_bytes = f.read()
    head = ('--%s\r\n'
            'Content-Disposition: form-data; name="file"; filename="%s"\r\n'
            'Content-Type: application/vnd.android.package-archive\r\n\r\n'
            % (bd, apk_name)).encode('utf-8')
    tail = ('\r\n--%s--\r\n' % bd).encode('utf-8')
    prefix = ('--%s\r\n'
              'Content-Disposition: form-data; name="access_token"\r\n\r\n'
              '%s\r\n' % (bd, token)).encode('utf-8')
    payload = prefix + head + apk_bytes + tail

    st, r = http(
        'POST', f'{base}/releases/{rel_id}/attach_files', {
            'Content-Type': 'multipart/form-data; boundary=' + bd,
        }, payload, raw=True)
    if st in (200, 201):
        print('  已上传: %s  %.1f MB' % (apk_name, len(apk_bytes) / 1048576))
    else:
        print('  附件上传失败: [%s] %s' % (st, str(r)[:200]))
        print('  → 请到网页端手动上传')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--repo', required=True)
    ap.add_argument('--tag', required=True)
    ap.add_argument('--apk', required=True)
    ap.add_argument('--name', required=True)
    ap.add_argument('--code', type=int, required=True)
    ap.add_argument('--version-name', required=True)
    ap.add_argument('--notes', default='')
    # 从文件读说明文字。
    #
    # 为什么不直接用 --notes：说明里常含 markdown 表格（行首是 "|"）和反引号，
    # 经 shell 传递时会被当成管道/命令替换拆开，实测报
    # "unrecognized arguments: 2dp 细线——"。走文件就完全绕开转义问题。
    ap.add_argument('--notes-file', default='')
    ap.add_argument('--sha', default='')
    a = ap.parse_args()

    if a.notes_file:
        import io as _io
        with _io.open(a.notes_file, encoding='utf-8') as _f:
            a.notes = _f.read()

    size_mb = round(os.path.getsize(a.apk) / 1048576, 1)
    body = build_body(a.code, a.version_name, os.path.basename(a.apk), a.sha,
                      size_mb, a.repo, a.tag, a.notes)

    # 按应用的正则预检，确保它能读出 versionCode
    import re
    m = re.search(r'versionCode[:\s=]+(\d+)', body, re.IGNORECASE)
    print('预检：应用将读到 versionCode =', m.group(1) if m else '（读不到！）')
    print()

    publish_github(a.repo, a.tag, a.name, body, a.apk, os.path.basename(a.apk))
    publish_gitee(a.repo, a.tag, a.name, body, a.apk)


if __name__ == '__main__':
    main()
