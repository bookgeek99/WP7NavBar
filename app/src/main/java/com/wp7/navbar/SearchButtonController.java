package com.wp7.navbar;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;


import de.robv.android.xposed.XposedBridge;

/**
 * 搜索键辅助（方案第七节 / 第十一节）。
 *
 * [2C-退役后] 本类职责缩减为：
 *  - getDefaultLayout after：向右段注入独立 wp7search token（不注入 ime_switcher）
 *  - 提供 WP7 放大镜 KeyButtonDrawable 构造（createSearchKeyButtonDrawable）
 *  - 提供 AI 助手触发（invokeAssist，供独立 Search ContextualButton 的点击复用）
 *  - 提供 back/home 参照色收集（collectRefColor，供 Search 图标颜色对齐）
 *  - 提供模块设置读取（isImeSwitcherEnabled / isSearchButtonEnabled）
 *
 * 旧 masquerade（右段 ime_switcher 伪装搜索键）相关方法已全部删除：
 * setupSearchButton / onSetImageDrawableBefore / markedViews / clickListeners 等。
 * 新 Search 由 SearchContextualButtonFactory 独立 ContextualButton 承担。
 * 左段 ime_switcher（左侧切输入法）仍由本类的 injectLayouts 注入保留。
 */
public final class SearchButtonController {

    private static final String TAG = "WP7NavBar";

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
            // 关键（方案第二节）：横屏完全不碰——左段输入法键、右段 Search 全部不注入，Recent 完全原生。
            // 用全局方向状态而非 inflater 的 Context Configuration（后者在旋转时更新滞后）。
            boolean landscape = NavigationBarController.sCurrentOrientation
                    == NavigationBarController.NavBarOrientation.LANDSCAPE;
            String injected = injectLayouts(orig, imeEnabled && !landscape, searchBtn && !landscape);
            if (!injected.equals(orig)) {
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
     * 统一注入导航栏布局字符串（布局格式：左段;中段;右段，段内用 "," 分隔）：
     *  - 左段：把 left 占位替换为 ime_switcher（左侧切输入法，IME 弹出时显示）
     *  - 右段：注入独立 wp7search token（新 Search ContextualButton）。
     *
     * [2C-退役] 旧 masquerade（右段注入 ime_switcher 伪装成搜索键）已删除：
     * 改为独立 wp7search ContextualButton，不再碰 recent 位置与 right token。
     * 左段 ime_switcher 保留（左侧切输入法功能）。
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
        // ---- 右段：[2C-退役] 不再注入 ime_switcher；仅注入独立 wp7search token ----
        if (searchBtnEnabled && !parts[2].contains("wp7search")) {
            // [2C-修复] 移除 right token：系统会把 right 重映射为 menu_ime，
            // 导致右段多出一个 menu 键、Recent 错位。
            String right = parts[2];
            right = right.replaceAll("(^|,)\\s*right(\\[[^\\]]*])?(?=,|$)", "");
            right = right.replaceAll(",,", ",").replaceAll("^,", "").replaceAll(",$", "");
            if (right.trim().isEmpty()) right = "recent[.5WC]";
            // [2C-6] recent 改为 .5W，与左侧 ime_switcher[.5W] 左右对称角键：
            //   左半 = ime(.5) + back(1) = 1.5
            //   中   = Space(1)          = home 的叠加位
            //   右半 = search(1) + recent(.5) = 1.5   ← 对称
            right = right.replaceAll("recent\\[\\s*1\\s*W", "recent[.5W");
            parts[2] = "wp7search[1W]," + right;
        }
        return String.join(";", parts);
    }
    /** [2C-1] 是否注入独立 wp7search token（验证阶段开关，便于回退）。 */
    static final boolean WP7_2C1_ENABLED = true;

    /** 判断 View 所在 Configuration 是否横屏。 */
    static boolean isLandscape(View v) {
        try {
            return v.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable t) {
            return false;
        }
    }

    // =====================================================================
    // 搜索键配置：可见性 / 行为 / margin / 监听器
    // =====================================================================

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
