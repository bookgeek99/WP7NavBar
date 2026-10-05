# WP7NavBar 第二阶段 2C —— 反编译分析报告（inflate 链路）

> 基于反编译 `MiuiSystemUI.apk`（`/system_ext/priv-app/MiuiSystemUI/`，apktool 3.0.1 解包）
> 目的：搞清 `NavigationBarInflaterView.inflateButtons` 如何将 token 名映射到 dispatcher，
> 确认「独立 Search ContextualButton」是否可接入 layout token 系统。

---

## 1. `inflateLayout(String)`（smali 行 2165）

```java
mCurrentLayout = layoutString;
if (layoutString == null) layoutString = getDefaultLayout();
String[] seg = layoutString.split(";", 3);   // 严格 3 段
if (seg.length != 3) { Log.d("NavBarInflater","Invalid layout."); seg = getDefaultLayout().split(";",3); }

String[] s0 = seg[0].split(",");
String[] s1 = seg[1].split(",");
String[] s2 = seg[2].split(",");

// 段0（ends_group）→ horizontal + vertical 各 inflate 一次
inflateButtons(s0, mHorizontal.findViewById(0x7f0b0443), landscape=false, ...);
inflateButtons(s0, mVertical.findViewById(0x7f0b0443),   landscape=true,  ...);
// 段1（center_group）
inflateButtons(s1, mHorizontal.findViewById(0x7f0b0270), false, ...);
inflateButtons(s1, mVertical.findViewById(0x7f0b0270),   true,  ...);
// 段2（ends_group，同段0）
inflateButtons(s2, mHorizontal.findViewById(0x7f0b0443), false, ...);
inflateButtons(s2, mVertical.findViewById(0x7f0b0443),   true,  ...);
```

**关键**：每个 token 段都会 **horizontal + vertical 各 inflate 一次** → 一个 token 产生**两个 KeyButtonView**（正好对应"双方向 View"）。

---

## 2. `inflateButtons(String[] tokens, ViewGroup parent, boolean landscape, boolean ...)`（行 939）

对每个 token：
1. `name = extractButton(token)`（剥掉 `[...]` 修饰符）
2. **按名字硬编码匹配布局资源**：

| token 名 | inflated layout | 资源 id |
|---|---|---|
| `left` / `right` | → 转成 `space` / `menu_ime` | — |
| `home` | `0x7f0e00fc` | `home` |
| `back` | `0x7f0e004f` | `back` |
| `recent` | `0x7f0e03ec` | `recent_apps` |
| `menu_ime` | `0x7f0e01f1` | `menu_ime` |
| `space` | `0x7f0e033e` | `nav_key_space` |
| `clipboard` | `0x7f0e0079` | `clipboard` |
| `contextual` | `0x7f0e008a` | `contextual` |
| `home_handle` | `0x7f0e00fd` | `home_handle` |
| `ime_switcher` | `0x7f0e0106` | `ime_switcher` |
| `key(...)` | `0x7f0e00ab` | `custom_key` |
| **其他（不匹配）** | **无 inflate，`goto_d` 跳过** | — |

3. inflate 出的 View（或包一层 `ReverseRelativeLayout` 后）→ `parent.addView(v)` → **`addToDispatchers(v)`**

> ⚠️ **自定义 token 名（如 `search`）不会被 inflate**（落到 `cond_f` 分支直接跳过）。

---

## 3. `addToDispatchers(View v)`（行 290）—— token→dispatcher 绑定

```java
SparseArray<ButtonDispatcher> s = mButtonDispatchers;
int key = v.getId();                          // ← 用 View 的资源 id 作 key
int idx = s.indexOfKey(key);
if (idx >= 0) ((ButtonDispatcher) s.valueAt(idx)).addView(v);
if (v instanceof ViewGroup) { ...递归子 View... }
```

**核心规则**：
> `mButtonDispatchers` 的 key **必须等于 inflate 出来的 View 的 `android:id`**。
> 匹配成功 → `dispatcher.addView(view)`。

例：`ime_switcher` token → inflate `layout/ime_switcher`（id=`@id/ime_switcher`=`0x7f0b05a3`）→ 查 `mButtonDispatchers[0x7f0b05a3]`（=ime_switcher 的 ContextualButton）→ `addView`。

---

## 4. `layout/contextual.xml`（= menu_container 容器）结构

```xml
<FrameLayout android:id="@id/menu_container" ...>       <!-- 0x7f0b0751 -->
    <KeyButtonView android:id="@id/menu" ... />          <!-- menu -->
    <include layout="@layout/ime_switcher" .../>          <!-- ime_switcher 键 -->
    <include layout="@layout/rotate_suggestion" .../>     <!-- 旋转建议键 -->
    <KeyButtonView android:id="@id/accessibility_button" ... />  <!-- 无障碍键 -->
</FrameLayout>
```

**解释**：
- `menu_container`（ContextualButtonGroup）是一个 FrameLayout，内含 4 个 contextual 子键。
- 这些子键**常驻存在**（静止时 `invisible`），按需显示。
- 这正是 2B 中 `mButtonData`（2 个 ContextualButton：accessibility + ime_switcher）的来源。

---

## 5. `layout/ime_switcher.xml`

```xml
<KeyButtonView android:id="@id/ime_switcher" ... />
```

`layout/custom_key.xml`（`key(...)` token 用）：
```xml
<KeyButtonView ... />   <!-- 注意：无 android:id！ -->
```

> `custom_key` 出来的 KeyButtonView **没有 id** → `addToDispatchers` 里 `v.getId()` = `NO_ID(-1)` → 匹配不到任何 dispatcher。

---

## 6. 对 2C-1「独立 Search ContextualButton」的可行性结论

### 直接 `mButtonDispatchers.put(NEW_ID, searchButton)` —— ❌ 不够
因为：
1. `inflateButtons` **不认自定义 token 名**（`search` 会被跳过）；
2. 即使认，也没有对应 layout 资源能 inflate 出 `id==NEW_ID` 的 KeyButtonView；
3. `addToDispatchers` 靠 **inflate 出的 View 的 id** 匹配，无法凭空绑定。

### 可行路线

| 路线 | 做法 | 评价 |
|---|---|---|
| **A. hook `inflateButtons`** | 遇到自定义 token（如 `wp7search`）时，自行 inflate `custom_key`（或复用 `ime_switcher` layout），`setId(NEW_SEARCH_ID)`，再 `addToDispatchers` | ✅ **最干净**，但需 hook 一个较复杂的方法 |
| **B. hook `addToDispatchers`** | 在 `addToDispatchers` after 检查，若是我们的 View 则重定向 | ⚠️ 时机难控 |
| **C. 复用 ime_switcher layout + 改 id** | `ime_switcher` token → inflate（id=0x7f0b05a3）→ 改成 NEW_ID → 但会破坏 IME | ❌ 破坏 IME |
| **D. 复用 `contextual` 容器** | 向 `menu_container` 动态 `addView` 一个 KeyButtonView + 注册 dispatcher | ⚠️ 需处理 ContextualButtonGroup |

### ⭐ 推荐：路线 A（hook `inflateButtons`）
在 `inflateButtons` 的 **before/after** 拦截：
- 若 token 名 == `wp7search`：用 `mLayoutInflater` inflate `layout/custom_key`，`setId(WP7_SEARCH_ID)`，包 `ReverseRelativeLayout`（对齐系统做法），`parent.addView` + `mButtonDispatchers[NEW_ID].addView`。
- 或者更简单：**hook `inflateButtons` 的 after**，遍历新加的子 View，若 id==WP7_SEARCH_ID 则手动 `addToDispatchers`。

### WP7_SEARCH_ID 选择
- 不能与 SystemUI 资源冲突 → 用**模块自定义 id**（如 `0x7f00ff01` 之类）或运行时 `View.generateViewId()`（但需保证稳定性用于 dispatcher key）。
- `Resource.getIdentifier` 只在 SystemUI 包内有效。

---

## 7. `getDefaultLayout()` 与 `onFinishInflate` 时序（补充）

- `getDefaultLayout()` 返回布局串（我们已 hook 注入）。
- `inflateLayout(串)` 在 `onFinishInflate` / `setButtonDispatchers` 之后调用。
- **`setButtonDispatchers`** 用新的 SparseArray 重建 `mHorizontal/mVertical × ends_group/center_group` 的所有绑定（`addAll`）。

> **2C-1 的注册时机**：必须在 `inflateLayout` 之前把新 dispatcher put 进 `mButtonDispatchers`（否则 inflate 时查不到）。

---

## 8. 源码位置

- `smali_classes2/com/android/systemui/navigationbar/views/NavigationBarInflaterView.smali`
  - `inflateLayout` @2165
  - `inflateButtons` @939
  - `addToDispatchers` @290
  - `setButtonDispatchers` @2665
  - `getDefaultLayout` @685
- `res/layout/contextual.xml` / `ime_switcher.xml` / `custom_key.xml`
