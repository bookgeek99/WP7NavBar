package com.wp7.navbar;

import android.view.View;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * 第二阶段 2B：ContextualButton / ContextualButtonGroup 只读探测。
 *
 * 【严格只读】不修改任何字段 / View / 点击 / 可见性。
 * 输出前缀 [WP7NavBar][CONTEXTUAL_PROBE]。
 *
 * 目标：搞清 HyperOS 3 的 ContextualButton 生命周期与 ContextualButtonGroup 机制，
 * 判断 accessibility_button 能否复用为横屏 Search，或需新建独立 ContextualButton。
 * 为 2C 提供事实依据。
 */
public final class ContextualProbe {

    private static final String TAG = "WP7NavBar";
    private static final String P = "[CONTEXTUAL_PROBE] ";
    private static final boolean ENABLED = true;

    private ContextualProbe() {}

    private static void log(String msg) {
        if (ENABLED) XposedBridge.log(TAG + ": " + P + msg);
    }

    // ==================================================================
    // 主入口：从 NavigationBarInflaterView 的 mButtonDispatchers 里分析
    // ==================================================================

    /** 对 mButtonDispatchers 中的每个 entry 做 Contextual 深度探测。 */
    public static void probeDispatchers(View inflaterView, Object sparseArray) {
        if (!ENABLED || sparseArray == null) return;
        log("========== CONTEXTUAL DISPATCHERS PROBE ==========");
        try {
            if (!(sparseArray instanceof android.util.SparseArray)) return;
            android.util.SparseArray<?> sa = (android.util.SparseArray<?>) sparseArray;
            for (int k = 0; k < sa.size(); k++) {
                int key = sa.keyAt(k);
                Object d = sa.valueAt(k);
                if (d == null) continue;
                String cn = d.getClass().getSimpleName();
                // 只深挖 Contextual 相关
                if (cn.contains("Contextual")) {
                    log("----- ENTRY 0x" + Integer.toHexString(key) + " res="
                            + resNameSafe(inflaterView.getResources(), key) + " class=" + cn + " -----");
                    dumpObjectFull("  ", d);
                    dumpClassHierarchy("  ", d.getClass());
                    dumpDeclaredMethods("  ", d.getClass());
                }
            }
        } catch (Throwable t) {
            log("probeDispatchers err: " + t);
        }
        log("========== END CONTEXTUAL DISPATCHERS PROBE ==========");
    }

    /** 专门探测 NavigationBarView.mContextualButtonGroup。 */
    public static void probeContextualButtonGroup(View navBarView) {
        if (!ENABLED || navBarView == null) return;
        log("========== CONTEXTUAL_BUTTON_GROUP PROBE ==========");
        try {
            Object group = SystemUiReflection.getFieldQuiet(navBarView, "mContextualButtonGroup");
            if (group == null) {
                log("mContextualButtonGroup = NOT_FOUND");
                return;
            }
            log("group class = " + group.getClass().getName());
            dumpClassHierarchy("  ", group.getClass());
            dumpAllFields("  ", group, true);
            // 找集合字段里的 ContextualButton
            dumpCollectionsInObject("  ", group);
            dumpDeclaredMethods("  ", group.getClass());
        } catch (Throwable t) {
            log("probeContextualButtonGroup err: " + t);
        }
        log("========== END CONTEXTUAL_BUTTON_GROUP PROBE ==========");
    }

    // ==================================================================
    // 通用反射打印
    // ==================================================================

    /** 打印对象全部 declared fields（含继承链），非 null 高亮。 */
    private static void dumpObjectFull(String ind, Object o) {
        if (o == null) return;
        try {
            dumpAllFields(ind, o, false);
        } catch (Throwable t) {
            log(ind + "dumpObjectFull err: " + t);
        }
    }

    private static void dumpAllFields(String ind, Object o, boolean all) {
        Class<?> cls = o.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    // 跳过 static / 合成字段（除非 all）
                    if (!all && (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.isSynthetic())) continue;
                    f.setAccessible(true);
                    Object val = f.get(o);
                    if (!all && val == null) {
                        log(ind + "FIELD " + f.getName() + " = null");
                        continue;
                    }
                    log(ind + "FIELD " + cls.getSimpleName() + "." + f.getName()
                            + " type=" + f.getType().getSimpleName()
                            + " value=" + describeFull(val));
                } catch (Throwable t) {
                    log(ind + "FIELD " + f.getName() + " ERR " + t);
                }
            }
            cls = cls.getSuperclass();
        }
    }

    /** 找对象里的集合字段（List/Map/SparseArray/Set），列出其中元素。 */
    private static void dumpCollectionsInObject(String ind, Object o) {
        Class<?> cls = o.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(o);
                    if (val instanceof List) {
                        List<?> list = (List<?>) val;
                        log(ind + "LIST " + f.getName() + " size=" + list.size());
                        for (int i = 0; i < list.size(); i++) {
                            log(ind + "  [" + i + "]=" + describeFull(list.get(i)));
                        }
                    } else if (val instanceof java.util.Map) {
                        java.util.Map<?, ?> m = (java.util.Map<?, ?>) val;
                        log(ind + "MAP " + f.getName() + " size=" + m.size() + " keys=" + m.keySet());
                        for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                            log(ind + "  key=" + e.getKey() + " val=" + describeFull(e.getValue()));
                        }
                    } else if (val instanceof android.util.SparseArray) {
                        android.util.SparseArray<?> sa = (android.util.SparseArray<?>) val;
                        log(ind + "SPARSEARRAY " + f.getName() + " size=" + sa.size());
                        for (int i = 0; i < sa.size(); i++) {
                            log(ind + "  0x" + Integer.toHexString(sa.keyAt(i)) + "="
                                    + describeFull(sa.valueAt(i)));
                        }
                    } else if (val instanceof java.util.Set) {
                        java.util.Set<?> s = (java.util.Set<?>) val;
                        log(ind + "SET " + f.getName() + " size=" + s.size());
                        for (Object e : s) log(ind + "  =" + describeFull(e));
                    }
                    // 深挖 ButtonData（ContextualButtonGroup 内部类）
                    if (val instanceof List && f.getName().equals("mButtonData")) {
                        List<?> list = (List<?>) val;
                        for (int i = 0; i < list.size(); i++) {
                            Object bd = list.get(i);
                            log(ind + "  ButtonData[" + i + "] class=" + bd.getClass().getName());
                            dumpAllFields(ind + "    ", bd, false);
                        }
                    }
                } catch (Throwable ignored) { }
            }
            cls = cls.getSuperclass();
        }
    }

    /** 打印类继承链。 */
    private static void dumpClassHierarchy(String ind, Class<?> cls) {
        StringBuilder sb = new StringBuilder(ind).append("HIERARCHY: ");
        Class<?> c = cls;
        while (c != null) {
            sb.append(c.getSimpleName());
            c = c.getSuperclass();
            if (c != null) sb.append(" <- ");
        }
        log(sb.toString());
        // 是否继承 ButtonDispatcher
        boolean isDispatcher = false;
        c = cls;
        while (c != null) {
            if (c.getSimpleName().equals("ButtonDispatcher")) { isDispatcher = true; break; }
            c = c.getSuperclass();
        }
        log(ind + "  isButtonDispatcherSubclass = " + isDispatcher);
    }

    /** 打印 declaredMethods（只读，不调用）。 */
    private static void dumpDeclaredMethods(String ind, Class<?> cls) {
        try {
            Method[] ms = cls.getDeclaredMethods();
            log(ind + "DECLARED_METHODS count=" + ms.length);
            for (Method m : ms) {
                if (m.isSynthetic()) continue;
                String name = m.getName();
                // 只打印关键方法，避免刷屏
                if (name.contains("isib") || name.contains("own") || name.contains("urrent")
                        || name.contains("ttach") || name.contains("ispatch") || name.contains("etButton")
                        || name.contains("lick") || name.contains("awable") || name.contains("ispatcher")) {
                    StringBuilder ps = new StringBuilder();
                    for (Class<?> pt : m.getParameterTypes()) {
                        if (ps.length() > 0) ps.append(", ");
                        ps.append(pt.getSimpleName());
                    }
                    log(ind + "  " + m.getReturnType().getSimpleName() + " " + name
                            + "(" + ps + ")");
                }
            }
        } catch (Throwable t) {
            log(ind + "dumpDeclaredMethods err: " + t);
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static String resNameSafe(android.content.res.Resources res, int id) {
        try { return res.getResourceEntryName(id); }
        catch (Throwable t) { return "0x" + Integer.toHexString(id); }
    }

    private static String describeFull(Object o) {
        if (o == null) return "null";
        if (o instanceof List) return "List(size=" + ((List<?>) o).size() + ")";
        if (o instanceof android.util.SparseArray) return "SparseArray(size=" + ((android.util.SparseArray<?>) o).size() + ")";
        if (o instanceof java.util.Map) return "Map(size=" + ((java.util.Map<?, ?>) o).size() + ")";
        // View 特殊描述
        if (o instanceof View) {
            View v = (View) o;
            return v.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(v))
                    + "{id=" + resNameSafe(v.getResources(), v.getId())
                    + " vis=" + v.getVisibility()
                    + " rect=[" + v.getLeft() + "," + v.getTop() + "," + v.getRight() + "," + v.getBottom() + "]}";
        }
        String s = String.valueOf(o);
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}