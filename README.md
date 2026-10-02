# webview_kt

基于 Android `WebView` 的轻量浏览器（Kotlin）。

包名：`com.setsuodu.webview`  
最低 SDK：24 · 目标 SDK：见 `app/build.gradle.kts`

---

## 功能现状（已实现）

| 功能 | 说明 |
|------|------|
| 网页浏览 | JS / DOM Storage / 自适应视口；`WebViewClient` 拦截外跳 |
| 地址栏 | 自动补 `https://`；进度条 |
| 主页 | SharedPreferences 可配置；`🏠` 回主页 |
| 扫码 | ZXing Embedded，结果为 URL 则打开 |
| 下载 | `DownloadListener` → 系统 `DownloadManager` |
| 下载路径 | 设置里可改，默认公共目录 `Download` |
| 下载列表 | 独立 Activity；进度 / 完成态与 DM 同步；真实文件名（含 `-1` 重名） |
| 删除 | 可勾选同时删除本地文件；进行中会 `dm.remove` |
| 权限 | 存储（旧系统动态申请）；安装 APK 引导「未知应用」 |
| 打开文件 | 优先 `DownloadManager.getUriForDownloadedFile` + FileProvider |

### 源码地图

```
app/src/main/java/com/setsuodu/webview/
├── MainActivity.kt          # WebView、下载监听、菜单、设置、下载完成广播
├── DownloadListActivity.kt  # 下载列表 UI、轮询同步、打开/分享/删除
├── DownloadStore.kt         # 路径配置 + 下载历史 JSON + 与 DM 同步
├── PermissionHelper.kt      # 存储 / 安装未知应用
└── ui/theme/                # 遗留 Compose 主题（主界面仍是 XML View）

app/src/main/res/layout/
├── activity_main.xml
├── activity_download_list.xml
└── item_download.xml
```

### 菜单（主界面【…】）

- 书签 — 占位（未实现）
- **下载列表** — `DownloadListActivity`
- **设置** — 默认主页 + 下载保存路径

### 调试

```bash
adb logcat -s WebViewDL:D *:S
```

关键日志：`enqueue` / `query id=… dmStatus=…` / `ACTION_DOWNLOAD_COMPLETE`  
`dmStatus`：1=pending 2=running 4=paused 8=success 16=failed

### 构建

Android Studio 打开工程 → Sync → Run。  
依赖：`appcompat`、`zxing-android-embedded:4.3.0`（见 `app/build.gradle.kts`）。

---

## TODO：资源嗅探（给后续 AI 直接开工）

目标：接近夸克「雷达」——在 WebView 加载过程中收集媒体 URL，提供列表，可下载 / 复制。**不做站点专用破解、不碰 DRM。**

### 产品形态（建议）

1. **浮层入口**（主界面右下角圆形按钮 + 角标数量）  
2. **资源列表页/底部 sheet**：类型、URL、建议文件名、来源页  
3. 操作：复制链接、加入下载（走现有 `startDownload` / `DownloadManager`）  
4. 可选：仅当识别到「标准播放器」时在播放器区域显示 ↓（第二期；第一期只做浮层+列表）

### 技术路线（第一期：通用嗅探）

在 `MainActivity` 的 `WebViewClient` 中覆盖：

```text
shouldInterceptRequest(view, WebResourceRequest)
```

对每个请求检查：

| 条件 | 示例 |
|------|------|
| MIME | `video/*` `audio/*` `application/vnd.apple.mpegurl` `application/x-mpegURL` `application/dash+xml` |
| 路径后缀 | `.m3u8` `.mpd` `.mp4` `.webm` `.mkv` `.m4a` `.mp3` `.ts` `.m4s` `.flv` |
| 查询串 | 含 `m3u8` / `mpd` 等（注意去重、去掉一次性签名差异可选） |

**注意：**

- `shouldInterceptRequest` 必须尽快返回（通常 `return null` 继续加载），**不要在里面做网络 IO**。  
- 收集到的 URL 丢到主线程的列表（`ConcurrentHashMap` / 按 URL 去重）。  
- 保留：`url`、`mimeType`、`contentDisposition`（若有）、`pageUrl`（`view.url`）、`time`。  
- Cookie：下载时继续用现有 `CookieManager.getCookie(url)`。

可选增强（第二期）：

- 注入只读 JS，监听 `PerformanceObserver` / 重写 `MediaSource` 仅用于发现 blob（blob 下载复杂，可先只展示「存在 blob，请用录制」）。  
- m3u8：列表里标记为 HLS；下载仍先下 playlist 文本或整链交给外部工具（完整 TS 合并可后续用 ffmpeg 原生库，**不要第一期做**）。

### 建议新增文件

```text
SniffStore.kt       # 内存+可选 SharedPreferences 的资源列表（按 tab/页面可清空）
SniffListActivity.kt 或 BottomSheet
layout: activity_sniff_list.xml / item_sniff.xml
```

`MainActivity`：

- 浮钮 `btnSniff` + 角标  
- `shouldInterceptRequest` 写入 `SniffStore`  
- 导航到新页面时策略：清空或按 host 分组（推荐：**同 host 保留，跨 host 清空**）

### 与现有下载的衔接

```text
嗅探列表点「下载」
  → 复用 MainActivity.startDownload(url, userAgent, contentDisposition, mimeType)
  → 或抽成 DownloadHelper 供两处调用
```

不要再写第二套下载队列。

### 明确不做（第一期）

- B 站 / YouTube 专用解析 API、破解签名  
- Widevine / 付费 DRM  
- 自动转码 m3u8→mp4  
- 后台静默全站爬虫（那是服务端 AnimeNetflix 的事）

### 验收标准

- [ ] 打开含明文 `.mp4` 或 `.m3u8` 的测试页，浮钮角标 > 0  
- [ ] 列表可复制 URL、可触发系统下载并出现在「下载列表」  
- [ ] 切换到无关网站后列表按约定清空或过滤  
- [ ] `shouldInterceptRequest` 不卡顿首屏（不在回调里读 body）  
- [ ] log 标签建议：`WebViewSniff`（与 `WebViewDL` 分开）

### 实现顺序（新 AI 按此执行）

1. 写 `SniffStore`（去重、按页面清空策略、线程安全）  
2. `MainActivity.WebViewClient.shouldInterceptRequest` 挂钩 + 单元级手动测 log  
3. 浮钮 + 角标  
4. 列表 UI + 复制 / 下载  
5. （可选）设置项：开关嗅探、是否跨域保留  

---

## 其它 TODO

- [ ] 书签（菜单已占位）  
- [ ] 前进 / 多窗口  
- [ ] 下载中通知点击跳转下载列表  
- [ ] 自定义路径在 Android 10+ 的分区存储兼容说明（设置里提示优先用公共 Download）

---

## 相关项目

- 片库 / 服务端爬虫与 m3u8 入库：见 [AnimeNetflix](https://github.com/setsuodu/AnimeNetflix)（后端抓取，不在本 App 内做站库爬虫）。

---

## License

未指定则默认仅供个人学习使用；提交到公开仓库前请自行补充许可证。
