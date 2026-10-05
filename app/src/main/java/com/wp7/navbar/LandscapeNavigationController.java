package com.wp7.navbar;

import android.view.View;
import android.widget.ImageView;

import de.robv.android.xposed.XposedBridge;

/**
 * 横屏导航控制器（第一阶段基线）。
 *
 * 横屏目标：Back + Home + Recent，全部由 SystemUI 原生管理。
 *
 * 本 controller 只做一件事：隐藏横屏所有 ime_switcher（防御层）。
 *  - Back / Home 图标替换由 Wp7IconController 在 setImageDrawable hook 统一处理（横竖屏共用）
 *  - 布局串里的 ime_switcher 已在 inflateLayout before 阶段剥离（Wp7NavbarHook.removeImeSwitchers）
 *  - 此处 hideAllImeSwitchers 是防御层：确保即使布局串清理有遗漏，横屏也不出现输入法键
 *  - 不创建 Search、不修改 Recent、不碰 Recent 的 click/dispatcher/layout/margin
 */
public final class LandscapeNavigationController {
    private static final String TAG = "WP7NavBar";
    private final View navBarView;

    LandscapeNavigationController(View navBarView) {
        this.navBarView = navBarView;
    }

    /** 横屏应用：隐藏所有 ime_switcher（防御层），其余不动。 */
    void apply() {
        try {
            hideAllImeSwitchers(navBarView);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": landscape hideIme err: " + t);
        }
    }

    private void hideAllImeSwitchers(View v) {
        try {
            if (v instanceof ImageView && v.getId() == SystemUiIds.ID_IME_SWITCHER) {
                if (v.getVisibility() != View.GONE) {
                    v.setVisibility(View.GONE);
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    hideAllImeSwitchers(vg.getChildAt(i));
                }
            }
        } catch (Throwable ignored) { }
    }
}