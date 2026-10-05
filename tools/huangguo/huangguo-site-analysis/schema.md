# 数据模型与字段说明

> 给建库/建表用。字段类型从脱敏样本推断，`data-samples/field-types.txt` 是自动生成的原始表。

---

## 表 1：`series`（剧集）

| 字段 | 类型 | 必有 | 说明 |
|---|---|---|---|
| `slug` | string(8) | ✅ | **URL 用的短 ID**，字母数字混合，如 `czv81qsm` |
| `url` | string | ✅ | 详情页完整地址 `{base}/series/{slug}` |
| `title` | string | ✅ | 剧名。取自 `<h1>`，需去掉尾部 `· 黄果剧场` |
| `cover` | string | ⚠️ | 封面图。`{base}/uploads/content/series/{series_id}/cover.webp?v={版本}` |
| `description` | string(≤500) | ⚠️ | 简介原文。**出品方和标签都塞在这一句里** |
| `tags` | string[] | ⚠️ | 从 `description` 里用正则 `标签([^。]{1,80})` 抠出来，再按 `、,，/` 切分 |
| `episode_count_listed` | int | ✅ | 详情页上**实际列出的分集链接数** |
| `episode_count_declared` | int? | ❌ | 页面文字「全N集」。**可能为 null**（多数页面没写）|
| `episodes` | array | ✅ | 分集数组，见下 |

### ⚠️ 两个容易踩的坑

1. **`slug` 不是数字**。早期文档以为是 `/series/220` 这种数字 ID，实测 404。
   实际是短字母数字。**别按数字 ID 建 URL 规则。**
2. **`episode_count_listed` 可能小于真实集数**。详情页可能分页，
   实测某剧 `<h1>` 下只列出 8 个分集链接，但站内其实更多。
   **不要用它当真实集数**，要准就得翻分集分页。

---

## 表 2：`episode`（分集）

| 字段 | 类型 | 必有 | 说明 |
|---|---|---|---|
| `slug` | string(8) | ✅ | 分集短 ID |
| `url` | string | ✅ | `{base}/video/{slug}` |
| `title` | string | ✅ | 如 `剧名 · 第1集` |
| `episode_no` | int? | ⚠️ | 从 `title` 里正则 `第\s*(\d+)\s*集` 抠出来。**可能为 null** |
| `content_id` | string | ✅ | **站点内部数字 ID**，媒体文件路径用它，不用 slug |
| `hls` | string | ✅ | `{base}/uploads/content/video/{content_id}/master.m3u8` |
| `poster` | string | ⚠️ | 同 series 的 cover |
| `series_slug` | string | ⚠️ | 播放页回链，用于确认归属 |
| `variants` | array | ❌ | 码率档位，见下 |

### ⚠️ 两套 ID 并存

```
URL 层    用 slug        /video/osir6u43
媒体层    用 content_id  /uploads/content/video/1164/master.m3u8
```

**两者不能互推**，只能从播放页的 `data-content-id` 同时拿到。

---

## 表 3：`variant`（码率档位）

从 `master.m3u8` 解析，**子播放列表地址是相对路径，要按 master 的目录拼回去**。

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | `480p` / `720p` / `1080p` |
| `resolution` | string | `270x480` / `404x720` / `608x1080` |
| `bandwidth` | int | 417606 / 648003 / 1085123 |
| `url` | string | 子播放列表完整地址，**带签名** `?n=<时间戳>.<hash>` |

### ⚠️ 签名有时效

子播放列表的 `?n=` 参数是签名的。**不要把它入库长期缓存** ——
每次播放前重新取一次播放页拿 `data-hls`，再取 master。

---

## 站点侧的选择器速查

解析 HTML 时用到的锚点（改版时先查这几个）：

| 位置 | 选择器 / 正则 | 取什么 |
|---|---|---|
| 列表页 | `href="/series/([a-z0-9]+)"` | 剧 slug |
| 详情页 | `<h1[^>]*>([^<]{1,120})</h1>` | 剧名 |
| 详情页 | `<meta name="description" content="([^"]{0,500})"` | 简介（含标签）|
| 详情页 | `href="/video/([a-z0-9]+)"` | 分集 slug |
| 播放页 | `data-content-id="([^"]*)"` | 数字 ID |
| 播放页 | `data-hls="([^"]*)"` | m3u8 地址 |
| 播放页 | `data-poster="([^"]*)"` | 封面 |

**自检命令**：`python huangguo.py check` —— 会逐个验证上面每一条，改版时一眼看出哪一步断了。

---

## 实际样本统计（3 页样本）

```
剧集           43 部
分集           248 集（每部按 max-eps=2 限流抓的，实际更多）
含播放地址     77 集（同样受限流影响，不是站点缺数据）
全集列表       有
sitemap.xml    404（robots.txt 里声明了但实际没有）
```

> `data-samples/index.json` 里有同一份统计。

---

## 建表建议

```sql
CREATE TABLE series (
  slug            VARCHAR(16)  PRIMARY KEY,
  title           VARCHAR(200) NOT NULL,
  cover           VARCHAR(500),
  description     VARCHAR(1000),
  tags            VARCHAR(200),          -- 逗号分隔，或另开一张 tags 表
  episode_count   INT,
  scraped_at      DATETIME
);

CREATE TABLE episode (
  slug            VARCHAR(16)  PRIMARY KEY,
  series_slug     VARCHAR(16)  NOT NULL,
  content_id      VARCHAR(32),           -- 备用：媒体路径用它
  episode_no      INT,
  title           VARCHAR(300),
  -- ⚠️ hls 不要入库：带签名、有时效，每次播放前现取
  FOREIGN KEY (series_slug) REFERENCES series(slug)
);
```
