package com.framatome.vr.tours

import android.app.Application
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class PublicInboxImporter private constructor(
    private val app: Application?,
    private val externalBaseOverride: File?,
    private val sharedRoots: List<File>,
    private val legacyRoot: File,
    private val maxExtractBytes: Long
) {
    constructor(app: Application) : this(
        app = app,
        externalBaseOverride = null,
        sharedRoots = defaultSharedRoots(),
        legacyRoot = defaultLegacyRoot(),
        maxExtractBytes = DEFAULT_MAX_BYTES
    )

    constructor(config: ImportConfig) : this(
        app = null,
        externalBaseOverride = config.externalBase,
        sharedRoots = config.sharedRoots,
        legacyRoot = config.legacyRoot,
        maxExtractBytes = config.maxExtractBytes
    )

    data class ImportConfig(
        val externalBase: File,
        val sharedRoots: List<File> = defaultSharedRoots(),
        val legacyRoot: File = defaultLegacyRoot(),
        val maxExtractBytes: Long = DEFAULT_MAX_BYTES
    )

    private val externalBase: File by lazy {
        externalBaseOverride ?: TourStorage.ensureAppDirs(requireNotNull(app))
    }
    private val scratchRoot: File by lazy {
        File(externalBase, ".imports").apply { if (!exists()) mkdirs() }
    }
    private val importLedger: File by lazy {
        File(externalBase, ".import_ledger").apply { if (!exists()) mkdirs() }
    }

    fun runImportOnce() {
        try {
            if (!externalBase.exists()) externalBase.mkdirs()
        } catch (t: Throwable) {
            logWarn("Cannot ensure app external tours dir", t)
            return
        }

        val zipSources = (sharedRoots.flatMap { root ->
            listOf(File(root, "inbox"), File(root, "Inbox"), File(root, "Tours"), File(root, "tours"), root)
        } + legacyRoot)

        zipSources.distinctBy { it.absolutePath }.forEach { maybeImportZips(it) }

        sharedRoots
            .flatMap { listOf(File(it, "Tours"), File(it, "tours")) }
            .distinctBy { it.absolutePath }
            .forEach { syncFolders(it) }

        purgeInvalidTours()
    }

    private fun maybeImportZips(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles { f -> f.isFile && f.extension.equals("zip", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.forEach { zip ->
                if (alreadyImported(zip)) return@forEach
                try {
                    logInfo("Importing zip ${zip.absolutePath}")
                    val scratch = File(scratchRoot, "${inferFolderName(zip.name)}-${UUID.randomUUID()}")
                    if (scratch.exists()) scratch.deleteRecursively()
                    scratch.mkdirs()
                    safeUnzip(zip, scratch)
                    extractNestedZips(scratch)
                    cleanupJunk(scratch)
                    val imported = finalizeScratch(scratch, inferFolderName(zip.name))
                    recordImport(zip, imported)
                } catch (t: Throwable) {
                    logError("Failed to import ${zip.absolutePath}", t)
                }
            }
    }

    private fun alreadyImported(zip: File): Boolean {
        val ledgerFile = File(importLedger, "${ledgerKey(zip)}.marker")
        if (!ledgerFile.exists()) return false
        try {
            val storedTimestamp = ledgerFile.readText().trim().toLongOrNull() ?: return false
            return storedTimestamp >= zip.lastModified()
        } catch (_: Throwable) {
            return false
        }
    }

    private fun recordImport(zip: File, tourNames: List<String>) {
        try {
            val key = ledgerKey(zip)
            val ledgerFile = File(importLedger, "$key.marker")
            ledgerFile.writeText(zip.lastModified().toString())
            val metaFile = File(importLedger, "$key.tours")
            metaFile.writeText(tourNames.joinToString("\n"))
        } catch (t: Throwable) {
            logWarn("Failed to record import marker for ${zip.name}", t)
        }
    }

    private fun finalizeScratch(scratch: File, fallbackName: String): List<String> {
        val discovered = discoverTours(scratch)
        val moved = if (discovered.isEmpty()) {
            val destName = sanitizeName(fallbackName).ifBlank { "Tour" }
            val dest = replaceDest(destName)
            moveDirectory(scratch, dest)
            listOf(dest.name)
        } else {
            discovered.map { tourDir ->
                val preferredName = if (tourDir == scratch) fallbackName else tourDir.name
                val safeName = sanitizeName(preferredName).ifBlank { fallbackName }
                val dest = replaceDest(safeName)
                moveDirectory(tourDir, dest)
                dest.name
            }.also {
                if (scratch.exists()) scratch.deleteRecursively()
            }
        }
        moved.forEach { name ->
            val marker = File(externalBase, "$name/.imported")
            marker.parentFile?.mkdirs()
            marker.writeText("1")
            logInfo("Imported tour folder $name")
        }
        return moved
    }

    private fun replaceDest(baseName: String): File {
        val safe = sanitizeName(baseName).ifBlank { "Tour" }
        val dest = File(externalBase, safe)
        if (dest.exists()) {
            val importedMarker = File(dest, ".imported")
            if (importedMarker.exists()) {
                logInfo("Replacing existing imported tour: ${dest.name}")
                dest.deleteRecursively()
            } else {
                var suffix = 1
                var candidate = File(externalBase, "${safe}_$suffix")
                while (candidate.exists()) {
                    suffix++
                    candidate = File(externalBase, "${safe}_$suffix")
                }
                return candidate
            }
        }
        return dest
    }

    private fun moveDirectory(source: File, dest: File) {
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!source.renameTo(dest)) {
            source.copyRecursively(dest, overwrite = true)
            source.deleteRecursively()
        }
    }

    private fun discoverTours(root: File): List<File> {
        if (containsEntryFile(root)) {
            return listOf(root)
        }
        val results = mutableListOf<File>()
        fun dfs(dir: File, depth: Int) {
            if (depth > 5) return
            dir.listFiles()?.forEach { child ->
                if (!child.isDirectory || isIgnorable(child.name)) return@forEach
                if (containsEntryFile(child)) {
                    results.add(child)
                } else {
                    dfs(child, depth + 1)
                }
            }
        }
        dfs(root, 0)
        return results.distinct()
    }

    private fun containsEntryFile(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val entries = dir.listFiles { file ->
            file.isFile && (file.name.equals("index.html", true) || file.name.equals("index.htm", true))
        }
        return !entries.isNullOrEmpty()
    }

    private fun syncFolders(source: File) {
        if (!source.exists()) return
        source.listFiles { f -> f.isDirectory }?.forEach { folder ->
            try {
                val dest = File(externalBase, sanitizeName(folder.name).ifBlank { folder.name })
                logInfo("Syncing public tour folder: ${folder.absolutePath}")
                if (dest.exists()) dest.deleteRecursively()
                folder.copyRecursively(dest, overwrite = true)
                File(dest, ".imported").writeText("1")
            } catch (t: Throwable) {
                logError("Failed to sync folder ${folder.absolutePath}", t)
            }
        }
    }

    private fun purgeInvalidTours() {
        if (!externalBase.exists()) return
        externalBase.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.forEach { dir ->
            if (!containsEntryFile(dir)) {
                logInfo("Purging invalid tour directory (no index file): ${dir.name}")
                dir.deleteRecursively()
            }
        }
    }

    private fun inferFolderName(zipName: String): String {
        return zipName.removeSuffix(".zip").removeSuffix(".ZIP")
    }

    private fun sanitizeName(raw: String): String {
        return raw.trim()
            .replace(Regex("[\\n\\r]"), " ")
            .replace(Regex("[/\\\\]+"), "_")
            .take(120)
    }

    private fun safeUnzip(zipFile: File, destDir: File) {
        var total: Long = 0
        val destCanonical = destDir.canonicalFile
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.startsWith("/") || name.contains("..")) {
                    throw IOException("Invalid zip entry path: $name")
                }
                val outFile = File(destDir, name).canonicalFile
                if (
                    outFile != destCanonical &&
                    !outFile.path.startsWith(destCanonical.path + File.separator)
                ) {
                    throw IOException("Zip entry escapes destination: $name")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { fos ->
                        val copied = zis.copyToLimited(fos, maxExtractBytes - total)
                        total += copied
                        if (total > maxExtractBytes) throw IOException("Zip too large; aborting")
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun extractNestedZips(dir: File, depth: Int = 0, maxDepth: Int = 5) {
        if (depth > maxDepth) return
        val zips = dir.walkTopDown()
            .filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
            .toList()
        if (zips.isEmpty()) return

        for (zip in zips) {
            try {
                val targetName = inferFolderName(zip.name)
                val target = File(zip.parentFile, sanitizeName(targetName).ifBlank { targetName })
                if (!target.exists()) target.mkdirs()
                logInfo("Extracting nested zip: ${zip.absolutePath}")
                safeUnzip(zip, target)
                zip.delete()
                cleanupJunk(target)
                flattenSingleFolder(target)
            } catch (t: Throwable) {
                logError("Failed to extract nested zip ${zip.absolutePath}", t)
            }
        }
        extractNestedZips(dir, depth + 1, maxDepth)
    }

    private fun flattenSingleFolder(dir: File) {
        fun findDeepIndexFolder(current: File, depth: Int = 0): File {
            if (depth > 5) return current
            if (containsEntryFile(current)) return current
            val children = current.listFiles { f -> f.isDirectory && !isIgnorable(f.name) } ?: return current
            return if (children.size == 1) findDeepIndexFolder(children.first(), depth + 1) else current
        }

        val target = findDeepIndexFolder(dir)
        if (target != dir) {
            logInfo("Flattening nested tour folder from ${target.absolutePath}")
            target.listFiles()?.forEach { nested ->
                val dest = File(dir, nested.name)
                if (dest.exists()) dest.deleteRecursively()
                nested.renameTo(dest)
            }
            target.deleteRecursively()
        }
    }

    private fun cleanupJunk(dir: File) {
        dir.walkBottomUp().forEach { file ->
            if (isIgnorable(file.name)) {
                file.deleteRecursively()
            }
        }
    }

    private fun isIgnorable(name: String): Boolean {
        return name == ".imported" ||
            name == ".bundled" ||
            name == ".imports" ||
            name == ".import_ledger" ||
            name.equals("__MACOSX", ignoreCase = true) ||
            name.equals(".DS_Store", ignoreCase = true) ||
            name.equals("thumbs.db", ignoreCase = true)
    }

    private fun logInfo(message: String) = runLogging(message) { Log.i(TAG, message) }
    private fun logWarn(message: String, throwable: Throwable? = null) = runLogging(message) {
        Log.w(TAG, message, throwable)
    }
    private fun logError(message: String, throwable: Throwable? = null) = runLogging(message) {
        Log.e(TAG, message, throwable)
    }

    private fun runLogging(message: String, block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            println("$TAG: $message")
        }
    }

    companion object {
        private const val TAG = "PublicInboxImporter"
        private const val DEFAULT_MAX_BYTES: Long = 4L * 1024 * 1024 * 1024

        @Suppress("DEPRECATION")
        private fun externalStorageRoot(): File = Environment.getExternalStorageDirectory()

        private fun defaultSharedRoots(): List<File> {
            val extRoot = externalStorageRoot()
            return listOf(
                File(extRoot, "FramatomeVR"),
                File(extRoot, "FramatomeVRPro")
            )
        }

        private fun defaultLegacyRoot(): File = externalStorageRoot()
    }
}

private fun ledgerKey(zip: File): String {
    val identity = try {
        zip.canonicalPath
    } catch (_: Throwable) {
        zip.absolutePath
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)
    val safeName = zip.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return "$digest-$safeName"
}

private fun ZipInputStream.copyToLimited(out: java.io.OutputStream, limit: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var bytesCopied = 0L
    var bytes = read(buffer)
    var remaining = limit
    while (bytes >= 0) {
        if (remaining <= 0) throw IOException("Exceeded max extraction size")
        val toWrite = if (bytes.toLong() > remaining) remaining.toInt() else bytes
        out.write(buffer, 0, toWrite)
        bytesCopied += toWrite
        remaining -= toWrite
        bytes = read(buffer)
    }
    return bytesCopied
}
