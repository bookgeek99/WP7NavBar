# GROUP_MAPPING_REPORT.md — 导航栏 View/Group 归属与原生布局逆向

> 基线：`6c21be8`（2C-4 参数修复 + 2C-5 center_group 实验）。
> 本报告**只做逆向分析，未修改任何 Java 功能代码**。
> 数据来源：`LANDSCAPE_PROBE` 实测 dump（PID 7121, 2026-10-05 17:04:51，已含 lp/parent）、
> 反编译 `navigation_layout.xml` / `NavigationBarInflaterView.smali`、`mCurrentLayout` 字段。
> 设备：Redmi K70 Ultra，HyperOS 3.0.307，Android 16。

---

## 0. 当前 `mCurrentLayout`

```
ime_switcher[.5W],back[1WC] ; home ; wp7search[1W],recent[1WC]
└──── 左段(left) ────┘   └中段┘ └────── 右段(right) ──────┘
```

三段用 `;` 分隔；段内用 `,` 分隔。`inflateLayout` 按「段」调用 `inflateButtons()`，
每段对应 `NearestTouchFrame` 下的一个容器。

---

## 1. 真实 View Hierarchy（实测）

### 1.1 竖屏（`horizontal` FrameLayout，`mIsVertical=false`）

```
FrameLayout id=horizontal                         (GONE when landscape)
└── NearestTouchFrame id=nav_buttons
    ├── LinearLayout id=ends_group   rect=[0,0,1030,153]  lp={w=match(1030),h=match}  z=下
    │   ├── wrapper(ReverseRelativeLayout)  ← ime_switcher
    │   │   └── KeyButtonView id=ime_switcher  GONE  lp={w=195,h=-1}
    │   ├── wrapper(ReverseRelativeLayout)  ← back
    │   │   └── KeyButtonView id=back          VIS   lp={w=195,h=-1}
    │   └── wrapper(ReverseRelativeLayout)  ← recent_apps
    │       └── KeyButtonView id=recent_apps   VIS   lp={w=195,h=-1}
    └── LinearLayout id=center_group rect=[320,0,710,153] lp={w=wrap(390),h=match} z=上
        ├── KeyButtonView id=home       VIS  lp={w=195,h=-1,weight=0}
        └── wrapper(ReverseRelativeLayout)   ← wp7search（本模块放入）
            └── KeyButtonView id=0x7f1f0001 VIS lp={w=195,h=-1}
```

> 注：`ime_switcher` / `back` / `recent` 的 KeyButtonView `rect` 打印的是**相对各自 wrapper 内部**的坐标
> （因为它们包在 wrapper 里），故三者数值相近（~49~244）。wrapper 自身坐标需另算。

### 1.2 横屏（`vertical` FrameLayout，`mIsVertical=true`）

```
FrameLayout id=vertical                            (VISIBLE when landscape)
└── NearestTouchFrame id=nav_buttons
    ├── ReverseLinearLayout id=ends_group  rect=[0,0,153,1030] lp={w=match,h=match}
    │   ├── wrapper ← recent_apps     VIS  inner lp={w=-1,h=195}
    │   ├── wrapper ← 【本模块 Search 横屏占位？】 VIS inner lp={w=-1,h=195}
    │   ├── wrapper ← back            VIS  inner lp={w=-1,h=195}
    │   └── wrapper ← ime_switcher    GONE
    └── ReverseLinearLayout id=center_group
        └── KeyButtonView id=home     VIS  lp={w=-1,h=195,weight=0}
```

（横屏两容器的子顺序：ends_group 实测为 recent→(search)→back→ime_switcher；
center_group 为 home。横屏本阶段不做架构改动。）

---

## 2. 逐项回答（题目要求的 1–5）

### 2.1 ime_switcher
- **View**：`KeyButtonView id=ime_switcher(0x7f0b05a3)`，`vis=GONE`（无输入法时）
- **ContextualButton**：`ContextualButton@ea7116f(mId=2131428771)`（`mButtonDispatchers[0x7f0b05a3]`）
- **ContextualButtonGroup**：其 `buttons=null`（**不属于任何 group**，独立 ContextualButton）
- **wrapper**：`ReverseLinearLayout$ReverseRelativeLayout id=NO_ID`
- **直接父 ViewGroup**：`LinearLayout id=ends_group(0x7f0b0443)`
- **所在 layout/group**：**左段（left）**，token `ime_switcher[.5W]`
- **screen rect**：竖屏 inner `[0,0,147,153]`（wrapper 内）；wrapper 位于 ends_group 最左
- **LayoutParams**：内层 `KeyButtonView {w=195,h=-1}`；外层 wrapper `LinearLayout.LayoutParams{w=0,h=-1,weight=0.5}`

### 2.2 recent_apps
- **KeyButtonView**：`id=recent_apps(0x7f0b09d7)`，`mCode=82`，`vis=VISIBLE`
- **ButtonDispatcher**：`ButtonDispatcher@f4c6e7c(mId=2131429847)`，`mViews.size=2`
  （竖屏/横屏各一：竖 `[50,0,245,153]`，横 `[0,16,153,211]`）
- **wrapper**：`ReverseLinearLayout$ReverseRelativeLayout id=NO_ID`
- **直接父 ViewGroup**：`LinearLayout id=ends_group(0x7f0b0443)`
- **所在 layout/group**：**右段（right）**，token `recent[1WC]`
- **screen rect**：见 §1（wrapper 内坐标）
- **LayoutParams**：内层 `{w=195,h=-1}`；外层 wrapper `{w=0,h=-1,weight=1.0}`

### 2.3 back
- **所在 group**：`LinearLayout id=ends_group(0x7f0b0443)`（**左段**，token `back[1WC]`）
- **wrapper**：`ReverseRelativeLayout id=NO_ID`
- **screen rect**：竖屏 wrapper 在 ends_group 内，紧邻 ime_switcher
- **LayoutParams**：内层 `{w=195,h=-1}`；外层 wrapper `{w=0,h=-1,weight=1.0}`

### 2.4 home
- **所在 group**：`LinearLayout id=center_group(0x7f0b0270)`（**中段**，token `home`）
- **screen rect**：竖屏 `center_group rect=[320,0,710,153]`，home 在其内部 `[0,0,195,153]`
- **LayoutParams**：`{w=195,h=-1,weight=0}`（**直接作为 center_group 子级，无 wrapper**）

### 2.5 当前 wp7search
- **所在 group**：`LinearLayout id=center_group(0x7f0b0270)`（**本模块 2C-5 实验放入**）
- **wrapper**：`ReverseRelativeLayout id=NO_ID`（本模块创建）
- **screen rect**：竖屏在 center_group 内，位于 home 右侧（`[0,0,195,153]` 相对坐标）
- **LayoutParams**：内层 `{w=195,h=-1}`；外层 wrapper `{w=0,h=-1,weight=1.0}`
  （在 `center_group`(wrap_content) 下，weight 不参与分配，退化为内容宽）

---

## 3. 重点问题 A–F 的结论

### A. ime_switcher 和 recent_apps 是否存在左右对称的原生容器？

**否。** 两者**都在同一个 `ends_group`**（left/right 段都被 inflate 进 ends_group），
**没有左右对称的两个容器**。`NearestTouchFrame` 下只有 **两个**容器：
`ends_group`（全宽）与 `center_group`（居中叠加）。

### B. SystemUI 为什么让 ime_switcher 位于左侧？

**因为 layout 串里它属于「左段」**（`ime_switcher[.5W],back[1WC]`）。
`inflateLayout` 按段顺序把三段依次 inflate 进 `ends_group`：
**左段键在左、右段键在右、中段进 center_group**。
所以 ime_switcher 在左是**段序（left 段）决定的**，不是专门为它设计的"左侧容器"。

### C. recent_apps 为什么位于 ends_group 右侧？

**因为 layout 串里它属于「右段」**（`recent[1WC]`）。同理，右段键在 ends_group 里靠右。
（在原始系统中，右侧段通常还有 `right` / `menu_ime` 等 token。）

### D. 是否存在一个现成的右侧 ContextualButton / ContextualButtonGroup 可以放 Recent？

**没有专门给 Recent 的右侧容器。** 现有的 ContextualButtonGroup 只有
`menu_container(0x7f0b0751)`（`mViews.size=0`，未使用）。
`ime_switcher(0x7f0b05a3)` 是独立 ContextualButton（`buttons=null`，不在 group 内）。
**即：系统没有"左 ime / 右 recent"这种对称容器对。**

### E. 如果把 Recent 移出 ends_group，能否让 ends_group 恢复 `Back | Space | Search`？

**理论上可以改变 ends_group 的构成，但不能"把 Recent 移出去还保持原生"**：
- `recent_apps` 的 token 在 **layout 串的右段**，且 `recent` 是**导航键**（`ButtonDispatcher`，非 ContextualButton）。
- 若把它从右段移除 → **Recent 键会消失**（系统不会自动补回），且违反"Recent 必须原生"约束。
- 即使移除，`ends_group` 变成 `[ime_switcher][back][Space]`，
  **`Space`（weight=1，系统每段自动加）仍会撑开中间** → back 仍被 weight 拉向中间，
  **不一定能让 back 天然靠左**。
- 结论：**E 的设想在"保持 Recent 原生"前提下不可直接实现**。

### F. 依据（不猜，全部来自反编译 / 实测）

1. `navigation_layout.xml`（反编译）：
   ```xml
   <NearestTouchFrame id=nav_buttons>
       <LinearLayout id=ends_group   width=match_parent height=match_parent/>
       <LinearLayout id=center_group width=wrap_content height=match_parent layout_gravity=center/>
   </NearestTouchFrame>
   ```
   → 只有 ends_group + center_group 两个容器，**ends_group 全宽、center_group 居中叠加**。

2. `NavigationBarInflaterView.inflateLayout` 实测行为：
   - 对 **每个段**（left/center/right）调用 `inflateButtons(tokens, 容器, isLandscape, ...)`；
   - 同时在每段容器里**无条件 addView 一个 `Space(w=0,h=0,weight=1.0,INVISIBLE)`**（smali ~2397/2444）。
   - left/right 段 → 容器是 `ends_group`；center 段 → 容器是 `center_group`。

3. `mCurrentLayout` 实测：`ime_switcher[.5W],back[1WC];home;wp7search[1W],recent[1WC]`
   → ime_switcher 属左段、recent 属右段、home 属中段。

4. View 层级实测（`LANDSCAPE_PROBE` dump）：
   `ends_group` 子级为 ime_switcher/back/recent 的 **wrapper**；`center_group` 子级为 home。

---

## 4. 当前真实 Hierarchy（画图）

```
FrameLayout(horizontal / vertical)
└── NearestTouchFrame(nav_buttons)
    ├── ends_group                         ← 全宽（match_parent）
    │   ├── [左段] ime_switcher  (ContextualButton, .5W)   ← 独立，不在 group
    │   ├── [左段] back          (ButtonDispatcher, 1W)
    │   ├── Space                (系统每段自动加, weight=1, INVISIBLE)
    │   ├── [右段] recent_apps   (ButtonDispatcher, 1W)    ← 原生导航键
    │   └── (本模块 2C-5 前) wp7search  → 已移出
    │
    └── center_group                       ← 居中叠加（wrap_content）
        ├── [中段] home          (ButtonDispatcher, 无 wrapper)
        └── (本模块 2C-5 后) wp7search (ContextualButton, wrapper+weight=1)
```

---

## 5. 我认为最合理的目标 Hierarchy（供讨论，本报告不实施）

用户目标：`IME/contextual | Back | Home | Search | Recent`，
即 **Back 与 Search 分居两端、Home 居中、Recent 在最右**。

**受限于系统只有 ends_group(全宽) + center_group(居中) 两个容器**，可能的合理结构：

```
NearestTouchFrame
├── ends_group (match_parent)
│   ├── ime_switcher   (左段, .5W)      → 最左
│   ├── back           (左段, 1W)       → 靠左
│   ├── Space          (系统自动)
│   └── recent_apps    (右段, 1W)       → 靠右
│
└── center_group (wrap_content, 居中)
    ├── home
    └── wp7search
```

即：**Back/Recent 分居 ends_group 两端（原生），Home+Search 居中（center_group）**。
→ 这与当前 2C-5 实验的实际结构**已经一致**。

**障碍**：`ends_group` 的 `Space(weight=1)` 会把 back 与 recent 各推向两端的同时，
**back 的右边界可能侵入 center_group 的居中区**（实测 back 右端与 center_group 左端重叠）。
要完全消除重叠，需减小 back/recent 的实际占宽或调整 Space —— **但那属于修改原生机制**，
与本阶段"不改功能"约束冲突，需明确授权。

---

## 6. 本报告未做的修改

- **未修改任何 Java 功能代码**（Search 放置逻辑保持 2C-5 现状）。
- 仅在 `LandscapeProbe.dumpView` 增加了**只读**的 `lp=` / `parent=` 打印（诊断，不改行为）。
- 未改 `recent_apps` / `Space` / `ime_switcher` / `center_group` / `home`。

**下一步请 ChatGPT 指示**：是否允许修改 `ends_group` 的构成（如调整 Space 或按键顺序），
以消除 back 与 center_group 的残余重叠。