# WP7NavBar 第二阶段 2A —— 横屏导航栏结构逆向探测报告

> 设备：Redmi K70 Ultra（rothko），HyperOS 3.0.307，Android 16
> 基线提交：`c3c1bef`
> 探测版本：`wp7-navbar-probe5.apk`（只读探测，未修改任何功能）
> 探测日志前缀：`WP7NavBar: [LANDSCAPE_PROBE]`

---

## 0. 探测方法

- 在 `getDefaultLayout` after 记录原始返回串
- 在 `inflateLayout` before/after 记录布局串（含 BEFORE_CLEAN）
- 在 `onConfigurationChanged`（方向变更）延迟 300ms 后 dump：
  - `NavigationBarView` 完整 View 树（class / id / resName / vis / rect / clickable / cd / mCode）
  - `NavigationBarView` 反射字段（找 ButtonDispatcher / ContextualButtonGroup）
  - `NavigationBarInflaterView` 反射字段（layout / button / orientation / dispatcher 相关）
- 全程**只读**，不修改任何 View / 字段 / 布局 / 点击。

---

## 1. 决定性发现：横屏不重新 inflateLayout

**时间线（实测）**：
```
10:43:01  竖屏启动 → getDefaultLayout 调用（PORTRAIT）
10:43:12  切横屏   → 仅 injectView/onConfigurationChanged，【无 getDefaultLayout、无 inflateLayout】
10:43:13  回竖屏   → 仅 onConfigurationChanged
10:43:18  再切横屏 → 【仍无 getDefaultLayout、无 inflateLayout】
```

**结论**：
> **HyperOS 3 旋转时 `NavigationBarInflaterView` 不重新 `getDefaultLayout` / `inflateLayout`。**
> 竖屏与横屏**复用同一个 View 实例与同一个 `mCurrentLayout` 字符串**。
> 旋转只触发 `NavigationBarView.onConfigurationChanged`，内部切换 `mHorizontal` / `mVertical` 的可见性并重新布局。

这直接解释了第一阶段所有"旋转后错乱"的根因，也证明 `removeImeSwitchers()` 的必要性（它作用在下一次 inflate，而非旋转当下）。

---

## 2. 布局串

### 2.1 `getDefaultLayout` 返回值（PORTRAIT）
```
left[.5W],back[1WC];home;recent[1WC],right[.5W]
```
（注：此为注入后观察值，SystemUI 原生竖屏串结构相同；左段 `left+back`，中段 `home`，右段 `recent+right`。）

### 2.2 `NavigationBarInflaterView.mCurrentLayout`（横屏时仍为）
```
ime_switcher[.5W],back[1WC];home;ime_switcher[1W],recent[.3WC]
```
> 这是**被 WP7NavBar 竖屏注入过的串**，旋转到横屏后**未被替换**（因不重调 getDefaultLayout）。
> 右段 `ime_switcher[1W],recent[.3WC]` 即竖屏 Search（伪装 ime_switcher）。

### 2.3 段结构解析（三段制，`;` 分隔）
| segment | 竖屏（horizontal） | 横屏（vertical） |
|---|---|---|
| segment[0] | 左段：`left[.5W], back[1WC]` | 顶段：同样内容，竖排 |
| segment[1] | 中段：`home` | 中段：`home` |
| segment[2] | 右段：`ime_switcher[1W], recent[.3WC]` | 底段：同样内容，竖排 |

---

## 3. 横屏完整 View 树（实测）

```
NavigationBarView                          id=navigation_bar_view(0x7f0b0843) rect=[0,0,153,1220]
└─ NavigationBarInflaterView               id=navigation_inflater(0x7f0b0845)
   ├─ FrameLayout horizontal               id=horizontal(0x7f0b056b)  vis=GONE   ← 竖屏用
   │   └─ NearestTouchFrame nav_buttons
   │       └─ LinearLayout ends_group       (竖排时的容器)
   └─ FrameLayout vertical                 id=vertical(0x7f0b0d43)    vis=VISIBLE ← 横屏用
       └─ NearestTouchFrame nav_buttons     [0,26,153,1056]
           ├─ ReverseLinearLayout ends_group(0x7f0b0443) [0,0,153,1030]  ← 竖排
           │   ├─ RR`L [0,0,153,81]    → KeyButtonView recent_apps  vis=VISIBLE mCode=82   ← Recent（顶部）
           │   ├─ RR`L [0,81,153,352]  → KeyButtonView ime_switcher vis=GONE    mCode=0
           │   ├─ Space                vis=INVISIBLE [0,352,0,623]
           │   ├─ RR`L [0,623,153,894] → KeyButtonView back         vis=VISIBLE mCode=4    ← Back
           │   └─ RR`L [0,894,153,1030]→ KeyButtonView ime_switcher vis=GONE    mCode=0
           └─ ReverseLinearLayout center_group(0x7f0b0270) [0,0,153,1030]
               └─ KeyButtonView home(0x7f0b0565) vis=VISIBLE mCode=3  [0,417,153,612]   ← Home（居中）
```

**横屏从顶到底可视顺序**：`Recent`（顶） → `ime_switcher(GONE)` → `[space]` → `Back` → `ime_switcher(GONE)`；`Home` 居中。

> 注意：`horizontal` 与 `vertical` 是**同一个 InflaterView 下的两个互斥容器**，由 `mIsVertical` 决定显隐。

---

## 4. Recent View（实测）

```
RECENT VIEW FOUND:
  class = com.android.systemui.navigationbar.views.buttons.KeyButtonView
  id    = recent_apps (0x7f0b09d7)
  mCode = 82
  cd    = "任务键"
  parent= ReverseLinearLayout$ReverseRelativeLayout
```

- **两个实例**：竖屏一个 `[0,0,82,153]`，横屏一个 `[0,0,153,81]`（顶部）。
- Recent **未被 WP7NavBar Hook**，行为完全原生。

---

## 5. `mButtonDispatchers`（SparseArray，完整）

| key | 资源名 | 类 | mId | mViews |
|---|---|---|---|---|
| `0x7f0b0039` | `accessibility_button` | **ContextualButton** | 2131427385 | 0 |
| `0x7f0b0153` | `back` | ButtonDispatcher | 2131427667 | **2**（竖/横） |
| `0x7f0b0565` | `home` | ButtonDispatcher | 2131428709 | **2** |
| `0x7f0b0568` | `home_handle` | ButtonDispatcher | 2131428712 | 0 |
| `0x7f0b05a3` | `ime_switcher` | **ContextualButton** | 2131428771 | **4** |
| `0x7f0b0751` | `menu_container` | **ContextualButtonGroup** | 2131429201 | 0 |
| `0x7f0b09d7` | `recent_apps` | ButtonDispatcher | 2131429847 | **2** |

### 5.1 重点：Recent Dispatcher
```
fieldName = mButtonDispatchers[0x7f0b09d7 "recent_apps"]
class     = ButtonDispatcher
mId       = 2131429847 (0x7f0b09d7)
mViews    = [0]=KeyButtonView{recent_apps VISIBLE [0,0,82,153]}   ← 竖屏
            [1]=KeyButtonView{recent_apps VISIBLE [0,0,153,81]}   ← 横屏
```
> Recent 用标准 `ButtonDispatcher`（**非 ContextualButton**），**未被我方触碰**。

### 5.2 重点：ime_switcher 是 ContextualButton
```
key   = 0x7f0b05a3 "ime_switcher"
class = com.android.systemui.navigationbar.views.buttons.ContextualButton
mId   = 2131428771
mViews= 4 个 KeyButtonView（impl 竖屏左+竖屏右+横屏上+横屏下，全 GONE）
```
> 竖屏 Search 就是把「右段的那 1 个 ContextualButton 的 KeyButtonView」改 Drawable + mCode=0 伪装出来的。

### 5.3 ContextualButtonGroup
```
fieldName = NavigationBarView.mContextualButtonGroup  (同时也在 mButtonDispatchers[0x7f0b0751 "menu_container"])
class     = ContextualButtonGroup
mId       = 2131429201
mViews    = 0（空容器）
内部      = 探测 mButtonMap/mContextualButtons 均为 null（字段名待确认）
```
> 这是系统为 `menu_ime` 等 contextual 键准备的容器，横屏原生时不显示。

---

## 6. `NavigationBarInflaterView` 关键字段（实测）

| 字段 | 类型 | 值 |
|---|---|---|
| `mButtonDispatchers` | SparseArray | 见第 5 节（7 项） |
| `mCurrentLayout` | String | `ime_switcher[.5W],back[1WC];home;ime_switcher[1W],recent[.3WC]` |
| `mHorizontal` | FrameLayout | `#7f0b056b`（竖屏容器，横屏 GONE） |
| `mVertical` | FrameLayout | `#7f0b0d43`（横屏容器，横屏 VISIBLE） |
| `mIsVertical` | boolean | `true`（横屏时） |
| `mLastLandscape` | View | `KeyButtonView recent_apps [0,0,153,81]` |
| `mLastPortrait` | View | `KeyButtonView recent_apps [0,0,82,153]` |
| `mLayoutInflater` | LayoutInflater | PhoneLayoutInflater |
| `mLandscapeInflater` | LayoutInflater | PhoneLayoutInflater |

> **重要**：`mLastLandscape` / `mLastPortrait` 都指向 **recent_apps**（说明 inflate 顺序里 recent 是最后 inflate 的键之一）。没有独立的 `mLandscape` 布局常量字段——横竖屏共用 `mCurrentLayout`。

---

## 7. 横屏 layout 是否可直接增加独立 token？

**分析**：
- 布局串是 `segment0;segment1;segment2` 三段，每段 `token[modifier],token[modifier]...`。
- token 名直接映射到 `mButtonDispatchers` 的 key（资源名），inflate 时按 name 查找 dispatcher。
- **可行方向**：在 `mCurrentLayout` 的底段（segment[2]）**插入一个新 token**，但该 token 名必须能在 `mButtonDispatchers` 里找到 dispatcher，否则 inflate 会失败。
- **结论**：不能凭空加 token。要么：
  1. **复用已有 ContextualButton**（如 `accessibility_button`，当前 mViews=0，空闲）→ 把它改名/改 id 指向新搜索键；
  2. **在 `mButtonDispatchers` 动态放入一个新 ButtonDispatcher**（key 用新资源 id），再在 layout 串加对应 token；
  3. **复用 `ime_switcher` 的 ContextualButton**，但让它变成真正的独立键（横屏时不再 GONE）。

---

## 8. 是否存在可安全增加独立 ButtonDispatcher 的位置

**是。** `mButtonDispatchers` 是 `SparseArray<ButtonDispatcher>`，运行时可 `put(newId, new ButtonDispatcher(newId, context))`，然后在 layout 串加 token `search[1WC]`。inflate 时 `NavigationBarInflaterView` 会按 name 找 dispatcher 并 inflate 对应 `KeyButtonView`。

**风险**：HyperOS 不重调 `inflateLayout`（第 1 节），所以**必须在首次 inflate 前（竖屏启动时）就注入**，横屏旋转时无法再新增。这意味着横屏 Search 要么：
- (a) 两个方向共用一套注入（竖屏已有，横屏复用→但横屏是 vertical 布局，token 会出现在底段）；
- (b) 在竖屏首次 inflate 时就同时为 vertical 布局准备好 Search dispatcher。

---

## 9. Recent 点击链路（未替换，仅读取）

- Recent View = `KeyButtonView{recent_apps}`，`clickable=true longClickable=false`，`mCode=82`。
- 对应 `ButtonDispatcher(mId=2131429847)`，**未 Hook**。
- 点击最终走 `ButtonDispatcher` → `NavigationBar` 的 `onButtonClick` 分发。
- 实测横屏点 Recent → 正常出最近任务，**完全原生**。

---

## 10. 是否存在 ContextualButtonGroup / menu_ime

- **存在** `ContextualButtonGroup`：`menu_container`（`0x7f0b0751`），`mViews=0`（横屏原生时不显示任何 contextual 键）。
- **无独立 `menu_ime` token**；`ime_switcher` 本身就是 `ContextualButton`（见 5.2）。
- `accessibility_button` 是另一个空闲 `ContextualButton`（mViews=0）。

---

## 11. 对第二阶段 2C 的启示（供方案设计）

1. **横屏 Search 不能靠旋转时注入** —— 必须竖屏首次 inflate 时就把横屏（vertical）所需的 Search 也准备好。
2. **最近的路是复用 ContextualButton 机制**：`ime_switcher` 已是 ContextualButton，`menu_container` 是 ContextualButtonGroup。可考虑让横屏 Search 成为 `menu_container` 里的一个 ContextualButton，出现在 vertical 底段。
3. **Recent 必须保持原生 ButtonDispatcher**（`recent_apps`），不移动、不改 click。
4. `mCurrentLayout` 是**唯一真相源**，横竖屏共用；修改它需要同时满足 horizontal 和 vertical 两套排列。
5. **`removeImeSwitchers` 是第一阶段的必要防御**，第二阶段做 Search 时若复用 ime_switcher 机制需重新评估其行为。

---

## 附录：探测源码

- `LandscapeProbe.java`（新增，292 行，只读）
- `Wp7NavbarHook.java`：getDefaultLayout after 记录原始串；inflateLayout before 记录 BEFORE/CLEAN + 分析；横屏 after dump
- `NavigationBarController.java`：onConfigurationChanged 横屏延迟 dump View 树 / dispatcher / inflater 字段

**探测结束后已确认：所有功能（竖屏 Search/IME、横屏原生、旋转）均未改变。**
