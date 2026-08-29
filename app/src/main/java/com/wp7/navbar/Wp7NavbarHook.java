package com.wp7.navbar;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * WP7 NavBar 核心 Hook
 *
 * 目标：把 HyperOS 3 三键导航栏的【返回】【主页】图标替换为 WP7 风格，
 *      【最近】保持原样。
 *
 * 关键观察（来自反编译 + 真机日志）：
 *  - NavigationBarInflaterView.inflateButtons 是创建按键的入口，但在它执行时
 *    KeyButtonView 还没被设置 drawable（日志显示 getDrawable()==null）。
 *  - 真正给按键设置图标的是 KeyButtonView.setImageDrawable(Drawable)。
 *  - HyperOS 的 KeyButtonView.setImageDrawable 会把传入 drawable 强转成
 *    KeyButtonDrawable，若直接 setImageDrawable(自定义Drawable) 会 ClassCastException。
 *
 * 因此方案：
 *  - Hook KeyButtonView.setImageDrawable 的 afterHookedMethod —— 此时系统已把
 *    KeyButtonDrawable 设置好（getDrawable() 非空）。
 *  - 通过 KeyButtonView 的 mCode(KEYCODE_BACK=4 / KEYCODE_HOME=3) 判断类型。
 *  - 反射替换 KeyButtonDrawable 的 mState.mChildState 为 WP7 图标的 ConstantState，
 *    这样在保留系统暗色/颜色/尺寸/ripple 逻辑的前提下，只替换绘制内容。
 */
public class Wp7NavbarHook implements IXposedHookLoadPackage {

    private static final String TAG = "WP7NavBar";
    private static final String PACKAGE_SYSTEMUI = "com.android.systemui";

    // KeyEvent 常量
    private static final int KEYCODE_HOME = 3;
    private static final int KEYCODE_BACK = 4;

    // 图标固定色（导航栏白色系；跟随系统缩放/暗度叠加由 KeyButtonDrawable 处理）
    private static final int ICON_COLOR = Color.WHITE;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(PACKAGE_SYSTEMUI)) {
            return;
        }
        XposedBridge.log(TAG + ": SystemUI loaded. Setting up hooks.");

        try {
            // Hook KeyButtonView.setImageDrawable —— 真正设置图标的地方
            Class<?> keyBtnCls = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.views.buttons.KeyButtonView", lpparam.classLoader);
            XposedBridge.hookAllMethods(keyBtnCls, "setImageDrawable", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof ImageView) {
                            replaceIfWp7((ImageView) param.thisObject);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": setImageDrawable after err: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked KeyButtonView.setImageDrawable");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setup err: " + t);
        }
    }

    /** 判断 KeyButtonView 是否为 back/home，若是则替换其图标内部 childState */
    private void replaceIfWp7(ImageView iv) {
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
            // KeyButtonDrawable 有 mState 字段；替换 mState.mChildState
            Object stateField = getField(d, "mState");
            if (stateField != null) {
                setField(stateField, "mChildState", childState);
                XposedBridge.log(TAG + ": replaced " + typeName(type) + " icon");
            } else {
                XposedBridge.log(TAG + ": " + typeName(type) + " no mState field");
            }
            iv.invalidate();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": replace " + typeName(type) + " err: " + t);
        }
    }

    private Object createWp7ConstantState(int type) {
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

    private int resolveButtonType(ImageView iv) {
        // 优先用 mCode 字段判断（KeyButtonView 私有字段）
        try {
            Object code = getField(iv, "mCode");
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

    private String typeName(int type) {
        return type == KEYCODE_BACK ? "BACK" : "HOME";
    }

    private Object getField(Object obj, String name) {
        try {
            return XposedHelpers.getObjectField(obj, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private void setField(Object obj, String name, Object value) {
        try {
            XposedHelpers.setObjectField(obj, name, value);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setField " + name + " err: " + t);
        }
    }
}