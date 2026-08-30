package com.wp7.navbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 原生 Material 风格 UI 工具类。
 *
 * 不引入任何第三方库，仅用 Android 原生 API 拼装出接近 Material Design 的组件：
 *  - 圆角卡片（白底 + elevation 阴影）
 *  - 波纹点击效果（RippleDrawable）
 *  - Material 风格 switch（android.widget.Switch + colorAccent）
 */
final class Ui {

    private Ui() { }

    /** 圆角纯色 drawable。cornerDp 为圆角（dp），color 为填充色。 */
    static Drawable rounded(int cornerDp, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(cornerDp)); // 这里用 px，见下方 dp()
        d.setColor(color);
        return d;
    }

    /** 圆角 + 波纹点击效果（Material 4.0 风格）。 */
    static Drawable roundedRipple(int cornerDp, int solidColor, int rippleColor) {
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.RECTANGLE);
        content.setCornerRadius(dp(cornerDp));
        content.setColor(solidColor);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    /** 创建一个 Material 风格卡片容器（白底圆角 + elevation 阴影）。 */
    static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(gradientRounded(16, Color.WHITE));
        card.setElevation(dp(2)); // 阴影
        // 内边距
        card.setPadding(dp(18), dp(6), dp(18), dp(6));
        return card;
    }

    /** 纯色圆角 GradientDrawable（白底卡片用）。 */
    static GradientDrawable gradientRounded(int cornerDp, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(cornerDp));
        d.setColor(color);
        return d;
    }

    /** 标题文本（section 标题，灰色小字）。 */
    static TextView sectionTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#79747E"));
        tv.setGravity(Gravity.LEFT);
        tv.setLetterSpacing(0.08f);
        if (tv.getTypeface() != null) {
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return tv;
    }

    /** 通用 dp→px 转换。 */
    static int dp(float dp) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, dp,
                android.content.res.Resources.getSystem().getDisplayMetrics());
    }
}