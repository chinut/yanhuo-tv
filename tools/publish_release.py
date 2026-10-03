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


def http(method, url, headers=None, data=None, timeout=180):
    r = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw.decode('utf-8')) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')


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

> Gitee 的 Release 不支持通过 API 挂二进制附件（attach_files 对令牌返回 405），
> Gitee 侧请手动上传附件，或到 GitHub 的 Release 页面下载。
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


def publish_gitee(repo, tag, name, body):
    print()
    print('=' * 58)
    print('Gitee')
    print('=' * 58)
    token = read_cred('git:https://gitee.com')
    if not token:
        print('  读不到令牌，跳过')
        return

    st, rls = http('GET', f'https://gitee.com/api/v5/repos/{repo}/releases?access_token={token}')
    if isinstance(rls, list):
        for r in rls:
            if r.get('tag_name') == tag:
                print('  删除已存在的 Release id=', r['id'])
                http('DELETE', f"https://gitee.com/api/v5/repos/{repo}/releases/{r['id']}"
                               f"?access_token={token}")

    data = urllib.parse.urlencode({
        'access_token': token, 'tag_name': tag, 'name': name,
        'body': body, 'target_commitish': 'main', 'prerelease': 'false',
    }).encode('utf-8')
    st, r = http('POST', f'https://gitee.com/api/v5/repos/{repo}/releases',
                 {'Content-Type': 'application/x-www-form-urlencoded; charset=utf-8'}, data)
    if st in (200, 201) and isinstance(r, dict):
        print('  名称:', repr(r.get('name')))
        print('  页面: https://gitee.com/{}/releases/tag/{}'.format(repo, tag))
        print('  ⚠️ APK 附件需手动上传（Gitee API 不开放该接口）')
    else:
        print('  创建失败:', st, str(r)[:300])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--repo', required=True)
    ap.add_argument('--tag', required=True)
    ap.add_argument('--apk', required=True)
    ap.add_argument('--name', required=True)
    ap.add_argument('--code', type=int, required=True)
    ap.add_argument('--version-name', required=True)
    ap.add_argument('--notes', default='')
    ap.add_argument('--sha', default='')
    a = ap.parse_args()

    size_mb = round(os.path.getsize(a.apk) / 1048576, 1)
    body = build_body(a.code, a.version_name, os.path.basename(a.apk), a.sha,
                      size_mb, a.repo, a.tag, a.notes)

    # 按应用的正则预检，确保它能读出 versionCode
    import re
    m = re.search(r'versionCode[:\s=]+(\d+)', body, re.IGNORECASE)
    print('预检：应用将读到 versionCode =', m.group(1) if m else '（读不到！）')
    print()

    publish_github(a.repo, a.tag, a.name, body, a.apk, os.path.basename(a.apk))
    publish_gitee(a.repo, a.tag, a.name, body)


if __name__ == '__main__':
    main()
