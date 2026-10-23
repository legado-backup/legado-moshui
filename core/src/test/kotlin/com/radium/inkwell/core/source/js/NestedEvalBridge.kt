package com.radium.inkwell.core.source.js

/** 测试用的桥：在脚本里调用时再进一次 eval，模拟 java.getString("@js:…") 的嵌套求值 */
class NestedEvalBridge(private val runtime: ScriptRuntime) {
    fun again(): String? = runtime.eval("1", emptyMap())
}
