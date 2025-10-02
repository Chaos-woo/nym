package com.cw2.nym.presentation.messages

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

// 顶层常量：供注解在编译期引用（必须是编译期常量）
private const val BUNDLE = "messages.NymBundle"

/**
 * Nym 消息绑定工具
 *
 * 为什么需要：
 * - 统一管理多语言资源，避免硬编码文案
 * - 便于后续国际化扩展与动态切换
 */
internal object NymBundle : DynamicBundle(BUNDLE) {

    /**
     * 使用 UTF-8 读取 properties，避免 Java 默认 ISO-8859-1 导致中文报错/乱码。
     * 为什么：Java 的 ResourceBundle 按规范使用 ISO-8859-1 读取 .properties，
     * 但我们的资源文件以 UTF-8 保存（更可读）。因此这里提供 UTF-8 Control。
     */
    private val UTF8_CONTROL: java.util.ResourceBundle.Control = object : java.util.ResourceBundle.Control() {
        override fun newBundle(
            baseName: String,
            locale: java.util.Locale,
            format: String,
            loader: ClassLoader,
            reload: Boolean
        ): java.util.ResourceBundle? {
            val bundleName = toBundleName(baseName, locale)
            val resourceName = "$bundleName.properties"
            val stream = if (reload) {
                val url = loader.getResource(resourceName) ?: return null
                val connection = url.openConnection()
                connection.useCaches = false
                connection.getInputStream()
            } else {
                loader.getResourceAsStream(resourceName)
            } ?: return null

            return stream.use { input ->
                val reader = java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)
                java.util.PropertyResourceBundle(reader)
            }
        }
    }

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        // 根据设置决定 UI 文案语言；AUTO 使用 IDE 当前语言
        return try {
            val settings = com.cw2.nym.data.settings.NymSettings.getInstance()
            when (settings.uiLanguagePreference.uppercase()) {
                "EN" -> messageForLocale(key, java.util.Locale.ENGLISH, *params)
                "ZH", "ZH_CN", "ZH-CN" -> messageForLocale(key, java.util.Locale.SIMPLIFIED_CHINESE, *params)
                else -> getMessage(key, *params) // AUTO -> 交给 IDE 当前 Locale
            }
        } catch (_: Throwable) {
            // 在早期启动阶段可能获取不到设置或发生异常，使用默认
            getMessage(key, *params)
        }
    }

    private fun messageForLocale(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        locale: java.util.Locale,
        vararg params: Any
    ): String {
        return try {
            val bundle = java.util.ResourceBundle.getBundle(BUNDLE, locale, UTF8_CONTROL)
            val pattern = bundle.getString(key)
            val formatter = java.text.MessageFormat(pattern, locale)
            formatter.format(params)
        } catch (_: Throwable) {
            // 如果指定语言缺少资源，则退回 EN
            val fallbackBundle = java.util.ResourceBundle.getBundle(BUNDLE, java.util.Locale.ENGLISH, UTF8_CONTROL)
            val pattern = fallbackBundle.getString(key)
            val formatter = java.text.MessageFormat(pattern, java.util.Locale.ENGLISH)
            formatter.format(params)
        }
    }
}
