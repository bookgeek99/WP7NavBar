package com.wp7.navbar;

import android.view.View;

import de.robv.android.xposed.XposedBridge;

/**
 * 横屏导航控制器（方案第八节）。
 *
 * 横屏最终目标（第一阶段）：Back + Home + Recent，其中 Recent 完全由 SystemUI 原生管理。
 *
 * 本 controller 只允许：
 *  - Back / Home 图标替换（由 Wp7IconController 在 setImageDrawable hook 统一处理）
 *  - 清理任何残留的 ime_switcher（横屏布局不应出现输入法键）
 *
 * 明确不做（方案第八/十一/十八节）：
 *  - 不创建 Search
 *  - 不修改 Recent（drawable / visibility / click / dispatcher / layout / margin 全部不动）
 *  - 不注入、不改变横屏 layout XML
 */
public final class LandscapeNavigationController {

    private static final String TAG = "WP7NavBar";

    private final View navBarView;

    LandscapeNavigationController(View navBarView) {
        this.navBarView = navBarView;
    }

    /** 横屏应用：Back/Home 图标由统一 hook 处理；清理残留 ime_switcher。 */
    void apply() {
        // 横屏布局可能复用了竖屏注入的串（含 ime_switcher），强制把横屏所有 ime_switcher 设 GONE。
        try {
            hideAllImeSwitchers(navBarView);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": landscape hideIme err: " + t);
        }
    }

    private void hideAllImeSwitchers(View v) {
        try {
            if (v instanceof android.widget.ImageView && v.getId() == SystemUiIds.ID_IME_SWITCHER) {
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

    @SuppressWarnings("unused")
    View getNavBarView() { return navBarView; }
}