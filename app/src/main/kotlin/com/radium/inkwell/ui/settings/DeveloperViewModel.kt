package com.radium.inkwell.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.util.RunLogRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** 已保存日志的概况；null = 还没统计完 */
data class LogSummary(val sessions: Int, val bytes: Long)

class DeveloperViewModel(
    private val appPrefs: AppPrefs,
    private val recorder: RunLogRecorder,
) : ViewModel() {

    val logRecording: StateFlow<Boolean> = appPrefs.logRecording
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _summary = MutableStateFlow<LogSummary?>(null)
    val summary: StateFlow<LogSummary?> = _summary.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /** 打好的 zip，交给页面拉起系统分享 */
    private val _exportFile = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val exportFile: SharedFlow<File> = _exportFile

    val messages = MessageBus()

    init {
        refreshSummary()
    }

    fun setLogRecording(on: Boolean) {
        viewModelScope.launch {
            appPrefs.setLogRecording(on)
            // 录制进程由 InkwellApp 跟着偏好起停，这里刷新时文件未必已经建出来 —— 不要紧，
            // 下次进页或导出/清除后还会再算
            refreshSummary()
        }
    }

    fun export() {
        if (_exporting.value) return
        _exporting.value = true
        viewModelScope.launch {
            try {
                _exportFile.emit(recorder.export())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                messages.emit("导出失败: ${e.message?.take(60)}")
            } finally {
                _exporting.value = false
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            recorder.clear()
            refreshSummary()
            messages.emit(if (recorder.isRecording) "已清除，正在录制的这份保留" else "已清除日志")
        }
    }

    fun disableDeveloperMode(onDone: () -> Unit) {
        viewModelScope.launch {
            appPrefs.setDeveloperMode(false)
            onDone()
        }
    }

    private fun refreshSummary() {
        viewModelScope.launch {
            val (sessions, bytes) = recorder.summary()
            _summary.value = LogSummary(sessions, bytes)
        }
    }
}
