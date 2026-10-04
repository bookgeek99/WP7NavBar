package com.wp7.navbar;

import android.content.res.Configuration;
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
 * 本 controller 在竖屏重建/旋转回来时执行一次 Search 配置（标记 + 行为 + margin）。
 * 用 OnPreDrawListener 持续监听：旋转/重建后新的 ime_switcher 出现时自动重新标记。
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
            // post 到下一帧：确保 layout 完成、X 坐标有效，再标记右段搜索键
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

    /** 竖屏应用：标记右段搜索键 + 读取参照颜色 + 配置行为。 */
    void apply() {
        try {
            // 1. 读取 back/home 参照颜色（供 Search 图标对齐）
            SearchButtonController.collectRefColor(navBarView);
            // 2. 标记并配置右段搜索键（ime_switcher）
            markAndSetupSearchButtons(navBarView);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": portrait apply err: " + t);
        }
    }

    /** 注册 OnPreDrawListener 持续监听：每次绘制前检查，竖屏且未正确标记时标记右段搜索键。 */
    private void markAndSetupSearchButtons(final View root) {
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                markNowTry(root);
                return true;
            }
        });
    }

    /** 尝试标记右段搜索键；返回 true 表示本次状态稳定（横屏或已正确标记）。 */
    private boolean markNowTry(View root) {
        // 横屏：不标记搜索键（右段 Search 仅竖屏）
        if (root.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return true;
        }
        List<ImageView> candidates = new ArrayList<>();
        collectImeSwitchers(root, candidates);
        if (candidates.isEmpty()) return false;
        // 清理死引用（旋转后旧 View 被 GC/重建），仅保留当前真实候选
        SearchButtonController.markedViews.retainAll(candidates);
        // 找 X 坐标最大且已 layout（X>0）的 ime_switcher —— 竖屏右段搜索键在屏幕最右。
        // 不依赖 View 层级，旋转后 OnPreDraw 在 layout 完成时触发，X 一定有效。
        ImageView rightmost = null;
        int rightX = -1;
        for (ImageView iv : candidates) {
            int[] loc = new int[2];
            iv.getLocationOnScreen(loc);
            if (loc[0] > rightX) { rightX = loc[0]; rightmost = iv; }
        }
        if (rightmost == null || rightX <= 0) {
            return false; // 还没 layout，下次绘制再试
        }
        // 始终确保配置正确：旋转/重建后系统可能重置了图标或可见性（View 实例被复用但 drawable 被覆盖）。
        // setupSearchButton 幂等，代价可控；仅当标记集合变化或需要时重设。
        if (SearchButtonController.markedViews.size() == 1
                && SearchButtonController.markedViews.contains(rightmost)
                && rightmost.getVisibility() == View.VISIBLE
                && rightmost.getDrawable() != null) {
            return true; // 已正确且可见，无需重复配置
        }
        SearchButtonController.markedViews.clear();
        SearchButtonController.markedViews.add(rightmost);
        SearchButtonController.setupSearchButton(rightmost);
        XposedBridge.log(TAG + ": portrait search configured x=" + rightX);
        return true;
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