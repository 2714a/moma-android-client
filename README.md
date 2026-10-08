# 中国移动 AI 客户端 —— 原生 Android 版（Kotlin）

真正的安卓端：用 **Kotlin + Android Studio 标准工程（XML 布局 + OkHttp）** 直接对接中国移动 MoMA / 九天 AI 的 OpenAI 兼容 API，不再依赖 Python/Kivy 桥接。

> ⚠️ **关于 APK 文件**：无论原生还是 Kivy 方案，生成 `.apk` 都必须跑 **Android 官方构建工具链（Android SDK + Gradle）**。这套工具链只有 x86 版，沙箱（无 Android SDK）和手机 Termux（aarch64）都跑不了编译——**APK 只能由 Android Studio（电脑）或 GitHub Actions（云端）产出**。本仓库是「完整可编译的工程」。

## 工程结构

```
moma-android/
├── build.gradle.kts / settings.gradle.kts / gradle.properties   # Gradle 工程配置
├── app/
│   ├── build.gradle.kts                                         # 模块依赖（appcompat/recyclerview/material/okhttp）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/moma/
│       │   ├── MainActivity.kt          # 聊天界面 + 流式输出（RecyclerView 气泡）
│       │   ├── ApiClient.kt             # OkHttp SSE 流式调用 + 多 Key 轮询
│       │   ├── ConfigStore.kt           # SharedPreferences 配置读写
│       │   ├── ChatAdapter.kt           # RecyclerView 适配器
│       │   └── SettingsActivity.kt       # 设置页
│       └── res/                         # 布局 / 菜单 / 字符串 / 颜色 / 主题
└── .github/workflows/android.yml                          # GitHub Actions 云端编译 APK
```

## 功能

- 💬 RecyclerView 气泡聊天（用户右绿 / AI 左灰），SSE **流式逐字**显示
- 🔀 多 API Key 自动轮询
- ⚙ 设置页：Base URL（可填反代地址）、多 Key、模型、温度、max_tokens
- 📂 顶部「新会话」清空当前对话
- 🟢 Material 3 主题（中国移动绿）

## 出 APK 的两种方式

### 方式一：GitHub Actions 云端编译（手机也能完成，推荐）

1. GitHub 网页新建空仓库（如 `moma-android`）；
2. 把本工程全部文件上传到**仓库根**（用 Add file → Upload files 多选；含 `.github/` 目录）；
3. 提交后打开仓库 **Actions** 页，等 10~25 分钟；
4. 运行页底部 **Artifacts** 下载 `moma-android-debug-apk`，解压得到 APK 安装。

### 方式二：Android Studio（电脑）

1. 电脑装 Android Studio，导入本工程（Open → 选 `moma-android` 目录）；
2. 等 Gradle 同步完成（会自动下载 SDK/依赖）；
3. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**，产物在 `app/build/outputs/apk/debug/`。

## 使用

安装 APK 后：右上菜单「设置」→ 填 **API Base URL**（如 `https://zhenze-huhehaote.cmecloud.cn/v1` 或你的反代地址）+ **API Key(s)** + 模型名 → 返回聊天即可，回复流式逐字显示。

## 合规提醒

默认用于**个人或团队内部**调用你自己申请的 API。请勿对外分发、转售或共享 Key，遵守移动云 MoMA 平台条款。
