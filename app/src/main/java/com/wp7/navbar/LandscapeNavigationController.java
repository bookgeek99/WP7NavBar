package com.wp7.navbar;

import android.view.View;

/**
 * 横屏导航控制器。
 *
 * [2C-8] 本类已不再隐藏 ime_switcher —— 横屏现在也要显示输入法切换键（与竖屏一致）。
 *  - Back / Home 图标替换由 Wp7IconController 在 setImageDrawable hook 统一处理（横竖屏共用）
 *  - 导航栏布局串由 SearchButtonController.onGetDefaultLayout 统一注入（横竖屏均注入 ime_switcher）
 *  - ime_switcher 的可见性由 Wp7NavbarHook.updateLeftImeSwitcherVisibility 按 IME 状态统一控制
 *  - 不创建 Search（横屏不注入 Search）、不修改 Recent、不碰其 click/dispatcher/layout/margin
 *
 * 保留此类作为横屏分支的挂载点（no-op），后续如需横屏专属逻辑可在此扩展。
 */
public final class LandscapeNavigationController {
    private static final String TAG = "WP7NavBar";
    private final View navBarView;

    LandscapeNavigationController(View navBarView) {
        this.navBarView = navBarView;
    }

    /** 横屏应用：当前无专属处理（ime_switcher 已改为按 IME 状态显示）。 */
    void apply() {
        // [2C-8] no-op：横屏不再隐藏 ime_switcher。
    }
}