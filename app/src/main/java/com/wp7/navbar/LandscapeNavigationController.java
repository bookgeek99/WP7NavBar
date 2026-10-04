package com.wp7.navbar;

import android.view.View;

import de.robv.android.xposed.XposedBridge;

/**
 * 横屏导航控制器（方案第八节）。
 *
 * 横屏最终目标（第一阶段）：Back + Home + Recent，其中 Recent 完全由 SystemUI 原生管理。
 *
 * 本 controller 极简，只允许：
 *  - Back 图标替换
 *  - Home 图标替换
 * （这两者实际由 Wp7IconController 在 setImageDrawable hook 统一处理，这里只做日志确认）
 *
 * 明确不做（方案第八/十一/十八节）：
 *  - 不创建 Search
 *  - 不修改 Recent（drawable / visibility / click / dispatcher / layout / margin 全部不动）
 *  - 不修改横屏 ime_switcher（保持系统原生）
 *  - 不注入、不改变横屏 layout XML
 */
public final class LandscapeNavigationController {

    private static final String TAG = "WP7NavBar";

    private final View navBarView;

    LandscapeNavigationController(View navBarView) {
        this.navBarView = navBarView;
    }

    /** 横屏应用：仅确认 Back/Home 图标替换由统一 hook 处理，Recent/ime_switcher 完全不动。 */
    void apply() {
        XposedBridge.log(TAG + ": landscape apply — back/home icon only, recent untouched");
        // Back/Home 图标替换在 KeyButtonView.setImageDrawable hook 中统一执行（Wp7IconController.applyIfWp7），
        // 该 hook 只改 drawable 不碰行为，因此横屏 Recent 的可见性/点击/布局完全由 SystemUI 原生管理。
        // 这里不需要也不允许对横屏视图做任何额外修改。
    }

    @SuppressWarnings("unused")
    View getNavBarView() { return navBarView; }
}