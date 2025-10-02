package com.cw2.nym.presentation.templates

import com.intellij.codeInsight.template.impl.DefaultLiveTemplatesProvider

/*
 * Live Template 提供器
 *
 * 设计说明（中文）：
 * - 通过 DefaultLiveTemplatesProvider 注册资源路径下的模板文件（templates/nym.xml）。
 * - 采用资源方式而不是代码动态拼装，便于后续在不改动代码的情况下调整模板内容与预览。
 * - 模板命名归入 "Nym" 组，避免与用户自定义模板冲突。
 */
class NymLiveTemplatesProvider : DefaultLiveTemplatesProvider {
    override fun getDefaultLiveTemplateFiles(): Array<String> = arrayOf("templates/nym")

    override fun getHiddenLiveTemplateFiles(): Array<String> = emptyArray()
}
