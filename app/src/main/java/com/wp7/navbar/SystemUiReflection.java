package com.wp7.navbar;

import android.content.Context;
import android.view.View;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * SystemUI 反射工具集中类。
 *
 * 按方案第二十四节：所有反射调用统一走这里，失败时记录日志（哪个字段/方法/原因），
 * 不允许静默吞掉异常。提供 getField / setField / call / findClass 的便捷封装。
 */
public final class SystemUiReflection {

    private static final String TAG = "WP7NavBar";

    private SystemUiReflection() {}

    /** 读取字段；失败返回 null 并记录日志。 */
    public static Object getField(Object obj, String name) {
        if (obj == null) return null;
        try {
            return XposedHelpers.getObjectField(obj, name);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getField '" + name + "' on "
                    + obj.getClass().getSimpleName() + " failed: " + t);
            return null;
        }
    }

    /** 读取字段；失败返回 null 但不记录日志（用于探测可选字段）。 */
    public static Object getFieldQuiet(Object obj, String name) {
        if (obj == null) return null;
        try {
            return XposedHelpers.getObjectField(obj, name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写字段；失败记录日志。 */
    public static boolean setField(Object obj, String name, Object value) {
        if (obj == null) return false;
        try {
            XposedHelpers.setObjectField(obj, name, value);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setField '" + name + "' on "
                    + obj.getClass().getSimpleName() + " failed: " + t);
            return false;
        }
    }

    /** 调用方法；失败返回 null 并记录日志。 */
    public static Object call(Object obj, String methodName, Object... args) {
        if (obj == null) return null;
        try {
            return XposedHelpers.callMethod(obj, methodName, args);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": call '" + methodName + "' on "
                    + obj.getClass().getSimpleName() + " failed: " + t);
            return null;
        }
    }

    /** 查找类；失败记录日志。 */
    public static Class<?> findClass(String name, ClassLoader cl) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": findClass '" + name + "' failed: " + t);
            return null;
        }
    }

    /**
     * 从系统对象（如 NavigationBarInflaterView / NavigationBar）中提取一个可用 Context，
     * 用于跨进程访问模块设置 (ContentProvider) 或获取资源。
     */
    public static Context getContextFromObject(Object obj) {
        if (obj == null) return null;
        // 优先 mContext 字段
        try {
            Object c = getFieldQuiet(obj, "mContext");
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) { }
        // 若对象是 View 本身，直接用 getContext()
        if (obj instanceof View) {
            try {
                Context c = ((View) obj).getContext();
                if (c != null) return c;
            } catch (Throwable ignored) { }
        }
        // 尝试从 mView / view 字段取 View 再拿 context
        String[] viewFields = { "mView", "view", "mParentView" };
        for (String f : viewFields) {
            try {
                Object v = getFieldQuiet(obj, f);
                if (v instanceof View) {
                    Context c = ((View) v).getContext();
                    if (c != null) return c;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** dp 转 px（用系统 resources）。 */
    public static int dp(float dp) {
        return (int) (dp * android.content.res.Resources.getSystem().getDisplayMetrics().density);
    }
}
