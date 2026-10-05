# WP7 NavBar

> 把 HyperOS / Android 的原生三键导航栏，改成 **Windows Phone 7 风格**。
> 纯 LSPosed Hook —— **不修改 SystemUI APK、不替换系统资源**，卸载即还原。

[![Release](https://img.shields.io/github/v/release/bookgeek99/WP7NavBar?style=flat-square)](https://github.com/bookgeek99/WP7NavBar/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/bookgeek99/WP7NavBar/release.yml?style=flat-square)](https://github.com/bookgeek99/WP7NavBar/actions)

---

## 目录

- [效果预览](#效果预览)
- [功能特性](#功能特性)
- [环境要求](#环境要求)
- [安装教程](#安装教程) ← **新手请看这里**
- [使用说明](#使用说明)
- [常见问题](#常见问题)
- [卸载还原](#卸载还原)
- [从源码构建](#从源码构建)
- [项目结构](#项目结构)
- [技术实现](#技术实现)
- [开发者文档](#开发者文档)
- [更新日志](#更新日志)
- [License](#license)

---

## 效果预览

```
竖屏（键盘收起）            竖屏（键盘弹出）
┌───────────────────────┐   ┌───────────────────────┐
│  ◁    ⊞    🔍    ▣   │   │ ⌨  ◁  ⊞  🔍   ▣    │
└───────────────────────┘   └───────────────────────┘
  返回  主页  搜索  最近         输入法切换 + 上面四键

横屏（键盘弹出）
┌───────────────────────────────────────────┐
│  ⌨    ◁    ⊞     ▣                      │
└───────────────────────────────────────────┘
```

- **返回 ◁**：WP7 风格细线左箭头
- **主页 ⊞**：经典 Windows 四格徽标（带曲线飘扬感）
- **搜索 🔍**：WP7 风格放大镜（圆圈右上、柄左下），**单击唤起超级小爱，长按识屏**
- **最近 ▣**：保持系统原生，靠右角落
- **输入法切换 ⌨**：键盘弹出时出现在最左角落，点击弹出输入法选择菜单

## 功能特性

| 功能 | 说明 |
|------|------|
| 🎨 **WP7 图标** | 返回 / 主页 / 搜索 三个按键重绘为 Windows Phone 7 风格矢量图标（纯 Canvas 代码绘制，无图片资源） |
| 🔍 **独立搜索键** | 一个真正独立的导航键（非伪装），单击唤起超级小爱语音助手，长按触发识屏 |
| ⚖️ **对称布局** | 五键布局左右对称：`输入法(.5W) 返回(1W) [留白] 搜索(1W) 最近(.5W)`，主页始终居中 |
| 🔄 **横竖屏一致** | 横屏与竖屏行为统一，键盘弹出时均显示输入法切换键 |
| ⚙️ **可视化设置** | 模块 App 内可开关各项功能，支持一键重启 SystemUI |
| 🎯 **零侵入** | 仅 Hook 运行时行为，不动 APK、不替换资源文件，卸载后完全还原 |

**可配置项**（模块 App 内）：

| 选项 | 默认 | 说明 |
|------|------|------|
| 左侧切换输入法 | 开 | 键盘弹出时在最左侧显示输入法切换键 |
| 任务键搜索键 | 开 | 启用 WP7 搜索键（相应地最近键靠右对齐） |

## 环境要求

| 项 | 要求 |
|----|------|
| **设备** | 需 **已 Root**（Magisk / KernelSU） |
| **框架** | **LSPosed**（Zygisk 版） |
| **系统** | HyperOS 3 / Android 16（已在 Redmi K70 Ultra，2407FRK8EC 验证）<br>其他 HyperOS 机型可能可用，见[常见问题](#常见问题) |
| **作用域** | `com.android.systemui` |

> ⚠️ **不适用于**：手势导航模式（本模块针对三键导航栏设计）、未 Root 设备、非 HyperOS 的 AOSP 系统（未测试）。

## 安装教程

### 1. 下载 APK

前往 [**Releases 页面**](https://github.com/bookgeek99/WP7NavBar/releases/latest) 下载最新版 `WP7NavBar-x.x.apk`。

> 建议从 Releases 下载**已签名**的正式包，而非源码自行构建的调试包。

### 2. 安装 APK

用文件管理器点击安装，或：

```bash
adb install WP7NavBar-2.0.apk
```

### 3. 在 LSPosed 中启用

1. 打开 **LSPosed** 管理器
2. 进入 **模块** 页，找到 **WP7 NavBar**，打开开关
3. 点进模块详情，**勾选作用域**：`系统界面 (com.android.systemui)`
4. **重启 SystemUI**（见下一步）或直接重启手机

### 4. 重启 SystemUI

三种任选其一：

- **模块 App 内**：点「重启 SystemUI」按钮（推荐）
- **命令行**：`adb shell su -c 'pkill -f com.android.systemui'`
- **直接重启手机**（最稳妥）

重启后，导航栏图标即变为 WP7 风格。

## 使用说明

### 导航键操作

| 操作 | 效果 |
|------|------|
| 单击 **返回 ◁** | 返回上一级（系统原生行为） |
| 单击 **主页 ⊞** | 回到桌面（系统原生行为） |
| 单击 **搜索 🔍** | 唤起**超级小爱**（语音助手） |
| 长按 **搜索 🔍** | 触发**识屏** |
| 单击 **最近 ▣** | 打开最近任务（系统原生行为） |
| 单击 **输入法 ⌨** | 弹出输入法选择菜单 |

### 修改设置

打开 **WP7 NavBar** App（桌面图标），开关对应功能即可。**设置修改后需要点「重启 SystemUI」生效。**

## 常见问题

<details>
<summary><b>Q：安装后图标没变化？</b></summary>

依次检查：
1. LSPosed 里模块是否**已启用**
2. **作用域是否勾选** `com.android.systemui`（最容易漏）
3. 是否**重启过 SystemUI**（改完设置 / 首次启用都需重启）
4. 是否处于**三键导航**模式（手势模式无效）

可在终端查看日志确认：
```bash
adb logcat -d | grep WP7NavBar
```
看到 `replaced BACK/HOME icon` 即为生效。
</details>

<details>
<summary><b>Q：设置改了没反应？</b></summary>

设置通过跨进程读取，改动后**必须点 App 内「重启 SystemUI」按钮**（或命令行 `pkill -f com.android.systemui`）才会生效。
</details>

<details>
<summary><b>Q：搜索键点击没反应 / 唤起不了小爱？</b></summary>

本模块通过 `ACTION_ASSIST` 唤起超级小爱，需设备**已安装小米超级小爱**并设为默认语音助手。若未安装或使用其他助手，行为可能不同。
</details>

<details>
<summary><b>Q：其他小米 / 红米机型能用吗？</b></summary>

本模块**仅在 Redmi K70 Ultra + HyperOS 3.0.307.0 上实测通过**。由于依赖 HyperOS 特定的类名、资源 ID 与布局结构，其他机型 / 版本可能失效甚至导致 SystemUI 异常（LSPosed 作用域内一般不会导致系统无法启动，但仍请谨慎）。

**风险自负**，使用前建议先备份重要数据。
</details>

<details>
<summary><b>Q：会不会影响系统稳定性？</b></summary>

本模块**只替换图标绘制内容**，保留系统原生的点击、涟漪、缩放、暗色适配等全部逻辑。布局调整也仅复用系统原生容器。理论上不影响稳定性。若出现问题，关闭模块开关并重启 SystemUI 即可立即恢复。
</details>

<details>
<summary><b>Q：升级新版本需要先卸载吗？</b></summary>

不需要。所有版本使用**同一签名**，直接覆盖安装即可。设置会保留。
</details>

## 卸载还原

1. 在 LSPosed 中**关闭模块开关**（或直接卸载 APK）
2. **重启 SystemUI** 或重启手机

导航栏立即恢复系统原生样式。**本模块不修改任何系统文件，卸载无残留。**

## 从源码构建

### 标准环境

```bash
# 需要 JDK 17、Android SDK（platforms;android-36 + build-tools 36.0.0）
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/WP7NavBar-<版本>.apk
```

> **自动发布**：推送 `v*` 标签（如 `git tag v2.0 && git push --tags`）会触发 GitHub Actions 自动构建并创建 Release。

### arm64 容器 / 特殊环境

本项目在 **arm64 Ubuntu 容器**中开发，与常规桌面环境有以下差异：

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17 | 容器 openjdk-17 |
| Android SDK | `/root/android-sdk` | 仅 `platforms;android-36` + `build-tools 36.0.0` |
| Gradle | 8.1 | 系统版 `/opt/gradle/gradle-8.1`（非 wrapper） |
| AGP | 8.1.0 | 与 SDK 36 配套 |
| aapt2 | 2.19 | 原生 arm64 `/opt/aapt2-native/aapt2` |
| LSPosed API | 82 | `compileOnly`，不打包进 APK |

**关键**：arm64 容器需在 `gradle.properties` 指定原生 aapt2：
```properties
android.aapt2FromMavenOverride=/opt/aapt2-native/aapt2
```
> ⚠️ **推送到常规 x86_64 环境或 CI 前请删除该行**（CI 工作流已自动 `sed` 移除）。

构建命令：

```bash
export PATH=/opt/gradle/gradle-8.1/bin:$PATH
export ANDROID_HOME=/root/android-sdk
gradle assembleRelease
```

### 签名配置

发布签名配置在 `app/build.gradle` 的 `signingConfigs.release`：

- 默认读取仓库内 `keystore/wp7-release.jks`
- 密码优先读环境变量，缺省用内置值：

| 环境变量 | 含义 |
|----------|------|
| `WP7_KEYSTORE_PASSWORD` | keystore 密码 |
| `WP7_KEY_PASSWORD` | key 密码 |
| `WP7_KEY_ALIAS` | key 别名 |

> 若你 fork 本仓库用于自己的发布，**请替换为自己的 keystore 并重新设置密码**。

## 项目结构

```
wp7-navbar/
├── .github/workflows/release.yml   # CI：tag 触发构建 + 发布 Release
├── keystore/wp7-release.jks        # 发布签名
├── app/
│   ├── build.gradle                # compileSdk 36 / minSdk 26 / 签名配置
│   └── src/main/
│       ├── AndroidManifest.xml     # xposed 模块声明 + 作用域
│       ├── assets/xposed_init      # LSPosed 入口类声明
│       ├── res/                    # 图标、主题、颜色
│       └── java/com/wp7/navbar/
│           ├── Wp7NavbarHook.java              # Xposed 入口：挂载 hook + 生命周期分发
│           ├── MainActivity.java               # 模块设置界面（Material 风格，纯代码构建）
│           ├── NavigationBarController.java    # 方向状态机（PORTRAIT / LANDSCAPE）
│           ├── PortraitNavigationController.java   # 竖屏逻辑
│           ├── LandscapeNavigationController.java  # 横屏逻辑
│           ├── SearchButtonController.java     # Search 键布局注入 + 颜色对齐 + AI 唤起
│           ├── SearchContextualButtonFactory.java  # 独立 Search ContextualButton 构建
│           ├── Wp7IconController.java          # Back/Home/Search 图标替换
│           ├── Wp7IconDrawable.java            # WP7 图标矢量绘制（纯 Canvas）
│           ├── SettingsStore.java              # 设置持久化（SharedPreferences）
│           ├── Wp7SettingsProvider.java        # 跨进程设置读取（ContentProvider）
│           ├── SystemUiIds.java                # SystemUI 资源 ID 集中管理
│           ├── SystemUiReflection.java         # 反射工具集中类
│           └── Ui.java                         # UI 工具（dp 转换等）
├── docs/DEV_LOG.md                 # 开发日志
└── *_REPORT.md                     # 各阶段逆向分析报告
```

## 技术实现

### 核心机制

HyperOS 3 的导航栏按键由 `NavigationBarInflaterView.inflateButtons(...)` 创建，再经 `KeyButtonView.setImageDrawable(Drawable)` 设置图标。

**两个关键坑**：

1. `inflateButtons` 执行时 `KeyButtonView` 的 drawable **尚未设置**（为 null），不能在此处 hook 替换。
2. `KeyButtonView.setImageDrawable(Drawable)` 会把传入的 Drawable **强转成 `KeyButtonDrawable`**，直接传自定义 Drawable 会 `ClassCastException`。

### 图标替换方案

**Hook `KeyButtonView.setImageDrawable` 的 after**：

- 此时系统已把 `KeyButtonDrawable` 设置好（`getDrawable()` 非空）
- 通过 `KeyButtonView.mCode`（`KEYCODE_BACK=4` / `KEYCODE_HOME=3`）判断按键类型
- **反射替换 `KeyButtonDrawable.mState.mChildState`** 为 `Wp7IconDrawable` 的 ConstantState

这样**保留系统自带的暗色强度、颜色、涟漪、缩放等全部逻辑**，只替换绘制内容 —— 体验与换图标前无差异。

### 搜索键实现

搜索键是一个**独立的 ContextualButton**（非 ime_switcher 伪装）：

- 通过 `getDefaultLayout` 注入 `wp7search` token，由 `inflateButtons` 创建独立按键
- 颜色对齐：读取 back/home 的 `mLightColor` / `mDarkColor` / `mDarkIntensity`，用 `ArgbEvaluator` 算出实际显示色
- 布局：置于 `ends_group`，通过 token 权重实现左右对称

> 详细的逆向分析见仓库根目录的 `*_REPORT.md`。

## 开发者文档

仓库根目录包含各阶段的逆向分析报告，供二次开发参考：

| 文件 | 内容 |
|------|------|
| `2C_STATUS_REPORT.md` | 2C 阶段状态总览 |
| `2C_LAYOUT_MECHANISM_REPORT.md` | Search 布局权重机制分析 |
| `2C4_LAYOUT_OVERLAP_REPORT.md` | ends_group / center_group 叠层结构分析 |
| `GROUP_MAPPING_REPORT.md` | 导航栏分组映射逆向 |
| `CONTEXTUAL_BUTTON_REPORT.md` | ContextualButton 机制研究 |
| `DECOMPILE_INFLATE_ANALYSIS.md` | inflateButtons 反编译分析 |
| `LANDSCAPE_PROBE_REPORT.md` | 横屏结构探测报告 |
| `docs/DEV_LOG.md` | 开发日志 |

## 更新日志

见 [Releases](https://github.com/bookgeek99/WP7NavBar/releases)。版本历史简述：

- **v2.0** — 独立搜索键、五键对称布局、横竖屏一致、颜色对齐、去圆角留白
- **v1.0** — 基础 WP7 图标替换（返回 / 主页）

## License

[MIT](LICENSE) — 仅供个人学习与逆向研究使用。请勿用于商业用途。
使用本模块造成的任何后果由使用者自行承担。