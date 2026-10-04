package com.wp7.navbar;

import android.content.res.Configuration;
import android.view.View;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XposedBridge;

/**
 * 导航栏控制器（方案第六节）。
 *
 * 每个 NavigationBarView 实例对应一个 Controller，负责：
 *  - 保存当前 navBarView（用 WeakReference，避免 static 持有具体 View）
 *  - 判断当前 orientation（唯一来源：Configuration.orientation）
 *  - 切换 Portrait / Landscape controller
 *  - 防止重复初始化（initialized 属于本实例）
 *
 * 方案第三/六节核心：orientation 唯一来源是 Configuration.orientation，
 * 不用 LinearLayout.getOrientation() 判断整个导航栏状态。
 */
public final class NavigationBarController {

    private static final String TAG = "WP7NavBar";

    /** 导航栏方向状态（方案第二十节：整个项目只有两个主要状态）。 */
    enum NavBarOrientation { PORTRAIT, LANDSCAPE }

    /** 全局当前方向（供 getDefaultLayout 等无 View 上下文处使用，避免 Configuration 更新滞后）。
     *  由 onAttached / onConfigurationChanged 更新，时序与 SystemUI 配置回调一致。 */
    public static volatile NavBarOrientation sCurrentOrientation = NavBarOrientation.PORTRAIT;

    private final WeakReference<View> navBarViewRef;
    private NavBarOrientation orientation;
    private PortraitNavigationController portraitController;
    private LandscapeNavigationController landscapeController;
    private boolean initialized = false;

    public NavigationBarController(View navBarView) {
        this.navBarViewRef = new WeakReference<>(navBarView);
        this.orientation = detectOrientation(navBarView);
    }

    /** 唯一入口：从 Configuration 读取方向。 */
    private static NavBarOrientation detectOrientation(View v) {
        try {
            int o = v.getResources().getConfiguration().orientation;
            return o == Configuration.ORIENTATION_LANDSCAPE
                    ? NavBarOrientation.LANDSCAPE : NavBarOrientation.PORTRAIT;
        } catch (Throwable t) {
            return NavBarOrientation.PORTRAIT;
        }
    }

    /** 导航栏挂载时调用（onAttachedToWindow）。 */
    public void onAttached() {
        XposedBridge.log(TAG + ": onAttached orient=" + orientation);
        sCurrentOrientation = orientation;
        applyCurrent();
    }

    /** 配置变化时调用（onConfigurationChanged）。方向未变则不重建（方案第六节）。 */
    public void onConfigurationChanged(Configuration newConfig) {
        NavBarOrientation newOrientation =
                newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
                        ? NavBarOrientation.LANDSCAPE : NavBarOrientation.PORTRAIT;
        sCurrentOrientation = newOrientation;
        if (newOrientation == orientation) {
            XposedBridge.log(TAG + ": onConfigurationChanged, orientation unchanged (" + orientation + "), skip rebuild");
            return;
        }
        XposedBridge.log(TAG + ": orientation changed " + orientation + " -> " + newOrientation);
        this.orientation = newOrientation;
        applyCurrent();
    }

    /** 应用当前方向的 controller。 */
    private void applyCurrent() {
        View navBarView = navBarViewRef.get();
        if (navBarView == null) {
            XposedBridge.log(TAG + ": navBarView GC'd, controller stale");
            return;
        }
        if (orientation == NavBarOrientation.PORTRAIT) {
            if (portraitController == null) {
                portraitController = new PortraitNavigationController(navBarView);
                XposedBridge.log(TAG + ": portrait controller created");
            }
            portraitController.apply();
        } else {
            if (landscapeController == null) {
                landscapeController = new LandscapeNavigationController(navBarView);
                XposedBridge.log(TAG + ": landscape controller created");
            }
            landscapeController.apply();
        }
    }

    /** 防重复初始化（方案第二十三节：属于实例，不用全局 static）。 */
    public boolean isInitialized() { return initialized; }
    public void markInitialized() { this.initialized = true; }

    public NavBarOrientation getOrientation() { return orientation; }
    public View getNavBarView() { return navBarViewRef.get(); }
}