package com.wp7.navbar;

import android.content.Context;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Constructor;

import de.robv.android.xposed.XposedBridge;

/**
 * 第二阶段 2C-1：独立 Search ContextualButton 的最小实现。
 *
 * 目标（仅验证链路，不做图标/点击/布局）：
 *   wp7search token
 *     → inflate custom_key
 *     → KeyButtonView
 *     → setId(WP7_SEARCH_ID)
 *     → 【调用原系统 addToDispatchers(view)】
 *     → mButtonDispatchers[WP7_SEARCH_ID]
 *     → SearchContextualButton.addView(view)
 *
 * 关键原则：
 *  - 复用 SystemUI 现有 custom_key layout，不 new KeyButtonView。
 *  - 调用原 addToDispatchers(view)，不复制其绑定逻辑。
 *  - horizontal / vertical 各创建一份（由 inflateButtons 自身两次调用保证）。
 *  - 不碰 recent_apps。
 */
public final class SearchContextualButtonFactory {

    private static final String TAG = "WP7NavBar";
    private static final String P = "[WP7Search2C1] ";
    private static final String TOKEN = "wp7search";

    private SearchContextualButtonFactory() {}

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + P + msg);
    }

    // ==================================================================
    // 1. 注册：在 NavigationBarInflaterView.onFinishInflate after 时
    //    把 Search ContextualButton put 进 mButtonDispatchers
    // ==================================================================

    /** 把一个已构造好的 Search ContextualButton 放进给定的 SparseArray（若未存在）。 */
    public static void putInto(Object sparseArray, Object inflaterView) {
        try {
            if (!(sparseArray instanceof SparseArray)) {
                log("putInto: arg not SparseArray");
                return;
            }
            @SuppressWarnings("unchecked")
            SparseArray<Object> dispatchers = (SparseArray<Object>) sparseArray;
            if (dispatchers.indexOfKey(SystemUiIds.WP7_SEARCH_ID) >= 0) {
                log("putInto: already present");
                return;
            }
            Context ctx = (inflaterView instanceof View)
                    ? ((View) inflaterView).getContext() : null;
            if (ctx == null) {
                log("putInto: context null");
                return;
            }
            Object searchBtn = createContextualButton(ctx, SystemUiIds.WP7_SEARCH_ID);
            if (searchBtn == null) {
                log("putInto: createContextualButton FAILED");
                return;
            }
            dispatchers.put(SystemUiIds.WP7_SEARCH_ID, searchBtn);
            log("putInto OK: id=0x" + Integer.toHexString(SystemUiIds.WP7_SEARCH_ID)
                    + " class=" + searchBtn.getClass().getSimpleName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": putInto err: " + t);
        }
    }

    /** 在 inflaterView 的 mButtonDispatchers 中注册（若不存在）。 */
    public static void ensureRegistered(Object inflaterView) {
        try {
            if (!(inflaterView instanceof View)) return;
            View v = (View) inflaterView;
            Object dispatchersObj = SystemUiReflection.getFieldQuiet(inflaterView, "mButtonDispatchers");
            if (!(dispatchersObj instanceof SparseArray)) {
                log("ensureRegistered: mButtonDispatchers not SparseArray, skip");
                return;
            }
            @SuppressWarnings("unchecked")
            SparseArray<Object> dispatchers = (SparseArray<Object>) dispatchersObj;
            if (dispatchers.indexOfKey(SystemUiIds.WP7_SEARCH_ID) >= 0) {
                return; // 已注册
            }
            Context ctx = v.getContext();
            Object searchBtn = createContextualButton(ctx, SystemUiIds.WP7_SEARCH_ID);
            if (searchBtn == null) {
                log("ensureRegistered: createContextualButton FAILED");
                return;
            }
            dispatchers.put(SystemUiIds.WP7_SEARCH_ID, searchBtn);
            log("registered Search ContextualButton id=0x"
                    + Integer.toHexString(SystemUiIds.WP7_SEARCH_ID)
                    + " class=" + searchBtn.getClass().getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ensureRegistered err: " + t);
        }
    }

    /**
     * 尝试构造 ContextualButton(context, id)。
     * 反编译确认 ContextualButton 继承 ButtonDispatcher，构造签名需运行时探测。
     */
    private static Object createContextualButton(Context ctx, int id) {
        try {
            Class<?> cbCls = SystemUiReflection.findClass(
                    "com.android.systemui.navigationbar.views.buttons.ContextualButton",
                    ctx.getClassLoader());
            if (cbCls == null) {
                log("createContextualButton: class not found");
                return null;
            }
            // 打印所有构造签名（诊断）
            StringBuilder all = new StringBuilder("ctors: ");
            for (Constructor<?> c : cbCls.getDeclaredConstructors()) {
                if (c.isSynthetic()) continue;
                all.append("(");
                for (Class<?> p : c.getParameterTypes()) all.append(p.getSimpleName()).append(",");
                all.append(") ");
            }
            log(all.toString());
            // 真实签名：ContextualButton(Context, int id, int iconResId)
            for (Constructor<?> c : cbCls.getDeclaredConstructors()) {
                Class<?>[] ps = c.getParameterTypes();
                if (ps.length == 3 && ps[0] == Context.class && ps[1] == int.class && ps[2] == int.class) {
                    try {
                        c.setAccessible(true);
                        Object o = c.newInstance(ctx, id, 0); // iconResId=0（2C-2 再设图标）
                        log("ctor (Context,int,int) OK");
                        return o;
                    } catch (Throwable e) {
                        log("ctor (Context,int,int) err: " + e);
                    }
                }
            }
            // 退路：(Context, int)
            for (Constructor<?> c : cbCls.getDeclaredConstructors()) {
                Class<?>[] ps = c.getParameterTypes();
                if (ps.length == 2 && ps[0] == Context.class && ps[1] == int.class) {
                    try {
                        c.setAccessible(true);
                        Object o = c.newInstance(ctx, id);
                        log("ctor (Context,int) OK");
                        return o;
                    } catch (Throwable e) {
                        log("ctor (Context,int) err: " + e);
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": createContextualButton err: " + t);
        }
        return null;
    }

    // ==================================================================
    // 2. inflateButtons 处理：识别 wp7search，创建 View 并 setId
    // ==================================================================

    /** token 是否为 WP7 search。 */
    public static boolean isSearchToken(String token) {
        if (token == null) return false;
        String name = token;
        int b = name.indexOf('[');
        if (b >= 0) name = name.substring(0, b);
        return TOKEN.equals(name.trim());
    }

    /**
     * 处理单个 wp7search token（由 inflateButtons hook 调用）：
     * inflate custom_key → setId(WP7_SEARCH_ID) → addView 到 parent → addToDispatchers(view)。
     *
     * @param inflaterView NavigationBarInflaterView 实例（用于调 addToDispatchers 与取 LayoutInflater）
     * @param token        原始 token（含修饰符，如 "wp7search[1W]"）
     * @param parent       当前 container（ends_group/center_group 的 horizontal 或 vertical）
     * @param landscape    是否横屏容器
     */
    public static void inflateSearchButton(Object inflaterView, String token,
                                           ViewGroup parent, boolean landscape) {
        try {
            if (parent == null) {
                log("inflateSearchButton: parent null, skip");
                return;
            }
            LayoutInflater li = resolveLayoutInflater(inflaterView, landscape);
            if (li == null) {
                log("inflateSearchButton: LayoutInflater null, skip");
                return;
            }
            // inflate custom_key（KeyButtonView 模板，无 id）
            View btn = li.inflate(SystemUiIds.LAYOUT_CUSTOM_KEY, parent, false);
            if (btn == null) {
                log("inflateSearchButton: inflate custom_key returned null");
                return;
            }
            btn.setId(SystemUiIds.WP7_SEARCH_ID);
            // 与系统 generic key 一致：设置可点击等（先最小化，仅 setId）
            parent.addView(btn);
            // 调用原系统 addToDispatchers(view) —— 让 SystemUI 自己完成绑定
            boolean bound = callAddToDispatchers(inflaterView, btn);
            log("token=" + TOKEN
                    + " landscape=" + landscape
                    + " view=" + shortId(btn)
                    + " id=0x" + Integer.toHexString(btn.getId())
                    + " parent=" + parent.getClass().getSimpleName()
                    + " parentId=0x" + Integer.toHexString(parent.getId())
                    + " addToDispatchers=" + bound);
            dumpSearchDispatcher(inflaterView);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": inflateSearchButton err: " + t);
        }
    }

    /** 取 inflaterView 的 mLandscapeInflater / mLayoutInflater。 */
    private static LayoutInflater resolveLayoutInflater(Object inflaterView, boolean landscape) {
        try {
            Object li = SystemUiReflection.getFieldQuiet(inflaterView,
                    landscape ? "mLandscapeInflater" : "mLayoutInflater");
            if (li instanceof LayoutInflater) return (LayoutInflater) li;
            // 退路
            if (inflaterView instanceof View) {
                return LayoutInflater.from(((View) inflaterView).getContext());
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 反射调用 NavigationBarInflaterView.addToDispatchers(View)。 */
    private static boolean callAddToDispatchers(Object inflaterView, View btn) {
        try {
            Class<?> cls = inflaterView.getClass();
            java.lang.reflect.Method m = cls.getDeclaredMethod("addToDispatchers", View.class);
            m.setAccessible(true);
            m.invoke(inflaterView, btn);
            return true;
        } catch (Throwable t) {
            log("callAddToDispatchers err: " + t);
            return false;
        }
    }

    /** putInto 完成后（setButtonDispatchers after）dump Search dispatcher 状态。 */
    public static void dumpAfterBind(Object inflaterView) {
        dumpSearchDispatcher(inflaterView);
    }

    /** 打印 Search dispatcher 的 mViews 情况。 */
    private static void dumpSearchDispatcher(Object inflaterView) {
        try {
            Object dispatchersObj = SystemUiReflection.getFieldQuiet(inflaterView, "mButtonDispatchers");
            if (!(dispatchersObj instanceof SparseArray)) return;
            @SuppressWarnings("unchecked")
            SparseArray<Object> dispatchers = (SparseArray<Object>) dispatchersObj;
            Object d = dispatchers.get(SystemUiIds.WP7_SEARCH_ID);
            if (d == null) {
                log("Search dispatcher = null");
                return;
            }
            Object mViews = SystemUiReflection.getFieldQuiet(d, "mViews");
            int n = (mViews instanceof java.util.List) ? ((java.util.List<?>) mViews).size() : -1;
            log("Search dispatcher class=" + d.getClass().getSimpleName()
                    + " mViews=" + n);
            if (mViews instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) mViews;
                for (int i = 0; i < list.size(); i++) {
                    Object vv = list.get(i);
                    if (vv instanceof View) {
                        View v = (View) vv;
                        log("  Search View[" + i + "] id=0x" + Integer.toHexString(v.getId())
                                + " parent=" + (v.getParent() == null ? "null"
                                        : v.getParent().getClass().getSimpleName())
                                + " vis=" + v.getVisibility());
                    }
                }
            }
        } catch (Throwable t) {
            log("dumpSearchDispatcher err: " + t);
        }
    }

    private static String shortId(View v) {
        return v.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(v));
    }
}