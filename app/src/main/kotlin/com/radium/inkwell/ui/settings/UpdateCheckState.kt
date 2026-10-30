package com.radium.inkwell.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.DeterminateProgressBar
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.ui.components.NO_BROWSER_MESSAGE
import com.radium.inkwell.ui.components.openExternalLink
import com.radium.inkwell.update.CheckResult
import com.radium.inkwell.update.UpdateChannel
import com.radium.inkwell.update.UpdateInfo
import com.radium.inkwell.update.UpdateManager
import com.radium.inkwell.update.UpdateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 设置主页「检查更新」用的状态与弹窗，避免把整套下载逻辑塞进二级页。 */
internal class UpdateCheckState(
    val currentVersion: String,
    val channel: UpdateChannel,
    val source: UpdateSource,
    val checking: Boolean,
    val check: () -> Unit,
    val UpdateDialog: @Composable () -> Unit,
)

/**
 * @param messages 一次性提示走调用页的 [MessageBus]。从前直接 await `showSnackbar`：它要等那条提示
 *   自己消失（约 4 秒）才返回，「正在检查…」在「已是最新版本」弹出来之后还挂着 4 秒；
 *   下载失败时更糟 —— 弹窗一直转圈、「以后再说」点不动，失败提示又压在模态弹窗后面看不见。
 */
@Composable
internal fun rememberUpdateCheckState(messages: MessageBus): UpdateCheckState {
    val context = LocalContext.current
    val updateManager = koinInject<UpdateManager>()
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()

    val currentVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    val channel by appPrefs.updateChannel.collectAsStateWithLifecycle(initialValue = UpdateChannel.STABLE)
    val source by appPrefs.updateSource.collectAsStateWithLifecycle(initialValue = UpdateSource.GITHUB)

    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    /**
     * 下载/安装没成的原因，**写在弹窗里**而不是弹 Snackbar：弹窗是模态的，Snackbar 落在它后面根本看不见。
     * 弹窗留着，用户看完原因可以直接再点一次（比如刚去系统里授了「安装未知应用」权限）。
     */
    var downloadError by remember { mutableStateOf<String?>(null) }

    fun checkUpdate() {
        if (checking) return
        checking = true
        scope.launch {
            // 先收「正在检查…」再发提示：提示与它无关，不该陪着挂
            val result = try {
                updateManager.check(source, channel, currentVersion)
            } finally {
                checking = false
            }
            when (result) {
                is CheckResult.Available -> {
                    downloadError = null
                    update = result.info
                }
                CheckResult.UpToDate -> messages.emit("已是最新版本")
                is CheckResult.Failed -> messages.emit("检查失败: ${result.message}")
            }
        }
    }

    fun dismissDialog() {
        update = null
        downloadError = null
    }

    val dialog: @Composable () -> Unit = {
        update?.let { info ->
            val direct = info.directInstall
            AppAlertDialog(
                onDismissRequest = { if (!downloading) dismissDialog() },
                title = "发现新版本 v${info.latestVersion}" + if (info.isPrerelease) "（测试版）" else "",
                text = info.notes.ifBlank { "暂无更新说明" },
                confirmText = when {
                    direct != null -> if (downloadError != null) "重试" else "下载并安装"
                    info.browserIsApk -> "下载 APK"
                    else -> "查看 Release"
                },
                dismissText = "以后再说",
                confirmLoading = downloading,
                dismissEnabled = !downloading,
                onConfirm = {
                    if (direct != null) {
                        downloading = true
                        downloadProgress = 0f
                        downloadError = null
                        scope.launch {
                            // 转圈先收、再说原因：失败原因写在弹窗里，按钮立刻能点
                            val error = try {
                                val apk = updateManager.downloadAndVerify(
                                    direct, context.cacheDir,
                                ) { downloadProgress = it }
                                if (updateManager.install(context, apk)) {
                                    null
                                } else {
                                    "请先在系统里授予「安装未知应用」权限，再点一次"
                                }
                            } catch (e: CancellationException) {
                                // 离开设置页取消的下载不是「下载失败」
                                throw e
                            } catch (e: Exception) {
                                "下载失败: ${e.message?.take(80)}"
                            } finally {
                                downloading = false
                            }
                            if (error == null) dismissDialog() else downloadError = error
                        }
                    } else {
                        // 先关弹窗：拉不起浏览器时的那句提示才不会被模态弹窗挡住
                        dismissDialog()
                        info.browserUrl?.let {
                            if (!context.openExternalLink(it)) messages.emit(NO_BROWSER_MESSAGE)
                        }
                    }
                },
                content = {
                    if (downloading) {
                        DeterminateProgressBar(progress = { downloadProgress })
                        Text(
                            "正在下载并校验… ${(downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    downloadError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
    }

    return UpdateCheckState(
        currentVersion = currentVersion,
        channel = channel,
        source = source,
        checking = checking,
        check = ::checkUpdate,
        UpdateDialog = dialog,
    )
}
