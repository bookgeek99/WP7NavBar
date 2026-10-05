# 2C-4 后续：布局参数已修正，但暴露**结构性叠层**问题

> 基线：`83f6463` → 本次修复后（未 push 前）。
> 数据来源：实测 `SEGMENT-DUMP` / `LANDSCAPE_PROBE`（16:41 旋转 dump）+ 反编译 layout XML。

---

## 0. 摘要（请先看这段）

**2C-4 要求的两个参数修复已完成，并且 View 数据已 100% 正确**：

| 项 | 修复前 | 修复后（实测） |
|---|---|---|
| Search 外层 wrapper | `w=195, weight=0` | **`w=0, weight=1.0`** ✅ |
| Search 内层 KeyButtonView | `w=130` | **`w=195`（navigation_key_width）** ✅ |
| 横屏外层 | `w=195` | **`h=0, weight=1.0`** ✅ |
| 横屏内层 | `h=130` | **`h=195`** ✅ |

实测 View 树（竖屏）：

```
ends_group rect=[0,0,1030,153]
  [0] ime_switcher wrapper w=0 weight=0.5  inner w=195  rect=[0,0,114,153]
  [1] back wrapper          w=0 weight=1.0  inner w=195  rect=[114,0,343,153]  宽229
  [2] Space                 w=0 h=0 weight=1.0  INVISIBLE  rect=[343,0,572,0]
  [3] Search wrapper        w=0 weight=1.0  inner w=195  rect=[572,0,801,153]  宽229
  [4] recent wrapper        w=0 weight=1.0  inner w=195  rect=[801,0,1030,153] 宽229
```

**Search 与 back/recent 完全等宽（229）、内层等宽（195）。**

---

## 1. 但用户实测反馈："位置不对，且**不是一个层的东西**"

用户原话（竖屏）：

> 搜索键在 Home 键和 Recent 键中间，颜色发浅。
> 虽然视觉上是在它俩中间，我怀疑它们就不是一个层的东西。
> 因为我在点搜索键的时候，它的那个响应圈，和点 Home 键的时候响应的那个圈有重叠。
> 因为它俩离得近。所以感觉它俩就不是一个层上的东西。
> 大小倒是比较接近，看不出大小上的区别。

**"响应圈（ripple）重叠" 是关键线索。**

---

## 2. 根因：`ends_group` 与 `center_group` 是**叠层**，不是并排

反编译 `res/layout/navigation_layout.xml`（确证）：

```xml
<FrameLayout id=horizontal ...>
  <NearestTouchFrame id=nav_buttons width=match_parent height=match_parent>
      <!-- 先加：z 在下 -->
      <LinearLayout id=ends_group  width="match_parent" height="match_parent"/>
      <!-- 后加：z 在上，居中叠加 -->
      <LinearLayout id=center_group width="wrap_content" height="match_parent"
                    layout_gravity="center" gravity="center"/>
  </NearestTouchFrame>
</FrameLayout>
```

**结构真相**：
- `ends_group` = **全宽（match_parent）**，负责 **back / Space / recent**（weight 把 back 顶左、recent 顶右，`Space` weight=1 撑开中间留白）。
- `center_group` = **wrap_content + 居中**，负责 **home**，**叠加在 ends_group 之上**。
- 两者是 **z-order 叠层**：home 居中区域「压在」ends_group 的中间留白上。

实测坐标（竖屏，16:41 dump）：
```
ends_group    rect=[0,0,1030,153]            ← 全宽
  Search wrapper  rect=[572,0,801,153]       ← 屏幕 x 572~801
center_group  rect=[417,0,612,153]           ← 屏幕 x 417~612（居中）
  home            rect=[417,0,612,153]       ← 屏幕 x 417~612
```

**Search（x 572~801）与 center_group/home（x 417~612）在 572~612 重叠 ~40px。**
→ 这正是用户观察到的「点击 Search 的 ripple 圈和 Home 的 ripple 圈重叠」。

---

## 3. 结论：这不是参数问题，是**放置位置**问题

- 参数已完全对齐（等宽、weight 正确）✅
- 但 `wp7search` 被放进 **`ends_group`**，而 ends_group 的设计是「两端分布、中间留白给 center_group」。
  Search 以 weight=1 落在中间 → 与 **`center_group`(home)** 物理重叠。
- **视觉上**：Search 图标居中偏左、看起来「在 Home 和 Recent 之间」；
  但 **触摸/ripple 区域**与 home 重叠 → 用户察觉「不是一个层」。

---

## 4. 需要 ChatGPT 决定的方向（本报告不实施）

要得到 `[Back] [Home] [Search] [Recent]` 且**互不重叠**，可能的方案：

- **方案 A**：把 `wp7search` 注入 **`center_group`**（与 home 同组），
  变成 `center_group = [home][wp7search]`，居中出现两个键。
  （与 ChatGPT「不要动 center_group」的要求冲突，需明确授权。）

- **方案 B**：保持 ends_group，但接受「Search 在 Home 右侧、部分重叠」的视觉效果，
  仅通过 **减小 weight / 调整 Space** 拉开距离（可能无法完全消除重叠，因为 center_group 是叠加层）。

- **方案 C**：**隐藏/移除 ends_group 里的 Space**，让 back/recent 靠得更近或让出中间。
  （与 ChatGPT「不要动 Space」冲突。）

- **方案 D**：**不放在导航栏 View 树里**，改用别的挂载点（大改动）。

**关键约束冲突**：
- ChatGPT 要求「Search 与 Back/Recent 同 weight=1、同级别导航键」+「不动 Space / center_group / recent」；
- 但 `ends_group` 中间是**留给 center_group 的叠层留白**，
  在同一层放第 4 个 weight=1 的键 → **必然与 center_group 重叠**。

**这两个要求在现有结构下无法同时满足**，需要取舍或授权改结构。

---

## 5. 本轮已完成的代码改动（参数修复，未 push）

`SearchContextualButtonFactory.wrapWithReverseRelative`：

1. **内层**：`FrameLayout.LayoutParams(navigation_key_width, MATCH_PARENT)`
   （不再复制 custom_key 的 `navigation_side_padding`=130）。
2. **外层**：`LinearLayout.LayoutParams(0, MATCH_PARENT, weight)`，
   `weight` 来自 token（`wp7search[1W]`→1.0）。
3. **横屏不再自写分支**：因容器是 `ReverseLinearLayout`，`addView` 时会自动
   `reverseParams()` 交换 width/height（smali 已证）。自写横屏分支会被二次交换成错误值。

**诊断保留**：`dumpSegmentLayout` / `LANDSCAPE_PROBE` 继续可用。