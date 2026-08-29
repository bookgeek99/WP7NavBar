package com.wp7.navbar;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.graphics.Color;
import android.view.Gravity;

/**
 * 简单的模块入口 Activity
 * 用于在 LSPosed 中确认模块已安装/激活。
 * 核心逻辑在 Wp7NavbarHook 中。
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("WP7 NavBar");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText(
                "HyperOS 3 三键导航栏 WP7 风格图标\n\n\n" +
                "模块已安装 ✅\n" +
                "请到 LSPosed 中激活:\n" +
                "作用域 → com.android.systemui\n\n" +
                "激活后重启 SystemUI 生效:\n" +
                "adb shell pkill -f com.android.systemui");
        desc.setTextSize(15);
        desc.setTextColor(Color.DKGRAY);
        desc.setGravity(Gravity.CENTER);
        root.addView(desc);

        setContentView(root);
    }
}
