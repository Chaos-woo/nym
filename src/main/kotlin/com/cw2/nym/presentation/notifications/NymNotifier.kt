package com.cw2.nym.presentation.notifications

import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationGroupManager

/**
 * 统一通知工具
 *
 * 为什么需要：
 * - 集中封装，避免重复创建 Notification 对象
 * - 统一通知分组名称，便于用户在事件日志中检索
 * - 严禁输出敏感信息（符合隐私规范）
 */
internal object NymNotifier {
    // 分组ID必须是稳定的不随本地化变化的常量，避免在不同语言下找不到分组
    private const val GROUP_ID: String = "Nym.Notifications"

    private fun notify(message: String, type: NotificationType) {
        // 使用新版 API，确保在 2024.1+ 平台上正确显示气泡
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
        group.createNotification(message, type).notify(null)
    }

    fun info(message: String) = notify(message, NotificationType.INFORMATION)

    fun warn(message: String) = notify(message, NotificationType.WARNING)

    fun error(message: String) = notify(message, NotificationType.ERROR)
}
