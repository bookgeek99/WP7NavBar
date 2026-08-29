# WP7 NavBar 开发日志

> HyperOS 3（K70 Ultra / Android 16）三键导航栏图标定制为 WP7 风格的 LSPosed 模块

## 项目状态总览
| 阶段 | 状态 | 备注 |
|------|------|------|
| 项目接手 | ✅ 完成 | 接手前 agent 未跑通的成果 |
| 根源定位 | ✅ 完成 | 反编译 SystemUI 找到真实方法 |
| Hook 方案 | ✅ 完成 | hook `KeyButtonView.setImageDrawable` |
| 图标绘制 | ✅ 完成 | WP7 四格徽标（飘扬感）+ 细线返回箭头 |
| 真机验证 | ✅ 完成 | 返回/主页图标已成功替换 |
| 构建环境 | ✅ 完成 | arm64 容器 AGP8.1/Gradle8.1/SDK36 |

---

## 一、项目接手（2026-08-30）

接手前 agent 未跑通的成果，核心卡点：**Hook 挂上但回调不触发**。用户提供了需求文档 + 源码 zip。

## 二、根源定位：反编译 SystemUI

拉取设备 `/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk`（52MB）反编译，用 apktool decode 到 smali，确认：

**关键结论**：
- 之前 hook 的方法名**根本不存在**：
  - `NavigationBarInflaterView.inflateView` / `inflateButton` → 实际是 `inflateButtons`
  - `KeyButtonView.onFinishInflate` → 不存在
  - 因此 `hookAllMethods` 静默失败。
- 真实的导航键创建/设置图标入口：
  - `NavigationBarInflaterView.inflateButtons([String[], ViewGroup, Z, Z])`
  - `KeyButtonView.setImageDrawable(Drawable)`（第1789行）
  - `MiuiKeyButtonView` 继承自 `KeyButtonView`。

**类路径（HyperOS 3 / navigationbar 有 views 子包）**：
`com.android.systemui.navigationbar.views.NavigationBarInflaterView`
`com.android.systemui.navigationbar.views.buttons.KeyButtonView` / `MiuiKeyButtonView`

## 三、技术方案论证与迭代

### v1：hook `inflateButtons`
结果：日志显示 `BACK/HOME getDrawable null, skip` —— inflate 时 drawable 尚未设置，图标没变。

### v2（最终方案）：hook `KeyButtonView.setImageDrawable` 的 after
- 系统设置好 KeyButtonDrawable 后，反射替换其 `mState.mChildState` 为 `Wp7IconDrawable` 的 ConstantState。
- **关键坑**：`KeyButtonView.setImageDrawable` 会把传入 Drawable **check-cast 成 `KeyButtonDrawable`**，直接 set 自定义 Drawable 会 ClassCastException。必须走"替换 childState"路径。
- 这样保留系统暗色/颜色/ripple/缩放逻辑，只换绘制内容 → 用户体验与换图标前一致。

### hook 逻辑
安装包 hook `setImageDrawable` after → 用 `mCode`（KEYCODE_BACK=4/KEYCODE_HOME=3）+ `contentDescription` 兜底判断类型 → 反射替换 childState。

## 四、图标绘制过程

用户需求：返回=细线左箭头；主页=经典 Win7 四格徽标；最近不换。

| 版本 | 形态 | 结果 |
|------|------|------|
| 初始 | 波浪横条/四格平行四边形 | 偏右下、形状不对 |
| Kimi 版 | 规则平行四边形 | 用户反馈不对 |
| GPT 版 | 贝塞尔曲线不规则四格 | 不对 |
| 参考图版 | 按图精确绘制不规则四格 | 不对 |
| **定稿（飘扬感版）** | 4 个左倾平行四边形 + 上下边缘曲线拖拽 | ✅ 用户确认"都对" |

**最终参数**（`drawWin7Logo`）：
- `half = s*0.30`，`hh = s*0.28`（半宽/半高）
- `gap = s*0.09`（格间隙，加大一倍）
- `lean = s*0.06`（倾斜量）
- `curve = s*0.07`（曲线拖拽量）
- 返回箭头 & home 图标整体缩小到 **2/3**（`s * 2f/3f`）

## 五、构建环境（arm64 容器）
| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17 | 容器 openjdk-17 |
| Android SDK | `/root/android-sdk` | platforms;android-36 + build-tools 36.0.0 |
| Gradle | 8.1 | 系统版，非 wrapper |
| AGP | 8.1.0 | 与 SDK36 配套调整 |
| aapt2 | 2.19 | `/opt/aapt2-native/aapt2`（gradle.properties override） |
| LSPosed API | 82 | compileOnly |

**关键坑/教训**：
1. Android gradle 构建必须用 `android.aapt2FromMavenOverride=/opt/aapt2-native/aapt2`（放 gradle.properties 才生效）。
2. 增量构建 APK 大小不变（缓存）→ 需 `gradle clean assembleDebug` 强制重打包。
3. settings.gradle 的 `FAIL_ON_PROJECT_REPOS` 与根 build.gradle 的 `allprojects{repositories}` 冲突 → 移除根 build.gradle 的 allprojects 仓库块。

## 六、真机验证
- 设备：K70 Ultra（2407FRK8EC / rothko），Android 16，HyperOS 3.0.307.0，Magisk + LSPosed(Zygisk)。
- 模块 `com.wp7.navbar` 已安装，作用域 `com.android.systemui`。
- 重启 SystemUI（`pkill -f com.android.systemui`）。
- 日志确认：`WP7NavBar: replaced BACK icon` / `WP7NavBar: replaced HOME icon`。
- 用户确认图标效果正确（返回箭头缩小、home 四格徽标缝隙加大且正确）。

## 七、命令/工具备忘
- 反编译：`apk_reverse` 包（apktool decode 到 smali 最稳）
- 拉日志：`logcat -d | grep WP7NavBar`，或 LSPosed 日志 zip（`/data/adb/lspd/log/modules_*.log`）
- 重启 SystemUI：`pkill -f com.android.systemui`
- 构建：`export PATH=/opt/gradle/gradle-8.1/bin:$PATH; gradle clean assembleDebug --no-daemon`

## 八、遗留 / 后续计划
- 未来需求：在导航栏两端空白处添加功能按钮（如输入法切换按钮），技术上是往 NavigationBar 视图树插入新 View + 注册点击，可复用 `inflateButtons` hook 基础设施。本次未做。
