# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [2.0] - 2026-10-05

第二阶段：独立搜索键 + 布局与颜色重构。

### 新增
- **独立搜索键（放大镜）**：不再复用系统 `ime_switcher` 伪装，而是创建真正独立的 `ContextualButton`。
  - 单击：唤起超级小爱语音助手（`ACTION_ASSIST`）
  - 长按：触发屏幕识别
- **五键对称布局**：`输入法(.5W) 返回(1W) [留白] 搜索(1W) 最近(.5W)`，主页始终居中。
- **横屏输入法切换键**：横屏呼出键盘时同样显示输入法切换键（此前仅竖屏有）。
- **去圆角留白**：移除导航栏根容器为避开屏幕圆角而保留的边距（竖屏左右 / 横屏上下），使边角键更贴边。
- **搜索键颜色对齐**：读取 back/home 的 `mLightColor` / `mDarkColor` / `mDarkIntensity`，
  用 `ArgbEvaluator` 计算实际显示色，使放大镜颜色与其它键完全一致。

### 变更
- 横竖屏行为统一：布局串与可见性控制在两个方向下逻辑一致。
- 设置修改后需重启 SystemUI 生效（App 内提供一键按钮）。

### 修复
- 修复搜索键刷新时序问题：图标刷新可能早于 `dispatcher.mViews` 绑定完成，
  导致颜色停留在默认兜底色（偏浅）；改为延迟重试（最多 3 次）。

### 移除
- 删除旧 masquerade（`ime_switcher` 伪装搜索键）相关代码。
- 清理死代码：`removeImeSwitchers` / `analyzeLayoutString` / `findViewGroupById`。

## [1.0] - 2026-08-29

第一阶段：基础图标替换。

### 新增
- **返回键**：替换为 WP7 风格细线左箭头。
- **主页键**：替换为经典 Windows 四格徽标（带曲线飘扬感）。
- 反射替换 `KeyButtonDrawable.mState.mChildState`，保留系统原生的点击、涟漪、缩放与暗色适配逻辑。
- 模块设置界面（Material 风格，纯代码构建）。
- 跨进程设置读取（ContentProvider）。
