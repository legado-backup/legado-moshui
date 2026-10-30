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
    /** 规则列表已从库里读过一遍；在这之前 [rules] 的空不代表「还没有净化规则」，别闪空态 */
    val rulesLoaded: Boolean = false,
    /** 正在编辑的规则；null = 没开编辑面板 */
    val editing: ReplaceRuleEntity? = null,
    /** 打开编辑面板那一刻的规则，用来判断有没有没保存的改动 */
    val editingOriginal: ReplaceRuleEntity? = null,
    /** 编辑面板里的试算：用当前规则跑一遍示例文本 */
    val sampleText: String = "",
    /**
     * 试算结果；null = 还没有可展示的结果（「替换什么」还空着，或正则写错了 —— 错误另见 [patternError]）。
     *
     * 必须和空串分开：空串的意思是「整段被删光了」，页面会标红警告。从前没填规则时也给空串，
     * 刚粘进一段样例、还没开始写规则，就先被红字警告「这条规则大概太贪婪」。
     */
    val sampleResult: String? = null,
    val patternError: String? = null,
    /** 保存中：按钮转圈、不再接受第二下 */
    val saving: Boolean = false,
) {
    /** 编辑面板里有没保存的改动（只看规则本身；样例文本不存，不算） */
    val editorDirty: Boolean get() = editing != null && editing != editingOriginal
}

class ReplaceRuleViewModel(
    private val repo: ReplaceRuleRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReplaceRuleUiState())
    val state: StateFlow<ReplaceRuleUiState> = _state.asStateFlow()

    val messages = MessageBus()

    init {
        viewModelScope.launch {
            repo.observeAll().collect { rules ->
                _state.value = _state.value.copy(rules = rules, rulesLoaded = true)
            }
        }
    }

    fun setEnabled(rule: ReplaceRuleEntity, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(rule.id, enabled) }
    }

    fun delete(rule: ReplaceRuleEntity) {
        viewModelScope.launch { repo.delete(listOf(rule.id)) }
    }

    fun newRule() = openEditor(ReplaceRuleEntity(id = "", name = "", pattern = ""))

    fun edit(rule: ReplaceRuleEntity) = openEditor(rule)

    private fun openEditor(rule: ReplaceRuleEntity) {
        // 上一个面板还在飞的正则试算别落到这一个上
        recomputeJob?.cancel()
        _state.value = _state.value.copy(
            editing = rule,
            editingOriginal = rule,
            sampleText = "",
            sampleResult = null,
            patternError = null,
        )
    }

    fun dismissEditor() {
        recomputeJob?.cancel()
        _state.value = _state.value.copy(editing = null, editingOriginal = null)
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
        // 每条路径都先掐掉上一次还在飞的正则试算 —— 不只是正则这条。从前只有正则分支 cancel：
        // 正则试到一半把开关拨成纯文本（或把规则删空），旧任务随后落地，把「正则有误」和它的结果
        // 写到一条纯文本规则上，保存按钮跟着 patternError 一直灰着，怎么改都点不动。
        recomputeJob?.cancel()
        if (draft.pattern.isEmpty()) {
            _state.value = _state.value.copy(patternError = null, sampleResult = null)
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
                    _state.value = _state.value.copy(patternError = message, sampleResult = null)
                },
            )
        }
    }

    private var recomputeJob: Job? = null

    fun save() {
        val s = _state.value
        // 连点两下「保存」：新建规则的 editing 要等 repo.create 回来才清空，这期间第二下照样进得来，
        // 于是建出两条一模一样的规则
        if (s.saving) return
        val draft = s.editing ?: return
        if (draft.pattern.isBlank()) {
            messages.emit("要替换的内容不能为空")
            return
        }
        if (draft.isRegex && runCatching { Regex(draft.pattern) }.isFailure) {
            messages.emit("正则表达式有误，改好再存")
            return
        }
        _state.value = s.copy(saving = true)
        viewModelScope.launch {
            try {
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
                recomputeJob?.cancel()
                _state.value = _state.value.copy(editing = null, editingOriginal = null)
                messages.emit("已保存")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 面板留着，改的东西还在，用户可以再点一次
                messages.emit("保存失败: ${e.message?.take(80)}")
            } finally {
                _state.value = _state.value.copy(saving = false)
            }
        }
    }
}
