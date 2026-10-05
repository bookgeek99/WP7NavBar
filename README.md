# WP7 NavBar — LSPosed 模块
把 **HyperOS 3（K70 Ultra / Android 16）** 三键导航栏定制为 **Windows Phone 7 风格**：
【返回】【主页】图标换成 WP7 样式，并新增一个**任务键左侧的 WP7 风格搜索键**（放大镜）。
纯 LSPosed Hook，**不修改 SystemUI APK、不替换系统资源**。

## 横竖屏行为（第一阶段）
| 方向 | 布局 | 说明 |
|------|------|------|
| **竖屏** | Back + Home + **Search** | Search 由 ime_switcher 伪装；Recent 移到最右 |
| **横屏** | Back + Home + **Recent** | 横屏不注入 Search、不修改 Recent；仅对 HyperOS 布局缓存遗留的 ime_switcher 做清理/隐藏。Back/Home 仅替换 Drawable |

> 第一阶段目标（方案）：横屏彻底放手，优先修复"横屏多任务键失灵"。横屏 Search 留待第二阶段研究系统原生 ButtonDispatcher 机制后实现（Back+Home+Search+Recent 四个独立键）。
## 功能效果
| 按键 | WP7 风格 | 状态 |
|------|---------|------|
| 返回 (Back) | 细线左箭头 | ✅ 已替换 |
| 主页 (Home) | 经典 Windows 四格徽标（飘扬感、带曲线） | ✅ 已替换 |
| 最近 (Recents) | 保持原样 | ✅ 移至最右侧（搜索键开启时） |
| 搜索键 (放大镜) | WP7 风格，圆圈在右上、柄在左下 | ✅ 常驻显示，点击唤起超级小爱、长按识屏 |
| 左侧输入法切换 | ime_switcher | ✅ 可开关（IME 弹出时显示） |
### 搜索键行为
- **单击**：唤起超级小爱（语音对话）—— 发送 `ACTION_ASSIST` 到 `VoiceService`。
- **长按**：触发超级小爱识屏 —— 携带 `voice_assist_start_from_key=long_press_home_key`。
- 颜色、阴影与 back/home 对齐；做边缘淡化处理。
### 设置界面（模块 App）
打开模块 App 可配置：
- **左侧切换输入法**：控制左侧 ime_switcher 的注入。
- **任务键搜索键**：控制搜索键显示。开启时 recent 移到最右、注入搜索键；关闭时 recent 恢复系统默认位置与大小。

## 技术方案
HyperOS 3 的 `MiuiSystemUI`（`com.android.systemui` v16.03.251211.r）导航栏按键是通过
`NavigationBarInflaterView.inflateButtons(...)` 创建，再经 `KeyButtonView.setImageDrawable(Drawable)` 设置图标。

关键坑：
- `NavigationBarInflaterView.inflateButtons` 执行时 `KeyButtonView` 的 drawable **尚未设置**（为 null），因此不能在该处的 before/after hook 里替换。
- `KeyButtonView.setImageDrawable(Drawable)` 会把传入的 Drawable **强转成 `KeyButtonDrawable`**，直接 `setImageDrawable(自定义Drawable)` 会 `ClassCastException`。

因此采用 **Hook `KeyButtonView.setImageDrawable` 的 afterHookedMethod**：
- 此时系统已把 `KeyButtonDrawable` 设置好（`getDrawable()` 非空）。
- 通过 `KeyButtonView.mCode`（`KEYCODE_BACK=4` / `KEYCODE_HOME=3`）判断按键类型，`contentDescription` 兜底。
- **反射替换 `KeyButtonDrawable.mState.mChildState`** 为 `Wp7IconDrawable` 的 `ConstantState`。
- 这样保留系统自带的暗色强度、颜色、ripple、缩放等逻辑，只替换绘制内容 —— **体验和换图标前无差异**。

## 环境要求
- **设备**：Redmi K70 Ultra（2407FRK8EC / rothko），Android 16（SDK 36），HyperOS 3.0.307.0
- **已 root**（Magisk）+ **LSPosed**（Zygisk）
- 作用域勾选：`com.android.systemui`

## 构建环境说明
> ⚠️ 本项目在 **arm64 容器（Ubuntu）** 中构建，与常见桌面环境略有差异：
>
> | 组件 | 版本 | 说明 |
> |------|------|------|
> | JDK | 17 | 容器 openjdk-17 |
> | Android SDK | `/root/android-sdk` | 仅 **platforms;android-36** + build-tools **36.0.0** |
> | Gradle | 8.1 | 系统版 `/opt/gradle/gradle-8.1`（非 wrapper） |
> | AGP | 8.1.0 | 与 SDK 36 配套调整 |
> | aapt2 | 2.19 | 原生 arm64 `/opt/aapt2-native/aapt2` |
> | LSPosed API | 82 | `compileOnly`，不打包进 APK |
>
> **关键配置**：由于该容器仅下载了 `platforms;android-36`，且默认 AAPT2 运行失败，需在
> `gradle.properties` 中指定 `android.aapt2FromMavenOverride=/opt/aapt2-native/aapt2`。
> **推送到其它环境构建时，请删除这一行**（或改为本地正确的 aapt2 路径）。

### 构建命令（本容器）
```bash
export PATH=/opt/gradle/gradle-8.1/bin:$PATH
gradle clean assembleDebug --no-daemon
```

### 安装 & 生效
```bash
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/_wp7.apk
adb shell su -c 'pm install -r /data/local/tmp/_wp7.apk'
adb shell su -c 'pkill -f com.android.systemui'   # 重启 SystemUI
```

## 项目结构
```
wp7-navbar/
├── settings.gradle          # 仓库配置（阿里云镜像）
├── build.gradle             # 根构建脚本（AGP 8.1.0）
├── gradle.properties        # JVM/Android 配置 + aapt2 override
├── local.properties         # sdk.dir 指向 /root/android-sdk
├── app/                     # 模块
│   ├── build.gradle         # compileSdk 36 / minSdk 26 / targetSdk 36
│   └── src/main/
│       ├── AndroidManifest.xml          # xposed 模块声明 + 作用域
│       ├── assets/xposed_init           # LSPosed 入口
│       └── java/com/wp7/navbar/
│           ├── MainActivity.java        # 模块设置界面（Material 风格，动态构建）
│           ├── Wp7NavbarHook.java       # Xposed 纯入口：挂载 hook + 生命周期分发
│           ├── NavigationBarController.java      # orientation 状态机（PORTRAIT/LANDSCAPE）
│           ├── PortraitNavigationController.java  # 竖屏：Back/Home 图标 + Search 配置
│           ├── LandscapeNavigationController.java # 横屏：仅 Back/Home 图标，Recent 完全原生
│           ├── SearchButtonController.java        # 竖屏 Search（ime_switcher 伪装 + 布局注入）
│           ├── Wp7IconController.java   # Back/Home/Search WP7 图标替换（只改 drawable）
│           ├── SystemUiIds.java         # SystemUI 资源 ID 集中管理
│           ├── SystemUiReflection.java  # 反射工具集中类
│           ├── Wp7IconDrawable.java     # WP7 图标：纯代码 Canvas 矢量绘制
│           ├── SettingsStore.java       # 设置持久化（SharedPreferences）
│           ├── Wp7SettingsProvider.java # 跨进程设置读取（ContentProvider）
│           └── Ui.java                  # UI 工具（dp 转换等）
```

## 使用说明
1. 编译出 APK 并安装。
2. 在 LSPosed 模块列表启用，作用域勾选 `com.android.systemui`。
3. 重启 SystemUI（`adb shell pkill -f com.android.systemui`）或重启手机。
4. 返回/主页图标即变为 WP7 风格。

## 调试日志
```bash
adb logcat -d | grep WP7NavBar
```
- 成功替换会打印：`WP7NavBar: replaced BACK/HOME icon`

## License
仅供个人学习/逆向研究使用。