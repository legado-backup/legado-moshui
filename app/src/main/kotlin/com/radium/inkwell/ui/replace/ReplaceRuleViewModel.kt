package com.radium.inkwell.ui.replace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import com.radium.inkwell.data.repo.ReplaceRuleRepository
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.core.source.PurifyRule
import com.radium.inkwell.core.source.PurifyTimeoutException
import com.radium.inkwell.core.source.Purifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReplaceRuleUiState(
    val rules: List<ReplaceRuleEntity> = emptyList(),
    /** 正在编辑的规则；null = 没开编辑面板 */
    val editing: ReplaceRuleEntity? = null,
    /** 编辑面板里的试算：用当前规则跑一遍示例文本 */
    val sampleText: String = "",
    val sampleResult: String = "",
    val patternError: String? = null,
)

class ReplaceRuleViewModel(
    private val repo: ReplaceRuleRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReplaceRuleUiState())
    val state: StateFlow<ReplaceRuleUiState> = _state.asStateFlow()

    val messages = MessageBus()

    init {
        viewModelScope.launch {
            repo.observeAll().collect { rules ->
                _state.value = _state.value.copy(rules = rules)
            }
        }
    }

    fun setEnabled(rule: ReplaceRuleEntity, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(rule.id, enabled) }
    }

    fun delete(rule: ReplaceRuleEntity) {
        viewModelScope.launch { repo.delete(listOf(rule.id)) }
    }

    fun newRule() {
        _state.value = _state.value.copy(
            editing = ReplaceRuleEntity(id = "", name = "", pattern = ""),
            sampleText = "",
            sampleResult = "",
            patternError = null,
        )
    }

    fun edit(rule: ReplaceRuleEntity) {
        _state.value = _state.value.copy(
            editing = rule,
            sampleText = "",
            sampleResult = "",
            patternError = null,
        )
    }

    fun dismissEditor() {
        _state.value = _state.value.copy(editing = null)
    }

    fun updateDraft(draft: ReplaceRuleEntity) {
        _state.value = _state.value.copy(editing = draft)
        recompute(draft, _state.value.sampleText)
    }

    fun updateSample(text: String) {
        _state.value = _state.value.copy(sampleText = text)
        _state.value.editing?.let { recompute(it, text) }
    }

    /**
     * 试算：正则写错、或者写出个能把整段吃掉的规则，用户得在保存前就看见。
     * 净化是有损的 —— 一条贪婪的正则能把正文删空，而这在阅读时表现为「正文规则未匹配到内容」，
     * 根本查不到是净化干的。
     */
    private fun recompute(draft: ReplaceRuleEntity, sample: String) {
        if (draft.pattern.isEmpty()) {
            _state.value = _state.value.copy(patternError = null, sampleResult = "")
            return
        }
        if (!draft.isRegex) {
            _state.value = _state.value.copy(
                patternError = null,
                sampleResult = sample.replace(draft.pattern, draft.replacement),
            )
            return
        }
        // 编译和替换都要兜：`Regex(pattern)` 只管模式本身，替换串是另一套 Java 模板语法 ——
        // 写个 `$1` 而模式里没有分组、或者以 `$`/`\` 结尾，炸的是 `replace` 这一步
        // （IndexOutOfBounds / IllegalArgument）。不兜就是当场崩。
        //
        // 按键即算，而用户敲到一半的正则（`(a+)+`）碰上样例文本就可能陷进灾难性回溯 —— 从前直接在
        // 主线程上跑，当场卡死（ANR）。挪到 Default、走正式净化同一条路（Purifier → 看门狗）：
        // 超时就报「太复杂」，新的一次按键取消上一次。语义与正式净化完全一致，试算对、书里也就对。
        recomputeJob?.cancel()
        recomputeJob = viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    Purifier.strict(listOf(PurifyRule(draft.pattern, draft.replacement, isRegex = true)))
                        .apply(sample)
                }
            }
            outcome.fold(
                onSuccess = { result ->
                    _state.value = _state.value.copy(patternError = null, sampleResult = result)
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    val message = if (e is PurifyTimeoutException) {
                        "正则太复杂，在样例上执行超时（多半是嵌套的重复，如 (a+)+）"
                    } else {
                        // strict 把原始错误包成 SourceException，真正有用的说明在 cause 上
                        (e.cause ?: e).message?.take(120) ?: "正则表达式有误"
                    }
                    _state.value = _state.value.copy(patternError = message, sampleResult = "")
                },
            )
        }
    }

    private var recomputeJob: Job? = null

    fun save() {
        val draft = _state.value.editing ?: return
        if (draft.pattern.isBlank()) {
            messages.emit("要替换的内容不能为空")
            return
        }
        if (draft.isRegex && runCatching { Regex(draft.pattern) }.isFailure) {
            messages.emit("正则表达式有误，改好再存")
            return
        }
        viewModelScope.launch {
            if (draft.id.isEmpty()) {
                repo.create(
                    name = draft.name.ifBlank { draft.pattern.take(20) },
                    pattern = draft.pattern,
                    replacement = draft.replacement,
                    isRegex = draft.isRegex,
                    scope = draft.scope,
                )
            } else {
                repo.save(draft.copy(name = draft.name.ifBlank { draft.pattern.take(20) }))
            }
            _state.value = _state.value.copy(editing = null)
            messages.emit("已保存")
        }
    }
}
