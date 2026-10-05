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
    static float refDarkIntensity = 0f;   // [2C-7] back/home 当前的暗度（0=light,1=dark）
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
            // [2C-8] 横屏也注入 ime_switcher（输入法切换键），与竖屏一致；
            //         Search 仍只在竖屏注入（横屏布局单独处理）。
            //   用全局方向状态而非 inflater 的 Context Configuration（后者在旋转时更新滞后）。
            boolean landscape = NavigationBarController.sCurrentOrientation
                    == NavigationBarController.NavBarOrientation.LANDSCAPE;
            String injected = injectLayouts(orig, imeEnabled, searchBtn && !landscape);
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
// ---- 右段 ----
        // [2C-9] right token 的处置：
        //   - 搜索键「开启」时：移除 right（避免系统把 right 重映射为 menu_ime 多出按键），
        //     并注入 wp7search；recent 缩为 .5W 与左侧 ime_switcher 对称。
        //   - 搜索键「关闭」时：完全还原系统原生串（保留 right[.5W]），
        //     否则 right 的 weight 缺失会使 recent 占比由 1/4 变 1/3 → 变宽右移。
        if (searchBtnEnabled) {
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
            // [2C-7] 用与 back/home 相同的方式计算实际显示色：
            //   实际色 = ArgbEvaluator.evaluate(darkIntensity, mLightColor, mDarkColor)
            //   （KeyButtonDrawable 内部就是这样算的；此前我们只用 mLightColor → 颜色不一致）
            int actualColor = lightColor;
            try {
                Object ev = new android.animation.ArgbEvaluator();
                java.lang.reflect.Method evm = android.animation.ArgbEvaluator.class
                        .getMethod("evaluate", float.class, Object.class, Object.class);
                Object c = evm.invoke(ev, refDarkIntensity, lightColor, darkColor);
                if (c instanceof Integer) actualColor = (Integer) c;
            } catch (Throwable ignored) { }
            Drawable icon = new Wp7IconDrawable(size, actualColor, "SEARCH");
            Drawable kbd = (Drawable) kbdCtor.newInstance(icon, state);
            XposedBridge.log(TAG + ": [2C-7] color actual=0x" + Integer.toHexString(actualColor)
                    + " light=0x" + Integer.toHexString(lightColor)
                    + " dark=0x" + Integer.toHexString(darkColor)
                    + " darkIntensity=" + refDarkIntensity
                    + " loaded=" + refColorLoaded);
            // 把 darkIntensity 也设给我们的 KeyButtonDrawable（让它的 SRC_ATOP filter / 阴影一致）
            try {
                java.lang.reflect.Method sdi = kbdCls.getMethod("setDarkIntensity", float.class);
                sdi.setAccessible(true);
                sdi.invoke(kbd, refDarkIntensity);
            } catch (Throwable ignored) { }
            return kbd;
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
        // [2C-7] 取色成功后，重新刷新 Search 图标颜色（此前图标可能已用默认色创建）
        if (refColorLoaded) {
            try {
                SearchContextualButtonFactory.refreshSearchIconColor(root);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": [2C-7] refreshSearchIconColor err: " + t);
            }
        }
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
                                // [2C-7] 读取 back/home 当前的 darkIntensity（决定 light/dark 之间的插值）
                                Object di = SystemUiReflection.getFieldQuiet(st, "mDarkIntensity");
                                if (di instanceof Float) refDarkIntensity = (Float) di;
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

    // =====================================================================
    // [2C-9] inflateLayout before：按开关状态纠正缓存的布局串
    // =====================================================================

    /**
     * 修正 inflateLayout 收到的布局串，使其与当前开关状态一致。
     *
     * 背景：HyperOS 会把 getDefaultLayout 的返回串缓存进 mCurrentLayout，
     * 后续 inflateLayout 直接使用缓存串、不再调用 getDefaultLayout。
     * 因此在「开启 → 关闭」开关后，缓存串里仍残留 wp7search / recent[.5W]。
     * 本方法在每次 inflate 前依据开关状态纠正缓存串：
     *   - 搜索键关闭：移除 wp7search token，并把 recent 恢复为系统原生宽度（1W）
     *   - 输入法键关闭：移除 ime_switcher token
     *
     * @return 修正后的布局串；无需修正时原样返回。
     */
    static String fixCachedLayout(String layout, boolean imeEnabled, boolean searchBtnEnabled,
                                  boolean landscape) {
        if (layout == null || layout.isEmpty()) return layout;
        String[] parts = layout.split(";", -1);
        if (parts.length < 3) return layout;
        boolean changed = false;

        // ---- 左段：ime_switcher ----
        if (!imeEnabled && parts[0].contains("ime_switcher")) {
            parts[0] = removeToken(parts[0], "ime_switcher");
            changed = true;
        }

        // ---- 右段：wp7search + recent ----
        if (!searchBtnEnabled) {
            // 关闭搜索键：还原为系统原生右段。
            // 注意：这里【不】删 right token —— right[.5W] 参与 ends_group 的 weight 分配，
            //   删掉会使 recent 占比由 1/4 变 1/3（变宽且右移）。
            //   系统原生串即：recent[1WC], right[.5W]
            if (parts[2].contains("wp7search")) {
                parts[2] = removeToken(parts[2], "wp7search");
                changed = true;
            }
            // recent 恢复系统原生宽度（.5W → 1W）
            if (parts[2].matches(".*recent\\[\\s*\\.?5\\s*W.*")) {
                String restored = parts[2].replaceAll("recent\\[\\s*\\.?5\\s*W(C?)", "recent[1W$1");
                // 若 right token 已被移除（历史版本残留），补回以还原原生权重
                if (!restored.contains("right")) {
                    restored = restored.replaceAll("(recent\\[1W[C]?)", "$1,right[.5W]");
                }
                parts[2] = restored;
                changed = true;
            }
        }
        return changed ? String.join(";", parts) : layout;
    }

    /** 从某一段（形如 "a[x],b,c[y]"）中移除指定 token（连同其修饰符）。 */
    private static String removeToken(String segment, String tokenName) {
        String[] items = segment.split(",", -1);
        StringBuilder sb = new StringBuilder();
        for (String it : items) {
            String name = it.trim();
            int br = name.indexOf('[');
            if (br >= 0) name = name.substring(0, br).trim();
            if (name.equals(tokenName)) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(it);
        }
        return sb.toString();
    }

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
