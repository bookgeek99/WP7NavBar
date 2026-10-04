package com.wp7.navbar;

import android.view.View;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * 竖屏导航控制器（方案第七节）。
 *
 * 竖屏最终目标：Back + Home + Search。
 *  - Back / Home 图标替换：由 Wp7IconController 负责（在 setImageDrawable hook 中统一处理，横竖屏共用）
 *  - Search：由 SearchButtonController 负责（ime_switcher 伪装）
 *
 * 本 controller 在竖屏重建/旋转回来时执行一次 Search 配置（标记 + 行为 + margin），
 * 不再依赖全局 View.setVisibility hook 维持 Search 常显（方案第九/十节）。
 *
 * 不碰 Recent；不修改横屏布局。
 */
public final class PortraitNavigationController {

    private static final String TAG = "WP7NavBar";

    private final View navBarView;

    PortraitNavigationController(View navBarView) {
        this.navBarView = navBarView;
    }

    /** 竖屏应用：标记右段搜索键 + 读取参照颜色 + 配置行为。 */
    void apply() {
        XposedBridge.log(TAG + ": portrait apply");
        try {
            // 1. 读取 back/home 参照颜色（供 Search 图标对齐）
            SearchButtonController.collectRefColor(navBarView);
            // 2. 标记并配置右段搜索键（ime_switcher）
            markAndSetupSearchButtons(navBarView);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": portrait apply err: " + t);
        }
    }

    /** 深度遍历导航栏，找到右段（recent 左侧最近）的 ime_switcher 并配置为搜索键。 */
    private void markAndSetupSearchButtons(View root) {
        List<ImageView> candidates = new ArrayList<>();
        collectImeSwitchers(root, candidates);
        for (ImageView iv : candidates) {
            try {
                if (SearchButtonController.isSearchButtonStructuralStrict(iv)) {
                    SearchButtonController.markedViews.add(iv);
                    SearchButtonController.setupSearchButton(iv);
                    XposedBridge.log(TAG + ": portrait search marked+configured");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": markAndSetupSearchButtons err: " + t);
            }
        }
    }

    private void collectImeSwitchers(View v, List<ImageView> out) {
        if (v instanceof ImageView && v.getId() == SystemUiIds.ID_IME_SWITCHER) {
            out.add((ImageView) v);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectImeSwitchers(vg.getChildAt(i), out);
            }
        }
    }
}