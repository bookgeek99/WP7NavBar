package com.wp7.navbar;

import android.view.View;

import de.robv.android.xposed.XposedBridge;

/**
 * 竖屏导航控制器（方案第七节）。
 *
 * 竖屏最终目标：Back + Home + Search + Recent。
 *  - Back / Home 图标替换：由 Wp7IconController 负责（setImageDrawable hook，横竖屏共用）
 *  - Search：由 SearchContextualButtonFactory 负责（独立 ContextualButton，2C 阶段）
 *
 * [2C-退役] 旧 masquerade Search（把右段 ime_switcher 伪装成搜索键）已删除。
 * 本 controller 现在仅负责：
 *  - 收集 back/home 参照颜色（供 Search 图标颜色对齐）
 *
 * 用 post 在布局完成后执行一次；旋转/重建后重新收集参照色。
 */
public final class PortraitNavigationController {

    private static final String TAG = "WP7NavBar";

    private final View navBarView;

    PortraitNavigationController(View navBarView) {
        this.navBarView = navBarView;
    }

    /**
     * 静态入口：供 inflateLayout after hook 在布局刚创建时调用（此时 recent 已就绪）。
     */
    static void applyToRoot(final View root) {
        try {
            root.post(new Runnable() {
                @Override public void run() {
                    PortraitNavigationController c = new PortraitNavigationController(root);
                    c.apply();
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": applyToRoot err: " + t);
        }
    }

    /** 竖屏应用：读取参照颜色（供 Search 图标对齐）。 */
    void apply() {
        try {
            // 读取 back/home 参照颜色（供 Search 图标对齐）
            SearchButtonController.collectRefColor(navBarView);
            // [2C-退役] 不再标记 / 配置 ime_switcher。Search 由独立 ContextualButton 承担。
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": portrait apply err: " + t);
        }
    }
}