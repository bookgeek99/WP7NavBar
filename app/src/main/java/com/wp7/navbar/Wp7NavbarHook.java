package com.wp7.navbar;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

/**
 * WP7 NavBar 核心 Hook
 *
 * 目标：把 HyperOS 3 三键导航栏的【返回】【主页】图标替换为 WP7 风格，
 *      【最近】保持原样。
 *
 * 关键观察（来自反编译 + 真机日志）：
 *  - NavigationBarInflaterView.inflateButtons 是创建按键的入口，但在它执行时
 *    KeyButtonView 还没被设置 drawable（日志显示 getDrawable()==null）。
 *  - 真正给按键设置图标的是 KeyButtonView.setImageDrawable(Drawable)。
 *  - HyperOS 的 KeyButtonView.setImageDrawable 会把传入 drawable 强转成
 *    KeyButtonDrawable，若直接 setImageDrawable(自定义Drawable) 会 ClassCastException。
 *
 * 因此方案：
 *  - Hook KeyButtonView.setImageDrawable 的 afterHookedMethod —— 此时系统已把
 *    KeyButtonDrawable 设置好（getDrawable() 非空）。
 *  - 通过 KeyButtonView 的 mCode(KEYCODE_BACK=4 / KEYCODE_HOME=3) 判断类型。
 *  - 反射替换 KeyButtonDrawable 的 mState.mChildState 为 WP7 图标的 ConstantState，
 *    这样在保留系统暗色/颜色/尺寸/ripple 逻辑的前提下，只替换绘制内容。
 */
public class Wp7NavbarHook implements IXposedHookLoadPackage {

    private static final String TAG = "WP7NavBar";
    private static final String PACKAGE_SYSTEMUI = "com.android.systemui";

    // KeyEvent 常量
    private static final int KEYCODE_HOME = 3;
    private static final int KEYCODE_BACK = 4;
    private static final int KEYCODE_APP_SWITCH = 187; // RECENTS

    // menu_ime 里的 menu 键（用作 WP 搜索按钮）的资源 id
    private static final int ID_MENU_BUTTON = 0x7f0b0750;

    // ime_switcher（输入法切换按钮）的资源 id
    private static final int ID_IME_SWITCHER = 0x7f0b05a3;

    // recent（多任务键）的资源 id
    private static final int ID_RECENT_BUTTON = 0x7f0b09d7;

    // 顶层导航栏 LinearLayout 的资源 id（其子段按 index 包含各键）
    private static final int ID_NAV_CONTAINER = 0x7f0b0443;

    // 边缘按钮的透明度（0.0 透明 ~ 1.0 不透明）
    private static final float EDGE_BUTTON_ALPHA = 0.45f;

    // 是否已强制显示 menu_container 内的 ime_switcher（menu_ime）
    private volatile boolean menuShown = false;

    // IME（输入法）当前是否可见。由 NavigationBar.setImeWindowStatus 维护，
    // 用于 View.setVisibility before hook 拦截：非搜索的 ime_switcher 在 IME 未弹出时一律压 GONE，
    // 避免重建/旋转后默认 VISIBLE 造成首帧闪现。
    private static volatile boolean imeVisibleCached = false;

    // 图标固定色（导航栏白色系；跟随系统缩放/暗度叠加由 KeyButtonDrawable 处理）
    private static final int ICON_COLOR = Color.WHITE;

    // 搜索键（右段 ime_switcher）的 View 引用，用于转发正常按钮的暗色 intensity
    private static android.widget.ImageView searchBtnView = null;

    // 横屏竖排模式标志：横屏时隐藏所有 ime_switcher，setButtonVisibility/force-visibility 应跳过
    private static boolean landscapeMode = false;

    // 搜索键开关状态缓存（由 getDefaultLayout hook 更新），供 applyEdgeFade 判断是否淡化 recent
    private static boolean searchBtnEnabledCached = true;

    // 从返回键读取的 light/dark 颜色，用于让搜索键与 back/home 颜色对齐
    private static int refLightColor = 0;
    private static int refDarkColor = 0;
    private static boolean refColorLoaded = false;

    // 强制搜索键可见时的递归防护
    private static boolean inForceVis = false;
    // 从 back/home 读取的阴影参数，让搜索键带与它们一致的阴影
    private static int refShadowOffsetX = 0;
    private static int refShadowOffsetY = 0;
    private static int refShadowSize = 0;
    private static int refShadowColor = 0;

    // 阶段A：在创建入口标记搜索键，setImageDrawable before hook 直接替换
    private static final java.util.Set<ImageView> searchMarkedViews =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<ImageView, Boolean>());

    // 我们给搜索键设置的 View 层监听器实例（View -> listener），用于 setOnClickListener after
    // 检测是否被 dispatcher 的 mClickListener 覆盖，若是则重设回我们的搜索监听器（时序无关兜底）。
    private static final java.util.WeakHashMap<ImageView, View.OnClickListener> searchClickListeners =
            new java.util.WeakHashMap<ImageView, View.OnClickListener>();
    private static final java.util.WeakHashMap<ImageView, View.OnLongClickListener> searchLongClickListeners =
            new java.util.WeakHashMap<ImageView, View.OnLongClickListener>();
    // 防止 after hook 里重设监听器再次触发 hook 造成递归
    private static boolean inRestoreListener = false;

    // 阶段A增强：被标记为搜索键宿主的 ButtonDispatcher（其当前 View 是搜索键时，替换下发图标）
    private static final java.util.Set<Object> searchMarkedDispatchers =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>());


    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(PACKAGE_SYSTEMUI)) {
            return;
        }
        XposedBridge.log(TAG + ": SystemUI loaded. Setting up hooks.");

        try {
            // Hook KeyButtonView.setImageDrawable —— 真正设置图标的地方
            Class<?> keyBtnCls = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.views.buttons.KeyButtonView", lpparam.classLoader);
            XposedBridge.hookAllMethods(keyBtnCls, "setImageDrawable", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof ImageView)) return;
                        ImageView iv = (ImageView) param.thisObject;

                        // 搜索键：在 before 直接把传入 KeyButtonDrawable 的 childState 替换为 WP7 搜索图标，
                        // 避免“先显示输入法图标，再事后替换”的闪现。
                        if (iv.getId() == ID_IME_SWITCHER) {
                            boolean marked = isMarkedSearchButton(iv);
                            if (!marked && isSearchButtonEnabled(iv) && isSearchButtonStructuralStrict(iv)) {
                                searchMarkedViews.add(iv);
                                marked = true;
                                XposedBridge.log(TAG + ": [A] structural(strict) marked search in setImageDrawable");
                            }
                            Drawable incoming = param.args != null && param.args.length > 0
                                    ? (Drawable) param.args[0] : null;
                            XposedBridge.log(TAG + ": [A] ime_switcher setImageDrawable marked=" + marked
                                    + " incoming=" + (incoming != null ? incoming.getClass().getSimpleName() : "null"));
                            if (marked) {
                                Drawable kd = createSearchKeyButtonDrawable(iv, iv.getClass().getClassLoader());
                                if (kd != null) {
                                    param.args[0] = kd;
                                }
                                setupSearchButton(iv, false);
                                XposedBridge.log(TAG + ": [A] before replace search icon id=0x"
                                        + Integer.toHexString(iv.getId()));
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": setImageDrawable before err: " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof ImageView) {
                            ImageView iv = (ImageView) param.thisObject;
                            applyEdgeFade(iv);
                            replaceIfWp7(iv);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": setImageDrawable after err: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked KeyButtonView.setImageDrawable");

            // 阶段A增强：hook ButtonDispatcher.addView after —— 当已标记的搜索键 View 注册进 dispatcher 时，
            // 记录该 dispatcher，供后续精确识别。注意：不在 dispatcher 层统一替换图标，
            // 因为 ime_switcher dispatcher 同时管理左段输入法键和右段搜索键，
            // 统一替换会把左段输入法键也变成搜索图标。
            try {
                Class<?> btnDispCls = XposedHelpers.findClass(
                        "com.android.systemui.navigationbar.views.buttons.ButtonDispatcher", lpparam.classLoader);
                XposedBridge.hookAllMethods(btnDispCls, "addView", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length == 0) return;
                            Object view = param.args[0];
                            if (!(view instanceof ImageView)) return;
                            final ImageView iv = (ImageView) view;
                            if (iv.getId() != ID_IME_SWITCHER) return;
                            final Object dispatcher = param.thisObject;
                            // addView 时 View 可能尚未完全 attach（父链/recent 未就绪），结构判定会失败，
                            // 导致横屏搜索键的 View 层监听器被 dispatcher 的 mClickListener(输入法切换)覆盖后
                            // 没有兜底设回。这里 post 到下一帧再判定：此时 View 已 attach，结构判定可靠。
                            iv.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        boolean isSearch = isMarkedSearchButton(iv) || isSearchButtonStructuralStrict(iv);
                                        if (!isSearch) return;
                                        searchMarkedViews.add(iv);
                                        searchMarkedDispatchers.add(dispatcher);
                                        // 重设 View 层监听器（覆盖 addView 时 dispatcher 写入的输入法切换 listener）
                                        setupSearchButton(iv, false);
                                        // 注意：不再调 setupSearchDispatcherListeners —— dispatcher 层监听器会被
                                        // 左段输入法键共享（同一 ime_switcher dispatcher），addView 时用 dispatcher
                                        // 的 mClickListener 覆盖左段键，导致左段键点击也变小爱。只靠 View 层监听器
                                        // + View.setOnClickListener before 保护即可。
                                        XposedBridge.log(TAG + ": [A] post-addView search listener restored marked="
                                                + isMarkedSearchButton(iv));
                                    } catch (Throwable t) {
                                        XposedBridge.log(TAG + ": post-addView listener err: " + t);
                                    }
                                }
                            });
                        } catch (Throwable t) { }
                    }
                });
                XposedBridge.log(TAG + ": hooked ButtonDispatcher.addView (mark search dispatcher)");

                // 修复：ime_switcher dispatcher 同时管理左段输入法键和右段搜索键（横竖屏两套 View）。
                // ButtonDispatcher.setVisibility(int) 会把 mViews 里所有 View 刷成同一个 visibility，
                // 导致重建后 / IME 状态刷新时左段输入法键被一起刷成 VISIBLE 而冒出来。
                // 在 after 里逐 View 纠正：已标记搜索键 → 强制 VISIBLE；非搜索键（左段输入法键）→ 跟随目标值。
                XposedBridge.hookAllMethods(btnDispCls, "setVisibility", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length == 0) return;
                            Object target = param.args[0];
                            if (!(target instanceof Integer)) return;
                            int targetVis = (Integer) target;
                            // 只处理 ime_switcher dispatcher（含搜索键的 dispatcher）
                            if (!searchMarkedDispatchers.contains(param.thisObject)) return;
                            Object viewsObj = getField(param.thisObject, "mViews");
                            if (!(viewsObj instanceof java.util.List)) return;
                            java.util.List<?> views = (java.util.List<?>) viewsObj;
                            for (Object o : views) {
                                if (!(o instanceof ImageView)) continue;
                                ImageView iv = (ImageView) o;
                                if (iv.getId() != ID_IME_SWITCHER) continue;
                                if (isMarkedSearchButton(iv)) {
                                    // 搜索键永远可见
                                    if (iv.getVisibility() != View.VISIBLE) {
                                        iv.setVisibility(View.VISIBLE);
                                    }
                                } else {
                                    // 左段输入法键：跟随 IME 状态（dispatcher 的目标值）
                                    if (iv.getVisibility() != targetVis) {
                                        iv.setVisibility(targetVis);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": dispatcher setVisibility correct err: " + t);
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked ButtonDispatcher.setVisibility (per-view correct)");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": ButtonDispatcher hook err: " + t);
            }

            // setDarkIntensity：把正常按钮（back/home/recent 等）收到的暗色 intensity 转发给搜索键，
            // 因为系统对 ime_switcher 调用的 intensity 恒为 0.0（固定），导致搜索键颜色不变。
            XposedBridge.hookAllMethods(keyBtnCls, "setDarkIntensity", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof ImageView) {
                            ImageView iv = (ImageView) param.thisObject;
                            float intensity = param.args.length > 0 ? (Float) param.args[0] : -1f;
                            int iid = iv.getId();
                            // 正常按钮（back/home/recent，非 ime_switcher、非 menu）才转发
                            boolean normalBtn = (iid != ID_IME_SWITCHER && iid != ID_MENU_BUTTON);
                            if (normalBtn && searchBtnView != null) {
                                // 转发 intensity 给搜索键（用反射调 KeyButtonView.setDarkIntensity）
                                try {
                                    XposedHelpers.callMethod(searchBtnView, "setDarkIntensity", intensity);
                                    XposedBridge.log(TAG + ": [fwd] normal id=0x" + Integer.toHexString(iid)
                                            + " intensity=" + intensity + " -> search("
                                            + searchBtnView.getClass().getSimpleName() + ") via callMethod");
                                } catch (Throwable t) {
                                    // ignore，若搜索键不是 KeyButtonView 则直接对 drawable 处理
                                    try {
                                        Drawable d = searchBtnView.getDrawable();
                                        if (d != null) {
                                            XposedHelpers.callMethod(d, "setDarkIntensity", intensity);
                                            XposedBridge.log(TAG + ": [fwd] normal id=0x" + Integer.toHexString(iid)
                                                    + " intensity=" + intensity + " -> search drawable"
                                                    + " (callMethod failed: " + t + ")");
                                        }
                                    } catch (Throwable t2) { }
                                }
                            }
                            // 诊断：确认搜索键收到的 intensity
                            if (iid == ID_IME_SWITCHER) {
                                XposedBridge.log(TAG + ": [dark] ime_switcher intensity=" + intensity
                                        + " isSearch=" + (searchBtnView == iv)
                                        + " cls=" + iv.getClass().getSimpleName());
                            }
                        }
                    } catch (Throwable t) { }
                }
            });
            XposedBridge.log(TAG + ": hooked KeyButtonView.setDarkIntensity (forward to search)");

            // 强制搜索键始终可见：解除与输入法状态的关联（IME 收起后搜索键仍保持显示）
            // before：拦截非搜索（左段输入法键）ime_switcher 被设为 VISIBLE —— IME 未弹出时压成 GONE，
            //         避免重建/旋转后默认 VISIBLE 造成首帧闪现（after 已经渲染了一帧才压就来不及了）。
            XposedBridge.hookAllMethods(android.view.View.class, "setVisibility", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0) return;
                        if (!(param.thisObject instanceof ImageView)) return;
                        ImageView iv = (ImageView) param.thisObject;
                        if (iv.getId() != ID_IME_SWITCHER) return;
                        Object arg = param.args[0];
                        if (!(arg instanceof Integer)) return;
                        int vis = (Integer) arg;
                        if (vis != View.VISIBLE) return; // 只拦"设为可见"
                        if (imeVisibleCached) return;    // IME 弹出时左段键该显示，放行
                        // 搜索键永远放行（它本来就该常驻）
                        if (isMarkedSearchButton(iv)) return;
                        if (isSearchButtonStructuralStrict(iv)) return;
                        // 非搜索左段键 + IME 未弹出 → 压 GONE，阻止首帧可见
                        param.args[0] = View.GONE;
                    } catch (Throwable t) { }
                }
            });
            XposedBridge.log(TAG + ": hooked View.setVisibility (block left-ime visible when IME hidden)");

            // 根治监听器被覆盖的竞争：改为 before 拦截，让覆盖根本不发生。
            // addView(ButtonDispatcher:68) 用 mClickListener(输入法切换) 覆盖注册 View 的监听器；
            // 若用 after 恢复，覆盖与恢复之间有窗口期（点快就撞上 = 输入法切换）。
            // before 里直接把 param.args[0] 改写成我们的搜索监听器，覆盖即被替换，无窗口。
            XposedBridge.hookAllMethods(android.view.View.class, "setOnClickListener", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (inRestoreListener) return;
                        if (!(param.thisObject instanceof ImageView)) return;
                        ImageView iv = (ImageView) param.thisObject;
                        if (iv.getId() != ID_IME_SWITCHER) return;
                        Object incoming = param.args != null && param.args.length > 0 ? param.args[0] : null;
                        if (incoming == null) return; // 清除监听器，放行
                        // 严格判定：只保护"已被 setupSearchButton 正常配置过"的搜索键
                        // （searchClickListeners 里有我们的监听器实例）。绝不用结构兜底，也不用
                        // isMarkedSearchButton 之外的方式主动 setup —— 否则旋转过渡期父链不稳定会
                        // 误判左段输入法键为搜索键，把它的点击也改成小爱（严重回归）。
                        View.OnClickListener ours = searchClickListeners.get(iv);
                        if (ours == null) return; // 没配置过 = 不是我们要保护的搜索键，放行
                        if (incoming == ours) return; // 设的就是我们的，正常
                        // 已确认的搜索键要被设成别的 listener（dispatcher 输入法切换）→ 改写成我们的
                        param.args[0] = ours;
                        XposedBridge.log(TAG + ": search click listener PROTECTED (rewrite before)");
                    } catch (Throwable t) { inRestoreListener = false; }
                }
            });
            XposedBridge.hookAllMethods(android.view.View.class, "setOnLongClickListener", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (inRestoreListener) return;
                        if (!(param.thisObject instanceof ImageView)) return;
                        ImageView iv = (ImageView) param.thisObject;
                        if (iv.getId() != ID_IME_SWITCHER) return;
                        Object incoming = param.args != null && param.args.length > 0 ? param.args[0] : null;
                        if (incoming == null) return;
                        // 严格判定：只保护已配置过的搜索键，不用结构兜底/主动 setup，避免误伤左段键
                        View.OnLongClickListener ours = searchLongClickListeners.get(iv);
                        if (ours == null) return;
                        if (incoming == ours) return;
                        param.args[0] = ours;
                        XposedBridge.log(TAG + ": search longclick listener PROTECTED (rewrite before)");
                    } catch (Throwable t) { inRestoreListener = false; }
                }
            });
            XposedBridge.log(TAG + ": hooked View.setOnClickListener/setOnLongClickListener (protect-before)");

            XposedBridge.hookAllMethods(android.view.View.class, "setVisibility", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof ImageView)) return;
                        ImageView iv = (ImageView) param.thisObject;
                        // 所有被标记为搜索键的 View 都强制可见（横竖屏两套）
                        if (searchMarkedViews.contains(iv) && !inForceVis) {
                            if (iv.getVisibility() != View.VISIBLE) {
                                inForceVis = true;
                                iv.setVisibility(View.VISIBLE);
                                inForceVis = false;
                                XposedBridge.log(TAG + ": search FORCED VISIBLE id=0x"
                                        + Integer.toHexString(iv.getId()));
                            }
                        }
                    } catch (Throwable t) { }
                }
            });
            XposedBridge.log(TAG + ": hooked View.setVisibility (force search visible)");

            // ===== 探测：dump 当前导航栏布局字符串 =====
            try {
                Class<?> inflaterCls = XposedHelpers.findClass(
                        "com.android.systemui.navigationbar.views.NavigationBarInflaterView", lpparam.classLoader);
                // hook getDefaultLayout，注入 ime_switcher 到左段（测试版）
                XposedBridge.hookAllMethods(inflaterCls, "getDefaultLayout", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            String orig = (String) param.getResult();
                            if (orig == null) return;
                            // 只有设置开启"左侧切换输入法"时才注入 ime_switcher
                            boolean imeEnabled = isImeSwitcherEnabled(param.thisObject);
                            // 搜索键开关：开启才把 recent 移到最右并注入搜索键；关闭则右段保持系统默认
                            boolean searchBtn = isSearchButtonEnabled(param.thisObject);
                            searchBtnEnabledCached = searchBtn;
                            XposedBridge.log(TAG + "[probe] getDefaultLayout = " + orig
                                    + " (imeEnabled=" + imeEnabled + " searchBtn=" + searchBtn + ")");
                            String injected = injectLayouts(orig, imeEnabled, searchBtn);
                            if (!injected.equals(orig)) {
                                XposedBridge.log(TAG + "[probe] injected = " + injected);
                                param.setResult(injected);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "[probe] getDefaultLayout err: " + t);
                        }
                    }
                });
                // hook inflateLayout(String)，看传入的布局字符串 + 初始化搜索按钮
                XposedBridge.hookAllMethods(inflaterCls, "inflateLayout", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length > 0) {
                                XposedBridge.log(TAG + "[probe] inflateLayout = " + param.args[0]);
                            }
                        } catch (Throwable t) { }
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            clearNavBarSideMargins(param.thisObject);
                            // 阶段A：创建后立刻在结构层标记搜索键，before hook 即可生效
                            markSearchButtonsFromInflater(param.thisObject);
                            // 修复：重建后新 View 默认 VISIBLE，左段输入法键会冒出来。
                            // 按各 ime_switcher dispatcher 记录的 mVisibility（系统认为的 IME 可见性）纠正。
                            correctImeSwitchersAfterInflate(param.thisObject);
                            try {
                                if (param.thisObject instanceof android.view.View) {
                                    ((android.view.View) param.thisObject).requestLayout();
                                }
                            } catch (Throwable ignored) { }
                            if (param.thisObject instanceof android.view.View) {
                                final android.view.View v = (android.view.View) param.thisObject;
                                v.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            if (isLandscape(v)) {
                                                landscapeMode = true;
                                                // 阶段B：横屏保留搜索键，正常配置
                                                setupSearchButtonInHierarchy(v);
                                                return;
                                            }
                                            landscapeMode = false;
                                            setupSearchButtonInHierarchy(v);
                                        } catch (Throwable t) {
                                            XposedBridge.log(TAG + ": search delayed err: " + t);
                                        }
                                    }
                                }, 800L);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": inflateLayout after err: " + t);
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked layout probes");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": probe setup err: " + t);
            }

            // ===== 处理 ContextualButtonGroup 对 ime_switcher 的隐藏：强制 menu_ime 常驻显示 =====
            try {
                Class<?> cbgCls = XposedHelpers.findClass(
                        "com.android.systemui.navigationbar.views.buttons.ContextualButtonGroup", lpparam.classLoader);
                XposedBridge.hookAllMethods(cbgCls, "setButtonVisibility", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length > 1) {
                                Integer id = (Integer) param.args[0];
                                Boolean vis = (Boolean) param.args[1];
                                if (id != null && id == ID_IME_SWITCHER && Boolean.FALSE.equals(vis)) {
                                    // 横屏竖排：不强制显示，允许隐藏（配合 hideAllImeSwitchers 回归系统三键）
                                    if (landscapeMode) {
                                        // 阶段B：横屏也强制显示搜索键
                                        if (!searchBtnEnabledCached) {
                                            return;
                                        }
                                        param.args[1] = true;
                                        XposedBridge.log(TAG + ": [setButtonVisibility] FORCED LANDSCAPE id=0x"
                                                + Integer.toHexString(id) + " markedVisible=false->true");
                                        return;
                                    }
                                    // 搜索键关闭时：不强制显示 menu_ime（输入法键），让系统正常隐藏它，
                                    // 避免右侧多出一个输入法键。仅开启搜索键时才强制常驻显示。
                                    // 用 searchBtnEnabledCached（getDefaultLayout 阶段已更新），
                                    // 避免 getContextFromObject 从 ContextualButtonGroup 拿不到 context 而误判为开启。
                                    if (!searchBtnEnabledCached) {
                                        return; // 搜索键关闭：不强制显示，交给系统正常隐藏
                                    }
                                    // 强制 menu_ime(ime_switcher) 保持 markedVisible=true，防止被系统隐藏
                                    param.args[1] = true;
                                    XposedBridge.log(TAG + ": [setButtonVisibility] FORCED id=0x"
                                            + Integer.toHexString(id) + " markedVisible=false->true");
                                }
                            }
                        } catch (Throwable t) { }
                    }
                });
                XposedBridge.log(TAG + ": hooked ContextualButtonGroup.setButtonVisibility (force menuime)");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": setButtonVisibility hook err: " + t);
            }

            // ===== 去掉导航栏左右 inset 避让（贴边显示）=====
            try {
                Class<?> navBarViewCls = XposedHelpers.findClass(
                        "com.android.systemui.navigationbar.views.NavigationBarView", lpparam.classLoader);
                XposedBridge.hookAllMethods(navBarViewCls, "onApplyWindowInsets", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object v = param.thisObject;
                            if (v instanceof android.view.View) {
                                android.view.View view = (android.view.View) v;
                                XposedBridge.log(TAG + ": onApplyWindowInsets triggered, padL="
                                        + view.getPaddingLeft() + " padR=" + view.getPaddingRight());
                                int top = view.getPaddingTop();
                                int bottom = view.getPaddingBottom();
                                view.setPadding(0, top, 0, bottom);
                            }
                            // 强制显示 menu_container 内的 ime_switcher（menu_ime），作为 recent 左侧的搜索按钮
                            if (!menuShown && v != null) {
                                try {
                                    Object group = getField(v, "mContextualButtonGroup");
                                    if (group != null) {
                                        menuShown = true;
                                        // 搜索键开关关闭时不强制显示，让系统恢复正常（输入法键随 IME 显隐）
                                        if (isSearchButtonEnabled(v)) {
                                            XposedHelpers.callMethod(group, "setButtonVisibility", ID_IME_SWITCHER, true);
                                            XposedBridge.log(TAG + ": forced menuime(ime_switcher) visible in contextual group");
                                        }
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": force menuime err: " + t);
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": onApplyWindowInsets after err: " + t);
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked NavigationBarView.onApplyWindowInsets");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": onApplyWindowInsets setup err: " + t);
            }

            // ===== 旋转 / 导航栏重建、隐藏再显示时重新配置搜索键 =====
            // 根因：屏幕旋转或导航栏隐藏/重显时，系统不会重新走 inflateLayout，
            //       导致新的 ime_switcher 未被 setupSearchButton 处理，搜索键回退成输入法切换键。
            // 解决：hook NavigationBarView.onAttachedToWindow（重建/重显）和 onConfigurationChanged（旋转），
            //       重新触发搜索键配置。
            try {
                Class<?> navBarViewCls2 = XposedHelpers.findClass(
                        "com.android.systemui.navigationbar.views.NavigationBarView", lpparam.classLoader);
                // onAttachedToWindow：导航栏 View 重新挂载时触发（重建 / 隐藏再显示）
                XposedBridge.hookAllMethods(navBarViewCls2, "onAttachedToWindow", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof android.view.View) {
                                final android.view.View v = (android.view.View) param.thisObject;
                                v.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        reconfigureSearchButton(v);
                                    }
                                }, 800L);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": onAttachedToWindow reconfigure err: " + t);
                        }
                    }
                });
                // onConfigurationChanged：屏幕旋转时触发
                XposedBridge.hookAllMethods(navBarViewCls2, "onConfigurationChanged", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof android.view.View) {
                                final android.view.View v = (android.view.View) param.thisObject;
                                v.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        reconfigureSearchButton(v);
                                    }
                                }, 800L);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": onConfigurationChanged reconfigure err: " + t);
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked NavigationBarView onAttachedToWindow/onConfigurationChanged (search reconfig)");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": search reconfig hook err: " + t);
            }

            // ===== 强制 ime_switcher 随 IME 状态显示/隐藏 =====
            try {
                Class<?> navBarCls = XposedHelpers.findClass(
                        "com.android.systemui.navigationbar.views.NavigationBar", lpparam.classLoader);
                XposedBridge.hookAllMethods(navBarCls, "setImeWindowStatus", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // 更新 IME 可见性缓存，供 View.setVisibility before hook 判断
                            Boolean vis = (Boolean) getField(param.thisObject, "mImeVisible");
                            if (vis != null) {
                                imeVisibleCached = vis;
                            }
                            updateImeSwitcherVisibility(param.thisObject);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": setImeWindowStatus after err: " + t);
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked setImeWindowStatus");

                // ===== 点击 ime_switcher 时改为弹出输入法选择菜单（而非循环切换）=====
                try {
                    Class<?> imeMgrCls = XposedHelpers.findClass(
                            "android.view.inputmethod.InputMethodManager", lpparam.classLoader);
                    XposedBridge.hookAllMethods(navBarCls, "onImeSwitcherClick", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 从 NavigationBar 读 displayId，调 showInputMethodPickerFromSystem(true, displayId)
                                Object imm = getField(param.thisObject, "mInputMethodManager");
                                Integer displayId = (Integer) getField(param.thisObject, "mDisplayId");
                                if (imm != null && displayId != null) {
                                    XposedBridge.log(TAG + ": ime switch click -> show picker");
                                    XposedHelpers.callMethod(imm, "showInputMethodPickerFromSystem", true, (int) displayId);
                                    param.setResult(null); // 阻止原循环切换逻辑
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": onImeSwitcherClick hook err: " + t);
                            }
                        }
                    });
                    XposedBridge.log(TAG + ": hooked onImeSwitcherClick");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": onImeSwitcherClick setup err: " + t);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": ime hook setup err: " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setup err: " + t);
        }
    }

    /** 判断 KeyButtonView 是否为 back/home，若是则替换其图标内部 childState */
    private void replaceIfWp7(ImageView iv) {
        int type = resolveButtonType(iv);
        if (type == 0) {
            return; // 不是 back/home，跳过（recents 保持原样）
        }
        try {
            Drawable d = iv.getDrawable();
            if (d == null) {
                XposedBridge.log(TAG + ": " + typeName(type) + " getDrawable null, skip");
                return;
            }
            Object childState = createWp7ConstantState(type);
            if (childState == null) {
                return;
            }
            // KeyButtonDrawable 有 mState 字段；替换 mState.mChildState
            Object stateField = getField(d, "mState");
            if (stateField != null) {
                setField(stateField, "mChildState", childState);
                XposedBridge.log(TAG + ": replaced " + typeName(type) + " icon");
            } else {
                XposedBridge.log(TAG + ": " + typeName(type) + " no mState field");
            }
            iv.invalidate();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": replace " + typeName(type) + " err: " + t);
        }
    }

    /**
     * 让边缘按钮（输入法切换 / 多任务）变淡。
     * 识别：ime_switcher 用 id；recent 用 mCode(KEYCODE_APP_SWITCH) 或 contentDescription。
     */
    private void applyEdgeFade(ImageView iv) {
        try {
            // 搜索键不淡化（保持不透明，与 back/home 一致）
            if (iv == searchBtnView) {
                iv.setAlpha(1.0f);
                return;
            }
            boolean isEdge = false;
            // ime_switcher
            if (iv.getId() == ID_IME_SWITCHER) {
                isEdge = true;
            }
            // recent / 多任务：mCode==82(MENU) 或 contentDescription 含"任务键"
            if (!isEdge) {
                try {
                    Object code = getField(iv, "mCode");
                    if (code instanceof Integer && (Integer) code == KEYCODE_APP_SWITCH) {
                        isEdge = true;
                    }
                } catch (Throwable ignored) { }
                if (!isEdge) {
                    CharSequence cd = iv.getContentDescription();
                    if (cd != null && (String.valueOf(cd).contains("任务键")
                            || String.valueOf(cd).toLowerCase().contains("recents")
                            || String.valueOf(cd).contains("最近"))) {
                        isEdge = true;
                    }
                }
            }
            // 搜索键关闭时，或横屏竖排模式：任务键（recent）恢复不透明、不缩小，回归系统三键样式
            boolean isRecentBtn = iv.getId() != ID_IME_SWITCHER;
            if (isEdge && (landscapeMode || !searchBtnEnabledCached) && isRecentBtn) {
                iv.setAlpha(1.0f);
                XposedBridge.log(TAG + ": recent restored opaque (landscape/searchOff)");
                return;
            }
            if (isEdge) {
                iv.setAlpha(EDGE_BUTTON_ALPHA);
                XposedBridge.log(TAG + ": applyEdgeFade on id=" + iv.getId()
                        + " code=" + getField(iv, "mCode") + " alpha=" + EDGE_BUTTON_ALPHA);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": applyEdgeFade err: " + t);
        }
    }

    private Object createWp7ConstantState(int type) {
        try {
            int size = 48;
            String t = type == KEYCODE_BACK ? "BACK" : "HOME";
            Drawable icon = new Wp7IconDrawable(size, ICON_COLOR, t);
            return icon.getConstantState();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": createConstantState err: " + t);
            return null;
        }
    }

    private int resolveButtonType(ImageView iv) {
        // 优先用 mCode 字段判断（KeyButtonView 私有字段）
        try {
            Object code = getField(iv, "mCode");
            if (code instanceof Integer) {
                int c = (Integer) code;
                if (c == KEYCODE_BACK) return KEYCODE_BACK;
                if (c == KEYCODE_HOME) return KEYCODE_HOME;
            }
        } catch (Throwable ignored) { }

        // 兜底：contentDescription
        try {
            CharSequence cd = iv.getContentDescription();
            if (cd != null) {
                String s = cd.toString().toLowerCase();
                if (s.contains("back") || s.contains("返回")) return KEYCODE_BACK;
                if (s.contains("home") || s.contains("主页") || s.contains("主屏") || s.contains("主屏幕")) return KEYCODE_HOME;
            }
        } catch (Throwable ignored) { }

        return 0;
    }

    private String typeName(int type) {
        return type == KEYCODE_BACK ? "BACK" : "HOME";
    }

    /** 判断是否为可作搜索按钮的 ime_switcher（右段注入的那个）。 */
    private boolean isSearchButton(ImageView iv) {
        try {
            if (!(iv instanceof ImageView) || iv.getId() != ID_IME_SWITCHER) return false;
            return isImeSwitcherAdjacentLeftOfRecent(iv);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 判断该 ime_switcher 是否为“右段搜索键”。
     * 依据“该 ime_switcher 在视觉上紧邻 recent 键左侧”判定。
     * 用 getLocationOnScreen 的屏幕 X 坐标比较：搜搜键位于 recent 左侧，且与 recent 的水平距离较小。
     * 左段 ime_switcher 离 recent 很远（中间隔着 back/home），故不被误判。
     * 该相对位置关系在横屏（竖排/反向布局）下同样成立，不依赖反向布局的 child index。
     */
    private boolean isImeSwitcherAdjacentLeftOfRecent(ImageView iv) {
        // 向上找到导航栏根容器（NavigationBarView），在其下遍历找 recent 键
        View recent = null;
        android.view.ViewParent np = iv.getParent();
        while (np != null) {
            if (np instanceof ViewGroup) {
                recent = findRecentRecursive((ViewGroup) np, iv);
                if (recent != null) break;
            }
            np = np.getParent();
        }
        if (recent == null) {
            XposedBridge.log(TAG + ": [search] recent not found, not search");
            return false;
        }
        int[] li = new int[2], lr = new int[2];
        try {
            iv.getLocationOnScreen(li);
            recent.getLocationOnScreen(lr);
        } catch (Throwable t) {
            return false;
        }
        float imeCx = li[0] + iv.getWidth() / 2f;
        float recentCx = lr[0] + recent.getWidth() / 2f;
        float dist = recentCx - imeCx;
        // 搜索键应在 recent 左侧（dist>0），且水平距离在合理范围（紧邻，不跨 back/home）
        boolean leftOfRecent = dist > 0;
        boolean adjacent = dist <= dp(220); // 约一个键宽+margin，横屏竖排亦成立
        boolean isSearch = leftOfRecent && adjacent;
        XposedBridge.log(TAG + ": [search] imeCx=" + imeCx + " recentCx=" + recentCx
                + " dist=" + dist + " leftOfRecent=" + leftOfRecent
                + " adjacent=" + adjacent + " isSearch=" + isSearch);
        return isSearch;
    }

    /** 在指定容器内递归查找 recent 键（不含 iv 自身）。 */
    private View findRecentRecursive(ViewGroup vg, View exclude) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c == exclude) continue;
            if (isRecentView(c)) return c;
            if (c instanceof ViewGroup) {
                View r = findRecentRecursive((ViewGroup) c, exclude);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 判断是否为 recent（任务键）。综合 id / mCode / contentDescription。 */
    private boolean isRecentView(View v) {
        try {
            if (v.getId() == ID_RECENT_BUTTON) return true;
            Object code = getField(v, "mCode");
            if (code instanceof Integer && ((Integer) code) == KEYCODE_APP_SWITCH) return true;
            if (v instanceof ImageView) {
                CharSequence cd = v.getContentDescription();
                if (cd != null && (String.valueOf(cd).contains("任务键")
                        || String.valueOf(cd).toLowerCase().contains("recents")
                        || String.valueOf(cd).contains("最近"))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 判断一个 View 是否为返回/主页功能键（用于结构判断：搜索键中间不被它们隔开）。 */
    private boolean isBackOrHomeView(View v) {
        try {
            if (v instanceof ImageView) {
                Object code = getField(v, "mCode");
                if (code instanceof Integer) {
                    int c = (Integer) code;
                    if (c == KEYCODE_BACK || c == KEYCODE_HOME) return true;
                }
                CharSequence cd = v.getContentDescription();
                if (cd != null) {
                    String s = cd.toString().toLowerCase();
                    if (s.contains("back") || s.contains("返回")
                            || s.contains("home") || s.contains("主页") || s.contains("主屏")) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) { }
        return false;
    }

    /**
     * 旋转 / 导航栏重建、隐藏再显示时重新配置搜索键。
     * 复位 menuShown 并重跑 setupSearchButtonInHierarchy，确保新的 ime_switcher 被配置成搜索键。
     */
    private void reconfigureSearchButton(Object navView) {
        try {
            if (navView == null) return;
            // 横屏竖排布局：搜索键/输入法键注入不适配，把所有 ime_switcher 隐藏，
            // 让横屏导航栏回归系统默认三键（back/home/recent），避免错位与"输入法键一直显示"。
            if (navView instanceof android.view.View) {
                int orient = ((android.view.View) navView).getResources().getConfiguration().orientation;
                if (orient == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    landscapeMode = true;
                    XposedBridge.log(TAG + ": reconfigureSearchButton (LANDSCAPE) -> setup search");
                    menuShown = false;
                    setupSearchButtonInHierarchy(navView);
                    return;
                } else {
                    landscapeMode = false;
                }
            }
            // 复位 menuShown，让 onApplyWindowInsets 能重新强制 menuime 可见
            menuShown = false;
            XposedBridge.log(TAG + ": reconfigureSearchButton (rotate/re-show)");
            if (navView instanceof android.view.View) {
                final android.view.View v = (android.view.View) navView;
                // 等导航栏完成布局（坐标有效）后再配置，避免 dist=0 导致识别失败
                if (!v.isLaidOut() || v.isLayoutRequested()) {
                    try {
                        v.getViewTreeObserver().addOnGlobalLayoutListener(
                                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                                    @Override
                                    public void onGlobalLayout() {
                                        try {
                                            v.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                        } catch (Throwable t) { }
                                        try {
                                            setupSearchButtonInHierarchy(v);
                                        } catch (Throwable t) {
                                            XposedBridge.log(TAG + ": reconfig (layout) err: " + t);
                                        }
                                    }
                                });
                        return;
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": reconfig addOnGlobalLayout err: " + t);
                    }
                }
                setupSearchButtonInHierarchy(v);
            } else {
                setupSearchButtonInHierarchy(navView);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": reconfigureSearchButton err: " + t);
        }
    }

    /** 判断导航栏当前是否横屏。 */
    private boolean isLandscape(View v) {
        try {
            return v.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从 inflater 的视图树中递归查找并配置搜索按钮（menu 键）。 */
    private void setupSearchButtonInHierarchy(Object inflater) {
        if (inflater instanceof View) {
            collectRefColor((View) inflater);
            setupSearchButtonInView((View) inflater);
        }
    }

    /**
     * 深度遍历导航栏，找到 back/home 键，读取其 KeyButtonDrawable 的
     * mLightColor/mDarkColor，用于让搜索键与 back/home 颜色完全一致。
     */
    private void collectRefColor(View root) {
        try {
            if (refColorLoaded) return;
            collectRefColorRecursive((android.view.ViewGroup) root);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": collectRefColor err: " + t);
        }
    }

    private void collectRefColorRecursive(android.view.ViewGroup vg) {
        if (refColorLoaded) return;
        try {
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof android.widget.ImageView) {
                    int cid = child.getId();
                    // back=0x7f0b0153, home=0x7f0b0565
                    if (cid == 0x7f0b0153 || cid == 0x7f0b0565) {
                        try {
                            Drawable d = ((android.widget.ImageView) child).getDrawable();
                            Object st = getField(d, "mState");
                            if (st != null) {
                                Object lc = getField(st, "mLightColor");
                                Object dc = getField(st, "mDarkColor");
                                if (lc instanceof Integer && dc instanceof Integer) {
                                    refLightColor = (Integer) lc;
                                    refDarkColor = (Integer) dc;
                                    // 读取阴影参数
                                    Object sox = getField(st, "mShadowOffsetX");
                                    Object soy = getField(st, "mShadowOffsetY");
                                    Object ssz = getField(st, "mShadowSize");
                                    Object scl = getField(st, "mShadowColor");
                                    if (sox instanceof Integer) refShadowOffsetX = (Integer) sox;
                                    if (soy instanceof Integer) refShadowOffsetY = (Integer) soy;
                                    if (ssz instanceof Integer) refShadowSize = (Integer) ssz;
                                    if (scl instanceof Integer) refShadowColor = (Integer) scl;
                                    refColorLoaded = true;
                                    XposedBridge.log(TAG + ": ref color from id=0x" + Integer.toHexString(cid)
                                            + " light=0x" + Integer.toHexString(refLightColor)
                                            + " dark=0x" + Integer.toHexString(refDarkColor)
                                            + " shadowSize=" + refShadowSize
                                            + " off=(" + refShadowOffsetX + "," + refShadowOffsetY + ")"
                                            + " color=0x" + Integer.toHexString(refShadowColor));
                                    return;
                                }
                            }
                        } catch (Throwable t) { }
                    }
                }
                if (child instanceof android.view.ViewGroup) {
                    collectRefColorRecursive((android.view.ViewGroup) child);
                }
                if (refColorLoaded) return;
            }
        } catch (Throwable t) { }
    }

    /**
     * 清除导航栏 horizontal 容器的左右边距，让按钮贴边。
     * 系统为避免屏幕圆角，给 horizontal FrameLayout 设了
     * paddingStart/End 和 layout_marginStart/End（rounded_corner_content_padding）。
     */
    private void clearNavBarSideMargins(Object inflater) {
        try {
            if (inflater == null) return;
            // inflater 持有 mHorizontal / mVertical 字段（horizontal FrameLayout）
            Object h = getField(inflater, "mHorizontal");
            if (h instanceof android.view.View) {
                android.view.View hv = (android.view.View) h;
                // 清除 padding
                int top = hv.getPaddingTop();
                int bottom = hv.getPaddingBottom();
                hv.setPadding(0, top, 0, bottom);
                // 清除 LayoutParams 的 margin（左右）
                android.view.ViewGroup.LayoutParams lp = hv.getLayoutParams();
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) lp;
                    mlp.leftMargin = 0;
                    mlp.rightMargin = 0;
                    hv.setLayoutParams(mlp);
                }
                XposedBridge.log(TAG + ": nav bar horizontal side margins cleared");
            }
            // 阶段B：同时清除 mVertical（横屏竖排容器）的边距
            // 横屏竖排容器在屏幕侧边，"避圆角"是上下方向的 padding/margin，需连同 top/bottom 一起清
            try {
                Object v = getField(inflater, "mVertical");
                if (v instanceof android.view.View) {
                    android.view.View vv = (android.view.View) v;
                    vv.setPadding(0, 0, 0, 0);
                    android.view.ViewGroup.LayoutParams lp = vv.getLayoutParams();
                    if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                        android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) lp;
                        mlp.leftMargin = 0;
                        mlp.rightMargin = 0;
                        mlp.topMargin = 0;
                        mlp.bottomMargin = 0;
                        vv.setLayoutParams(mlp);
                    }
                    XposedBridge.log(TAG + ": nav bar vertical side margins cleared (all)");
                } else {
                    XposedBridge.log(TAG + ": mVertical not found or not View: " + (v != null ? v.getClass().getName() : "null"));
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": clear vertical margins err: " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": clearNavBarSideMargins err: " + t);
        }
    }

    private void setupSearchButtonInView(View root) {
        try {
            if (root instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) root;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    if (child instanceof ImageView && child.getId() == ID_IME_SWITCHER) {
                        boolean isSearch = isMarkedSearchButton((ImageView) child);
                        XposedBridge.log(TAG + ": [diag] ime_switcher id=0x"
                                + Integer.toHexString(child.getId())
                                + " parent=" + (child.getParent() != null ? child.getParent().getClass().getSimpleName() : "null")
                                + " isSearch=" + isSearch
                                + " vis=" + child.getVisibility());
                        // 左段（非搜索）ime_switcher：横竖屏都压 GONE。
                        // 重建/旋转后新 View 默认 VISIBLE，左段输入法键会冒出来（横屏短暂闪现、竖屏一直挂着）。
                        // 重建时 IME 通常是关闭的，压 GONE 正确；若 IME 实际开着，
                        // 随后的 setImeWindowStatus / ButtonDispatcher.setVisibility(after 逐View纠正) 会按真实状态恢复。
                        if (!isSearch) {
                            child.setVisibility(View.GONE);
                            XposedBridge.log(TAG + ": hid non-search ime_switcher (landscape=" + landscapeMode + ")");
                        }
                    }
                    if (child instanceof ImageView && isMarkedSearchButton((ImageView) child)) {
                        setupSearchButton((ImageView) child);
                    }
                    if (child instanceof android.view.ViewGroup) {
                        setupSearchButtonInView(child);
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setupSearchButtonInView err: " + t);
        }
    }

    /**
     * 阶段A：在 inflateLayout 完成后，直接基于结构标记右段搜索键。
     * 依据：右段最后一个 recent 左侧的 ime_switcher 即搜索键。
     */
    private void markSearchButtonsFromInflater(Object inflater) {
        try {
            Object h = getField(inflater, "mHorizontal");
            Object v = getField(inflater, "mVertical");
            markSearchButtonsInContainer(h);
            markSearchButtonsInContainer(v);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": markSearchButtonsFromInflater err: " + t);
        }
    }

    private void markSearchButtonsInContainer(Object container) {
        try {
            if (!(container instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) container;
            // DFS 遍历顺序等价于视觉左到右顺序（不依赖 getLocationOnScreen，创建后即可用）
            java.util.List<View> ordered = new java.util.ArrayList<View>();
            collectViewsOrdered(root, ordered);
            if (ordered.isEmpty()) return;

            // 找最后一个 recent 的索引，往前找最近的 ime_switcher —— 即右段搜索键
            int recentIdx = -1;
            for (int i = 0; i < ordered.size(); i++) {
                if (isRecentView(ordered.get(i))) recentIdx = i;
            }
            if (recentIdx < 0) return;
            for (int i = recentIdx - 1; i >= 0; i--) {
                View c = ordered.get(i);
                if (c.getId() == ID_IME_SWITCHER && c instanceof ImageView) {
                    ImageView iv = (ImageView) c;
                    if (searchMarkedViews.add(iv)) {
                        XposedBridge.log(TAG + ": [A] marked search button id=0x"
                                + Integer.toHexString(iv.getId())
                                + " cls=" + iv.getClass().getSimpleName());
                    }
                    // 立刻请求重排，避免首次显示时先按旧测量尺寸显示再变大
                    try {
                        iv.requestLayout();
                        View p = iv.getParent() instanceof View ? (View) iv.getParent() : null;
                        if (p != null) p.requestLayout();
                        View gp = p != null && p.getParent() instanceof View ? (View) p.getParent() : null;
                        if (gp != null) gp.requestLayout();
                    } catch (Throwable ignored) { }
                    // 标记后立即配置行为与 margin（不碰图标，图标交给 before hook 替换），
                    // 解决“位置先偏右贴 recent，800ms 后才调正”的问题。
                    setupSearchButton(iv, false);
                    break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": markSearchButtonsInContainer err: " + t);
        }
    }

    private void collectViewsOrdered(ViewGroup vg, java.util.List<View> out) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c.getId() == ID_IME_SWITCHER || isRecentView(c)) {
                out.add(c);
            }
            if (c instanceof ViewGroup) {
                collectViewsOrdered((ViewGroup) c, out);
            }
        }
    }

    private boolean isMarkedSearchButton(ImageView iv) {
        return searchMarkedViews.contains(iv);
    }

    /**
     * 在搜索键所属的导航栏容器里找到 recent（任务键）View。
     * 用于横屏时给 recent 设 topMargin 来推开搜索键（bottomMargin 在 ReverseRelativeLayout 里不生效）。
     * 从搜索键向上找第一个包含 recent 的父容器，再在该容器内 DFS 找 recent。
     */
    private View findRecentSibling(ImageView iv) {
        try {
            android.view.ViewParent p = iv.getParent();
            while (p instanceof ViewGroup) {
                ViewGroup scope = (ViewGroup) p;
                View r = findRecentRecursive(scope, iv);
                if (r != null) {
                    XposedBridge.log(TAG + ": findRecentSibling found recent in " + scope.getClass().getSimpleName());
                    return r;
                }
                p = scope.getParent();
            }
            XposedBridge.log(TAG + ": findRecentSibling no recent found");
            return null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": findRecentSibling err: " + t);
            return null;
        }
    }

    /**
     * 判断搜索键所在的导航栏容器是横排（竖屏导航栏）还是竖排（横屏导航栏）。
     * 向上找最近的 LinearLayout 祖先，读其 getOrientation()：
     * HORIZONTAL(0)=竖屏导航栏（返回 true），VERTICAL(1)=横屏导航栏（返回 false）。
     * 找不到 LinearLayout 时回退到全局 orientation（PORTRAIT=true）。
     * 这样不依赖旋转过渡期全局配置的更新时序，避免横竖屏两套 View 交替覆盖 margin。
     */
    private boolean isInHorizontalNavContainer(ImageView iv) {
        try {
            android.view.ViewParent p = iv.getParent();
            while (p instanceof View) {
                if (p instanceof android.widget.LinearLayout) {
                    int o = ((android.widget.LinearLayout) p).getOrientation();
                    boolean horiz = (o == android.widget.LinearLayout.HORIZONTAL);
                    XposedBridge.log(TAG + ": isInHorizontalNavContainer found LinearLayout="
                            + p.getClass().getSimpleName() + " orientation=" + o + " -> " + horiz);
                    return horiz;
                }
                p = ((View) p).getParent();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": isInHorizontalNavContainer err: " + t);
        }
        // 回退：读全局 orientation
        try {
            return iv.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 导航栏重建（inflateLayout）后纠正所有 ime_switcher View 的可见性。
     * 新建 View 默认 VISIBLE，会让左段输入法键冒出来。这里按各 dispatcher 记录的
     * mVisibility（系统认为的 IME 可见性）纠正：搜索键永远 VISIBLE，左段键跟随 IME 状态。
     */
    private void correctImeSwitchersAfterInflate(Object inflater) {
        try {
            int corrected = 0;
            for (Object dispatcher : searchMarkedDispatchers) {
                if (dispatcher == null) continue;
                Object visObj = getField(dispatcher, "mVisibility");
                int targetVis = (visObj instanceof Integer) ? (Integer) visObj : View.GONE;
                Object viewsObj = getField(dispatcher, "mViews");
                if (!(viewsObj instanceof java.util.List)) continue;
                java.util.List<?> views = (java.util.List<?>) viewsObj;
                for (Object o : views) {
                    if (!(o instanceof ImageView)) continue;
                    ImageView iv = (ImageView) o;
                    if (iv.getId() != ID_IME_SWITCHER) continue;
                    if (isMarkedSearchButton(iv)) {
                        if (iv.getVisibility() != View.VISIBLE) {
                            iv.setVisibility(View.VISIBLE);
                            corrected++;
                        }
                    } else {
                        if (iv.getVisibility() != targetVis) {
                            iv.setVisibility(targetVis);
                            corrected++;
                        }
                    }
                }
            }
            // 兜底：某些重建路径下新 View 还没注册进 dispatcher（mViews 为空）。
            // 此时直接在 inflater 视图树里 DFS 找左段（未标记）的 ime_switcher，按当前是否横屏纠正。
            if (inflater instanceof android.view.View) {
                java.util.List<View> all = new java.util.ArrayList<View>();
                collectViewsOrdered((ViewGroup) inflater, all);
                for (View c : all) {
                    if (!(c instanceof ImageView)) continue;
                    ImageView iv = (ImageView) c;
                    if (iv.getId() != ID_IME_SWITCHER) continue;
                    if (isMarkedSearchButton(iv)) {
                        if (iv.getVisibility() != View.VISIBLE) {
                            iv.setVisibility(View.VISIBLE);
                            corrected++;
                        }
                    } else {
                        // 未注册的左段输入法键：重建时 IME 通常是关闭的，直接压 GONE；
                        // 若 IME 实际开着，下一次 setImeWindowStatus / dispatcher.setVisibility 会恢复。
                        if (iv.getVisibility() != View.GONE) {
                            iv.setVisibility(View.GONE);
                            corrected++;
                        }
                    }
                }
            }
            XposedBridge.log(TAG + ": correctImeSwitchersAfterInflate corrected=" + corrected
                    + " dispatchers=" + searchMarkedDispatchers.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": correctImeSwitchersAfterInflate err: " + t);
        }
    }

    /**
     * 更严格的结构判定：仅在同一父容器链下，iv 是 recent 左侧最近的 ime_switcher 才认为是搜索键。
     * 避免把左段输入法键误标。
     */
    private boolean isSearchButtonStructuralStrict(ImageView iv) {
        try {
            View recent = null;
            android.view.ViewParent p = iv.getParent();
            ViewGroup scope = null;
            while (p instanceof ViewGroup) {
                scope = (ViewGroup) p;
                View r = findRecentRecursive(scope, iv);
                if (r != null) {
                    recent = r;
                    break;
                }
                p = scope.getParent();
            }
            if (scope == null || recent == null) return false;

            java.util.List<View> ordered = new java.util.ArrayList<View>();
            collectViewsOrdered(scope, ordered);
            int recentIdx = -1;
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i) == recent) {
                    recentIdx = i;
                    break;
                }
            }
            if (recentIdx <= 0) return false;
            for (int i = recentIdx - 1; i >= 0; i--) {
                View c = ordered.get(i);
                if (c.getId() == ID_IME_SWITCHER) {
                    return c == iv;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 判断 ButtonDispatcher 当前是否服务于搜索键。
     * ime_switcher 的 dispatcher 同时管理左段输入法键和右段搜索键，
     * 因此不能仅凭 dispatcher id 判断；需其当前 View(mCurrentView) 是已标记搜索键才替换，
     * 避免误伤左段输入法键。
     */
    private boolean isSearchDispatcher(Object dispatcher) {
        try {
            if (dispatcher == null) return false;
            if (!searchMarkedDispatchers.contains(dispatcher)) return false;
            Object current = getField(dispatcher, "mCurrentView");
            if (current instanceof ImageView && isMarkedSearchButton((ImageView) current)) {
                return true;
            }
            // mCurrentView 可能尚未设置：遍历 mViews，若任一已注册 View 是搜索键也接受
            Object views = getField(dispatcher, "mViews");
            if (views instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) views;
                for (Object o : list) {
                    if (o instanceof ImageView && isMarkedSearchButton((ImageView) o)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 阶段A：把传入 KeyButtonDrawable 的 childState 直接替换成 WP7 搜索图标 */
    private boolean replaceSearchDrawable(Drawable incoming) {
        try {
            if (incoming == null) return false;
            Object childState = createWp7ConstantState("SEARCH");
            if (childState == null) return false;
            Object stateField = getField(incoming, "mState");
            if (stateField == null) return false;
            setField(stateField, "mChildState", childState);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": replaceSearchDrawable err: " + t);
            return false;
        }
    }

    /**
     * 把系统原生 menu 键配置为 WP 风格搜索按钮：
     *  - 设为可见、可点击
     *  - 禁用系统 MENU 键码（mCode=0），使点击走 OnClick
     *  - 替换为放大镜图标
     *  - 单击/长按触发 AI 助手
     * 只改动 menu 键自身，不碰其父容器，避免破坏布局。
     */
    private void setupSearchButton(ImageView iv) {
        setupSearchButton(iv, true);
    }

    private void setupSearchButton(ImageView iv, boolean applyDrawable) {
        try {
            int id = iv.getId();
            if (!isSearchButtonEnabled(iv)) {
                XposedBridge.log(TAG + ": search button disabled, skip setup id=0x" + Integer.toHexString(id));
                return;
            }
            XposedBridge.log(TAG + ": setupSearchButton enter id=0x" + Integer.toHexString(id)
                    + " vis=" + iv.getVisibility() + " drawable=" + (iv.getDrawable() != null)
                    + " mCode=" + getField(iv, "mCode") + " applyDrawable=" + applyDrawable);
            iv.setVisibility(View.VISIBLE);
            iv.setClickable(true);
            iv.setLongClickable(true);

            try {
                XposedHelpers.setObjectField(iv, "mCode", 0);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": search set mCode err: " + t);
            }

            if (applyDrawable) {
                try {
                    Drawable kd = createSearchKeyButtonDrawable(iv, iv.getClass().getClassLoader());
                    if (kd != null) {
                        iv.setImageDrawable(kd);
                        iv.invalidate();
                        XposedBridge.log(TAG + ": search set KeyButtonDrawable success");
                    } else {
                        setField(iv, "mDrawable", new Wp7IconDrawable(dp(32), ICON_COLOR, "SEARCH"));
                        iv.invalidate();
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": search create KeyButtonDrawable err: " + t);
                }
            }

            try {
                iv.setAlpha(1.0f);
            } catch (Throwable t) { }

            try {
                // 关键：不能读全局 getResources().getConfiguration().orientation —— 旋转过渡期该值对
                // 横竖屏两套 View 是同一个且可能未更新，导致两组 setupSearchButton 交替覆盖同一可见
                // 搜索键的 margin（竖屏设 mEnd=117 后又被横屏 clear 成 0），出现"偏右一下再恢复"。
                // 改为判断这个 View 所在容器本身的排列方向（LinearLayout.getOrientation），
                // 这是 View 自身布局属性，不依赖全局配置时序：HORIZONTAL=竖屏导航栏，VERTICAL=横屏导航栏。
                boolean horizontalNav = isInHorizontalNavContainer(iv);
                XposedBridge.log(TAG + ": search horizontalNav(container)=" + horizontalNav);
                android.view.ViewGroup.LayoutParams lp = iv.getLayoutParams();
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) lp;
                    int gap = dp(36);
                    if (horizontalNav) {
                        // 竖屏横排：搜索键在 recent 左侧，用 marginEnd 拉开水平间距，清除 bottomMargin
                        mlp.bottomMargin = 0;
                        mlp.setMarginEnd(gap);
                        XposedBridge.log(TAG + ": search margin set mEnd=" + gap + " bottom=0");
                    } else {
                        // 横屏竖排：搜索键在 recent 上方。bottomMargin 在 ReverseRelativeLayout 里可能不生效，
                        // 改为给 recent 设 topMargin 来推开搜索键。
                        mlp.setMarginEnd(0);
                        mlp.bottomMargin = 0;
                        XposedBridge.log(TAG + ": search margin clear (landscape)");
                        // 找到 recent 并设 topMargin
                        View recent = findRecentSibling(iv);
                        if (recent != null) {
                            android.view.ViewGroup.LayoutParams rlp = recent.getLayoutParams();
                            if (rlp instanceof android.view.ViewGroup.MarginLayoutParams) {
                                android.view.ViewGroup.MarginLayoutParams rmlp = (android.view.ViewGroup.MarginLayoutParams) rlp;
                                rmlp.topMargin = gap;
                                recent.setLayoutParams(rmlp);
                                recent.requestLayout();
                                XposedBridge.log(TAG + ": recent topMargin set=" + gap);
                            }
                        }
                    }
                    iv.setLayoutParams(mlp);
                    iv.requestLayout();
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": search margin err: " + t);
            }

            View.OnClickListener clickListener = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    XposedBridge.log(TAG + ": search CLICK -> voice assist");
                    invokeAssist(v, 5, false);
                }
            };
            View.OnLongClickListener longClickListener = new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    XposedBridge.log(TAG + ": search LONGCLICK -> screen recognition");
                    invokeAssist(v, 6, true);
                    return true;
                }
            };
            iv.setOnClickListener(clickListener);
            iv.setOnLongClickListener(longClickListener);
            // 记录我们设置的监听器实例，供 setOnClickListener/setOnLongClickListener after 兜底：
            // 若被 dispatcher 的 mClickListener(输入法切换) 覆盖，则重设回我们的。
            searchClickListeners.put(iv, clickListener);
            searchLongClickListeners.put(iv, longClickListener);

            searchBtnView = iv;
            searchMarkedViews.add(iv);
            XposedBridge.log(TAG + ": search button configured id=0x" + Integer.toHexString(id));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setupSearchButton err: " + t);
        }
    }

    /**
     * 触发 AI 助手（超级小爱）。
     * 通过 AssistUtils.showSessionForActiveService 启动当前激活的助手服务。
     * 使用反射规避 @hide 类编译期不可见的问题。
     */
    private void invokeAssist(View v, int invocationType, boolean screenRecognition) {
        try {
            Context ctx = v.getContext();
            if (ctx == null) return;
            Context appCtx = ctx.getApplicationContext();
            if (appCtx == null) appCtx = ctx;

            // 方式1（已验证有效）：向超级小爱 VoiceService 发送 android.intent.action.ASSIST
            // 长按任务键触发小爱正是该机制（MiuiInputKeyEventLog: launchVoiceAssistant from long_press_menu_key
            //  -> startForegroundServiceAsUser 发送 ACTION_ASSIST 到 com.miui.voiceassist/...VoiceService）。
            // 区别：识屏 = 带 extra voice_assist_start_from_key=long_press_home_key；语音对话 = 不携带。
            boolean launched = false;
            try {
                android.content.Intent it = new android.content.Intent(
                        android.content.Intent.ACTION_ASSIST);
                it.setClassName("com.miui.voiceassist", "com.xiaomi.voiceassistant.VoiceService");
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                it.putExtra("invocation_type", invocationType);
                if (screenRecognition) {
                    // 识屏模式（与系统长按 home 一致）：触发小爱识屏
                    it.putExtra("voice_assist_start_from_key", "long_press_home_key");
                } else {
                    // 语音对话模式（与系统长按任务键一致）
                    it.putExtra("invocation_source", "wp7_navbar_search");
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    appCtx.startForegroundService(it);
                } else {
                    appCtx.startService(it);
                }
                XposedBridge.log(TAG + ": invokeAssist START via ACTION_ASSIST->VoiceService"
                        + (screenRecognition ? " (SCREEN RECOG)" : " (VOICE)"));
                launched = true;
            } catch (Throwable e) {
                XposedBridge.log(TAG + ": invokeAssist ACTION_ASSIST failed: " + e);
            }

            // 方式2（后备）：showSessionForActiveService
            if (!launched) {
                try {
                    android.os.Bundle bundle = new android.os.Bundle();
                    bundle.putInt("invocation_type", invocationType);
                    Class<?> auCls = Class.forName("com.android.internal.app.AssistUtils");
                    Object assistUtils = auCls.getConstructor(Context.class).newInstance(appCtx);
                    Method m = auCls.getMethod("showSessionForActiveService",
                            android.os.Bundle.class, int.class, String.class,
                            Class.forName("com.android.internal.app.IVoiceInteractionSessionShowCallback"),
                            android.os.IBinder.class);
                    boolean shown = (Boolean) m.invoke(assistUtils, bundle, 4,
                            appCtx.getAttributionTag(), null, null);
                    XposedBridge.log(TAG + ": invokeAssist showSession shown=" + shown);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": invokeAssist showSession err: " + t);
                }
            }

            // 方式3（最后手段）：KEYCODE_SEARCH 键码
            try {
                android.view.KeyEvent down = new android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_SEARCH);
                android.view.KeyEvent up = new android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_SEARCH);
                // 通过 View 分发（仅作日志确认，实际可能不生效）
                v.dispatchKeyEvent(down);
                v.dispatchKeyEvent(up);
                XposedBridge.log(TAG + ": invokeAssist dispatched KEYCODE_SEARCH");
            } catch (Throwable t) { /* ignore */ }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": invokeAssist err: " + t);
        }
    }

    /** 为指定类型创建 WP 常量状态。 */
    private Object createWp7ConstantState(String type) {
        try {
            int size = 48;
            Drawable icon = new Wp7IconDrawable(size, ICON_COLOR, type);
            return icon.getConstantState();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": createConstantState(" + type + ") err: " + t);
            return null;
        }
    }

    /**
     * 反射构造一个 KeyButtonDrawable 包裹放大镜图标。
     * KeyButtonDrawable(Drawable, KeyButtonDrawable$ShadowDrawableState)。
     * 关键：从搜索键当前系统 drawable 读取其 mLightColor/mDarkColor（保证颜色与其他按钮一致）。
     * ShadowDrawableState(int lightColor, int darkColor, boolean supportsAnimation, boolean horizontalFlip)。
     */
    private Drawable createSearchKeyButtonDrawable(ImageView iv, ClassLoader appCl) {
        try {
            Context ctx = iv.getContext();
            Class<?> kbdCls = Class.forName(
                    "com.android.systemui.navigationbar.views.buttons.KeyButtonDrawable", true, appCl);
            Class<?> sdsCls = Class.forName(
                    "com.android.systemui.navigationbar.views.buttons.KeyButtonDrawable$ShadowDrawableState", true, appCl);

            // 优先使用 back/home 键的颜色（refLightColor/refDarkColor），保证与它们完全一致；
            // 若未读到，则回退到读取搜索键自身 drawable 的颜色。
            int lightColor = 0xFFFFFFFF, darkColor = 0xFFBFBFBF;
            if (refColorLoaded) {
                lightColor = refLightColor;
                darkColor = refDarkColor;
            } else {
                try {
                    Drawable sysD = iv.getDrawable();
                    Object sysState = getField(sysD, "mState");
                    if (sysState != null) {
                        Object lc = getField(sysState, "mLightColor");
                        Object dc = getField(sysState, "mDarkColor");
                        if (lc instanceof Integer) lightColor = (Integer) lc;
                        if (dc instanceof Integer) darkColor = (Integer) dc;
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": search read sys color err: " + t);
                }
            }

            int size = dp(32);
            // ShadowDrawableState(lightColor, darkColor, supportsAnimation, horizontalFlip)
            java.lang.reflect.Constructor<?> sdsCtor = sdsCls.getDeclaredConstructor(
                    int.class, int.class, boolean.class, boolean.class);
            sdsCtor.setAccessible(true);
            Object state = sdsCtor.newInstance(lightColor, darkColor, false, false);
            // 应用从 back/home 读取的阴影参数，让搜索键带与它们一致的阴影
            try {
                setField(state, "mShadowOffsetX", refShadowOffsetX);
                setField(state, "mShadowOffsetY", refShadowOffsetY);
                setField(state, "mShadowSize", refShadowSize);
                setField(state, "mShadowColor", refShadowColor);
                XposedBridge.log(TAG + ": search shadow applied size=" + refShadowSize
                        + " off=(" + refShadowOffsetX + "," + refShadowOffsetY + ")"
                        + " color=0x" + Integer.toHexString(refShadowColor));
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": search shadow apply err: " + t);
            }
            // KeyButtonDrawable(Drawable, ShadowDrawableState)
            java.lang.reflect.Constructor<?> kbdCtor = kbdCls.getDeclaredConstructor(
                    Drawable.class, sdsCls);
            kbdCtor.setAccessible(true);
            Drawable icon = new Wp7IconDrawable(size, lightColor, "SEARCH");
            XposedBridge.log(TAG + ": search color light=0x" + Integer.toHexString(lightColor)
                    + " dark=0x" + Integer.toHexString(darkColor));
            return (Drawable) kbdCtor.newInstance(icon, state);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": createSearchKeyButtonDrawable err: " + t);
            return null;
        }
    }

    private Object getField(Object obj, String name) {
        try {
            return XposedHelpers.getObjectField(obj, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private int dp(float dp) {
        return (int) (dp * android.content.res.Resources.getSystem().getDisplayMetrics().density);
    }

    private void setField(Object obj, String name, Object value) {
        try {
            XposedHelpers.setObjectField(obj, name, value);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setField " + name + " err: " + t);
        }
    }

    /**
     * 从系统对象（如 NavigationBarInflaterView）中提取一个可用 Context，
     * 用于跨进程访问模块设置 (ContentProvider)。
     */
    private Context getContextFromObject(Object obj) {
        if (obj == null) return null;
        // 优先 mContext 字段
        try {
            Object c = getField(obj, "mContext");
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) { }
        // 若对象是 View 本身，直接用 getContext()
        if (obj instanceof android.view.View) {
            try {
                Context c = ((android.view.View) obj).getContext();
                if (c != null) return c;
            } catch (Throwable ignored) { }
        }
        // 尝试从 mView / view 字段取 View 再拿 context
        String[] viewFields = { "mView", "view", "mParentView" };
        for (String f : viewFields) {
            try {
                Object v = getField(obj, f);
                if (v instanceof android.view.View) {
                    Context c = ((android.view.View) v).getContext();
                    if (c != null) return c;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /**
     * 读取"左侧切换输入法"开关。
     * 通过 ContentProvider 跨进程访问模块设置；读取失败/异常时默认开启（true）。
     */
    private boolean isImeSwitcherEnabled(Object obj) {
        try {
            Context ctx = getContextFromObject(obj);
            if (ctx == null) return true; // 拿不到 context 时默认开启，避免破坏现有功能
            android.net.Uri uri = android.net.Uri.parse("content://com.wp7.navbar.settings/ime_switcher");
            android.database.Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        return c.getInt(0) != 0;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": isImeSwitcherEnabled err: " + t);
        }
        return true;
    }

    /**
     * 任务键左侧搜索键开关（search_button）。
     * 通过 ContentProvider 跨进程访问模块设置；读取失败/异常时默认开启（true）。
     */
    private boolean isSearchButtonEnabled(Object obj) {
        try {
            Context ctx = getContextFromObject(obj);
            if (ctx == null) return true;
            android.net.Uri uri = android.net.Uri.parse("content://com.wp7.navbar.settings/search_button");
            android.database.Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        return c.getInt(0) != 0;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": isSearchButtonEnabled err: " + t);
        }
        return true;
    }

    /**
     * 统一注入导航栏布局：
     *  - 左段：把 left 占位替换为 ime_switcher（左侧切输入法，IME 弹出时显示）
     *  - 右段：注入 menu_ime（WP 风格搜索按钮），并把 recent 移到最右
     *
     * 布局格式：左段;中段;右段，段内用 "," 分隔。
     */
    private String injectLayouts(String layout, boolean imeEnabled, boolean searchBtnEnabled) {
        if (layout == null || layout.isEmpty()) return layout;
        String[] parts = layout.split(";", -1);
        if (parts.length < 3) return layout;

        // ---- 左段：ime_switcher（可选，受开关控制）----
        if (imeEnabled && !parts[0].contains("ime_switcher")) {
            String left = parts[0];
            String injectedLeft;
            if (left.startsWith("left[")) {
                int idx = left.indexOf(']');
                String rest = idx >= 0 ? left.substring(idx + 1) : "";
                injectedLeft = "ime_switcher[.5W]" + rest; // 占位权重改为 0.5，让 back 右移、和右侧对称
            } else {
                injectedLeft = "ime_switcher[.5W]," + left;
            }
            parts[0] = injectedLeft;
        }

        // ---- 右段：把 recent 移到最右；右侧保留 right token（系统会将其重映射为 menu_ime，
        //     落在 recent 左侧的 menu_container 里），后续把这个 menu_ime 替换为放大镜 ----
        // 注意：不在 right 段额外注入 ime_switcher，避免系统把它放到中间空位(sx=876)。
        if (searchBtnEnabled && containsRecents(parts[2])) {
            String right = parts[2];
            String noRecent = removeToken(right, "recent");
            // 把段内的任意 .5W 占位统一改为 1W（和左边返回键对称，避免搜索键紧贴 recent）
            noRecent = noRecent.replace("[.5W]", "[1W]");
            String newRight;
            if (noRecent.isEmpty() || noRecent.trim().isEmpty()) {
                // 用独立 ime_switcher 作为搜索键（1W 权重，像 back 一样自适应宽度，与左段对称）
                newRight = "ime_switcher[1W]," + "recent[.3WC]";
            } else {
                // 原右段有内容：把 right/menu_ime 之类的固定宽度占位替换为独立 ime_switcher
                noRecent = noRecent.replaceAll("right(\\[[^\\]]*])?", "ime_switcher[1W]");
                noRecent = noRecent.replaceAll("menu_ime(\\[[^\\]]*])?", "ime_switcher[1W]");
                newRight = noRecent + ",recent[.3WC]";
            }
            parts[2] = newRight;
        } else if (!searchBtnEnabled && containsRecents(parts[2])) {
            // 搜索键关闭：右段保持系统默认布局（recent[1WC],right[.5W]）不动，
            // 让 recent 恢复默认位置与大小（right 占位使它不贴边）；
            // 右侧 menu_ime（输入法键）由 setButtonVisibility hook（关闭时不再强制显示）正常隐藏。
            // 此处不做任何改动。
            // parts[2] 保持不变
        }

        return String.join(";", parts);
    }

    /** 判断段字符串里是否包含 recent token。 */
    private boolean containsRecents(String seg) {
        if (seg == null) return false;
        // recent 作为独立 token（可能是 recent[...] 或 recent）
        return seg.matches(".*(^|,)\\s*recent(\\[.*?])?.*");
    }

    /** 从段字符串中移除指定的 token 项（连同其 [weight]）。 */
    private String removeToken(String seg, String token) {
        if (seg == null) return seg;
        String[] items = seg.split(",");
        StringBuilder sb = new StringBuilder();
        for (String it : items) {
            String name = it;
            int b = name.indexOf('[');
            if (b >= 0) name = name.substring(0, b);
            if (name.trim().equals(token)) {
                continue; // 跳过该 token
            }
            if (sb.length() > 0) sb.append(',');
            sb.append(it);
        }
        return sb.toString();
    }

    /**
     * 根据 NavigationBar.mImeVisible 强制 ime_switcher 显示/隐藏。
     * IME 可见 → 显示；IME 隐藏 → 隐藏。
     */
    private void updateImeSwitcherVisibility(Object navBar) {
        try {
            Boolean imeVisible = (Boolean) getField(navBar, "mImeVisible");
            if (imeVisible == null) {
                XposedBridge.log(TAG + ": mImeVisible null, skip ime switch");
                return;
            }
            Object navBarView = getField(navBar, "mView");
            if (navBarView == null) {
                XposedBridge.log(TAG + ": navBarView null, skip ime switch");
                return;
            }
            Object imeSwitch = XposedHelpers.callMethod(navBarView, "getImeSwitchButton");
            if (imeSwitch == null) {
                XposedBridge.log(TAG + ": imeSwitchButton null, skip");
                return;
            }
            // ButtonDispatcher.setVisibility(int)
            XposedHelpers.callMethod(imeSwitch, "setVisibility", imeVisible ? 0 : 8); // VISIBLE / GONE
            XposedBridge.log(TAG + ": ime switch visibility -> " + (imeVisible ? "VISIBLE" : "GONE"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": updateImeSwitcherVisibility err: " + t);
        }
    }
}