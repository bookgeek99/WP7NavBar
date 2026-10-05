# WP7NavBar 2C-4 前置 —— 布局机制分析报告（只读）

> 基线：commit `bb47879`。本报告**仅做布局机制分析，未修改任何功能代码**。
> 数据来源：`SearchContextualButtonFactory.dumpSegmentLayout`（新增只读诊断）+ 反编译 smali。
> 设备：Redmi K70 Ultra（rothko），HyperOS 3.0.307，Android 16。

---

## 0. 结论速览

| 项 | Back | Recent | **Search（当前）** | 系统正确做法 |
|---|---|---|---|---|
| 外层 wrapper 主轴尺寸 | **0** | **0** | **195（竖）/195（横）** ❌ | **0** |
| 外层 wrapper weight | **1.0** | **1.0** | **0.0** ❌ | **= token 数字** |
| 内层 KeyButtonView 主轴尺寸 | **195** | **195** | **130** ❌ | **195**（= `navigation_key_width`）|

**一句话**：Search 的外层应是 `主轴尺寸=0 + weight`，内层应是 `navigation_key_width`。
当前两者都不对，导致「不参与 weight 分配 + 宽度偏窄」。

---

## 1. 实测段容器完整 LayoutParams（竖屏 ends_group, LinearLayout）

`dumpSegmentLayout` 输出（横屏切换后 dump；`vis=0`=VISIBLE，`vis=4`=INVISIBLE）：

```
SEGMENT-DUMP landscape=false class=android.widget.LinearLayout id=0x7f0b0443 childCount=4
   [0] ReverseRelativeLayout  id=NO_ID  vis=0  lp=LayoutParams{w=0,h=-1,weight=0.5}
         * KeyButtonView id=ime_switcher(0x7f0b05a3) lp=LayoutParams{w=195,h=-1}
   [1] ReverseRelativeLayout  id=NO_ID  vis=0  lp=LayoutParams{w=0,h=-1,weight=1.0}
         * KeyButtonView id=back(0x7f0b0153)        lp=LayoutParams{w=195,h=-1}
   [2] Space                  id=NO_ID  vis=4  lp=LayoutParams{w=0,h=0,weight=1.0}
   [3] ReverseRelativeLayout  id=NO_ID  vis=0  lp=LayoutParams{w=195,h=-1,weight=0.0}   ← 我们的 Search
         * KeyButtonView id=0x7f1f0001              lp=LayoutParams{w=130,h=-1}
```

（注：此 dump 发生在 `inflateButtons` 处理 `wp7search` 的瞬间，**recent 尚未 inflate**，
故 index 3 之后无 recent；正式渲染后 recent 会追加在末位。Space 已由 inflateLayout 预先加好。）

## 2. 实测段容器完整 LayoutParams（横屏 ends_group, ReverseLinearLayout=vertical）

```
SEGMENT-DUMP landscape=true class=...ReverseLinearLayout id=0x7f0b0443 childCount=4
   [0] ReverseRelativeLayout  vis=0  lp=LayoutParams{w=-1,h=0,weight=0.5}
         * KeyButtonView id=ime_switcher  lp=LayoutParams{w=-1,h=195}
   [1] ReverseRelativeLayout  vis=0  lp=LayoutParams{w=-1,h=0,weight=1.0}
         * KeyButtonView id=back          lp=LayoutParams{w=-1,h=195}
   [2] Space                  vis=4  lp=LayoutParams{w=0,h=0,weight=1.0}
   [3] ReverseRelativeLayout  vis=0  lp=LayoutParams{w=-1,h=195,weight=0.0}   ← 我们的 Search
         * KeyButtonView id=0x7f1f0001    lp=LayoutParams{w=-1,h=130}
```

**规律**：外层 wrapper 在**主轴方向尺寸=0**（竖屏 `w=0`，横屏 `h=0`）+ weight；
交叉轴 = MATCH。内层 KeyButtonView 主轴尺寸 = 195（`navigation_key_width`）。

---

## 3. 回答问题 1：Back token `back[1WC]` 的最终 LayoutParams

- **外层 wrapper**：`LinearLayout.LayoutParams(width=0, height=MATCH_PARENT, weight=1.0)`
  （横屏为 `height=0, width=MATCH_PARENT, weight=1.0`）
- **内层 KeyButtonView**：`FrameLayout.LayoutParams`，宽度 = **195**（= `navigation_key_width` 60dp × 3.25）
- **实际宽度**：由父 LinearLayout 按 weight 分配 → 竖屏实测 238（含 wrapper 内 padding/gravity 影响）

## 4. 回答问题 2：Recent token `recent[1WC]` 的最终 LayoutParams

与 Back **完全一致**：
- 外层 wrapper：`width=0, height=MATCH, weight=1.0`
- 内层 KeyButtonView：宽度 = 195
- 实际宽度：竖屏实测 239

（结论：`1WC` → weight=1.0；`C` 后缀只影响 gravity=0x11(CENTER)，不影响尺寸。）

## 5. 回答问题 3：当前 Search token 的布局参数

从日志直接读取：

```
token 原文          : wp7search[1W]
parse 后 weight     : 1.0   （parseWeight 解析 "1W" → 1.0，数值正确）
outer LP.width      : 195（竖屏）/ 195（横屏）   ← ❌ 固定值，应为 0
outer LP.weight     : 0.0                        ← ❌ 我们没设 weight
inner LP.width      : 130（竖屏）/ 130（横屏）   ← ❌ 来自 custom_key 的 navigation_side_padding
inner LP.weight     : 0
```

- `parseWeight` 逻辑本身**正确**（`1W`→1.0）。
- 问题在 **`wrapWithReverseRelative` 构造外层 LP 时用了固定宽度、没写 weight**；
  **内层 flp 直接复制了 custom_key 的 LP（width=side_padding=130）**。

## 6. 回答问题 4：INVISIBLE Space 的来源

**结论（反编译确证，非猜测）**：

系统 `NavigationBarInflaterView.inflateLayout` 在 inflate 每个 segment 后，**无条件**向该段的
`LinearLayout` 容器 addView 一个 `android.widget.Space`：

```java
Space sp = new Space(context);
LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 0, 1.0f);  // w=0,h=0,weight=1.0
linearLayout.addView(sp, lp);
```

（smali 行约 2397 / 2444，对 left 段与 right 段各做一次。）

- **resource id**：NO_ID（无 id）
- **class**：`android.widget.Space`
- **LayoutParams**：`LinearLayout.LayoutParams{w=0, h=0, weight=1.0}`
- **parent**：段容器（`ends_group` 的 LinearLayout / ReverseLinearLayout）
- **来自哪个 token**：**不来自任何 token**，是 `inflateLayout` 为每段固定插入的
- **是否参与 weight 分配**：**是**（weight=1.0，占据 1 份权重）

**因此竖屏 ends_group 的权重总和 = ime_switcher(0.5) + back(1.0) + Space(1.0) + recent(1.0) = 3.5**
（我们的 Search 当前 weight=0，不参与 ⇒ 布局错位）。

---

## 7. 与系统做法的差异（当前代码）

`SearchContextualButtonFactory.wrapWithReverseRelative`：

| 项 | 当前实现 | 系统做法 |
|---|---|---|
| 内层 flp | `new FrameLayout.LayoutParams(btn.getLayoutParams())` → width=130 | 复制原始 View LP，但原始 View 宽度应为 195 |
| 外层 outerLp | `new LinearLayout.LayoutParams(w=195, MATCH)`（固定） | `LinearLayout.LayoutParams(主轴=0, 交叉轴=MATCH, weight=token数字)` |

**根因**：
1. **内层宽度来源错**：`custom_key.xml` 声明 `layout_width=@dimen/navigation_side_padding`（36dp≈130px），
   而系统 back/recent 用 `@dimen/navigation_key_width`（60dp≈195px）。
2. **外层未实现 weight 机制**：系统对 `[xW]` token 用 `width=0 + weight`，我们写了固定宽度。

---

## 8. 建议的修复方向（供审阅确认，本报告不实施）

1. **内层**：把包装前 KeyButtonView 的宽度设为 `navigation_key_width`（可用
   `res.getIdentifier("navigation_key_width","dimen","com.android.systemui")` 获取）。
2. **外层**：改为 `LinearLayout.LayoutParams(0, MATCH_PARENT, weight)`（竖屏）/
   `(MATCH_PARENT, 0, weight)`（横屏），`weight` 取 token 解析值（当前 1.0）。

**待审阅确认的问题**：
- Search 的 weight 应为多少？`wp7search[1W]`(weight=1.0) 时，
  竖屏 ends_group 权重和 = 0.5+1.0+1.0+1.0(Search)+1.0(recent)=4.5，
  Search 与 back/recent 等宽（各得 1/4.5）——是否符合「四键等宽」目标？
- 是否需要把 `wp7search` 的 weight 调整为其他值（如 `.5W`）以对齐视觉？

---

## 9. 附：诊断方法（本次新增，只读）

`SearchContextualButtonFactory.dumpSegmentLayout(ViewGroup seg, boolean landscape)`：
遍历段容器所有子 View，打印 class/id/visibility/LayoutParams(width,height,weight,margin)/
padding/rect，并对 ViewGroup 子级再打印一层。**不修改任何状态**，可随时移除。
