package com.wp7.navbar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * 跨进程设置 ContentProvider。
 *
 * SystemUI 进程内的 Hook 通过 ContentResolver 查询本 Provider 获取模块设置。
 * 由于 module App 与 systemui 是不同进程，ShredPreferences 无法直接跨进程读取，
 * 因此用 ContentProvider 作为桥梁（标准 Android 机制，无需第三方库/root）。
 *
 * URI 约定：
 *   content://com.wp7.navbar.settings/ime_switcher   → 返回 0/1
 *   content://com.wp7.navbar.settings/search_button  → 返回 0/1（任务键左侧搜索键）
 *
 * 返回格式：MatrixCursor 单行单列，列名 "value"，值为 1(开) 或 0(关)。
 */
public class Wp7SettingsProvider extends ContentProvider {

    public static final String AUTHORITY = "com.wp7.navbar.settings";
    public static final Uri URI_IME_SWITCHER = Uri.parse("content://" + AUTHORITY + "/ime_switcher");
    public static final Uri URI_SEARCH_BUTTON = Uri.parse("content://" + AUTHORITY + "/search_button");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        boolean value = false;
        if (ctx != null) {
            String path = uri.getLastPathSegment();
            if ("ime_switcher".equals(path)) {
                value = SettingsStore.getImeSwitcher(ctx);
            } else if ("search_button".equals(path)) {
                value = SettingsStore.getSearchButton(ctx);
            }
        }
        MatrixCursor cursor = new MatrixCursor(new String[] { "value" });
        cursor.addRow(new Object[] { value ? 1 : 0 });
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}