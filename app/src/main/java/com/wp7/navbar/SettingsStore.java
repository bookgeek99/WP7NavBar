package com.wp7.navbar;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 设置存储辅助类。
 *
 * 模块 App（com.wp7.navbar）进程内使用 SharedPreferences 保存设置；
 * SystemUI 进程通过 Wp7SettingsProvider (ContentProvider) 读取。
 *
 * 目前支持的设置项：
 *  - ime_switcher:  左侧切换输入法按钮（默认开）
 *  - search_button: 任务键左侧的搜索键（放大镜，单击唤起助手/长按识屏）（默认开）
 */
public final class SettingsStore {

    public static final String PREF_NAME = "wp7_settings";

    // 设置键
    public static final String KEY_IME_SWITCHER = "ime_switcher";
    public static final String KEY_SEARCH_BUTTON = "search_button";

    // 默认值
    private static final boolean DEFAULT_IME_SWITCHER = true;
    private static final boolean DEFAULT_SEARCH_BUTTON = true;

    private SettingsStore() { }

    public static SharedPreferences getPrefs(Context ctx) {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static boolean getImeSwitcher(Context ctx) {
        return getPrefs(ctx).getBoolean(KEY_IME_SWITCHER, DEFAULT_IME_SWITCHER);
    }

    public static void setImeSwitcher(Context ctx, boolean value) {
        getPrefs(ctx).edit().putBoolean(KEY_IME_SWITCHER, value).apply();
    }

    public static boolean getSearchButton(Context ctx) {
        return getPrefs(ctx).getBoolean(KEY_SEARCH_BUTTON, DEFAULT_SEARCH_BUTTON);
    }

    public static void setSearchButton(Context ctx, boolean value) {
        getPrefs(ctx).edit().putBoolean(KEY_SEARCH_BUTTON, value).apply();
    }
}
