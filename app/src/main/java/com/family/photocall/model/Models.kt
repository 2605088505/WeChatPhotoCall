package com.family.photocall.model

data class PointConfig(
    val x: Int = 0,
    val y: Int = 0,
    val xPercent: Float = 0f,
    val yPercent: Float = 0f
)

data class DelayConfig(
    val afterOpenWechatMs: Long = 1800,
    val afterSearchEntryMs: Long = 900,
    val afterInputMs: Long = 1200,
    val afterResultMs: Long = 1400,
    val afterChatMoreMs: Long = 900,
    val afterVideoCallMs: Long = 900,
    val afterVideoConfirmMs: Long = 800,
    val afterImeMenuMs: Long = 500,
    val afterImeClipboardMs: Long = 700,
    val afterImeItemMs: Long = 800
)

data class PointsConfig(
    val searchEntry: PointConfig = PointConfig(),
    val searchInput: PointConfig = PointConfig(),
    // 兼容旧配置字段，不再作为主路径
    val inputAction: PointConfig = PointConfig(),
    // 输入法：有的要先点总菜单/工具栏
    val imeMenu: PointConfig = PointConfig(),
    // 输入法剪贴板入口按钮
    val imeClipboard: PointConfig = PointConfig(),
    // 剪贴板面板里的第一条（刚复制的联系人名）
    val imeClipboardItem1: PointConfig = PointConfig(),
    val searchResult1: PointConfig = PointConfig(),
    val chatMore: PointConfig = PointConfig(),
    val videoCall: PointConfig = PointConfig(),
    val videoCallConfirm: PointConfig = PointConfig(),
    val homeTabWechat: PointConfig = PointConfig(),
    val back: PointConfig = PointConfig()
)

data class CalibrationConfig(
    val deviceModel: String = "",
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val densityDpi: Int = 0,
    val wechatVersionHint: String = "",
    val updatedAt: Long = 0L,
    val points: PointsConfig = PointsConfig(),
    val delays: DelayConfig = DelayConfig()
)

data class ContactConfig(
    val id: String,
    val displayName: String,
    val searchName: String,
    val avatarPath: String = "",
    val enabled: Boolean = true
)

data class AppConfig(
    val contacts: List<ContactConfig> = emptyList(),
    val calibration: CalibrationConfig = CalibrationConfig(),
    val dryRun: Boolean = true
)

enum class CalibrationStep(
    val key: String,
    val title: String,
    val instruction: String
) {
    SEARCH_ENTRY(
        "search_entry",
        "搜索入口",
        "请先打开微信主界面（会话列表），然后点一下右上角搜索/放大镜的位置。"
    ),
    SEARCH_INPUT(
        "search_input",
        "搜索输入框",
        "请进入搜索页，点顶部输入框中心，确保键盘弹出来。"
    ),
    IME_MENU(
        "ime_menu",
        "输入法总菜单（可选）",
        "如果剪贴板按钮藏在输入法“菜单/工具/更多”里，请先点开那个总菜单按钮。如果剪贴板按钮直接可见，点“跳过”。"
    ),
    IME_CLIPBOARD(
        "ime_clipboard",
        "输入法剪贴板按钮",
        "点输入法上的“剪贴板/Clipboard”入口按钮（第一次点击）。"
    ),
    IME_CLIPBOARD_ITEM1(
        "ime_clipboard_item1",
        "剪贴板第一条",
        "剪贴板面板打开后，点第一条文本（第二次点击）。自动流程会先把联系人名字复制到剪贴板，再点这一条完成粘贴。"
    ),
    SEARCH_RESULT_1(
        "search_result_1",
        "第一个搜索结果",
        "请手动搜索一个测试联系人，出现结果后，点第一个联系人条目中间位置。"
    ),
    CHAT_MORE(
        "chat_more",
        "聊天页加号",
        "请进入该联系人聊天页，点右下角/工具栏的“+”号位置。"
    ),
    VIDEO_CALL(
        "video_call",
        "视频通话按钮",
        "请打开“+”面板后，点“视频通话”按钮的位置。点完通常会再弹出视频/语音选择，先别点出去。"
    ),
    VIDEO_CALL_CONFIRM(
        "video_call_confirm",
        "确认视频通话",
        "弹出“视频通话 / 语音通话”选择后，点“视频通话”（不要点语音通话）。校准阶段点一下记下位置即可。"
    ),
    HOME_TAB_WECHAT(
        "home_tab_wechat",
        "底部微信标签（可选）",
        "点底部导航的“微信”标签位置，用于脚本跑偏时回到主界面。可跳过。"
    ),
    BACK(
        "back",
        "返回位置（可选）",
        "点一个可返回的位置（如左上角返回）。也可跳过。"
    );

    companion object {
        val required = listOf(
            SEARCH_ENTRY,
            SEARCH_INPUT,
            IME_CLIPBOARD,
            IME_CLIPBOARD_ITEM1,
            SEARCH_RESULT_1,
            CHAT_MORE,
            VIDEO_CALL,
            VIDEO_CALL_CONFIRM
        )
    }
}
