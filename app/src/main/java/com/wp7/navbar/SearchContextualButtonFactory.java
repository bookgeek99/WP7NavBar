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
            // [2C-修复] 模仿系统 inflateButtons 对 [xW] 键的处理：包一层
            // ReverseLinearLayout$ReverseRelativeLayout（Back/Home/Recent 都有），
            // 否则裸 KeyButtonView 宽度/间距与邻居不一致（视觉偏左）。
            //   wrapper = new ReverseRelativeLayout(ctx)
            //   wrapper.setDefaultGravity(g); wrapper.setGravity(g)
            //   wrapper.addView(btn, FrameLayout.LayoutParams(btn原LP))
            //   parent.addView(wrapper, LinearLayout.LayoutParams(keyWidth×weight, MATCH))
            android.view.ViewGroup.LayoutParams neighborLp = null;
            View neighbor = findNeighborKey(parent);
            if (neighbor != null) neighborLp = neighbor.getLayoutParams();
            View wrapper = wrapWithReverseRelative(parent, btn, token, neighborLp);
            if (wrapper != null) {
                btn = wrapper; // 绑定/日志用 wrapper
            } else {
                // 包装失败：退化为直接 addView
                android.view.ViewGroup.LayoutParams lp = null;
                if (parent instanceof android.widget.LinearLayout) {
                    int w = resolveKeyWidth(btn);
                    lp = new android.widget.LinearLayout.LayoutParams(w, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                } else {
                    lp = btn.getLayoutParams();
                }
                float weight = parseWeight(token);
                if (lp != null && weight > 0f && lp.width > 0) lp.width = (int)(lp.width * weight);
                if (lp != null) parent.addView(btn, lp); else parent.addView(btn);
            }
            // 调用原系统 addToDispatchers(view) —— 让 SystemUI 自己完成绑定
            // （若已包装，传 wrapper：系统 addToDispatchers 会递归子 View 找到真正的 KeyButtonView）
            boolean bound = callAddToDispatchers(inflaterView, btn);
            log("wrapper=" + shortId(btn)
                    + " innerKeyId=0x" + Integer.toHexString(SystemUiIds.WP7_SEARCH_ID));
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

    /** 读 SystemUI 的 navigation_key_width（与 back/home/recent 一致）。失败返回 WRAP_CONTENT。 */
    private static int resolveKeyWidth(View v) {
        try {
            android.content.Context ctx = v.getContext();
            android.content.res.Resources res = ctx.getResources();
            int id = res.getIdentifier("navigation_key_width", "dimen", "com.android.systemui");
            if (id != 0) return res.getDimensionPixelSize(id);
        } catch (Throwable ignored) { }
        return android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    /**
     * 把 KeyButtonView 包进 ReverseLinearLayout$ReverseRelativeLayout（模仿系统 [xW] 键结构），
     * 并加到 parent。成功返回 wrapper，失败返回 null。
     *
     * 系统逻辑（NavigationBarInflaterView.inflateButtons :goto_7）：
     *   wrapper = new ReverseRelativeLayout(ctx)
     *   FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(btn 原 LP)
     *   wrapper.setDefaultGravity(g); wrapper.setGravity(g)   // "WC"→0x11(CENTER)
     *   wrapper.addView(btn, flp)
     *   parent.addView(wrapper, LinearLayout.LayoutParams(keyWidth, MATCH))
     */
    private static View wrapWithReverseRelative(ViewGroup parent, View btn, String token,
                                                android.view.ViewGroup.LayoutParams neighborLp) {
        try {
            if (!(parent instanceof android.widget.LinearLayout)) return null;
            android.content.Context ctx = btn.getContext();
            Class<?> rrlCls = SystemUiReflection.findClass(
                    "com.android.systemui.navigationbar.views.buttons.ReverseLinearLayout$ReverseRelativeLayout",
                    btn.getClass().getClassLoader());
            if (rrlCls == null) { log("wrap: ReverseRelativeLayout not found"); return null; }
            java.lang.reflect.Constructor<?> ctor = rrlCls.getDeclaredConstructor(android.content.Context.class);
            ctor.setAccessible(true);
            android.view.ViewGroup wrapper = (android.view.ViewGroup) ctor.newInstance(ctx);

            // 内层 FrameLayout.LayoutParams（来自 btn 原 LP）
            android.view.ViewGroup.LayoutParams btnLp = btn.getLayoutParams();
            android.widget.FrameLayout.LayoutParams flp =
                    (btnLp != null) ? new android.widget.FrameLayout.LayoutParams(btnLp)
                                    : new android.widget.FrameLayout.LayoutParams(-2, -1);

            // gravity：token 以 "WC" 结尾 → 0x11(CENTER_HORIZONTAL|CENTER_VERTICAL)
            int gravity = 0x11;
            try {
                java.lang.reflect.Method m = rrlCls.getMethod("setDefaultGravity", int.class);
                m.setAccessible(true);
                m.invoke(wrapper, gravity);
            } catch (Throwable ignored) { }
            try { ((android.widget.RelativeLayout) wrapper).setGravity(gravity); } catch (Throwable ignored) { }

            wrapper.addView(btn, flp);

            // 外层：宽度对齐邻居键；邻居还没 inflate 时读 navigation_key_width
            int w;
            if (neighborLp != null && neighborLp.width > 0) {
                w = neighborLp.width;
            } else {
                w = resolveKeyWidth(btn);
            }
            float weight = parseWeight(token);
            if (weight > 0f && w > 0) w = (int) (w * weight);
            android.widget.LinearLayout.LayoutParams outerLp =
                    new android.widget.LinearLayout.LayoutParams(w, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            parent.addView(wrapper, outerLp);
            log("wrap OK: keyWidth=" + w + " gravity=0x" + Integer.toHexString(gravity));
            return wrapper;
        } catch (Throwable t) {
            log("wrap err: " + t);
            return null;
        }
    }

    /** 读 SystemUI 的 navigation_key_padding。失败返回 0。 */
    private static int resolveKeyPadding(View v) {
        try {
            android.content.Context ctx = v.getContext();
            android.content.res.Resources res = ctx.getResources();
            int id = res.getIdentifier("navigation_key_padding", "dimen", "com.android.systemui");
            if (id != 0) return res.getDimensionPixelSize(id);
        } catch (Throwable ignored) { }
        return 0;
    }

    /** 在容器内找一个已有的导航键（back/home/recent），用于复制其 LayoutParams/padding。 */
    private static View findNeighborKey(ViewGroup parent) {
        try {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View c = parent.getChildAt(i);
                int id = c.getId();
                if (id == SystemUiIds.ID_RECENT_BUTTON) return c;
            }
            for (int i = 0; i < parent.getChildCount(); i++) {
                View c = parent.getChildAt(i);
                if (c instanceof android.widget.ImageView) return c;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 从 token（如 wp7search[1W]）解析 weight；W 后缀且无数字=1.0，A 后缀=1.0，无后缀=0。 */
    private static float parseWeight(String token) {
        try {
            int lb = token.indexOf(0x5b); int rb = token.indexOf(0x5d);
            if (lb < 0 || rb <= lb) return 0f;
            String inner = token.substring(lb + 1, rb);
            if (inner.endsWith("W") || inner.endsWith("A")) {
                String num = inner.substring(0, inner.length() - 1);
                if (num.isEmpty()) return 1f;
                return Float.parseFloat(num);
            }
            return Float.parseFloat(inner);
        } catch (Throwable t) { return 0f; }
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

    // ==================================================================
    // 2C-2：给 Search dispatcher 设置 WP7 放大镜图标
    // ==================================================================

    /**
     * 给 mButtonDispatchers[WP7_SEARCH_ID] 设置 WP7 放大镜图标（mImageDrawable）。
     *
     * 必须在系统 addAll 绑定完成之后调用（setButtonDispatchers after），
     * 此时 dispatcher.mViews 已有 horizontal + vertical 两个 View。
     *
     * 完全走系统路径：dispatcher.setImageDrawable(KeyButtonDrawable)
     * 不依赖 View 顺序/resource name/mCode；通过 dispatcher identity 定位。
     */
    public static void applyWp7IconAfterBind(Object inflaterView) {
        try {
            Object dispatchersObj = SystemUiReflection.getFieldQuiet(inflaterView, "mButtonDispatchers");
            if (!(dispatchersObj instanceof SparseArray)) {
                log("applyWp7Icon: mButtonDispatchers not SparseArray");
                return;
            }
            SparseArray<?> dispatchers = (SparseArray<?>) dispatchersObj;
            Object dispatcher = dispatchers.get(SystemUiIds.WP7_SEARCH_ID);
            if (dispatcher == null) {
                log("applyWp7Icon: dispatcher null");
                return;
            }
            Object mViews = SystemUiReflection.getFieldQuiet(dispatcher, "mViews");
            android.widget.ImageView template = null;
            if (mViews instanceof java.util.List) {
                for (Object o : (java.util.List<?>) mViews) {
                    if (o instanceof android.widget.ImageView) { template = (android.widget.ImageView) o; break; }
                }
            }
            if (template == null) {
                log("applyWp7Icon: no ImageView in mViews");
                return;
            }
            // [颜色对齐] 设图标前先确保已从业 Home/Back 读取参照色。
            // template 是新 Search View，其 drawable 无有效 mState，无法作参照。
            // 由 inflaterView 向上找 NavigationBarView 后收集 refColor。
            try {
                if (!SearchButtonController.refColorLoaded && inflaterView instanceof View) {
                    View nbv = findNavBarView((View) inflaterView);
                    if (nbv != null) SearchButtonController.collectRefColor(nbv);
                }
            } catch (Throwable ignored) { }
            android.graphics.drawable.Drawable kbd = SearchButtonController.createSearchKeyButtonDrawable(template, template.getClass().getClassLoader());
            if (kbd == null) {
                log("applyWp7Icon: createSearchKeyButtonDrawable null");
                return;
            }
            Class<?> kbdCls = SystemUiReflection.findClass("com.android.systemui.navigationbar.views.buttons.KeyButtonDrawable", template.getClass().getClassLoader());
            if (kbdCls == null) {
                log("applyWp7Icon: KeyButtonDrawable class not found");
                return;
            }
            // ButtonDispatcher.setImageDrawable 是 public final，且在父类（ContextualButton 未重写），
            // 故用 getMethod（含继承）；找不到时逐级向父类 getDeclaredMethod。
            java.lang.reflect.Method m = null;
            try {
                m = dispatcher.getClass().getMethod("setImageDrawable", kbdCls);
            } catch (NoSuchMethodException nsme) {
                Class<?> cc = dispatcher.getClass();
                while (cc != null && m == null) {
                    try { m = cc.getDeclaredMethod("setImageDrawable", kbdCls); }
                    catch (NoSuchMethodException e2) { cc = cc.getSuperclass(); }
                }
            }
            if (m == null) {
                log("applyWp7Icon: setImageDrawable method not found");
                return;
            }
            m.setAccessible(true);
            m.invoke(dispatcher, kbd);
            log("applyWp7Icon OK: dispatcher=" + dispatcher.getClass().getSimpleName() + " kbd=" + kbd.getClass().getSimpleName());
            // [2C-3] 绑定 click / long click（走 dispatcher.setXxxListener，系统遍历 mViews 下发）
            bindSearchListeners(dispatcher, template);
        } catch (Throwable t) {
            log("applyWp7Icon err: " + t);
        }
    }
    // ==================================================================
    // 2C-3：给 Search dispatcher 绑定 click / long click
    // ==================================================================

    /**
     * 给 Search dispatcher 绑定点击 / 长按：
     *  - 单击 → 小爱语音助手
     *  - 长按 → 屏幕识别
     *
     * 走系统路径：dispatcher.setOnClickListener / setOnLongClickListener，
     * ButtonDispatcher 内部会遍历 mViews 对每个 View 调用 setOnClickListener。
     */
    private static void bindSearchListeners(Object dispatcher, android.widget.ImageView template) {
        try {
            android.view.View.OnClickListener click =
                    new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View v) {
                            SearchButtonController.invokeAssist(v, 5, false);
                        }
                    };
            android.view.View.OnLongClickListener lclick =
                    new android.view.View.OnLongClickListener() {
                        @Override public boolean onLongClick(android.view.View v) {
                            SearchButtonController.invokeAssist(v, 6, true);
                            return true;
                        }
                    };
            java.lang.reflect.Method mClick = dispatcher.getClass()
                    .getMethod("setOnClickListener", android.view.View.OnClickListener.class);
            mClick.setAccessible(true);
            mClick.invoke(dispatcher, click);
            java.lang.reflect.Method mLong = dispatcher.getClass()
                    .getMethod("setOnLongClickListener", android.view.View.OnLongClickListener.class);
            mLong.setAccessible(true);
            mLong.invoke(dispatcher, lclick);
            log("bindSearchListeners OK");
        } catch (Throwable t) {
            log("bindSearchListeners err: " + t);
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
                        android.view.ViewGroup.LayoutParams vlp = v.getLayoutParams();
                        int[] loc = new int[2];
                        v.getLocationOnScreen(loc);
                        log("  Search View[" + i + "] id=0x" + Integer.toHexString(v.getId())
                                + " parent=" + (v.getParent() == null ? "null"
                                        : v.getParent().getClass().getSimpleName())
                                + " lp=" + (vlp == null ? "null" : (vlp.getClass().getSimpleName()
                                        + "{w=" + vlp.width + ",h=" + vlp.height
                                        + (vlp instanceof android.widget.LinearLayout.LayoutParams
                                            ? ",weight=" + ((android.widget.LinearLayout.LayoutParams) vlp).weight : "")))
                                + " rect=[" + loc[0] + "," + loc[1] + "," + (loc[0]+v.getWidth()) + "," + (loc[1]+v.getHeight()) + "]"
                                + " vis=" + v.getVisibility());
                    }
                }
            }
        } catch (Throwable t) {
            log("dumpSearchDispatcher err: " + t);
        }
    }

    /** 从任意 View 向上寻找 NavigationBarView 实例（用作 refColor 收集根）。 */
    private static View findNavBarView(View v) {
        try {
            android.view.ViewParent p = v.getParent();
            while (p instanceof View) {
                View pv = (View) p;
                if (pv.getClass().getName().endsWith("NavigationBarView")) return pv;
                p = pv.getParent();
            }
        } catch (Throwable ignored) { }
        return null;
    }
    private static String shortId(View v) {
        return v.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(v));
    }
}