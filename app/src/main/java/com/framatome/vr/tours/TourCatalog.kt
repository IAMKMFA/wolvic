package com.framatome.vr.tours

import java.io.File
import java.util.Locale

object TourCatalog {
    private val reservedDirNames = setOf(".imports", ".import_ledger")

    fun scan(appStorageRoot: File, isEnabled: (String) -> Boolean): List<Tour> {
        if (!appStorageRoot.exists()) {
            return emptyList()
        }

        val canonicalRoot = appStorageRoot.canonicalFile
        val discovered = linkedMapOf<String, Tour>()
        discover(
            dir = canonicalRoot,
            appStorageRoot = canonicalRoot,
            depth = 0,
            maxDepth = 5,
            discovered = discovered,
            isEnabled = isEnabled
        )
        return discovered.values.sortedBy { it.name.lowercase(Locale.US) }
    }

    private fun discover(
        dir: File,
        appStorageRoot: File,
        depth: Int,
        maxDepth: Int,
        discovered: MutableMap<String, Tour>,
        isEnabled: (String) -> Boolean
    ) {
        if (depth > maxDepth || !dir.isDirectory) {
            return
        }

        val shouldTreatAsTour = dir != appStorageRoot
        val entryFileName = if (shouldTreatAsTour) findEntryFileName(dir) else null
        if (entryFileName != null) {
            val canonicalDir = dir.canonicalFile
            val relativePath = canonicalDir.relativeTo(appStorageRoot).invariantSeparatorsPath
            val thumbnail = listOf("thumbnail.jpg", "thumbnail.png", "thumbnail.jpeg")
                .asSequence()
                .map { File(canonicalDir, it) }
                .firstOrNull { it.exists() }
                ?.absolutePath

            discovered.putIfAbsent(
                relativePath,
                Tour(
                    name = canonicalDir.name,
                    absolutePath = canonicalDir.absolutePath,
                    relativePath = relativePath,
                    entryFileName = entryFileName,
                    thumbnailPath = thumbnail,
                    source = if (File(canonicalDir, ".bundled").exists()) {
                        TourSource.BUNDLED
                    } else {
                        TourSource.EXTERNAL
                    },
                    enabled = isEnabled(relativePath)
                )
            )
            return
        }

        dir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.filterNot { it.name.startsWith(".") }
            ?.filterNot { it.name in reservedDirNames }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.forEach { child ->
                discover(
                    dir = child,
                    appStorageRoot = appStorageRoot,
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    discovered = discovered,
                    isEnabled = isEnabled
                )
            }
    }

    private fun findEntryFileName(dir: File): String? {
        return when {
            File(dir, "index.html").exists() -> "index.html"
            File(dir, "index.htm").exists() -> "index.htm"
            else -> null
        }
    }
}
