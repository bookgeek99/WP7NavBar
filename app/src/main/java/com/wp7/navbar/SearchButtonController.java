package com.wp7.navbar;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * 竖屏 Search 按钮控制器（方案第七节 / 第十一节）。
 *
 * 职责：把右段的 ime_switcher 伪装成 WP7 搜索按钮（视觉 + 点击分离）。
 *  - 视觉：反射构造 KeyButtonDrawable 包裹放大镜图标
 *  - 点击：单击触发语音助手、长按触发识屏
 *  - 通过 getDefaultLayout 注入右段独立 ime_switcher
 *
 * 严格只在竖屏（Configuration.ORIENTATION_PORTRAIT）下工作；横屏完全由 SystemUI 原生管理，
 * 本控制器不注入、不修改 Recent、不碰横屏 ime_switcher（方案第二 / 八 / 十一节）。
 *
 * 设计要点（替代旧代码的多个全局状态）：
 *  - 用 WeakHashMap / newSetFromMap 持有 View 与 dispatcher，允许 GC，避免 static View 泄漏。
 *  - 不再依赖全局 View.setVisibility hook 维持 Search 常显；改由 PortraitController 在竖屏重建时配置。
 */
public final class SearchButtonController {

    private static final String TAG = "WP7NavBar";

    // ===== 进程级状态（Weak 引用，允许 GC）=====
    static final Set<ImageView> markedViews =
            Collections.newSetFromMap(new WeakHashMap<ImageView, Boolean>());
    static final Set<Object> markedDispatchers =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    static final WeakHashMap<ImageView, View.OnClickListener> clickListeners =
            new WeakHashMap<ImageView, View.OnClickListener>();
    static final WeakHashMap<ImageView, View.OnLongClickListener> longClickListeners =
            new WeakHashMap<ImageView, View.OnLongClickListener>();

    // 防止 after hook 重设监听器时递归
    static boolean inRestoreListener = false;

    // 从 back/home 读取的颜色 / 阴影，让搜索键与它们对齐（首次读取后缓存）
    static int refLightColor = 0;
    static int refDarkColor = 0;
    static boolean refColorLoaded = false;
    static int refShadowOffsetX = 0, refShadowOffsetY = 0, refShadowSize = 0, refShadowColor = 0;

    // 搜索键开关状态缓存（getDefaultLayout 阶段更新）
    static boolean enabledCached = true;

    private SearchButtonController() {}

    // =====================================================================
    // getDefaultLayout 注入：把右段替换成 ime_switcher(搜索) + recent
    // =====================================================================

    /** 供 Wp7NavbarHook hook NavigationBarInflaterView.getDefaultLayout 的 after 入口。 */
    static void onGetDefaultLayout(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
        try {
            Object inflater = param.thisObject;
            boolean imeEnabled = isImeSwitcherEnabled(inflater);
            boolean searchBtn = isSearchButtonEnabled(inflater);
            enabledCached = searchBtn;
            Object result = param.getResult();
            if (!(result instanceof String)) return;
            String orig = (String) result;
            // 关键（方案第二节）：横屏禁止注入 Search。横屏时只保留可选的左段输入法键注入，
            // 右段 Search 注入（ime_switcher 伪装 + recent 移位）全部跳过，Recent 完全原生。
            boolean landscape = isLandscape(inflater);
            String injected = injectLayouts(orig, imeEnabled, searchBtn && !landscape);
            if (!injected.equals(orig)) {
                XposedBridge.log(TAG + ": [inject] getDefaultLayout (landscape=" + landscape + ") -> " + injected);
                param.setResult(injected);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": onGetDefaultLayout err: " + t);
        }
    }

    /** 判断 inflater/context 当前是否为横屏 Configuration。 */
    static boolean isLandscape(Object obj) {
        try {
            Context ctx = SystemUiReflection.getContextFromObject(obj);
            if (ctx != null) {
                return ctx.getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * 统一注入导航栏布局字符串：
     *  - 左段：把 left 占位替换为 ime_switcher（左侧切输入法，IME 弹出时显示）
     *  - 右段：注入 ime_switcher 作为搜索键，并把 recent 移到最右
     * 布局格式：左段;中段;右段，段内用 "," 分隔。
     */
    static String injectLayouts(String layout, boolean imeEnabled, boolean searchBtnEnabled) {
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
                injectedLeft = "ime_switcher[.5W]" + rest;
            } else {
                injectedLeft = "ime_switcher[.5W]," + left;
            }
            parts[0] = injectedLeft;
        }

        // ---- 右段：把 recent 移到最右；右侧保留 right token（系统会重映射为 menu_ime）----
        if (searchBtnEnabled && containsRecents(parts[2])) {
            String right = parts[2];
            String noRecent = removeToken(right, "recent");
            noRecent = noRecent.replace("[.5W]", "[1W]");
            String newRight;
            if (noRecent.isEmpty() || noRecent.trim().isEmpty()) {
                newRight = "ime_switcher[1W]," + "recent[.3WC]";
            } else {
                noRecent = noRecent.replaceAll("right(\\[[^\\]]*])?", "ime_switcher[1W]");
                noRecent = noRecent.replaceAll("menu_ime(\\[[^\\]]*])?", "ime_switcher[1W]");
                newRight = noRecent + ",recent[.3WC]";
            }
            parts[2] = newRight;
        } else if (!searchBtnEnabled && containsRecents(parts[2])) {
            // 搜索键关闭：右段保持系统默认布局不动
        }

        return String.join(";", parts);
    }

    /** 判断段字符串里是否包含 recent token。 */
    static boolean containsRecents(String seg) {
        if (seg == null) return false;
        return seg.matches(".*(^|,)\\s*recent(\\[.*?])?.*");
    }

    /** 从段字符串中移除指定的 token 项（连同其 [weight]）。 */
    static String removeToken(String seg, String token) {
        if (seg == null) return seg;
        String[] items = seg.split(",");
        StringBuilder sb = new StringBuilder();
        for (String it : items) {
            String name = it;
            int b = name.indexOf('[');
            if (b >= 0) name = name.substring(0, b);
            if (name.trim().equals(token)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(it);
        }
        return sb.toString();
    }

    // =====================================================================
    // setImageDrawable 入口：识别右段搜索键并替换图标（before），横屏直接跳过
    // =====================================================================

    /**
     * 供 Wp7NavbarHook hook KeyButtonView.setImageDrawable 的 before 入口。
     * 仅当当前 Configuration 为竖屏、且该 View 是右段搜索键时才替换图标，
     * 避免首次绘制闪现错误图标。横屏一律跳过，保持系统原生 ime_switcher。
     */
    static void onSetImageDrawableBefore(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
        try {
            if (!(param.thisObject instanceof ImageView)) return;
            ImageView iv = (ImageView) param.thisObject;
            if (iv.getId() != SystemUiIds.ID_IME_SWITCHER) return;
            // 横屏：不做任何 Search 替换，保持系统原生
            if (isLandscape(iv)) return;
            boolean marked = markedViews.contains(iv);
            if (!marked && isSearchButtonStructuralStrict(iv)) {
                markedViews.add(iv);
                marked = true;
            }
            if (!marked) return;
            Drawable incoming = param.args != null && param.args.length > 0
                    ? (Drawable) param.args[0] : null;
            Drawable kd = createSearchKeyButtonDrawable(iv, iv.getClass().getClassLoader());
            if (kd != null) {
                param.args[0] = kd;
            }
            setupSearchButton(iv, false);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": onSetImageDrawableBefore err: " + t);
        }
    }

    /** 判断 View 所在 Configuration 是否横屏。 */
    static boolean isLandscape(View v) {
        try {
            return v.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 更严格的结构判定：仅在同一父容器链下，iv 是 recent 左侧最近的 ime_switcher 才认为是搜索键。
     * 避免把左段输入法键误标。
     */
    static boolean isSearchButtonStructuralStrict(ImageView iv) {
        try {
            if (iv.getId() != SystemUiIds.ID_IME_SWITCHER) return false;
            View recent = null;
            android.view.ViewParent p = iv.getParent();
            android.view.ViewGroup scope = null;
            while (p instanceof android.view.ViewGroup) {
                scope = (android.view.ViewGroup) p;
                View r = Wp7IconController.findRecentRecursive(scope, iv);
                if (r != null) { recent = r; break; }
                p = scope.getParent();
            }
            if (scope == null || recent == null) return false;

            List<View> ordered = new ArrayList<View>();
            collectViewsOrdered(scope, ordered);
            int recentIdx = -1;
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i) == recent) { recentIdx = i; break; }
            }
            if (recentIdx <= 0) return false;
            for (int i = recentIdx - 1; i >= 0; i--) {
                View c = ordered.get(i);
                if (c.getId() == SystemUiIds.ID_IME_SWITCHER) {
                    return c == iv;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** DFS 收集 ime_switcher / recent 视图（按视觉左到右顺序）。 */
    static void collectViewsOrdered(android.view.ViewGroup vg, List<View> out) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c.getId() == SystemUiIds.ID_IME_SWITCHER || Wp7IconController.isRecentView(c)) {
                out.add(c);
            }
            if (c instanceof android.view.ViewGroup) {
                collectViewsOrdered((android.view.ViewGroup) c, out);
            }
        }
    }

    // =====================================================================
    // 搜索键配置：可见性 / 行为 / margin / 监听器
    // =====================================================================

    static void setupSearchButton(ImageView iv) {
        setupSearchButton(iv, true);
    }

    /**
     * 把系统原生 ime_switcher 配置为 WP 风格搜索按钮：
     *  - 设为可见、可点击
     *  - 禁用系统键码（mCode=0），使点击走 OnClick
     *  - 替换为放大镜图标（applyDrawable=true 时）
     *  - 单击/长按触发 AI 助手
     * 只改动 ime_switcher 自身，不碰其父容器 / recent，避免破坏布局。
     */
    static void setupSearchButton(ImageView iv, boolean applyDrawable) {
        try {
            if (!isSearchButtonEnabled(iv)) return;
            iv.setVisibility(View.VISIBLE);
            iv.setClickable(true);
            iv.setLongClickable(true);

            try {
                SystemUiReflection.setField(iv, "mCode", 0);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": search set mCode err: " + t);
            }

            if (applyDrawable) {
                Drawable kd = createSearchKeyButtonDrawable(iv, iv.getClass().getClassLoader());
                if (kd != null) {
                    iv.setImageDrawable(kd);
                    iv.invalidate();
                }
            }

            try { iv.setAlpha(1.0f); } catch (Throwable ignored) { }

            // margin：竖屏横排容器（LinearLayout HORIZONTAL）才设置；横屏竖排不动
            try {
                if (isInHorizontalContainer(iv)) {
                    android.view.ViewGroup.LayoutParams lp = iv.getLayoutParams();
                    if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                        android.view.ViewGroup.MarginLayoutParams mlp =
                                (android.view.ViewGroup.MarginLayoutParams) lp;
                        int gap = SystemUiReflection.dp(36);
                        mlp.bottomMargin = 0;
                        mlp.setMarginEnd(gap);
                        iv.setLayoutParams(mlp);
                        iv.requestLayout();
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": search margin err: " + t);
            }

            View.OnClickListener clickListener = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    XposedBridge.log(TAG + ": search CLICK -> voice assist");
                    invokeAssist(v, 5, false);
                }
            };
            View.OnLongClickListener longClickListener = new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    XposedBridge.log(TAG + ": search LONGCLICK -> screen recognition");
                    invokeAssist(v, 6, true);
                    return true;
                }
            };
            inRestoreListener = true;
            iv.setOnClickListener(clickListener);
            iv.setOnLongClickListener(longClickListener);
            inRestoreListener = false;
            clickListeners.put(iv, clickListener);
            longClickListeners.put(iv, longClickListener);

            markedViews.add(iv);
            XposedBridge.log(TAG + ": search button configured id=0x" + Integer.toHexString(iv.getId()));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setupSearchButton err: " + t);
        }
    }

    /** 判断搜索键所在容器是横排（竖屏导航栏）还是竖排（横屏导航栏）。 */
    static boolean isInHorizontalContainer(ImageView iv) {
        try {
            android.view.ViewParent p = iv.getParent();
            while (p instanceof View) {
                if (p instanceof android.widget.LinearLayout) {
                    int o = ((android.widget.LinearLayout) p).getOrientation();
                    return o == android.widget.LinearLayout.HORIZONTAL;
                }
                p = ((View) p).getParent();
            }
        } catch (Throwable ignored) { }
        return !isLandscape(iv);
    }

    /** 触发 AI 助手（超级小爱）。视觉与点击分离：点击统一走这里。 */
    static void invokeAssist(View v, int invocationType, boolean screenRecognition) {
        try {
            Context ctx = v.getContext();
            if (ctx == null) return;
            Context appCtx = ctx.getApplicationContext();
            if (appCtx == null) appCtx = ctx;

            boolean launched = false;
            try {
                android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_ASSIST);
                it.setClassName("com.miui.voiceassist", "com.xiaomi.voiceassistant.VoiceService");
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                it.putExtra("invocation_type", invocationType);
                if (screenRecognition) {
                    it.putExtra("voice_assist_start_from_key", "long_press_home_key");
                } else {
                    it.putExtra("invocation_source", "wp7_navbar_search");
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    appCtx.startForegroundService(it);
                } else {
                    appCtx.startService(it);
                }
                launched = true;
                XposedBridge.log(TAG + ": invokeAssist via ACTION_ASSIST"
                        + (screenRecognition ? " (SCREEN)" : " (VOICE)"));
            } catch (Throwable e) {
                XposedBridge.log(TAG + ": invokeAssist ACTION_ASSIST failed: " + e);
            }

            if (!launched) {
                try {
                    android.os.Bundle bundle = new android.os.Bundle();
                    bundle.putInt("invocation_type", invocationType);
                    Class<?> auCls = Class.forName("com.android.internal.app.AssistUtils");
                    Object assistUtils = auCls.getConstructor(Context.class).newInstance(appCtx);
                    java.lang.reflect.Method m = auCls.getMethod("showSessionForActiveService",
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
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": invokeAssist err: " + t);
        }
    }

    // =====================================================================
    // KeyButtonDrawable 构造（反射）：让搜索键带与 back/home 一致的颜色/阴影
    // =====================================================================

    /** 反射构造一个 KeyButtonDrawable 包裹放大镜图标。 */
    static Drawable createSearchKeyButtonDrawable(ImageView iv, ClassLoader appCl) {
        try {
            Context ctx = iv.getContext();
            Class<?> kbdCls = Class.forName(
                    "com.android.systemui.navigationbar.views.buttons.KeyButtonDrawable", true, appCl);
            Class<?> sdsCls = Class.forName(
                    "com.android.systemui.navigationbar.views.buttons.KeyButtonDrawable$ShadowDrawableState", true, appCl);

            int lightColor = 0xFFFFFFFF, darkColor = 0xFFBFBFBF;
            if (refColorLoaded) {
                lightColor = refLightColor;
                darkColor = refDarkColor;
            } else {
                try {
                    Drawable sysD = iv.getDrawable();
                    Object sysState = SystemUiReflection.getFieldQuiet(sysD, "mState");
                    if (sysState != null) {
                        Object lc = SystemUiReflection.getFieldQuiet(sysState, "mLightColor");
                        Object dc = SystemUiReflection.getFieldQuiet(sysState, "mDarkColor");
                        if (lc instanceof Integer) lightColor = (Integer) lc;
                        if (dc instanceof Integer) darkColor = (Integer) dc;
                    }
                } catch (Throwable ignored) { }
            }

            int size = SystemUiReflection.dp(32);
            java.lang.reflect.Constructor<?> sdsCtor = sdsCls.getDeclaredConstructor(
                    int.class, int.class, boolean.class, boolean.class);
            sdsCtor.setAccessible(true);
            Object state = sdsCtor.newInstance(lightColor, darkColor, false, false);
            try {
                SystemUiReflection.setField(state, "mShadowOffsetX", refShadowOffsetX);
                SystemUiReflection.setField(state, "mShadowOffsetY", refShadowOffsetY);
                SystemUiReflection.setField(state, "mShadowSize", refShadowSize);
                SystemUiReflection.setField(state, "mShadowColor", refShadowColor);
            } catch (Throwable ignored) { }

            java.lang.reflect.Constructor<?> kbdCtor = kbdCls.getDeclaredConstructor(Drawable.class, sdsCls);
            kbdCtor.setAccessible(true);
            Drawable icon = new Wp7IconDrawable(size, lightColor, "SEARCH");
            return (Drawable) kbdCtor.newInstance(icon, state);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": createSearchKeyButtonDrawable err: " + t);
            return null;
        }
    }

    /** 深度遍历导航栏，读取 back/home 键 KeyButtonDrawable 的颜色/阴影，缓存供搜索键对齐。 */
    static void collectRefColor(View root) {
        if (refColorLoaded) return;
        if (!(root instanceof android.view.ViewGroup)) return;
        collectRefColorRecursive((android.view.ViewGroup) root);
    }

    private static void collectRefColorRecursive(android.view.ViewGroup vg) {
        if (refColorLoaded) return;
        try {
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof ImageView) {
                    int type = Wp7IconController.resolveButtonType((ImageView) child);
                    if (type == Wp7IconController.KEYCODE_BACK || type == Wp7IconController.KEYCODE_HOME) {
                        Drawable d = ((ImageView) child).getDrawable();
                        Object st = SystemUiReflection.getFieldQuiet(d, "mState");
                        if (st != null) {
                            Object lc = SystemUiReflection.getFieldQuiet(st, "mLightColor");
                            Object dc = SystemUiReflection.getFieldQuiet(st, "mDarkColor");
                            if (lc instanceof Integer && dc instanceof Integer) {
                                refLightColor = (Integer) lc;
                                refDarkColor = (Integer) dc;
                                Object sox = SystemUiReflection.getFieldQuiet(st, "mShadowOffsetX");
                                Object soy = SystemUiReflection.getFieldQuiet(st, "mShadowOffsetY");
                                Object ssz = SystemUiReflection.getFieldQuiet(st, "mShadowSize");
                                Object scl = SystemUiReflection.getFieldQuiet(st, "mShadowColor");
                                if (sox instanceof Integer) refShadowOffsetX = (Integer) sox;
                                if (soy instanceof Integer) refShadowOffsetY = (Integer) soy;
                                if (ssz instanceof Integer) refShadowSize = (Integer) ssz;
                                if (scl instanceof Integer) refShadowColor = (Integer) scl;
                                refColorLoaded = true;
                                return;
                            }
                        }
                    }
                }
                if (child instanceof android.view.ViewGroup) {
                    collectRefColorRecursive((android.view.ViewGroup) child);
                }
                if (refColorLoaded) return;
            }
        } catch (Throwable ignored) { }
    }

    // =====================================================================
    // 模块设置读取（跨进程 ContentProvider）
    // =====================================================================

    /** 读取"左侧切换输入法"开关；失败默认开启。 */
    static boolean isImeSwitcherEnabled(Object obj) {
        try {
            Context ctx = SystemUiReflection.getContextFromObject(obj);
            if (ctx == null) return true;
            android.net.Uri uri = android.net.Uri.parse("content://com.wp7.navbar.settings/ime_switcher");
            android.database.Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) return c.getInt(0) != 0;
                } finally { c.close(); }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": isImeSwitcherEnabled err: " + t);
        }
        return true;
    }

    /** 读取"搜索键"开关；失败默认开启。 */
    static boolean isSearchButtonEnabled(Object obj) {
        try {
            Context ctx = SystemUiReflection.getContextFromObject(obj);
            if (ctx == null) return true;
            android.net.Uri uri = android.net.Uri.parse("content://com.wp7.navbar.settings/search_button");
            android.database.Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) return c.getInt(0) != 0;
                } finally { c.close(); }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": isSearchButtonEnabled err: " + t);
        }
        return true;
    }
}
