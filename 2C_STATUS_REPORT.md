# WP7NavBar 第二阶段 2C —— 独立 Search 键实现现状报告

> 设备：Redmi K70 Ultra（rothko），HyperOS 3.0.307，Android 16。
> 本文档面向架构审阅：**只陈述事实、证据、当前阻塞点**，不做推断（推断部分单列）。
> 生成时间：2026-10-05

---

## 1. 已完成并验证的部分

### 2C-1（已提交 `c8a4abc`）：独立 Search ContextualButton 链路打通

链路（实测日志确证）：
```
wp7search token
  → inflateButtons hook 识别
  → inflate custom_key (0x7f0e00ab)
  → KeyButtonView
  → setId(WP7_SEARCH_ID = 0x7f1f0001)
  → 调原系统 addToDispatchers(view)
  → mButtonDispatchers[WP7_SEARCH_ID] = ContextualButton
  → ContextualButton.mViews = [horizontal View, vertical View]
```

实测：`mButtonDispatchers` size=8，含 `0x7f1f0001=ContextualButton mViews=2`；Recent 未受影响；Back/Home/Recent 全正常。

### 2C-2（已提交 `4442ffa`）：图标接入

在 `setButtonDispatchers` after，取 dispatcher（`ContextualButton`），调
`dispatcher.setImageDrawable(createSearchKeyButtonDrawable(...))`，
系统 `ButtonDispatcher.addView` 的 `ButtonInterface` 分支自动对 mViews 下发图标。

实测：横屏原空白位出现 WP7 放大镜；竖屏也出现；可点击；其他功能正常。

### 2C-3（**未提交**）：点击 / 长按

在 `applyWp7IconAfterBind` 后调 `bindSearchListeners(dispatcher, template)`：
`dispatcher.setOnClickListener`（单击→`invokeAssist(v,5,false)`=语音）、
`dispatcher.setOnLongClickListener`（长按→`invokeAssist(v,6,true)`=识屏）。

实测：点击触发小爱语音；长按触发识屏。（用户确认"功能正常"）

### 退役旧 masquerade 机制（**未提交**）

- `SearchButtonController.injectLayouts`：右段**不再注入 `ime_switcher`**；移除 `right` token
  （因系统把 `right` 重映射为 `menu_ime`，会多出 menu 键）；只注入 `wp7search[1W]`。
  左段 `ime_switcher`（左侧切输入法）**保留**。
- `PortraitNavigationController`：删除 mark `ime_switcher` 全部逻辑，仅保留 `collectRefColor`。
- `Wp7NavbarHook`：`onSetImageDrawableBefore` 退役；`onImeSwitcherClick` 简化。
- `SearchButtonController`：删除 `setupSearchButton`/`onSetImageDrawableBefore`/`markedViews`/`clickListeners` 等。

实测：旧 masquerade 放大镜消失；新独立放大镜保留；功能正常。

---

## 2. 🔴 当前阻塞点：新 Search 键宽度 / 位置与邻居不一致

### 2.1 实测 View 树（竖屏 horizontal，一次横屏旋转后的 dump）

```
LinearLayout id=ends_group rect=[0,0,1030,153]
  ReverseRelativeLayout rect=[0,0,119,153]        ← ime_switcher 包装层（GONE）
    KeyButtonView id=ime_switcher [0,0,119,153]
  ReverseRelativeLayout rect=[119,0,357,153]      ← Back 包装层，宽 238
    KeyButtonView id=back [21,0,216,153]          宽 195
  Space id=NO_ID rect=[357,0,596,0]              宽 239（INVISIBLE 占位）
  ReverseRelativeLayout rect=[596,0,791,153]      ← Search 包装层，宽 195  ← 应为 238
    KeyButtonView id=0x7f1f0001 [32,0,162,153]    宽 130  ← 应为 195
  ReverseRelativeLayout rect=[791,0,1030,153]     ← Recent 包装层，宽 239
    KeyButtonView id=recent_apps [22,0,217,153]   宽 195
```

### 2.2 数值对比表

| 键 | 外层包装层 rect | 外层宽 | 内层 KeyButtonView rect | 内层宽 |
|---|---|---|---|---|
| Back | `[119,0,357,153]` | **238** | `[21,0,216,153]` | **195** |
| **Search** | `[596,0,791,153]` | **195** ❌ | `[32,0,162,153]` | **130** ❌ |
| Recent | `[791,0,1030,153]` | **239** | `[22,0,217,153]` | **195** |

### 2.3 相关 dimen（反编译 `dimens.xml`）

```
navigation_key_width      = 60.0dp   （× 密度 3.25 ≈ 195px）
navigation_key_padding    = 0.0dp
navigation_side_padding   = 36.0dp   （× 3.25 ≈ 117px，实测 130）
```

- Back / Recent 内层 = **195px = navigation_key_width** ✅
- Search 内层 = **130px = navigation_side_padding**（custom_key 声明值）❌

### 2.4 现象

- 用户肉眼：新放大镜**偏左**、宽度与三大金刚**不一致**，颜色也不一致。
- 横竖屏现象相同。

---

## 3. 反编译得到的「系统对 [xW] token 的真实处理」（关键）

`NavigationBarInflaterView.inflateButtons` smali，对带 `W`/`A` 后缀的 token（`goto_7` 段）：

**步骤 1 — 包装（行 1800~1930）**：
```java
ReverseRelativeLayout wrapper = new ReverseRelativeLayout(context);
FrameLayout.LayoutParams flp =
        new FrameLayout.LayoutParams(originalView.getLayoutParams());  // ← 内层：复制原 LP
int gravity = ...;   // "WC" 结尾 → 0x11(CENTER)；"C" 结尾 → 0x10；否则按方向
wrapper.setDefaultGravity(gravity);
wrapper.setGravity(gravity);
wrapper.addView(originalView, flp);
```

**步骤 2 — 外层 LP（行 1930~1990，:cond_1b 分支）**：
```java
// 仅当 token 含数字+W（如 "1W"）时走此分支
float w = Float.parseFloat(数字部分);          // "1W" → 1.0
LinearLayout.LayoutParams outer =
        new LinearLayout.LayoutParams(0, -1, w);   // ← width=0, height=MATCH, weight=w
wrapper.setLayoutParams(outer);
```

**结论（推断）**：
- 系统的外层包装层是 **`width=0, weight=w`**——**完全交给 LinearLayout 的 weight 分配**，
  所以宽度由「父容器可用宽度 ÷ 各键 weight 之和」动态决定，**不是固定值**。
- 系统的内层 KeyButtonView 宽度 = **layout 声明值**（back.xml = `navigation_key_width` = 195）。

---

## 4. 当前代码实现（与系统做法的差异）

`SearchContextualButtonFactory.inflateSearchButton` 当前：

1. `inflate(custom_key, parent, false)` → btn（LP 是 `ViewGroup.LayoutParams`，width=`navigation_side_padding`）
2. `btn.setId(WP7_SEARCH_ID)`
3. **包装**：`wrapWithReverseRelative(...)` 反射构造 `ReverseRelativeLayout`：
   - 内层 `flp = new FrameLayout.LayoutParams(btn.getLayoutParams())` ← **width=130（side_padding）** ❌
   - gravity = 0x11
   - **外层** `new LinearLayout.LayoutParams(w, MATCH_PARENT)`，`w` = 邻居 LP.width 或 `resolveKeyWidth()`（实测 195）❌
4. `parent.addView(wrapper, outerLp)`

**与系统的差异（推断为阻塞根因）**：
- **内层宽度**：系统=195（key_width），我们=130（side_padding）
- **外层宽度**：系统=`width=0 + weight`，我们=固定 195

---

## 5. 待确认 / 需求助的点

1. **内层 LP** 是否应直接改为 `navigation_key_width`（195）？（即覆盖 custom_key 的 side_padding）
2. **外层 LP** 是否应改为 `LinearLayout.LayoutParams(0, MATCH_PARENT, weight)`（weight 来自 token 的 `1W`）？
3. 若外层用 `width=0+weight`，`wp7search[1W]` 的 weight=1 是否与 Back/Recent 的 weight 冲突
   （Back=`back[1WC]` weight 也是 1）？是否应改用不同 weight（如 `wp7search[.5W]`）？
4. `Space`（`[357,0,596,0]`，宽 239，INVISIBLE）是什么？是否影响 Search 的可用宽度分配？

---

## 6. 当前代码位置

- `SearchContextualButtonFactory.java`：`inflateSearchButton`（包装 + LP）、`applyWp7IconAfterBind`（图标）、`bindSearchListeners`（点击）
- `SearchButtonController.java`：`injectLayouts`（注入 wp7search token）、`createSearchKeyButtonDrawable`（图标构造）、`invokeAssist`（唤起助手）、`collectRefColor`（参照色）
- `Wp7NavbarHook.java`：`hookSearchContextualButton`（inflateButtons / setButtonDispatchers / onFinishInflate）
- `PortraitNavigationController.java`：仅 `collectRefColor`

## 7. 未提交改动清单

```
M PortraitNavigationController.java    （精简为只收集参照色）
M SearchButtonController.java          （退役旧 masquerade + injectLayouts 改造）
M SearchContextualButtonFactory.java   （2C-3 点击 + 包装 + LP）
M Wp7NavbarHook.java                   （退役 onSetImageDrawableBefore + onImeSwitcherClick 简化）
```

未提交 commit，等待审阅后决定下一步。

---

## 8. 附：颜色不一致（次要问题，暂未处理）

用户反馈新放大镜颜色与 Back/Home **不一致**（偏淡）。
`createSearchKeyButtonDrawable` 用 `refColorLoaded` 缓存从 Back/Home 读取的 `mLightColor`/`mDarkColor`；
`refColorLoaded=false` 时回退默认（0xFFFFFFFF / 0xFFBFBFBF）。
`collectRefColor` 现在由 `PortraitNavigationController.apply()` 调用，可能晚于
`applyWp7IconAfterBind`（在 setButtonDispatchers after）→ 取色时机可能早于参照色收集。
待布局问题解决后单独处理。
