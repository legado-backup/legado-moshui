package com.radium.inkwell.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.radium.inkwell.core.util.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 中转服务器更新的下载 + 校验 + 安装。
 *
 * 下载边下边算 sha256，与服务端给的对不上就删文件报错（绝不安装未校验的包）。
 * 但 sha256 只证明「下到的就是服务端说的那个文件」—— 它和下载地址出自同一份元数据，中转服务器或
 * 那份元数据一旦被人改了，两样一起改就过了。所以校验通过之后还要过 [authenticity]：包名与签名证书
 * 必须和本机已装的这一份对得上，才轮得到系统安装器。
 * 安装走系统安装器（ACTION_VIEW + FileProvider），需要「安装未知应用」权限。
 */
class UpdateInstaller(
    private val http: OkHttpClient,
    private val authenticity: ApkAuthenticity,
) {

    /**
     * 下载并校验 sha256、包名与签名，返回落地的 APK 文件。任何一步不过（含中途取消）都删文件再抛。
     * [onProgress] 回调 0f..1f（Compose State 可跨线程写，这里在 IO 线程回调）。
     */
    suspend fun downloadAndVerify(
        install: DirectInstall,
        cacheDir: File,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "updates").apply { mkdirs() }
        // 清掉上次的残留：避免占空间，也避免装到半截的旧包
        dir.listFiles()?.forEach { it.delete() }
        // 文件名来自服务端：`../../databases/inkwell.db` 这种会跳出 updates/，下面先截断写入、
        // 校验不过再删 —— 应用私有目录里任何文件都能被覆盖或删掉。只取最后一段，拿不到就用固定名
        val out = File(dir, File(install.filename).name.takeIf { it.isNotBlank() && it != ".." } ?: "inkwell-update.apk")

        try {
            val req = Request.Builder().url(install.url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("下载失败: HTTP ${resp.code}")
                val body = resp.body
                val total = install.size.takeIf { it > 0 } ?: body.contentLength()
                val md = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            // 阻塞读循环不认协程取消：不自己看一眼的话，用户离开页面后几十 MB 的包
                            // 照样在后台下完，白耗流量还占着连接。每读一块查一次，取消了就停
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            md.update(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                val actual = md.digest().toHex()
                if (!actual.equals(install.sha256, ignoreCase = true)) {
                    throw IOException("文件校验失败（sha256 不一致）")
                }
            }
            authenticity.verify(out)
        } catch (e: Exception) {
            // 取消、断网、校验不过：半截的、来路不明的包一个都别留。取消（CancellationException）照原样抛出去
            out.delete()
            throw e
        }
        out
    }

    /**
     * 拉起系统安装器。
     * @return true 已拉起安装；false 没有「安装未知应用」权限（已跳去授权页，需用户授权后重试）。
     */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        // 复用书源导出用的同一个 FileProvider（authority = ${applicationId}.fileprovider）
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return true
    }
}

/** 下载回来的安装包是不是 Inkwell 自己的新版本。不是就抛异常（消息直接给用户看），删文件由调用方负责 */
fun interface ApkAuthenticity {
    fun verify(apk: File)
}

/**
 * 按**本机已装的这一份**核对：包名一致、签名证书对得上。
 *
 * 包名是主要防线：同包名、不同签名的包系统安装器自己就会拒（「与已安装的应用冲突」）；
 * 换个包名的包却会被当成一个**全新的应用**装上 —— 用户以为在升级，实际装了别人的东西。
 * 签名在这里再核一遍，是为了在拉起安装器之前就给一句看得懂的话，而不是让用户在系统界面上撞墙。
 *
 * 解析签名块要把整个 APK 读一遍，只在 [UpdateInstaller.downloadAndVerify] 的 IO 线程里调，别放主线程。
 */
class InstalledAppAuthenticity(private val context: Context) : ApkAuthenticity {
    override fun verify(apk: File) {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
            ?: throw SecurityException("安装包无法解析，已删除")
        if (archive.packageName != context.packageName) {
            throw SecurityException("安装包不是 Inkwell（${archive.packageName}），已删除、未安装")
        }
        val apkSigning = archive.signingInfo
            ?: throw SecurityException("安装包没有签名，已删除、未安装")
        val installed = pm.getPackageInfo(context.packageName, flags).signingInfo
            ?: throw SecurityException("读不到当前应用的签名，已删除、未安装")
        val trusted = isTrustedUpdateSigner(
            apkSigners = apkSigning.apkContentsSigners.orEmpty().toCharsSet(),
            // 多签名的包没有轮换历史（系统也不支持），只按签名集合比
            apkLineage = if (apkSigning.hasMultipleSigners()) {
                emptySet()
            } else {
                apkSigning.signingCertificateHistory.orEmpty().toCharsSet()
            },
            installedSigners = installed.apkContentsSigners.orEmpty().toCharsSet(),
            installedTrusts = { cert ->
                pm.hasSigningCertificate(
                    context.packageName, Signature(cert).toByteArray(), PackageManager.CERT_INPUT_RAW_X509,
                )
            },
        )
        if (!trusted) throw SecurityException("安装包签名与当前应用不一致，已删除、未安装")
    }

    private fun Array<out Signature>.toCharsSet(): Set<String> = mapTo(HashSet()) { it.toCharsString() }
}

/**
 * 新包的签名能不能当成「同一个开发者的新版本」。证书一律用十六进制串表示（Signature.toCharsString），
 * 抽成纯函数是为了脱离 PackageManager 单测。
 *
 * @param apkSigners 新包当前的签名证书
 * @param apkLineage 新包的密钥轮换证明链（含当前证书；多签名时为空）
 * @param installedSigners 已装应用当前的签名证书
 * @param installedTrusts 已装应用认不认这张证书（含它自己轮换链里的旧证书）
 */
internal fun isTrustedUpdateSigner(
    apkSigners: Set<String>,
    apkLineage: Set<String>,
    installedSigners: Set<String>,
    installedTrusts: (String) -> Boolean,
): Boolean {
    if (apkSigners.isEmpty() || installedSigners.isEmpty()) return false
    // 同一把（或同一组）钥匙签的：绝大多数情况
    if (apkSigners == installedSigners) return true
    // 多签名不支持密钥轮换，集合不一致就是不一致
    if (apkSigners.size > 1 || installedSigners.size > 1) return false
    // 新版本轮换了签名密钥：新包的证明链里必须有已装的这把 —— 系统安装器认的也正是这条链
    if (installedSigners.single() in apkLineage) return true
    // 新包用的是已装应用自己轮换链里的旧钥匙：已装的那份证明过这张证书归它所有
    return installedTrusts(apkSigners.single())
}
