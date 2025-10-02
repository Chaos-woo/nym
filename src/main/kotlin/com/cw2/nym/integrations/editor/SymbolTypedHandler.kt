package com.cw2.nym.integrations.editor

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/*
 * 兼容占位的键入处理器（当前未使用）。
 * 中文说明：为避免重复逻辑与混淆，实际的特殊符号处理逻辑由 NymTypedActionHandler 承担。
 * 本类保持空实现，不再处理任何 "[$...]" 或历史 "[$[c]]" 模式。
 */
internal class SymbolTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result = Result.CONTINUE
}
