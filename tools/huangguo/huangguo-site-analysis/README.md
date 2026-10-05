# 黄果剧场（huangguo.video）站点分析报告

> 分析时间：2026-10-05 · 方式：只读抓取（GET/HEAD）· 未做任何写入、未绕过任何限制
>
> 这是**站点结构分析**，用于理解它是怎么搭的。工具和样本都在本包里。

---

## 一、站点定位与技术栈

| 项 | 值 | 依据 |
|---|---|---|
| 域名 | `huangguo.video` | — |
| CDN / 防护 | **Cloudflare** | `Server: cloudflare`、`cf-cache-status: DYNAMIC` |
| 渲染方式 | **服务端渲染（SSR）** | 三页 HTML 里都有完整数据，无 JS 也能读到 |
| 前端 | 原生 + 少量 vendor JS | 无 `_next`/`_nuxt`/`_astro` 构建目录 |
| 播放器 | **Plyr + hls.js** | `/assets/scripts/vendor/plyr.min.js`、`hls.min.js` |
| 安全模型 | **CSP nonce** | 每个响应带随机 nonce，`script-src-elem 'self' 'nonce-…'` |
| 反 XSS | 有 | 见上 |
| Sitemap | **声明了但 404** | `robots.txt` 写 `Sitemap: …/sitemap.xml`，实际 404（配置疏漏）|

### 页面路由

```
/videos                    列表（支持 ?page=N、?category=、?tags=）
/videos?category=all|1..4  分类
/videos?tags=1,2,5,6,7,8,40  标签筛选
/series/{slug}             剧集详情
/video/{slug}              单集播放
/ranking                   排行榜
/roles                     演员/角色库
/photos                    图库
/creator                   创作者（robots 禁止）
```

`robots.txt` 实际内容（透露了私有区结构）：

```
User-agent: *
Allow: /
Disallow: /api/
Disallow: /auth/
Disallow: /creator
Disallow: /login
Disallow: /register
Disallow: /logout
Disallow: /favorites
Disallow: /comments
Disallow: /settings
Sitemap: https://huangguo.video/sitemap.xml
```

**推断**：`/api/` 下是站点的 JSON 接口层，但**路径不可猜**（`/api/`、`/api/videos`、`/api/series` 全部 404）。实际交互走 SSR 表单 + 少量 `/assets/scripts/*.js`，所以**接口层不是主要入口** —— 页面本身就是数据源。

---

## 二、数据链路（核心发现）

```
① 列表页  GET /videos?page=N
             → 剧集卡片，链接 /series/{slug}
             🔑 slug 是**短字母数字**（czv81qsm），不是数字 ID

② 详情页  GET /series/{slug}
             <h1>                          剧名
             <meta name="description">     简介 + 出品方 + 标签（一句话里全都有）
             href="/video/{slug}"          分集列表
             og:image                      封面

③ 播放页  GET /video/{slug}
             🔑 **播放地址直接在播放器元素的 data-* 上，不用解析 JS**：
                 data-content-id="1164"
                 data-hls="/uploads/content/video/1164/master.m3u8"
                 data-poster="/uploads/content/series/217/cover.webp"

④ 播放流  GET {base}/uploads/content/video/{content_id}/master.m3u8
             标准 HLS，master 里 3 档：
                 480p   270x480    BANDWIDTH 417606
                 720p   404x720    BANDWIDTH 648003
                 1080p  608x1080   BANDWIDTH 1085123
             子播放列表带签名：?n=<时间戳>.<hash>   ← **有时效**
```

**这是这个站最好抓的地方**：播放地址不是藏在 JS 里拼出来的，而是**直接写在 HTML 属性上**。任何人 `view-source` 就能看到。说明他们**没有做播放地址的防抓取**，只做了签名时效。

---

## 三、数据模型

### 剧集（series）

```
slug                     短 ID，如 czv81qsm
url                      详情页地址
title                    剧名（<h1>，去掉尾部「· 黄果剧场」）
cover                    封面（/uploads/content/series/{id}/cover.webp，带 ?v= 版本号）
description              简介原文（含出品方和标签）
tags[]                   从简介里「标签A、B。」解析出来
episode_count_listed     页面上列出的分集链接数
episode_count_declared   「全N集」文字（可能为 null）
episodes[]               分集数组
```

### 分集（episode）

```
slug              短 ID
title             如「XXX · 第1集」
episode_no        从标题解析的集号
content_id        站点内部的数字内容 ID   ← 和 video 目录名一致
hls               master.m3u8 完整地址
poster            封面
series_slug       所属剧（播放页回链）
variants[]        码率档位 [{name, resolution, bandwidth, url}]
```

### 值得注意的字段设计

- **`content_id` 和 `slug` 是两套 ID**：`slug` 用于 URL，`content_id`（数字）用于媒体文件路径。`/uploads/content/video/{content_id}/…`。
- **封面路径也带 content_id 风格的数字**：`/uploads/content/series/217/cover.webp` —— 这里的 217 是 series 的数字 ID，但**URL 里用的是 slug**，所以要从封面路径反推 series 数字 ID。
- **封面有 `?v=<时间戳>` 版本号**：更新封面时用它破缓存。

---

## 四、分页与容量

```
/videos?page=1    20 部
/videos?page=8    累计 252 部（未到底）
```

实测每页约 20~38 部，`page` 参数正常递增、无重复。**总量未探完**（第 8 页仍在增长）。

首页只有 10 部不重复（"最新发布"等区块，同一部剧在多个区块重复出现，去重后 10 部）——
**要全量必须走 `?page=N`**，只抓首页会严重漏。

---

## 五、内容性质（⚠️ 必须知道）

抓了 3 页共 43 部做样本审计，标签分布：

```
都市 34 · 人妻 13 · 玄幻 4 · 古风 3 · 校园 3 · 职场 2
乱伦 1 · 男同 1 · 女同 1 · NTR 1
```

**这是一个色情短剧站**，不是普通短剧平台。标签体系（人妻、NTR、乱伦）和剧名都指向明确的成人内容，其中一部分涉及乱伦题材。

技术分析不受影响，但**这一条决定了这个数据不能用于面向公众的产品**。

---

## 六、抓取注意事项（工程要点）

| 要点 | 说明 |
|---|---|
| **播放地址有时效** | 子播放列表带 `?n=<ts>.<hash>` 签名，**别长期缓存**，每次播放前重新取详情页 |
| **必须有 Referer** | 请求带 `Referer: https://huangguo.video/` 更稳（封面/媒体可能有防盗链）|
| **控制频率** | 默认 0.8 秒间隔。`/api/` 被 robots 禁止，别去猜接口 |
| **解析要容错** | 站点改版会让正则失效 —— 用 `huangguo.py check` 快速判断是"站点变了"还是"自己代码坏了" |
| **不要绕 CSP/nonce** | nonce 是防 XSS 的正常安全措施，不是障碍 —— 数据在 HTML 里，不需要执行 JS |
| **封面走相对路径** | 页面里是 `/uploads/…`，要拼上 `https://huangguo.video` |

---

## 七、包内文件

```
huangguo-site-analysis/
├── README.md                  ← 你正在看的这份
├── huangguo.py                抓取工具（list / series / export / check）
├── schema.md                  数据模型与字段说明（给建库用）
├── raw-stats.txt              样本审计统计（标签分布、容量探测）
├── sanitize_samples.py        生成脱敏 JSON 形状样本
├── sanitize_html.py           HTML 样本脱敏（保留结构、抹掉正文）
├── samples/                   HTML 样本（**已脱敏**）
│   ├── list.html              列表页
│   ├── series.html            详情页
│   └── video.html             播放页
└── data-samples/
    ├── series.schema.json     全量数据形状（**剧名等正文已替换**）
    ├── one-series.json        单部剧完整结构（同上）
    ├── field-types.txt        字段类型表（自动生成）
    └── index.json             抓取摘要统计
```

## 关于脱敏（重要）

包里的**内容和入口都做了脱敏**，因为这是**结构分析包**，正文和真实入口没必要带：

| 位置 | 处理 |
|---|---|
| `data-samples/*.json` | 剧名 → `示例剧名 001`；简介/标签 → 占位符；slug → 同长度假值 |
| `data-samples/*.json` 的 `hls` / `content_id` | → `<content_id>` 占位符（**避免包里带可直接访问的播放清单**）|
| `samples/*.html` 的**可见文本** | → 等长占位符 |
| `samples/*.html` 的 `alt=` / `<meta content=>` | → 中文抹成占位符（ASCII 保留）|
| `samples/*.html` 的 `<script>` 代码 | **原样保留**（播放器逻辑、CSP nonce 是分析对象）|

**完整保留的**：DOM 层级、全部 class 名、`data-*` 属性、`href`/`src` 路径形状、
码率参数（bandwidth / resolution）、页面结构统计。

所以你能照着 `samples/*.html` 看出站点的 DOM 结构和样式体系，
照着 `data-samples/` 建表，但包里不含内容正文。

两个脱敏脚本也一并给了，你可以自己核对处理逻辑。

---

## 八、一句话总结

**技术上这是个做得很规矩的 SSR 站**：CSP nonce、Cloudflare、Plyr+hls.js、清晰的 RESTful 路由。
但它**没有对媒体地址做防抓取**（播放地址明文写在 HTML 属性上），
所以整条链路不需要执行 JS、不需要逆算法、不需要代理 —— 这是它最容易被抓的一点。

**内容上它是色情短剧站**，这一条比技术结论更重要。

---

## 九、使用须知

这个包是**站点结构分析**用的。里面的技术结论（SSR 架构、HLS 签名时效、
明文播放地址）可以用于：判断一个站的工程水平、给自己的站做架构参考、
评估媒体防盗链该怎么做。

**包里已经不含正文和可用入口**。如果你要把这套抓取逻辑用到别的站，
请确认那个站的内容你有权处理。

