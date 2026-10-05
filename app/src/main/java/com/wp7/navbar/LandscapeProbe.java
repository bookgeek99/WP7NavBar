package com.wp7.navbar;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * 第二阶段 2A：横屏导航栏只读探测。
 *
 * 【严格只读】本类不修改任何布局 / View / 字段 / 点击 / 可见性。
 * 仅在 XposedBridge.log 里输出前缀 [WP7NavBar][LANDSCAPE_PROBE] 的诊断信息，
 * 用于分析 HyperOS 3 横屏导航栏真实结构（layout / View 树 / Recent / ButtonDispatcher /
 * ContextualButtonGroup / InflaterView 内部字段）。
 *
 * 目的：为第二阶段 2C「横屏四键（Back|Home|Search|Recent）」提供事实依据，而非凭猜。
 */
public final class LandscapeProbe {

    private static final String TAG = "WP7NavBar";
    private static final String P = "[LANDSCAPE_PROBE] ";
    private static final boolean ENABLED = true;

    private LandscapeProbe() {}

    private static void log(String msg) {
        if (ENABLED) XposedBridge.log(TAG + ": " + P + msg);
    }

    // ==================================================================
    // 三 / 四：layout 字符串探测
    // ==================================================================

    /** 记录 getDefaultLayout 的返回值（按方向区分）。 */
    public static void logGetDefaultLayout(String orientation, Object result) {
        if (!ENABLED) return;
        log("getDefaultLayout orientation=" + orientation + " =\n" + result);
    }

    /** 记录 inflateLayout 的 BEFORE（原始传入串）与 BEFORE_CLEAN（清理后待写入串）。 */
    public static void logInflateBefore(String original, String cleaned) {
        if (!ENABLED) return;
        log("inflateLayout BEFORE = " + original);
        if (cleaned != null && !cleaned.equals(original)) {
            log("inflateLayout BEFORE_CLEAN = " + cleaned);
        }
    }

    /** 记录 inflateLayout 完成后（可选）。 */
    public static void logInflateAfter(Object inflaterView) {
        if (!ENABLED) return;
        log("inflateLayout AFTER (inflaterView=" + simpleName(inflaterView) + ")");
    }

    // ==================================================================
    // 五 / 六：View 层级遍历 + Recent 识别
    // ==================================================================

    /** 在横屏 inflate 完成后遍历整个 View 树并输出。 */
    public static void dumpLandscapeTree(View root) {
        if (!ENABLED) return;
        log("========== LANDSCAPE VIEW HIERARCHY ==========");
        dumpView(root, 0, root.getResources());
        log("========== END VIEW HIERARCHY ==========");
    }

    private static void dumpView(View v, int depth, Resources res) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("  ".repeat(Math.max(0, depth)));
            sb.append(simpleName(v));
            sb.append(" id=").append(resName(res, v.getId()));
            sb.append(" vis=").append(visName(v.getVisibility()));
            sb.append(" rect=[")
                    .append(v.getLeft()).append(",").append(v.getTop()).append(",")
                    .append(v.getRight()).append(",").append(v.getBottom()).append("]");
            sb.append(" clickable=").append(v.isClickable());
            sb.append(" longClickable=").append(v.isLongClickable());
            // [GROUP_MAPPING 只读] 附加 LayoutParams + parent
            try {
                android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp != null) {
                    sb.append(" lp=").append(lp.getClass().getSimpleName())
                      .append("{w=").append(lp.width).append(",h=").append(lp.height);
                    if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                        sb.append(",weight=").append(((android.widget.LinearLayout.LayoutParams) lp).weight);
                    }
                    sb.append("}");
                }
                sb.append(" parent=").append(simpleName(v.getParent()));
            } catch (Throwable ignored) { }
            CharSequence cd = v.getContentDescription();
            sb.append(" cd=").append(cd == null ? "null" : "\"" + cd + "\"");
            // mCode（KeyButtonView 专有）
            Object code = SystemUiReflection.getFieldQuiet(v, "mCode");
            if (code != null) sb.append(" mCode=").append(code);
            log(sb.toString());

            // Recent 识别
            if (isRecentLike(v, code)) {
                log("RECENT VIEW FOUND: class=" + v.getClass().getName()
                        + " id=" + resName(res, v.getId())
                        + " mCode=" + code
                        + " cd=" + (cd == null ? "null" : cd)
                        + " parent=" + simpleName(v.getParent()));
            }

            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    dumpView(vg.getChildAt(i), depth + 1, res);
                }
            }
        } catch (Throwable t) {
            log("dumpView err: " + t);
        }
    }

    private static boolean isRecentLike(View v, Object mCode) {
        try {
            if (v.getId() == SystemUiIds.ID_RECENT_BUTTON) return true;
            if (mCode instanceof Integer) {
                int c = (Integer) mCode;
                // KEYCODE_APP_SWITCH = 187
                if (c == 187) return true;
            }
            CharSequence cd = v.getContentDescription();
            if (cd != null) {
                String s = cd.toString();
                if (s.contains("最近") || s.contains("任务") || s.toLowerCase().contains("recent")) {
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    // ==================================================================
    // 七 / 八 / 九：ButtonDispatcher / ContextualButtonGroup / Reflection 遍历
    // ==================================================================

    /** 从 NavigationBarView 反射遍历所有字段，找 ButtonDispatcher / ContextualButtonGroup 等。 */
    public static void dumpDispatchers(View navBarView) {
        if (!ENABLED) return;
        log("========== NAVBARVIEW FIELDS (dispatcher probe) ==========");
        Class<?> cls = navBarView.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    String typeName = f.getType().getName();
                    String simple = f.getType().getSimpleName();
                    if (simple.contains("Dispatcher") || simple.contains("ContextualButton")
                            || typeName.contains("Dispatcher") || typeName.contains("ContextualButton")
                            || simple.contains("ButtonGroup")) {
                        Object val = f.get(navBarView);
                        log("FIELD " + cls.getSimpleName() + "." + f.getName()
                                + " type=" + simple + " value=" + describe(val));
                        dumpDispatcherObject(f.getName(), val);
                    }
                } catch (Throwable ignored) { }
            }
            cls = cls.getSuperclass();
        }
        log("========== END NAVBARVIEW FIELDS ==========");
    }

    /** 打印单个 dispatcher 的关键字段。 */
    private static void dumpDispatcherObject(String fieldName, Object dispatcher) {
        if (dispatcher == null) return;
        try {
            Object mId = SystemUiReflection.getFieldQuiet(dispatcher, "mId");
            Object mViews = SystemUiReflection.getFieldQuiet(dispatcher, "mViews");
            Object mClick = SystemUiReflection.getFieldQuiet(dispatcher, "mClickListener");
            Object mLongClick = SystemUiReflection.getFieldQuiet(dispatcher, "mLongClickListener");

            StringBuilder sb = new StringBuilder();
            sb.append("  DISPATCHER ").append(fieldName)
                    .append(" class=").append(dispatcher.getClass().getSimpleName())
                    .append(" mId=").append(mId);
            if (mViews instanceof List) {
                sb.append(" mViews.size=").append(((List<?>) mViews).size());
            }
            if (mClick != null) {
                sb.append(" mClickListener=").append(mClick.getClass().getName());
            }
            if (mLongClick != null) {
                sb.append(" mLongClickListener=").append(mLongClick.getClass().getName());
            }
            log(sb.toString());
        } catch (Throwable t) {
            log("  DISPATCHER " + fieldName + " err: " + t);
        }
    }

    /** 若 val 是 ContextualButtonGroup（含内部 dispatcher 列表），尽量打印。 */
    private static void dumpContextualGroup(String fieldName, Object group) {
        if (group == null) return;
        try {
            Object buttons = SystemUiReflection.getFieldQuiet(group, "mButtonMap");
            if (buttons == null) buttons = SystemUiReflection.getFieldQuiet(group, "mContextualButtons");
            log("  CONTEXTUAL_GROUP " + fieldName + " class=" + group.getClass().getName()
                    + " buttons=" + describe(buttons));
        } catch (Throwable ignored) { }
    }

    // ==================================================================
    // 十：NavigationBarInflaterView 内部字段
    // ==================================================================

    /** 反射遍历 NavigationBarInflaterView 的 layout / buttons / orientation / dispatcher 相关字段。 */
    public static void dumpInflaterFields(View inflaterView) {
        if (!ENABLED || inflaterView == null) return;
        log("========== INFLATERVIEW FIELDS ==========");
        Class<?> cls = inflaterView.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    String n = f.getName().toLowerCase();
                    if (n.contains("layout") || n.contains("button") || n.contains("orientation")
                            || n.contains("horizontal") || n.contains("vertical")
                            || n.contains("landscape") || n.contains("portrait")
                            || n.contains("dispatcher") || n.contains("current")) {
                        f.setAccessible(true);
                        Object val = f.get(inflaterView);
                        log("FIELD " + cls.getSimpleName() + "." + f.getName()
                                + " type=" + f.getType().getSimpleName()
                                + " value=" + describe(val));
                        // mButtonDispatchers 特别展开：每个 dispatcher 的 id / 类 / views
                        if (f.getName().equals("mButtonDispatchers")
                                && val instanceof android.util.SparseArray) {
                            android.util.SparseArray<?> sa = (android.util.SparseArray<?>) val;
                            for (int k = 0; k < sa.size(); k++) {
                                int key = sa.keyAt(k);
                                Object d = sa.valueAt(k);
                                StringBuilder sb = new StringBuilder();
                                sb.append("    DISPATCHER_KEY 0x").append(Integer.toHexString(key))
                                        .append("(");
                                String rn = resNameSafe(inflaterView.getResources(), key);
                                sb.append(rn).append(") class=").append(simpleName(d));
                                Object mId = SystemUiReflection.getFieldQuiet(d, "mId");
                                Object mViews = SystemUiReflection.getFieldQuiet(d, "mViews");
                                sb.append(" mId=").append(mId);
                                if (mViews instanceof List) {
                                    List<?> vs = (List<?>) mViews;
                                    sb.append(" mViews.size=").append(vs.size());
                                    for (int vi = 0; vi < vs.size(); vi++) {
                                        sb.append(" [").append(vi).append("]=")
                                                .append(describeDispatcherView(vs.get(vi), inflaterView.getResources()));
                                    }
                                }
                                log(sb.toString());
                                // ContextualButtonGroup / ContextualButton：展开内部
                                if (simpleName(d).contains("Contextual")) {
                                    dumpContextualGroup(sa.keyAt(k) + ":" + rn, d);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) { }
            }
            cls = cls.getSuperclass();
        }
        log("========== END INFLATERVIEW FIELDS ==========");
    }

    // ==================================================================
    // 十一：layout 字符串结构解析（只分析，不修改）
    // ==================================================================

    /**
     * 解析布局串结构：按 ';' 分段，每段按 ',' 分 token，token 形如 "back[1WC]"。
     * 输出 LANDSCAPE_LAYOUT_ANALYSIS。
     */
    public static void analyzeLayoutString(String layout) {
        if (!ENABLED || layout == null) return;
        log("========== LANDSCAPE_LAYOUT_ANALYSIS ==========");
        log("raw = " + layout);
        String[] segments = layout.split(";", -1);
        for (int i = 0; i < segments.length; i++) {
            log("segment[" + i + "] = " + segments[i]);
            String[] tokens = segments[i].split(",", -1);
            for (String tok : tokens) {
                if (tok.isEmpty()) continue;
                String name = tok;
                String modifier = "";
                int b = tok.indexOf('[');
                if (b >= 0) {
                    name = tok.substring(0, b);
                    int e = tok.indexOf(']', b);
                    modifier = e > b ? tok.substring(b + 1, e) : "";
                }
                log("    token name=" + name + " modifier=[" + modifier + "]");
            }
        }
        log("========== END LANDSCAPE_LAYOUT_ANALYSIS ==========");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static String resName(Resources res, int id) {
        if (id == View.NO_ID) return "NO_ID(0x" + Integer.toHexString(id) + ")";
        try {
            return res.getResourceEntryName(id) + "(0x" + Integer.toHexString(id) + ")";
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static String resNameSafe(Resources res, int id) {
        try {
            return res.getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    /** dispatcher.mViews 里的元素可能是 View 或 ButtonDispatcher 包装，尽量描述。 */
    private static String describeDispatcherView(Object o, Resources res) {
        if (o == null) return "null";
        if (o instanceof View) {
            View v = (View) o;
            return simpleName(v) + "{" + resNameSafe(res, v.getId())
                    + " vis=" + visName(v.getVisibility())
                    + " rect=[" + v.getLeft() + "," + v.getTop() + "," + v.getRight() + "," + v.getBottom() + "]}";
        }
        return describe(o);
    }

    private static String simpleName(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    private static String visName(int vis) {
        switch (vis) {
            case View.VISIBLE: return "VISIBLE";
            case View.INVISIBLE: return "INVISIBLE";
            case View.GONE: return "GONE";
            default: return String.valueOf(vis);
        }
    }

    private static String describe(Object o) {
        if (o == null) return "null";
        if (o instanceof List) return "List(size=" + ((List<?>) o).size() + ")";
        // SparseArray：完整列出 key=value
        if (o instanceof android.util.SparseArray) {
            android.util.SparseArray<?> sa = (android.util.SparseArray<?>) o;
            StringBuilder sb = new StringBuilder("SparseArray(size=" + sa.size() + "){");
            for (int i = 0; i < sa.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("0x").append(Integer.toHexString(sa.keyAt(i)))
                        .append("=").append(shortName(sa.valueAt(i)));
            }
            return sb.append("}").toString();
        }
        String s = String.valueOf(o);
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private static String shortName(Object o) {
        if (o == null) return "null";
        try {
            String cn = o.getClass().getSimpleName();
            Object id = SystemUiReflection.getFieldQuiet(o, "mId");
            Object views = SystemUiReflection.getFieldQuiet(o, "mViews");
            int n = views instanceof List ? ((List<?>) views).size() : -1;
            return cn + "@" + Integer.toHexString(System.identityHashCode(o))
                    + "(mId=" + id + ",views=" + n + ")";
        } catch (Throwable t) {
            return o.getClass().getSimpleName();
        }
    }
}