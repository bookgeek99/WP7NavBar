# WP7NavBar 第二阶段 2B —— ContextualButton 机制探测报告

> 设备：Redmi K70 Ultra（rothko），HyperOS 3.0.307，Android 16
> 基线提交：`91b2dcc`（2A）
> 探测版本：`wp7-navbar-probe2b2.apk`（只读探测）
> 探测日志前缀：`WP7NavBar: [CONTEXTUAL_PROBE]`
> **本阶段未实现横屏 Search，未修改任何现有行为。**

---

## 1. 类继承关系（实测）

| 类 | 继承链 | 是 ButtonDispatcher 子类 |
|---|---|---|
| `ButtonDispatcher` | `ButtonDispatcher <- Object` | — |
| `ContextualButton` | **`ContextualButton <- ButtonDispatcher <- Object`** | ✅ **是** |
| `ContextualButtonGroup` | **`ContextualButtonGroup <- ButtonDispatcher <- Object`** | ✅ **是** |

> **关键结论**：`ContextualButton` **本身就是 `ButtonDispatcher` 的子类**。
> 因此它可以像 back/home/recent 一样放进 `mButtonDispatchers`（SparseArray<ButtonDispatcher>）。

---

## 2. `ContextualButton` 类结构

### 2.1 自有字段
| 字段 | 类型 | 说明 |
|---|---|---|
| `mIconResId` | int | 图标资源 id |
| `mLightContext` | Context | ContextThemeWrapper |

### 2.2 继承自 ButtonDispatcher 的关键字段
| 字段 | 类型 | 说明 |
|---|---|---|
| `mId` | int | 资源 id |
| `mViews` | ArrayList | 管理的 KeyButtonView 列表 |
| `mCurrentView` | View | 当前显示的 View |
| `mClickListener` | OnClickListener | 点击监听 |
| `mLongClickListener` | OnLongClickListener | 长按监听 |
| `mImageDrawable` | KeyButtonDrawable | 图标 drawable |
| `mVisibility` | Integer | 可见性（4=GONE） |
| `mVertical` | boolean | 方向 |
| `mAlpha` / `mDarkIntensity` | Float | 透明度/暗度 |
| `mFadeAnimator` / `mFadeListener` | — | 淡入淡出 |

### 2.3 公开方法
```
void setVisibility(int)          ← ContextualButton 唯一自有方法
```
（其余方法继承自 ButtonDispatcher：`setImageDrawable` / `setOnClickListener` / `getCurrentView` / `addView` 等。）

---

## 3. `ime_switcher`（`0x7f0b05a3`）完整字段

| 字段 | 值 |
|---|---|
| class | `ContextualButton` |
| `mId` | 2131428771 |
| `mIconResId` | 17302624（`0x01080020`，系统图标） |
| `mViews` | **size=4**（竖屏左+竖屏右+横屏上+横屏下） |
| `mCurrentView` | `KeyButtonView{ime_switcher vis=8(GONE)}` |
| `mClickListener` | `NavigationBar$$ExternalSyntheticLambda7`（系统统一分发） |
| `mLongClickListener` | `NavigationBar$$ExternalSyntheticLambda11` |
| `mVisibility` | 4（GONE） |
| `mImageDrawable` | `KeyButtonDrawable@...`（非 null） |

> 竖屏 Search 就是复用它的 KeyButtonView（`mCode=0` + 换 Drawable）伪装而成。

---

## 4. `accessibility_button`（`0x7f0b0039`）完整字段

| 字段 | 值 |
|---|---|
| class | `ContextualButton` |
| `mId` | 2131427385 |
| `mIconResId` | 2131235694（`0x7f0800ee`，**emoji 图标**） |
| `mViews` | **size=0**（**无 View！**） |
| `mCurrentView` | null |
| `mClickListener` | `NavigationBar$$ExternalSyntheticLambda7`（**系统已设置**） |
| `mLongClickListener` | `NavigationBar$$ExternalSyntheticLambda11`（**系统已设置**） |
| `mVisibility` | 4（GONE） |
| `mImageDrawable` | `KeyButtonDrawable@...`（非 null） |

> `accessibility_button` 是系统预留的「无障碍按钮」，**当前 mViews=0**（未被 inflate 到任何布局），但**系统已给它配好点击/长按监听器**。

---

## 5. `menu_container`（`0x7f0b0751` / ContextualButtonGroup）完整字段

| 字段 | 值 |
|---|---|
| class | `ContextualButtonGroup` |
| `mId` | 2131429201 |
| `mButtonData` | **List size=2** |
| `mViews` | size=0 |
| `mClickListener` | null |
| `mVisibility` | 4（GONE） |

### 5.1 `ButtonData`（内部类）结构
```
ContextualButtonGroup$ButtonData {
    ContextualButton button;      ← 指向一个 ContextualButton
    boolean markedVisible;        ← 是否被标记为可见
}
```
- `ButtonData[0].button` = `ContextualButton@a440353`
- `ButtonData[1].button` = `ContextualButton@73065b6`

> `mButtonData` 管理的 2 个 button 即 `accessibility_button` + `ime_switcher`（ContextualButtonGroup 统一调度它们的显示）。
> `setButtonVisibility(int, boolean)` 是它唯一自有方法。

---

## 6. ContextualButtonGroup 当前管理哪些按钮

- `mButtonData` size = 2 → 管理 **`accessibility_button`** + **`ime_switcher`** 两个 ContextualButton。
- 二者共享 `ContextualButtonGroup` 的显示策略（`markedVisible`）。
- 这是 HyperOS 3「menu_ime 区域」的 contextual 键管理机制。

---

## 7. `ContextualButton` 是否可以直接加入 `mButtonDispatchers`？

**✅ 是。** 因为：
1. `ContextualButton extends ButtonDispatcher`（第 1 节）；
2. `mButtonDispatchers` 是 `SparseArray<ButtonDispatcher>`；
3. inflate 时 `NavigationBarInflaterView.inflateButton` 按 token name → 资源 id → 查找 dispatcher，再 `dispatcher.addView(keyButtonView)`。

**结论**：可 `mButtonDispatchers.put(newId, new ContextualButton(...))`，布局串用对应 token 名即可被 inflate。

---

## 8. `ContextualButton` 是否可独立显示而不依赖 IME？

**✅ 可以。** 证据：
- `accessibility_button` 本身是 ContextualButton，**不依赖 IME**（无障碍按钮）；
- `ContextualButton.setVisibility(int)` 是公开方法，可独立控制；
- `mCurrentView` / `mViews` 由 inflate 填充，与 IME 无强绑定。

> 注意：ContextualButton 的显示**受 ContextualButtonGroup 调度**（`mButtonData.markedVisible`）。若要让 Search 常驻，需确认它不被 group 的显示策略强制 GONE。

---

## 9. `ContextualButton` 是否天然支持两个方向的 View？

**✅ 是。** 证据：
- `ime_switcher.mViews` size=**4**（竖屏左+竖屏右+横屏上+横屏下），说明一个 ContextualButton **可管理多方向的多个 KeyButtonView**。
- `back`/`home`/`recent` 均各 2 个 View（竖/横）。

> 因此新建 Search ContextualButton 后，可在首次 inflate 时**同时为 horizontal 和 vertical 各创建 View**，满足"旋转不重 inflate"的约束。

---

## 10. 是否可以安全把 `accessibility_button` 作为 Search？

**⚠️ 需谨慎，存在风险**：
- ✅ `mViews=0`（空闲，未被布局使用）
- ✅ 继承 ButtonDispatcher，可 inflate
- ⚠️ **系统已给它设置 `mClickListener` / `mLongClickListener`**（`Lambda7` / `Lambda11`），需确认其语义（无障碍按钮点击行为）
- ⚠️ **它被 `ContextualButtonGroup.mButtonData` 管理**（`markedVisible` 策略可能强制 GONE）
- ⚠️ `mIconResId` 是 emoji 无障碍图标，需替换

---

## 11. 最终结论

### A. 可以安全复用 accessibility_button？
**不完全。** 虽然 `mViews=0`，但它被 `ContextualButtonGroup` 纳入 `mButtonData` 管理，且系统已绑定了 click/longclick。直接复用可能引入系统无障碍逻辑。

### B. 可以复用但需要解除某个系统逻辑？
**✅ 这是最接近的答案。** 若复用 `accessibility_button`，需：
1. 从 `ContextualButtonGroup.mButtonData` 中**移除**它（否则 group 会强制其可见性）；
2. 覆盖系统设置的 `mClickListener` / `mLongClickListener`（改为 Search 行为）；
3. 替换 `mIconResId` / `mImageDrawable` 为 WP7 放大镜。

### C. 不建议复用？
**从"最小惊喜"角度，更推荐【新建独立 ContextualButton】。**

### ⭐ 最终建议（优先级）
```
① 新建独立 ContextualButton（Search）
       ↓ mButtonDispatchers.put(NEW_ID, new ContextualButton(...))
       ↓ mCurrentLayout 加 token
② 复用 accessibility_button（需解除 mButtonData 关联）
③ 新建普通 ButtonDispatcher
④ 继续复用 ime_switcher（不推荐：IME 与 Search 概念冲突）
```

---

## 12. 关键技术细节汇总（供 2C 设计）

| 问题 | 结论 |
|---|---|
| ContextualButton 是 ButtonDispatcher 子类？ | **是**（`ContextualButton <- ButtonDispatcher`） |
| 可加入 mButtonDispatchers？ | **是** |
| 可独立显示，不依赖 IME？ | **是**（accessibility_button 即例） |
| 支持双方向 View？ | **是**（ime_switcher 有 4 个 View） |
| accessibility_button 可直接复用？ | **不推荐**（被 group 管理 + 系统绑定 click） |
| 新建 ContextualButton 可行？ | **推荐**，但需处理 ContextualButtonGroup 关联 |
| 横竖屏共用 mCurrentLayout？ | **是**（2A 结论，旋转不重 inflate） |

---

## 13. 源码

- `ContextualProbe.java`（新增，只读探测）
- `NavigationBarController.java`：横屏 dump 时调用 ContextualProbe

**探测全程只读，功能未改变。**