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
            // [2C-5 实验] 把 Search 放进 center_group（与 home 同组），而不是 ends_group。
            //   原因：ends_group(match_parent, z下) 与 center_group(wrap+center, z上) 是叠层，
            //   放 ends_group 中间必然与 home 重叠（见 2C4_LAYOUT_OVERLAP_REPORT.md）。
            //   本实验只做竖屏；横屏暂不改架构（保持原 parent）。
            ViewGroup targetParent = parent;
            if (!landscape) {
                ViewGroup cg = (inflaterView instanceof View)
                        ? findViewGroupById((View) inflaterView, SystemUiIds.ID_NAV_CENTER_GROUP)
                        : null;
                if (cg != null) {
                    targetParent = cg;
                    log("2C5: target parent switched to center_group id=0x"
                            + Integer.toHexString(SystemUiIds.ID_NAV_CENTER_GROUP)
                            + " class=" + cg.getClass().getName());
                } else {
                    log("2C5: center_group not found, keep original parent");
                }
            }
            // inflate custom_key（KeyButtonView 模板，无 id）
            View btn = li.inflate(SystemUiIds.LAYOUT_CUSTOM_KEY, targetParent, false);
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
            View wrapper = wrapWithReverseRelative(targetParent, btn, token, landscape);
            if (wrapper != null) {
                btn = wrapper; // 绑定/日志用 wrapper
            } else {
                // 包装失败：退化为直接 addView
                android.view.ViewGroup.LayoutParams lp = null;
                if (targetParent instanceof android.widget.LinearLayout) {
                    int w = resolveKeyWidth(btn);
                    lp = new android.widget.LinearLayout.LayoutParams(w, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                } else {
                    lp = btn.getLayoutParams();
                }
                float weight = parseWeight(token);
                if (lp != null && weight > 0f && lp.width > 0) lp.width = (int)(lp.width * weight);
                if (lp != null) targetParent.addView(btn, lp); else targetParent.addView(btn);
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
                    + " parent=" + targetParent.getClass().getSimpleName()
                    + " parentId=0x" + Integer.toHexString(targetParent.getId())
                    + " addToDispatchers=" + bound);
            dumpSearchDispatcher(inflaterView);
            // [2C-5 实验] 布局完成后（post 到 UI 队列末尾）打印四键坐标 + 计算 home∩search 交集。
            if (targetParent != null) {
                final ViewGroup fp = targetParent;
                final Object fiv = inflaterView;
                final boolean fl = landscape;
                targetParent.post(new Runnable() {
                    @Override public void run() {
                        dumpFourKeyRects(fiv, fp, fl);
                    }
                });
            }
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
                                                boolean landscape) {
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
            // [2C-4] 内层 KeyButtonView：与 Back/Recent 一致，主轴尺寸 = navigation_key_width。
            //   custom_key.xml 声明为 navigation_side_padding(130) —— 必须覆盖为 navigation_key_width(195)。
            // [2C-4] 内外层统一使用「竖屏语义」的 LayoutParams：
            //   外层 = LinearLayout.LayoutParams(0, MATCH_PARENT, weight)
            //   内层 = FrameLayout.LayoutParams(navigation_key_width, MATCH_PARENT)
            // 横屏容器是 ReverseLinearLayout/ReverseRelativeLayout，它会在 addView 时
            // 自动调用 reverseParams() 交换 width/height（已验证 smali）。
            // 因此这里【不要】自己写横屏分支，否则会被二次交换成错误的 w=0/h=MATCH。
            int innerMain = resolveKeyWidth(btn);
            android.widget.FrameLayout.LayoutParams flp =
                    new android.widget.FrameLayout.LayoutParams(
                            innerMain, android.view.ViewGroup.LayoutParams.MATCH_PARENT);

            // gravity：token 以 "WC" 结尾 → 0x11(CENTER_HORIZONTAL|CENTER_VERTICAL)
            int gravity = 0x11;
            try {
                java.lang.reflect.Method m = rrlCls.getMethod("setDefaultGravity", int.class);
                m.setAccessible(true);
                m.invoke(wrapper, gravity);
            } catch (Throwable ignored) { }
            try { ((android.widget.RelativeLayout) wrapper).setGravity(gravity); } catch (Throwable ignored) { }

            wrapper.addView(btn, flp);

            // [2C-4] 外层 wrapper：与系统 [xW] token 完全一致 ——
            //   主轴方向尺寸 = 0，交叉轴 = MATCH_PARENT，weight = token 解析值。
            //   让父 LinearLayout 按 weight 分配宽度（不写死像素、不手动算）。
            float weight = parseWeight(token);
            if (weight <= 0f) weight = 1.0f;   // 兜底：token 解析失败时按 1W
            android.widget.LinearLayout.LayoutParams outerLp =
                    new android.widget.LinearLayout.LayoutParams(
                            0, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            outerLp.weight = weight;
            parent.addView(wrapper, outerLp);
            log("wrap OK: outerMain=0 weight=" + weight
                    + " innerMain=" + innerMain + " landscape=" + landscape
                    + " gravity=0x" + Integer.toHexString(gravity));
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

    /** [2C-5] 从 root View 递归找指定 id 的 ViewGroup。 */
    private static ViewGroup findViewGroupById(View root, int id) {
        try {
            if (root == null) return null;
            if (root.getId() == id && root instanceof ViewGroup) return (ViewGroup) root;
            if (root instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) root;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    ViewGroup r = findViewGroupById(vg.getChildAt(i), id);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * [2C-5 实验] 打印 back/home/search/recent 的屏幕矩形 + 计算 home∩search 交集。
     * 全部通过 dispatcher.mViews 取【当前方向】的那个 View（landscape 取 [1]，竖屏取 [0]）。
     * 只读，不改状态。
     */
    private static void dumpFourKeyRects(Object inflaterView, ViewGroup targetParent, boolean landscape) {
        try {
            Object dispatchersObj = SystemUiReflection.getFieldQuiet(inflaterView, "mButtonDispatchers");
            if (!(dispatchersObj instanceof SparseArray)) { log("2C5 rect: dispatchers not SparseArray"); return; }
            @SuppressWarnings("unchecked")
            SparseArray<Object> dispatchers = (SparseArray<Object>) dispatchersObj;

            int[] ids = new int[]{
                    0x7f0b0153,                   // back
                    0x7f0b0565,                   // home
                    SystemUiIds.WP7_SEARCH_ID,    // search
                    SystemUiIds.ID_RECENT_BUTTON  // recent
            };
            String[] names = new String[]{"back", "home", "search", "recent"};
            int[] rects = new int[4 * 4]; // l,t,r,b per key

            for (int k = 0; k < ids.length; k++) {
                Object d = dispatchers.get(ids[k]);
                if (d == null) { log("2C5 rect[" + names[k] + "]: dispatcher null"); continue; }
                Object viewsObj = SystemUiReflection.getFieldQuiet(d, "mViews");
                String vs = viewsObj == null ? "null" : viewsObj.getClass().getName();
                log("2C5 rect[" + names[k] + "]: id=0x" + Integer.toHexString(ids[k])
                        + " dispatcher=" + d.getClass().getSimpleName() + " mViews=" + vs);
                // 取当前方向的 View
                View v = pickDirectionalView(viewsObj, landscape);
                if (v == null) { log("2C5   " + names[k] + ": no directional view"); continue; }
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                int w = v.getWidth(), h = v.getHeight();
                rects[k * 4] = loc[0]; rects[k * 4 + 1] = loc[1];
                rects[k * 4 + 2] = loc[0] + w; rects[k * 4 + 3] = loc[1] + h;
                log("2C5   " + names[k] + ": class=" + v.getClass().getSimpleName()
                        + " vis=" + v.getVisibility()
                        + " rect=[" + loc[0] + "," + loc[1] + "," + (loc[0] + w) + "," + (loc[1] + h) + "]"
                        + " w=" + w + " h=" + h);
            }

            // home(1) ∩ search(2)
            int[] inter = intersect(rects, 1, 2);
            log("2C5 INTERSECT home∩search = "
                    + (inter == null ? "NONE(0)" : "[" + inter[0] + "," + inter[1] + "," + inter[2] + "," + inter[3]
                    + "] area=" + Math.max(0, inter[2] - inter[0]) * Math.max(0, inter[3] - inter[1])));
            // search(2) ∩ recent(3)
            int[] ir2 = intersect(rects, 2, 3);
            log("2C5 INTERSECT search∩recent = "
                    + (ir2 == null ? "NONE(0)" : "[" + ir2[0] + "," + ir2[1] + "," + ir2[2] + "," + ir2[3] + "]"));
            // back(0) ∩ home(1)
            int[] ibh = intersect(rects, 0, 1);
            log("2C5 INTERSECT back∩home = "
                    + (ibh == null ? "NONE(0)" : "[" + ibh[0] + "," + ibh[1] + "," + ibh[2] + "," + ibh[3] + "]"));
            // center_group 自身坐标
            if (targetParent != null) {
                int[] cl = new int[2];
                targetParent.getLocationOnScreen(cl);
                log("2C5 center_group rect=[" + cl[0] + "," + cl[1] + ","
                        + (cl[0] + targetParent.getWidth()) + "," + (cl[1] + targetParent.getHeight()) + "]"
                        + " w=" + targetParent.getWidth() + " class=" + targetParent.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            log("dumpFourKeyRects err: " + t);
        }
    }

    /** 从 mViews（List 或数组）中按方向取：竖屏取第一个 width>height 的或 [0]；横屏取最后一个高>宽的或 [1]。 */
    private static View pickDirectionalView(Object viewsObj, boolean landscape) {
        java.util.List<View> list = new java.util.ArrayList<>();
        try {
            if (viewsObj instanceof java.util.List) {
                for (Object o : (java.util.List<?>) viewsObj) if (o instanceof View) list.add((View) o);
            } else if (viewsObj != null && viewsObj.getClass().isArray()) {
                int n = java.lang.reflect.Array.getLength(viewsObj);
                for (int i = 0; i < n; i++) {
                    Object o = java.lang.reflect.Array.get(viewsObj, i);
                    if (o instanceof View) list.add((View) o);
                }
            }
        } catch (Throwable ignored) { }
        if (list.isEmpty()) return null;
        for (View v : list) {
            boolean vert = v.getHeight() > v.getWidth();
            if (landscape == vert) return v;
        }
        return list.get(0);
    }

    /** 计算 rects[ai] 与 rects[bi] 的交集矩形；无交集返回 null。 */
    private static int[] intersect(int[] rects, int ai, int bi) {
        int l = Math.max(rects[ai * 4], rects[bi * 4]);
        int t = Math.max(rects[ai * 4 + 1], rects[bi * 4 + 1]);
        int r = Math.min(rects[ai * 4 + 2], rects[bi * 4 + 2]);
        int b = Math.min(rects[ai * 4 + 3], rects[bi * 4 + 3]);
        if (r <= l || b <= t) return null;
        return new int[]{l, t, r, b};
    }

    /**
     * [2C-4 前置分析，只读] dump 段容器（ends_group 的 LinearLayout）内所有子 View 的完整
     * LayoutParams / padding / rect，用于对比 Back / Search / Recent / Space 的布局机制。
     * 不修改任何状态。
     */
    private static void dumpSegmentLayout(ViewGroup seg, boolean landscape) {
        try {
            log("SEGMENT-DUMP landscape=" + landscape
                    + " class=" + seg.getClass().getName()
                    + " id=0x" + Integer.toHexString(seg.getId())
                    + " childCount=" + seg.getChildCount()
                    + " segLp=" + lpToString(seg.getLayoutParams()));
            for (int i = 0; i < seg.getChildCount(); i++) {
                View c = seg.getChildAt(i);
                int[] loc = new int[2];
                c.getLocationOnScreen(loc);
                String info = "  [" + i + "] " + c.getClass().getSimpleName()
                        + " id=0x" + Integer.toHexString(c.getId())
                        + " vis=" + c.getVisibility()
                        + " lp=" + lpToString(c.getLayoutParams())
                        + " padStart=" + c.getPaddingStart()
                        + " padEnd=" + c.getPaddingEnd()
                        + " rect=[" + loc[0] + "," + loc[1] + ","
                        + (loc[0] + c.getWidth()) + "," + (loc[1] + c.getHeight()) + "]";
                log(info);
                // 若是 ViewGroup（如 wrapper），再打印其子 View
                if (c instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) c;
                    for (int j = 0; j < vg.getChildCount(); j++) {
                        View cc = vg.getChildAt(j);
                        int[] cl = new int[2];
                        cc.getLocationOnScreen(cl);
                        log("      * " + cc.getClass().getSimpleName()
                                + " id=0x" + Integer.toHexString(cc.getId())
                                + " vis=" + cc.getVisibility()
                                + " lp=" + lpToString(cc.getLayoutParams())
                                + " rect=[" + cl[0] + "," + cl[1] + ","
                                + (cl[0] + cc.getWidth()) + "," + (cl[1] + cc.getHeight()) + "]");
                    }
                }
            }
        } catch (Throwable t) {
            log("dumpSegmentLayout err: " + t);
        }
    }

    /** LayoutParams 字符串化（含 LinearLayout weight）。 */
    private static String lpToString(android.view.ViewGroup.LayoutParams lp) {
        if (lp == null) return "null";
        String base = lp.getClass().getSimpleName() + "{w=" + lp.width + ",h=" + lp.height;
        if (lp instanceof android.widget.LinearLayout.LayoutParams) {
            base += ",weight=" + ((android.widget.LinearLayout.LayoutParams) lp).weight;
        }
        if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams m = (android.view.ViewGroup.MarginLayoutParams) lp;
            base += ",marginL=" + m.leftMargin + ",marginR=" + m.rightMargin;
        }
        return base + "}";
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