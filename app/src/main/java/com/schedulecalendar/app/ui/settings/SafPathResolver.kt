package com.schedulecalendar.app.ui.settings

import androidx.core.net.toUri

/**
 * SAF（存储访问框架）路径解析工具。
 *
 * 备份目录在历史版本中可能以三种形式持久化，必须统一解析：
 * 1. `content://com.android.externalstorage.../tree/primary%3ADownload%2F...`（标准 URI）
 * 2. `/tree/primary:Download/...`（早期版本只存了 `uri.path`）
 * 3. 普通文件系统路径
 *
 * 之前 BackupManager 与 StorageViewModel 各写了一份几乎逐行相同的解析逻辑，
 * 容易出现「一处修、一处漏」，这里收口为唯一实现。
 */
object SafPathResolver {

    /** 是否为 SAF 目录标识（标准 content URI 或旧版 `/tree/...` path） */
    fun isSafPath(path: String): Boolean =
        path.startsWith("content://") || path.startsWith("/tree/")

    /**
     * 将 SAF 路径解析为可读的文件系统路径（用于 UI 显示与目录定位）。
     * 解析失败或非 SAF 路径时原样返回。
     */
    fun toDisplayPath(rawPath: String): String {
        if (rawPath.isBlank()) return ""
        val treeSeg = when {
            rawPath.startsWith("content://") ->
                runCatching { rawPath.toUri().path?.removePrefix("/tree/") }.getOrNull()
            rawPath.startsWith("/tree/")     -> rawPath.removePrefix("/tree/")
            else                             -> null
        } ?: return rawPath
        return try {
            val decoded = java.net.URLDecoder.decode(treeSeg, "UTF-8")
            resolveDocumentId(decoded)
        } catch (_: Exception) {
            rawPath
        }
    }

    /** 将 SAF document id（如 "primary:Download/MyData"）转换为文件系统路径 */
    private fun resolveDocumentId(docId: String): String {
        val parts = docId.split(":", limit = 2)
        val volume = parts[0]
        val relativePath = if (parts.size > 1) parts[1] else ""
        val basePath = when (volume.lowercase()) {
            "primary" -> "/storage/emulated/0"
            "home"    -> "/storage/emulated/0"
            else      -> "/storage/$volume"
        }
        return if (relativePath.isNotEmpty()) "$basePath/$relativePath" else basePath
    }
}
