# 焰火TV（yanhuoTV）

一款为 **Android TV / 电视盒子** 重新设计的聚合播放器：央视与各省卫视直播、TVBox 影视订阅、
低端影视瀑布流，全部按**遥控器操作逻辑**与**大屏观感**重做。

> 本项目只做播放器与界面聚合，**不存储、不传播任何影视资源**。
> 直播源、订阅接口与在线影视地址均来自第三方公开接口，仅限个人学习研究使用，请勿用于商业用途。

---

## 功能

| 板块 | 内容 | 说明 |
|---|---|---|
| **A 直播** | 央视 / 卫视 / 各省地方台 | 内置 552 个频道，分组切换；播放中 `↑↓` 换台、`←→` 调音量；同名频道自动合并备用源 |
| **B 影视** | 电影 / 电视剧 / 动漫 / 综艺 | TVBox 订阅接口（只保留 HTTP-JSON 型站点，无需爬虫插件）；内置 11 个实测可用站点；多站点聚合搜索 |
| **C 瀑布流** | 低端影视（ddys） | 官方 `/api/v1` 接口；4 个官方域名自动探测容错；瀑布流 + 分类 + 搜索 + 线路/选集 |
| **D 设置** | 接口 / 域名 / 直播源 / 更新 | **手机扫码网页调试**：电视上不打字，用手机改全部设置 |

其他：开屏动画、遥控器自绘焦点系统、电视虚拟键盘、应用内自动更新（Gitee 优先 + GitHub 回退）。

## 遥控器操作

| 按键 | 作用 |
|---|---|
| `↑` `↓` | 导航栏内切板块 / 网格内上下移动 / **播放中换台** |
| `←` `→` | 在导航栏与内容区之间进出 / **播放中调音量** |
| `确定` | 进入、播放、切换开关 |
| `返回` | 逐级返回（播放中先退出播放，再回首页，不会误退出 App） |

## 技术要点

- Kotlin + Jetpack Compose（compileSdk 36 / minSdk 24）+ Media3 ExoPlayer
- **自绘焦点系统**（`ui/theme/TvFocus.kt`）：不依赖 Compose 的 focus/clickable，
  方向键在 Activity 层统一截获，按几何最近原则移动焦点。
  原因见文件头注释（`clickable`/`focusable` 焦点目标冲突导致确定键失效等三个已复现的坑）。
- TVBox 接口契约完整实现：`ac=videolist|detail`、`$$$`/`#`/`$` 三级分隔、type 0 的 XML 变体、
  解析接口（直链 / 内嵌 JSON / 网页解析）
- 低端影视官方 JSON 接口直连，**无需任何验证绕过**
- 手机调试：内置极简局域网 HTTP 服务 + ZXing 二维码

## 构建

```powershell
$env:JAVA_HOME="E:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug        # 调试包
.\gradlew.bat :app:assembleRelease      # 发布包（app/bawan.jks 签名）
```

电视模拟器（1080p 横屏）：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd BawanTV_TV
```

## 发布与自动更新

打 tag 触发 GitHub Actions 自动构建并发布 Release（`.github/workflows/release.yml`），
Gitee 侧见 `gitee-workflow-release.yml` 或本地一键脚本：

```powershell
.\tools\publish.ps1 -Tag v1.0.0 -GiteeToken <token> -GitHubToken <token>
```

App 内「设置 → 检查更新」会读取 **Gitee 优先、GitHub 回退** 的 latest release，
从说明里解析 `versionCode: N`，下载附件 APK 并调起系统安装器。

> Release 说明里必须包含 `versionCode: <数字>`，否则客户端识别不到版本。

## 目录

```
app/src/main/java/com/chinut/bawantv/
├── core/        设置、网络、二维码、手机调试服务、自动更新
├── live/        直播频道表解析与播放页
├── vod/         TVBox 订阅模型 / 解析 / 接口 / 地址解析
├── ddys/        低端影视 API 与域名容错
└── ui/          主题（设计令牌 / 自绘焦点 / 虚拟键盘）、导航、各板块界面
tools/           图标生成、遥控器回归脚本、一键发布
docs/            开发进度与待办、演示截图
```

## 进度

见 [docs/开发进度与待办.md](docs/开发进度与待办.md)。
当前主要待办：**把内置直播源换成可用源**（原有公开直连流已下线）。
