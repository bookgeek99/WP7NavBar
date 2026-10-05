package com.wp7.navbar;

import android.view.View;
import android.widget.ImageView;
import android.util.SparseArray;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * WP7 NavBar —— Xposed Hook 纯入口（方案第五节）。
 *
 * 职责 ONLY：
 *  1. 确认作用域是 SystemUI
 *  2. 挂载 hook（getDefaultLayout / inflateLayout / setImageDrawable / 生命周期）
 *  3. 为每个 NavigationBarView 创建/获取 NavigationBarController
 *  4. 把生命周期事件（onAttachedToWindow / onConfigurationChanged）交给 Controller
 *
 * 不在此：修改 Search / Recent / visibility / margin / drawable / 点击 / IME。
 * 业务逻辑全部分散到各 Controller 与 Wp7IconController。
 *
 * 相比旧版删除的 hook（方案第九/十/二十九节）：
 *  - 全局 View.setVisibility hook（×2）：曾用于维持 Search 常显 / 压左段输入法键，污染所有 View，已删
 *  - 全局 View.setOnClickListener / setOnLongClickListener hook：曾用于"保护"搜索监听器，已删
 *  - ButtonDispatcher.setVisibility / addView hook：曾逐 View 纠正可见性，已删
 *  - ContextualButtonGroup.setButtonVisibility hook：曾强制 menu_ime 常显，已删
 *  - NavigationBar.setImeWindowStatus / onImeSwitcherClick hook：曾强制 ime_switcher 显隐/弹输入法菜单，已删
 *  - 大量 postDelayed(800ms)：改为生命周期 onAttached / onConfigurationChanged 触发（方案第十五节）
 */
public class Wp7NavbarHook implements IXposedHookLoadPackage {

    private static final String TAG = "WP7NavBar";
    private static final String PACKAGE_SYSTEMUI = "com.android.systemui";

    /** 每个 NavigationBarView 实例对应一个 Controller（方案第六节/第十四节，避免 static 持有 View）。 */
    private final Map<View, NavigationBarController> controllers = new WeakHashMap<>();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(PACKAGE_SYSTEMUI)) {
            return;
        }
        XposedBridge.log(TAG + ": SystemUI loaded. Setting up hooks (refactored).");

        hookKeyButtonSetImageDrawable(lpparam);
        hookGetDefaultLayout(lpparam);
        hookNavigationBarViewLifecycle(lpparam);
        hookImeWindowStatus(lpparam);
        hookSearchContextualButton(lpparam);
        XposedBridge.log(TAG + ": all hooks setup done");
    }

    // =====================================================================
    // Hook 4: NavigationBar.setImeWindowStatus —— IME 弹出时显示左段输入法键
    // =====================================================================

    /**
     * Hook NavigationBar.setImeWindowStatus：IME 弹出/收起时更新左段输入法键可见性。
     * 这是精确控制（只动左段 ime_switcher），替代旧版全局 View.setVisibility hook（已删）。
     * 左段输入法键与右段搜索键同为 ime_switcher id，需排除已标记为搜索键的 View。
     */
    private void hookImeWindowStatus(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> navBarCls = SystemUiReflection.findClass(
                    "com.android.systemui.navigationbar.views.NavigationBar", lpparam.classLoader);
            if (navBarCls == null) return;

            XposedBridge.hookAllMethods(navBarCls, "setImeWindowStatus", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object navBar = param.thisObject;
                        Object imeVisible = SystemUiReflection.getFieldQuiet(navBar, "mImeVisible");
                        if (!(imeVisible instanceof Boolean)) return;
                        boolean show = (Boolean) imeVisible;
                        Object navBarView = SystemUiReflection.getFieldQuiet(navBar, "mView");
                        if (!(navBarView instanceof View)) return;
                        updateLeftImeSwitcherVisibility((View) navBarView, show);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": setImeWindowStatus after err: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked setImeWindowStatus");

            // 点击左段输入法键时弹出"输入法选择菜单"（而非系统默认的循环切换）。
            // 排除右段搜索键：搜索键已 mCode=0 且应走自定义 OnClick（呼小爱），
            // 若其点击冒泡到 onImeSwitcherClick，会误弹菜单，故在 before 里拦截判断。
            XposedBridge.hookAllMethods(navBarCls, "onImeSwitcherClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 若 500ms 内有搜索键被触摸（ACTION_DOWN 记录），说明本次点击来自搜索键，
                        // 不弹输入法菜单，交给搜索键的 OnClick（呼小爱）。旋转后 OnClick 丢失时，
                        // 这里阻断系统循环切换，搜索键的 OnTouchListener 仍记录了触摸。
                        long elapsed = android.os.SystemClock.uptimeMillis()
                                - SearchButtonController.lastSearchTouchTime;
                        if (elapsed >= 0 && elapsed < 500) {
                            param.setResult(null);
                            return;
                        }
                        Object imm = SystemUiReflection.getFieldQuiet(param.thisObject, "mInputMethodManager");
                        Object displayIdObj = SystemUiReflection.getFieldQuiet(param.thisObject, "mDisplayId");
                        if (imm != null && displayIdObj instanceof Integer) {
                            int displayId = (Integer) displayIdObj;
                            SystemUiReflection.call(imm, "showInputMethodPickerFromSystem", true, displayId);
                            param.setResult(null);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": onImeSwitcherClick hook err: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked onImeSwitcherClick");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookImeWindowStatus err: " + t);
        }
    }

    /**
     * 根据 IME 可见性更新左段输入法键（非搜索键的 ime_switcher）的可见性。
     * 遍历导航栏下所有 ime_switcher，跳过已标记为搜索键的 View（右段），
     * 其余（左段输入法键）按 IME 状态设 VISIBLE/GONE。
     */
    private void updateLeftImeSwitcherVisibility(View navBarView, boolean imeVisible) {
        // 横屏完全不碰 ime_switcher（保持系统原生），仅在竖屏调整左段输入法键可见性。
        if (NavigationBarController.sCurrentOrientation
                == NavigationBarController.NavBarOrientation.LANDSCAPE) {
            return;
        }
        updateLeftImeSwitcherRecursive(navBarView, imeVisible);
    }

    private void updateLeftImeSwitcherRecursive(View v, boolean imeVisible) {
        try {
            if (v instanceof ImageView && v.getId() == SystemUiIds.ID_IME_SWITCHER
                    && !SearchButtonController.markedViews.contains(v)) {
                int want = imeVisible ? View.VISIBLE : View.GONE;
                if (v.getVisibility() != want) {
                    v.setVisibility(want);
                    XposedBridge.log(TAG + ": left ime_switcher -> " + (imeVisible ? "VISIBLE" : "GONE"));
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    updateLeftImeSwitcherRecursive(vg.getChildAt(i), imeVisible);
                }
            }
        } catch (Throwable ignored) { }
    }

    // =====================================================================
    // Hook 1: KeyButtonView.setImageDrawable —— Back/Home 图标 + 竖屏 Search 图标
    // =====================================================================

    private void hookKeyButtonSetImageDrawable(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> keyBtnCls = SystemUiReflection.findClass(
                    "com.android.systemui.navigationbar.views.buttons.KeyButtonView", lpparam.classLoader);
            if (keyBtnCls == null) return;

            XposedBridge.hookAllMethods(keyBtnCls, "setImageDrawable", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // 竖屏 Search：仅右段搜索键在竖屏时替换图标，避免首帧闪现错误图标
                    SearchButtonController.onSetImageDrawableBefore(param);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof ImageView) {
                            // Back/Home 图标替换（横竖屏共用，只改 drawable 不碰行为/可见性）
                            Wp7IconController.applyIfWp7((ImageView) param.thisObject);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": setImageDrawable after err: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked KeyButtonView.setImageDrawable");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookKeyButtonSetImageDrawable err: " + t);
        }
    }

    // =====================================================================
    // Hook 2: NavigationBarInflaterView.getDefaultLayout —— 竖屏注入右段 Search
    // =====================================================================

    private void hookGetDefaultLayout(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> inflaterCls = SystemUiReflection.findClass(
                    "com.android.systemui.navigationbar.views.NavigationBarInflaterView", lpparam.classLoader);
            if (inflaterCls == null) return;

            XposedBridge.hookAllMethods(inflaterCls, "getDefaultLayout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // [2A 探测] 先记录 getDefaultLayout 原始返回串（在任何注入之前）
                    try {
                        LandscapeProbe.logGetDefaultLayout(
                                NavigationBarController.sCurrentOrientation.name(),
                                param.getResult());
                    } catch (Throwable ignored) { }
                    // 竖屏注入右段 ime_switcher(搜索)+recent；横屏由 SearchButtonController 内部判定跳过
                    SearchButtonController.onGetDefaultLayout(param);
                }
            });

            // hook inflateLayout：横屏时清理布局串里的 ime_switcher；布局完成后标记竖屏搜索键。
            XposedBridge.hookAllMethods(inflaterCls, "inflateLayout", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof String) {
                            String layoutStr = (String) param.args[0];
                            // 横屏时移除传入布局串里的 ime_switcher（可能来自竖屏串缓存），
                            // 还原为系统原生串，避免横屏出现多余输入法键。
                            if (NavigationBarController.sCurrentOrientation
                                    == NavigationBarController.NavBarOrientation.LANDSCAPE) {
                                String cleaned = removeImeSwitchers(layoutStr);
                                // [2A 探测] 记录横屏 BEFORE / BEFORE_CLEAN / 结构分析
                                try {
                                    LandscapeProbe.logInflateBefore(layoutStr, cleaned);
                                    LandscapeProbe.analyzeLayoutString(cleaned);
                                } catch (Throwable ignored) { }
                                if (!cleaned.equals(layoutStr)) {
                                    param.args[0] = cleaned;
                                }
                            }
                        }
                    } catch (Throwable ignored) { }
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 布局刚 inflate 完，此时 recent / ime_switcher 都已创建且结构稳定，
                    // 是标记右段搜索键的最佳时机（onAttachedToWindow 时 recent 可能尚未 inflate）
                    try {
                        if (param.thisObject instanceof View) {
                            PortraitNavigationController.applyToRoot((View) param.thisObject);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": inflateLayout after mark err: " + t);
                    }
                    // [2A 探测] 横屏 inflate 完成后：dump View 树 / dispatcher / inflater 字段
                    try {
                        if (NavigationBarController.sCurrentOrientation
                                == NavigationBarController.NavBarOrientation.LANDSCAPE) {
                            if (param.thisObject instanceof View) {
                                View inflaterView = (View) param.thisObject;
                                LandscapeProbe.logInflateAfter(inflaterView);
                                LandscapeProbe.dumpInflaterFields(inflaterView);
                                // NavigationBarInflaterView 的父链上找 NavigationBarView 再 dump
                                View navBarView = findNavBarView(inflaterView);
                                if (navBarView != null) {
                                    LandscapeProbe.dumpLandscapeTree(navBarView);
                                    LandscapeProbe.dumpDispatchers(navBarView);
                                }
                            }
                        }
                    } catch (Throwable ignored) { }
                }
            });
            XposedBridge.log(TAG + ": hooked getDefaultLayout + inflateLayout");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookGetDefaultLayout err: " + t);
        }
    }

    // =====================================================================
    // Hook 6 [2C-1]: NavigationBarInflaterView.onFinishInflate / inflateButtons
    //   —— 注册独立 Search ContextualButton + 识别 wp7search token
    // =====================================================================
    private void hookSearchContextualButton(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> inflaterCls = SystemUiReflection.findClass(
                    "com.android.systemui.navigationbar.views.NavigationBarInflaterView", lpparam.classLoader);
            if (inflaterCls == null) return;

            // onFinishInflate after：此时 mButtonDispatchers 已就绪、尚未 inflateLayout
            // → 注册 Search ContextualButton
            XposedBridge.hookAllMethods(inflaterCls, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        SearchContextualButtonFactory.ensureRegistered(param.thisObject);
                    } catch (Throwable ignored) { }
                }
            });

            // setButtonDispatchers before：把 Search ContextualButton put 进系统传入的
            // SparseArray 参数，这样 setButtonDispatchers 内部的 addAll 会自动扫描到
            // 已存在的 wp7search View 并完成绑定（不复制系统绑定逻辑）。
            XposedBridge.hookAllMethods(inflaterCls, "setButtonDispatchers", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof SparseArray) {
                            SearchContextualButtonFactory.putInto(param.args[0], param.thisObject);
                        }
                    } catch (Throwable ignored) { }
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // setButtonDispatchers 内部已对传入 SparseArray 每个 dispatcher 做 addAll，
                    // 此时我们的 Search dispatcher 应已收到 horizontal + vertical 两个 View。
                    try {
                        SearchContextualButtonFactory.dumpAfterBind(param.thisObject);
                    } catch (Throwable ignored) { }
                }
            });

            // inflateButtons before：若 tokens 含 wp7search，先手动创建 Search View，
            // 再把该 token 从数组里移除（系统不认识它会跳过，我们自行处理）。
            XposedBridge.hookAllMethods(inflaterCls, "inflateButtons", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 3) return;
                        if (!(param.args[0] instanceof String[])) return;
                        if (!(param.args[1] instanceof android.view.ViewGroup)) return;
                        String[] tokens = (String[]) param.args[0];
                        android.view.ViewGroup parent = (android.view.ViewGroup) param.args[1];
                        boolean landscape = (param.args[2] instanceof Boolean) && (Boolean) param.args[2];

                        boolean hasSearch = false;
                        for (String tk : tokens) {
                            if (SearchContextualButtonFactory.isSearchToken(tk)) { hasSearch = true; break; }
                        }
                        if (!hasSearch) return;

                        // 逐个处理 wp7search token（通常在段内只出现一次）
                        java.util.List<String> rest = new java.util.ArrayList<>();
                        for (String tk : tokens) {
                            if (SearchContextualButtonFactory.isSearchToken(tk)) {
                                SearchContextualButtonFactory.inflateSearchButton(
                                        param.thisObject, tk, parent, landscape);
                            } else {
                                rest.add(tk);
                            }
                        }
                        // 用去掉 wp7search 后的数组替换，避免系统再处理（它会跳过）
                        String[] newTokens = rest.toArray(new String[0]);
                        param.args[0] = newTokens;
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": inflateButtons before search err: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked search contextual button (2C-1)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookSearchContextualButton err: " + t);
        }
    }

    /** 从布局串（形如 "a[x],b;c;d[x],e"）中移除所有 ime_switcher token（连同 weight）。 */
    private static String removeImeSwitchers(String layout) {
        if (layout == null || !layout.contains("ime_switcher")) return layout;
        String[] parts = layout.split(";", -1);
        for (int i = 0; i < parts.length; i++) {
            String[] items = parts[i].split(",", -1);
            StringBuilder sb = new StringBuilder();
            for (String it : items) {
                String name = it.trim();
                int br = name.indexOf('[');
                if (br >= 0) name = name.substring(0, br).trim();
                if (name.equals("ime_switcher")) continue;
                if (sb.length() > 0) sb.append(",");
                sb.append(it);
            }
            parts[i] = sb.toString();
        }
        return String.join(";", parts);
    }

    /** [2A 探测] 从 inflaterView 向上找 NavigationBarView（按类名匹配）。 */
    private static View findNavBarView(View v) {
        try {
            android.view.ViewParent p = v.getParent();
            while (p instanceof View) {
                View pv = (View) p;
                String cn = pv.getClass().getName();
                if (cn.endsWith("NavigationBarView")) return pv;
                p = pv.getParent();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // =====================================================================
    // Hook 3: NavigationBarView 生命周期 —— orientation 状态切换
    // =====================================================================

    private void hookNavigationBarViewLifecycle(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> navBarViewCls = SystemUiReflection.findClass(
                    "com.android.systemui.navigationbar.views.NavigationBarView", lpparam.classLoader);
            if (navBarViewCls == null) return;

            // onAttachedToWindow：导航栏 View 挂载（含重建 / 隐藏再显示）
            XposedBridge.hookAllMethods(navBarViewCls, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View navBarView = (View) param.thisObject;
                    NavigationBarController controller = getOrCreateController(navBarView);
                    controller.onAttached();
                }
            });

            // onConfigurationChanged：屏幕旋转等配置变化（orientation 唯一来源，方案第三节）
            XposedBridge.hookAllMethods(navBarViewCls, "onConfigurationChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View navBarView = (View) param.thisObject;
                    NavigationBarController controller = getOrCreateController(navBarView);
                    Object cfg = param.args != null && param.args.length > 0 ? param.args[0] : null;
                    if (cfg instanceof android.content.res.Configuration) {
                        controller.onConfigurationChanged((android.content.res.Configuration) cfg);
                    }
                }
            });

            XposedBridge.log(TAG + ": hooked NavigationBarView lifecycle (onAttachedToWindow/onConfigurationChanged)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookNavigationBarViewLifecycle err: " + t);
        }
    }

    // =====================================================================
    // Controller 管理（每实例一个，WeakHashMap 允许 GC，方案第十四节）
    // =====================================================================

    private NavigationBarController getOrCreateController(View navBarView) {
        NavigationBarController controller = controllers.get(navBarView);
        if (controller == null) {
            controller = new NavigationBarController(navBarView);
            controllers.put(navBarView, controller);
            XposedBridge.log(TAG + ": NavigationBarController created for " + navBarView);
        }
        return controller;
    }
}