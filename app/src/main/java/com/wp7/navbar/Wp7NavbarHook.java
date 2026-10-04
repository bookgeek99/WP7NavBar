package com.wp7.navbar;

import android.view.View;
import android.widget.ImageView;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * WP7 NavBar —— Xposed Hook 纯入口（方案第五节）。
 *
 * 职责 ONLY：
 *  1. 确认作用域是 SystemUI
 *  2. 挂载 hook（getDefaultLayout / inflateLayout / setImageDrawable / 生命周期）
 *  3. 为每个 NavigationBarView 创建/获取 NavigationBarController
 *  4. 把生命周期事件（onAttachedToWindow / onConfigurationChanged）交给 Controller
 *
 * 不在此：修改 Search / Recent / visibility / margin / drawable / 点击 / IME。
 * 业务逻辑全部分散到各 Controller 与 Wp7IconController。
 *
 * 相比旧版删除的 hook（方案第九/十/二十九节）：
 *  - 全局 View.setVisibility hook（×2）：曾用于维持 Search 常显 / 压左段输入法键，污染所有 View，已删
 *  - 全局 View.setOnClickListener / setOnLongClickListener hook：曾用于"保护"搜索监听器，已删
 *  - ButtonDispatcher.setVisibility / addView hook：曾逐 View 纠正可见性，已删
 *  - ContextualButtonGroup.setButtonVisibility hook：曾强制 menu_ime 常显，已删
 *  - NavigationBar.setImeWindowStatus / onImeSwitcherClick hook：曾强制 ime_switcher 显隐/弹输入法菜单，已删
 *  - 大量 postDelayed(800ms)：改为生命周期 onAttached / onConfigurationChanged 触发（方案第十五节）
 */
public class Wp7NavbarHook implements IXposedHookLoadPackage {

    private static final String TAG = "WP7NavBar";
    private static final String PACKAGE_SYSTEMUI = "com.android.systemui";

    /** 每个 NavigationBarView 实例对应一个 Controller（方案第六节/第十四节，避免 static 持有 View）。 */
    private final Map<View, NavigationBarController> controllers = new WeakHashMap<>();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(PACKAGE_SYSTEMUI)) {
            return;
        }
        XposedBridge.log(TAG + ": SystemUI loaded. Setting up hooks (refactored).");

        hookKeyButtonSetImageDrawable(lpparam);
        hookGetDefaultLayout(lpparam);
        hookNavigationBarViewLifecycle(lpparam);
    }

    // =====================================================================
    // Hook 1: KeyButtonView.setImageDrawable —— Back/Home 图标 + 竖屏 Search 图标
    // =====================================================================

    private void hookKeyButtonSetImageDrawable(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> keyBtnCls = SystemUiReflection.findClass(
                    "com.android.systemui.navigationbar.views.buttons.KeyButtonView", lpparam.classLoader);
            if (keyBtnCls == null) return;

            XposedBridge.hookAllMethods(keyBtnCls, "setImageDrawable", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // 竖屏 Search：仅右段搜索键在竖屏时替换图标，避免首帧闪现错误图标
                    SearchButtonController.onSetImageDrawableBefore(param);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof ImageView) {
                            // Back/Home 图标替换（横竖屏共用，只改 drawable 不碰行为/可见性）
                            Wp7IconController.applyIfWp7((ImageView) param.thisObject);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": setImageDrawable after err: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked KeyButtonView.setImageDrawable");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookKeyButtonSetImageDrawable err: " + t);
        }
    }

    // =====================================================================
    // Hook 2: NavigationBarInflaterView.getDefaultLayout —— 竖屏注入右段 Search
    // =====================================================================

    private void hookGetDefaultLayout(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> inflaterCls = SystemUiReflection.findClass(
                    "com.android.systemui.navigationbar.views.NavigationBarInflaterView", lpparam.classLoader);
            if (inflaterCls == null) return;

            XposedBridge.hookAllMethods(inflaterCls, "getDefaultLayout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 竖屏注入右段 ime_switcher(搜索)+recent；横屏由 SearchButtonController 内部判定跳过
                    SearchButtonController.onGetDefaultLayout(param);
                }
            });
            XposedBridge.log(TAG + ": hooked getDefaultLayout");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookGetDefaultLayout err: " + t);
        }
    }

    // =====================================================================
    // Hook 3: NavigationBarView 生命周期 —— orientation 状态切换
    // =====================================================================

    private void hookNavigationBarViewLifecycle(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> navBarViewCls = SystemUiReflection.findClass(
                    "com.android.systemui.navigationbar.views.NavigationBarView", lpparam.classLoader);
            if (navBarViewCls == null) return;

            // onAttachedToWindow：导航栏 View 挂载（含重建 / 隐藏再显示）
            XposedBridge.hookAllMethods(navBarViewCls, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View navBarView = (View) param.thisObject;
                    NavigationBarController controller = getOrCreateController(navBarView);
                    controller.onAttached();
                }
            });

            // onConfigurationChanged：屏幕旋转等配置变化（orientation 唯一来源，方案第三节）
            XposedBridge.hookAllMethods(navBarViewCls, "onConfigurationChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View navBarView = (View) param.thisObject;
                    NavigationBarController controller = getOrCreateController(navBarView);
                    Object cfg = param.args != null && param.args.length > 0 ? param.args[0] : null;
                    if (cfg instanceof android.content.res.Configuration) {
                        controller.onConfigurationChanged((android.content.res.Configuration) cfg);
                    }
                }
            });

            XposedBridge.log(TAG + ": hooked NavigationBarView lifecycle (onAttachedToWindow/onConfigurationChanged)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookNavigationBarViewLifecycle err: " + t);
        }
    }

    // =====================================================================
    // Controller 管理（每实例一个，WeakHashMap 允许 GC，方案第十四节）
    // =====================================================================

    private NavigationBarController getOrCreateController(View navBarView) {
        NavigationBarController controller = controllers.get(navBarView);
        if (controller == null) {
            controller = new NavigationBarController(navBarView);
            controllers.put(navBarView, controller);
            XposedBridge.log(TAG + ": NavigationBarController created for " + navBarView);
        }
        return controller;
    }
}