package com.wp7.navbar;

/**
 * SystemUI 资源 ID 集中管理（方案第十三节）。
 *
 * 旧代码把 0x7f0b0xxx 这类硬编码 ID 散落在业务逻辑里，难以维护和适配不同 SystemUI 版本。
 * 这里集中定义，并预留运行时通过 Resources.getIdentifier 动态解析的扩展点。
 *
 * 当前值基于 HyperOS 3 / Android 16 的 SystemUI 反编译结果，作为 fallback 使用。
 * TODO（第二阶段）：改为优先 getIdentifier("ime_switcher"/"recent", "id", "com.android.systemui") 动态获取。
 */
public final class SystemUiIds {

    // ime_switcher（输入法切换键；竖屏被伪装成搜索键，横屏保持原生）
    public static final int ID_IME_SWITCHER = 0x7f0b05a3;

    // recent（多任务 / 任务键；核心导航按钮，本项目默认不 Hook）
    public static final int ID_RECENT_BUTTON = 0x7f0b09d7;

    // menu（menu_ime 里的 menu 键，旧方案曾用作搜索；保留以备扩展）
    public static final int ID_MENU_BUTTON = 0x7f0b0750;

    // 顶层导航栏容器（其子段按 index 包含各键）
    public static final int ID_NAV_CONTAINER = 0x7f0b0443;

    private SystemUiIds() {}
}