# WP7 NavBar — LSPosed 模块

把 **HyperOS 3（K70 Ultra / Android 16）** 三键导航栏的 **【返回】【主页】** 图标定制为 **Windows Phone 7 风格**，**【最近】** 保持原样。

纯 LSPosed Hook，**不修改 SystemUI APK、不替换系统资源**。

## 功能效果
| 按键 | WP7 风格 | 状态 |
|------|---------|------|
| 返回 (Back) | 细线左箭头 | ✅ 已替换 |
| 主页 (Home) | 经典 Windows 四格徽标（飘扬感、带曲线） | ✅ 已替换 |
| 最近 (Recents) | 保持原样 | — |

> 后续计划：在导航栏两端空白处添加功能按钮（如输入法切换按钮）。

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
│           ├── MainActivity.java        # 模块状态页（LSPosed 确认用）
│           ├── Wp7NavbarHook.java       # 核心 Hook（setImageDrawable）
│           └── Wp7IconDrawable.java     # WP7 图标：纯代码 Canvas 矢量绘制
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