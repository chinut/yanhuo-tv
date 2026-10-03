# 把 GitHub 上已清洗好的 release 正文同步到 Gitee。
#
# 背景：polish 脚本的流程是「读 GitHub → 清洗 → 写回两边」。
# 上一轮 GitHub 已经写成功了、Gitee 因参数错误失败；再跑时正文已相同，
# 脚本判定「无需改动」就跳过了，导致 Gitee 一直没同步。
# 这个脚本专门做「GitHub → Gitee」的单向同步。
import importlib.util

spec = importlib.util.spec_from_file_location(
    'c', r'G:\android\AndroidTV\tools\clean_release_notes.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

gh = m.read_cred('git:https://github.com')
gt = m.read_cred('git:https://gitee.com')
if not gt:
    raise SystemExit('没有 Gitee 凭据')

hh = {'Authorization': 'token ' + gh, 'Content-Type': 'application/json'}
st, rels = m.http('GET',
                  'https://api.github.com/repos/%s/releases?per_page=100' % m.REPO,
                  hh)
if not isinstance(rels, list):
    raise SystemExit('拿不到 GitHub release 列表：%s' % st)

# Gitee 现有 release（tag -> id/name）
st2, rls = m.http('GET',
                  'https://gitee.com/api/v5/repos/%s/releases'
                  '?access_token=%s&per_page=100' % (m.REPO, gt))
if not isinstance(rls, list):
    raise SystemExit('拿不到 Gitee release 列表：%s' % st2)
gitee = {x.get('tag_name'): x for x in rls}
print('GitHub %d 个 / Gitee %d 个' % (len(rels), len(gitee)))
print()

ok = fail = skip = 0
for r in rels:
    tag = r.get('tag_name', '')
    body = r.get('body') or ''
    g = gitee.get(tag)
    if not g:
        print('  %-10s Gitee 没有这个 release，跳过' % tag)
        skip += 1
        continue
    if (g.get('body') or '') == body:
        print('  %-10s 已一致' % tag)
        ok += 1
        continue
    st3, err = m.http('PATCH',
                      'https://gitee.com/api/v5/repos/%s/releases/%s'
                      % (m.REPO, g.get('id')),
                      {'Content-Type': 'application/json'},
                      {'access_token': gt, 'body': body,
                       'tag_name': tag,
                       'name': g.get('name') or ('焰火TV ' + tag)})
    if st3 in (200, 201):
        print('  %-10s ✅ 已同步（%d 字）' % (tag, len(body)))
        ok += 1
    else:
        print('  %-10s ❌ %s %s' % (tag, st3, str(err)[:100]))
        fail += 1

print()
print('同步完成：成功 %d / 失败 %d / 跳过 %d' % (ok, fail, skip))
