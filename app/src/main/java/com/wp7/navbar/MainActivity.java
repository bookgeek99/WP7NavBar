package com.wp7.navbar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;

/**
 * WP7 NavBar — Material 风格设置界面（无第三方库）。
 *
 * 功能：
 *  - 左侧切换输入法开关（对应 ime_switcher 注入）
 *  - 右侧剪贴板开关（clipboard，待验证）
 *  - 重启 SystemUI 按钮（root）
 *  - 关于页
 *
 * 设置保存到 SharedPreferences，由 Wp7SettingsProvider 跨进程暴露给 SystemUI。
 */
public class MainActivity extends Activity {

    private static final String GITHUB_URL = "https://github.com/bookgeek99/WP7NavBar";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 整屏：灰色背景 + ScrollView
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColorCompat(R.color.bg_screen));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        // --- 顶栏 ---
        root.addView(buildHeader());

        // --- 导航栏功能 section ---
        root.addView(sectionSpacer());
        root.addView(sectionTitle("导航栏功能"));
        root.addView(sectionSpacer(6));

        // 左侧切换输入法
        LinearLayout cardIme = Ui.card(this);
        cardIme.setLayoutParams(cardLp());
        cardIme.addView(settingRow(
                this, "左侧切换输入法",
                "输入法弹出时，导航栏左侧显示切换按钮，点击呼出输入法列表",
                SettingsStore.getImeSwitcher(this), "ime"));
        root.addView(cardIme);
        root.addView(sectionSpacer(10));

            // 任务键左侧搜索键
        LinearLayout cardSearch = Ui.card(this);
        cardSearch.setLayoutParams(cardLp());
        cardSearch.addView(settingRow(
                this, "任务键搜索键",
                "任务键左侧显示放大镜搜索键，单击唤起超级小爱、长按识屏",
                SettingsStore.getSearchButton(this), "search_button"));
        root.addView(cardSearch);

        // --- 操作 section ---
        root.addView(sectionSpacer());
        root.addView(sectionTitle("操作"));
        root.addView(sectionSpacer(6));

        LinearLayout cardAction = Ui.card(this);
        cardAction.setLayoutParams(cardLp());
        cardAction.addView(buildRestartButton());
        root.addView(cardAction);

        // --- 关于 section ---
        root.addView(sectionSpacer());
        root.addView(sectionTitle("关于"));
        root.addView(sectionSpacer(6));

        LinearLayout cardAbout = Ui.card(this);
        cardAbout.setLayoutParams(cardLp());
        cardAbout.addView(buildAbout());
        root.addView(cardAbout);

        // 底部留白
        root.addView(sectionSpacer(28));

        setContentView(scroll);
    }

    /** 顶栏：深蓝底 + 模块名 + WP7 徽标。 */
    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(Ui.dp(24), Ui.dp(28), Ui.dp(24), Ui.dp(28));
        header.setBackgroundColor(getColorCompat(R.color.primary));

        TextView title = new TextView(this);
        title.setText("WP7 NavBar");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText("HyperOS 三键导航栏 · WP7 风格定制");
        sub.setTextSize(14);
        sub.setTextColor(0xCCFFFFFF);
        sub.setPadding(0, Ui.dp(6), 0, 0);
        header.addView(sub);

        return header;
    }

    /**
     * 生成一个设置行（标题 + 副标题 + 右侧 switch）。
     * key 决定写入哪个设置项。
     */
    private View settingRow(Context ctx, String title, String desc, boolean value, final String key) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(14), 0, Ui.dp(14));

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView tTitle = new TextView(ctx);
        tTitle.setText(title);
        tTitle.setTextSize(16);
        tTitle.setTextColor(getColorCompat(R.color.text_primary));
        textCol.addView(tTitle);

        TextView tDesc = new TextView(ctx);
        tDesc.setText(desc);
        tDesc.setTextSize(13);
        tDesc.setTextColor(getColorCompat(R.color.text_secondary));
        tDesc.setPadding(0, Ui.dp(3), 0, 0);
        textCol.addView(tDesc);

        row.addView(textCol);

        Switch sw = new Switch(ctx);
        sw.setChecked(value);
        sw.setPadding(Ui.dp(12), 0, 0, 0);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if ("ime".equals(key)) {
                    SettingsStore.setImeSwitcher(MainActivity.this, isChecked);
                } else if ("search_button".equals(key)) {
                    SettingsStore.setSearchButton(MainActivity.this, isChecked);
                }
                Toast.makeText(MainActivity.this,
                        "已保存，重启 SystemUI 后生效", Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(sw);

        return row;
    }

    /** 重启 SystemUI 按钮（Material 风格，主色 + 波纹）。 */
    private View buildRestartButton() {
        TextView btn = new TextView(this);
        btn.setText("重启 SystemUI");
        btn.setTextSize(15);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, Ui.dp(14), 0, Ui.dp(14));
        btn.setBackground(Ui.roundedRipple(24, getColorCompat(R.color.primary), 0x33FFFFFF));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doRestartSystemUI();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btn.setLayoutParams(lp);
        return btn;
    }

    /** 关于区域：版本 + 说明 + GitHub 链接。 */
    private View buildAbout() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView ver = new TextView(this);
        ver.setText("版本  " + getVersionName());
        ver.setTextSize(14);
        ver.setTextColor(getColorCompat(R.color.text_primary));
        col.addView(ver);

        TextView desc = new TextView(this);
        desc.setText("LSPosed 模块 · 仅作用域 SystemUI\n" +
                "把 HyperOS 三键导航栏图标改为 WP7 风格。");
        desc.setTextSize(13);
        desc.setTextColor(getColorCompat(R.color.text_secondary));
        desc.setPadding(0, Ui.dp(6), 0, 0);
        col.addView(desc);

        TextView github = new TextView(this);
        github.setText("GitHub · 开源仓库 >");
        github.setTextSize(14);
        github.setTextColor(getColorCompat(R.color.primary));
        github.setTypeface(github.getTypeface(), android.graphics.Typeface.BOLD);
        github.setPadding(0, Ui.dp(14), 0, Ui.dp(4));
        github.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                }
            }
        });
        col.addView(github);

        return col;
    }

    /** 执行重启 SystemUI（root）。 */
    private void doRestartSystemUI() {
        Toast.makeText(this, "请求 SystemUI 重启…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "pkill -f com.android.systemui"});
                    DataOutputStream os = new DataOutputStream(p.getOutputStream());
                    os.writeBytes("exit\n");
                    os.flush();
                    int code = p.waitFor();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (code == 0) {
                                Toast.makeText(MainActivity.this,
                                        "已发送重启指令", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this,
                                        "无 root 或已取消授权，请手动重启 SystemUI",
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "无 root 或已取消授权，请手动重启 SystemUI",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private String getVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    private int getColorCompat(int id) {
        return getResources().getColor(id);
    }

    /** section 标题（带左右 margin 与卡片对齐）。 */
    private TextView sectionTitle(String text) {
        TextView tv = Ui.sectionTitle(this, text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(16);
        lp.rightMargin = Ui.dp(16);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 卡片布局参数（带左右 margin）。 */
    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(16);
        lp.rightMargin = Ui.dp(16);
        return lp;
    }

    private View sectionSpacer() {
        return sectionSpacer(18);
    }

    /** 竖直间隔，用于 section 之间留白；并加左右 margin。 */
    private View sectionSpacer(int heightDp) {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(heightDp));
        lp.leftMargin = Ui.dp(16);
        lp.rightMargin = Ui.dp(16);
        v.setLayoutParams(lp);
        return v;
    }
}