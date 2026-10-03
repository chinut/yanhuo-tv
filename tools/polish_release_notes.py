# 把旧 release 说明里残留的对话口吻清掉（保留全部技术内容）
#
# 原则：只改「人称 / 口吻 / 求助语句」，**不动技术描述**。
# 例如「你看到的正是这个」→「表现即为此」；
#     「有的话把它发我」→ 整句删掉（那是对话，不是发布说明）。
import json
import re
import sys
import importlib.util

spec = importlib.util.spec_from_file_location(
    'c', r'G:\android\AndroidTV\tools\clean_release_notes.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

# ---------- 整行删除（纯对话，不是发布说明） ----------
DROP_LINE = [
    r'把它发我',
    r'发我(，|,)?',
    r'请重点',
    r'下一步[：:]?\s*$',
    r'要我做吗',
    r'如果.*(告诉|发)我',
    r'辛苦',
    r'谢谢',
    r'麻烦你',
    r'欢迎反馈',       # 发布页不需要
    r'我可以',
    r'我建议',
]
DROP = re.compile('|'.join(DROP_LINE))

# ---------- 短语替换 ----------
REPL = [
    # 道歉 / 认错
    (r'\*\*?这次的问题是我造成的，抱歉。\*\*?', ''),
    (r'[，。]?\s*抱歉[。！]?', '。'),
    (r'[，。]?\s*对不起[。！]?', '。'),
    (r'先认错：[^\n]*\n?', ''),
    (r'上一版(我)?说["“]?修好了["”]?[^\n]*\n?', ''),
    (r'你是对的[^\n]*\n?', ''),
    (r'这是我上一版引入的 ?bug', '该问题由上一版引入'),
    (r'我上一版引入的', '上一版引入的'),

    # 第二人称
    (r'你(们)?看到的(正)?是', '表现即为'),
    (r'你(们)?看到的是', '表现为'),
    (r'你(们)?会看到', '会看到'),
    (r'你(们)?看到', '表现为'),
    (r'让?你(们)?能', '可'),
    (r'否则你(们)?只会看到', '否则只会看到'),
    (r'告诉你', '给出'),
    (r'(?<![一每])你(们)?的', ''),
    (r'(?<![一每])你(们)?', ''),

    # 第一人称
    (r'我们(?=[改做加修删])', ''),
    (r'(?<![一每])我(?=[改做加修删测查])', ''),
    (r'我觉得', ''),
    (r'我猜', ''),
    (r'(?<![一每])我(?=[发说看认])', ''),
    (r'(?<![一每])我(?!们)', ''),

    # 反馈 → 中性的"实测/现象"
    (r'用户反馈[:：]?', '实测现象：'),
    (r'真机反馈[:：]?', '真机实测：'),
    (r'反馈[:：]?', '实测：'),
    (r'用户说', '实测现象：'),
    (r'用户报', '实测现象：'),
    (r'有人反馈', '有实测发现'),
]

# 收尾：清理被替换后留下的病句
CLEANUP = [
    (r'[，,]\s*[，,]', '，'),
    (r'。\s*。', '。'),
    (r'([，。！？])\s+\1', r'\1'),
    (r'^\s*[，。、]\s*', ''),
    (r'[ \t]+\n', '\n'),
    (r'\n{3,}', '\n\n'),
]


def clean(text: str) -> str:
    lines = []
    for line in text.split('\n'):
        if DROP.search(line):
            continue
        lines.append(line)
    out = '\n'.join(lines)
    for pat, rep in REPL:
        out = re.sub(pat, rep, out)
    for pat, rep in CLEANUP:
        out = re.sub(pat, rep, out)
    # 标题行里不该出现被删空的括号
    out = re.sub(r'\*\*\s*\*\*', '', out)
    out = re.sub(r'（\s*）|\(\s*\)', '', out)
    return out.strip()


def main():
    gh = m.read_cred('git:https://github.com')
    gt = m.read_cred('git:https://gitee.com')
    hh = {'Authorization': 'token ' + gh, 'Content-Type': 'application/json'}

    st, rels = m.http('GET',
                      'https://api.github.com/repos/%s/releases?per_page=100' % m.REPO,
                      hh)
    if not isinstance(rels, list):
        print('拿不到 release 列表：%s' % st)
        return

    only = sys.argv[1:]
    for r in rels:
        tag = r.get('tag_name', '')
        if only and tag not in only:
            continue
        body = r.get('body') or ''
        new = clean(body)
        if new == body:
            print('  %-10s 无需改动' % tag)
            continue
        # 安全阀：改动超过一半就说明规则太激进，跳过并报告
        if len(body) and len(new) < len(body) * 0.45:
            print('  %-10s !! 改动过大（%d → %d 字），跳过'
                  % (tag, len(body), len(new)))
            continue

        st2, _ = m.http('PATCH',
                        'https://api.github.com/repos/%s/releases/%s'
                        % (m.REPO, r['id']), hh, {'body': new})
        print('  %-10s GitHub %s  (%d → %d 字)'
              % (tag, '已更新' if st2 in (200, 201) else '失败%s' % st2,
                 len(body), len(new)))

        if gt:
            st3, rls = m.http('GET',
                              'https://gitee.com/api/v5/repos/%s/releases'
                              '?access_token=%s&per_page=100' % (m.REPO, gt))
            rid, rname = None, '焰火TV ' + tag
            if isinstance(rls, list):
                for x in rls:
                    if x.get('tag_name') == tag:
                        rid = x.get('id')
                        rname = x.get('name') or rname
                        break
            if rid:
                # Gitee 的 PATCH 要求 tag_name 和 name 都必填，缺一个就 400
                # （报错："tag_name is missing" / "name is missing"）
                st4, err4 = m.http('PATCH',
                                   'https://gitee.com/api/v5/repos/%s/releases/%s'
                                   % (m.REPO, rid),
                                   {'Content-Type': 'application/json'},
                                   {'access_token': gt, 'body': new,
                                    'tag_name': tag, 'name': rname})
                print('             Gitee  %s'
                      % ('已更新' if st4 in (200, 201)
                         else '失败%s %s' % (st4, str(err4)[:90])))


if __name__ == '__main__':
    main()
