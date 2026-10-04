package com.wp7.navbar;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import de.robv.android.xposed.XposedBridge;

/**
 * WP7 图标控制器（方案第二十五节）。
 *
 * 唯一职责：把 SystemUI 导航栏的 Back / Home 按钮图标替换成 WP7 风格。
 * 只改 drawable 的 childState，不改点击、不改可见性、不改布局 —— 完全保留系统原生行为。
 *
 * 识别方式：KeyButtonView 的私有字段 mCode（BACK=4 / HOME=3），失败时回退 contentDescription。
 * 横竖屏都执行，这是唯一在两个 orientation 下都允许的业务逻辑（方案第二/八节）。
 *
 * 注意：本类是纯逻辑，不含任何 static View 状态；每次调用基于传入的 iv 即时处理。
 */
public final class Wp7IconController {

    private static final String TAG = "WP7NavBar";

    // KeyEvent 键码（与 android.view.KeyEvent 一致，避免依赖 framework 常量裁剪）
    static final int KEYCODE_BACK = 4;
    static final int KEYCODE_HOME = 3;
    static final int KEYCODE_APP_SWITCH = 187; // RECENTS

    // WP7 图标颜色（白色系；跟随系统缩放/暗度叠加由 KeyButtonDrawable 处理）
    static final int ICON_COLOR = Color.WHITE;

    private Wp7IconController() {}

    /**
     * 判断 KeyButtonView 是否为 back/home，若是则替换其图标内部 childState 为 WP7 图标。
     * 不是 back/home 时直接返回（recents 保持原样，符合方案第十八节"Recent 不 Hook"）。
     */
    public static void applyIfWp7(ImageView iv) {
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
            // KeyButtonDrawable 有 mState 字段（ShadowDrawableState）；替换其 mChildState
            Object stateField = SystemUiReflection.getField(d, "mState");
            if (stateField != null) {
                SystemUiReflection.setField(stateField, "mChildState", childState);
                XposedBridge.log(TAG + ": replaced " + typeName(type) + " icon");
            } else {
                XposedBridge.log(TAG + ": " + typeName(type) + " no mState field");
            }
            iv.invalidate();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": replace " + typeName(type) + " err: " + t);
        }
    }

    /** 为指定类型创建 WP7 图标的 ConstantState（BACK / HOME）。 */
    static Object createWp7ConstantState(int type) {
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

    /** 为指定字符串类型创建 WP7 图标的 ConstantState（供 SearchButtonController 复用）。 */
    static Object createWp7ConstantState(String type) {
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
     * 判断 ImageView 是 back 还是 home；返回 0 表示都不是。
     * 优先 mCode 字段（KeyButtonView 私有），失败回退 contentDescription。
     */
    static int resolveButtonType(ImageView iv) {
        // 优先用 mCode 字段判断（KeyButtonView 私有字段）
        try {
            Object code = SystemUiReflection.getFieldQuiet(iv, "mCode");
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

    /** 判断一个 View 是否 recent（任务键）。综合 id / mCode / contentDescription。仅用于识别，不修改。 */
    static boolean isRecentView(android.view.View v) {
        try {
            if (v.getId() == SystemUiIds.ID_RECENT_BUTTON) return true;
            Object code = SystemUiReflection.getFieldQuiet(v, "mCode");
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

    /** 在指定容器内递归查找 recent 键（不含 exclude 自身）。仅用于识别，不修改。 */
    static android.view.View findRecentRecursive(android.view.ViewGroup vg, android.view.View exclude) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            android.view.View c = vg.getChildAt(i);
            if (c == exclude) continue;
            if (isRecentView(c)) return c;
            if (c instanceof android.view.ViewGroup) {
                android.view.View r = findRecentRecursive((android.view.ViewGroup) c, exclude);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String typeName(int type) {
        return type == KEYCODE_BACK ? "BACK" : "HOME";
    }
}