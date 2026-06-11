package com.framatome.vr.tours

import android.content.Context
import android.util.Log
import com.framatome.vr.content.ImmersiveIntentFactory
import com.framatome.vr.tours.injection.TourHtmlInjector
import com.igalia.wolvic.R
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.text.Charsets

class LocalWebServer private constructor(
    private val baseDir: File,
    private val appContext: Context
) : NanoHTTPD(HOST, PORT) {
    private val baseCanonical: File = baseDir.canonicalFile
    // Media is streamed from the shared FramatomeVR root (parent of Tours) so the
    // WebXR viewer can reach Library/* as well as Tours/*.
    private val mediaRootCanonical: File = (baseDir.parentFile ?: baseDir).canonicalFile
    private val gogglesUuidCache = ConcurrentHashMap<String, TourHtmlInjector.GogglesUuidEntry>()
    // Cached bytes for immutable bundled assets (access-ordered LRU, byte-bounded
    // in cacheAsset). Guarded by synchronized(assetCache) on writes.
    private val assetCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {}

    override fun serve(session: IHTTPSession): Response {
        return try {
            val decodedUri = URLDecoder.decode(session.uri, Charsets.UTF_8.name())
            val pathOnly = decodedUri.substringBefore('?')
            Log.d(TAG, "serve ${session.method} $decodedUri from ${session.remoteIpAddress}")

            if (pathOnly.removePrefix("/") == "launch") {
                return serveLaunch(session)
            }

            if (pathOnly == "/health") {
                return serveHealth()
            }

            val uriPath = pathOnly.removePrefix("/")
            if (uriPath.isEmpty() || uriPath == "index.html") {
                return serveDynamicIndex()
            }

            if (uriPath == "__assets__/vr-goggles.png" || uriPath == TourHtmlInjector.GOGGLES_OVERRIDE_PATH) {
                return serveRawResource(R.raw.framatome_vr_goggles, "image/png")
            }

            if (uriPath == "__assets__/app-icon.png") {
                return serveRawResource(R.raw.framatome_app_icon, "image/png")
            }

            // Framatome WebXR media viewer (served from bundled assets).
            if (uriPath.startsWith(VIEWER_PREFIX)) {
                val assetRel = uriPath.removePrefix(VIEWER_PREFIX).ifBlank { "index.html" }
                if (assetRel.split('/').any { it == ".." }) return forbidden()
                return serveAsset("$VIEWER_ASSET_DIR/$assetRel")
            }

            // Raw media files streamed to the viewer from the shared storage tree.
            if (uriPath.startsWith(MEDIA_PREFIX)) {
                return serveMediaFile(session, uriPath.removePrefix(MEDIA_PREFIX))
            }

            // Generated/downscaled thumbnails for hub cards. Keys are content
            // hashes only — no path input, no traversal surface.
            if (uriPath.startsWith(THUMBS_PREFIX)) {
                val key = uriPath.removePrefix(THUMBS_PREFIX)
                val thumbFile = ThumbnailStore.resolve(appContext, key)
                return if (thumbFile != null) {
                    serveFile(session, thumbFile)
                } else {
                    notFound()
                }
            }

            // Playlist JSON for in-viewer gallery navigation. Keys come from the
            // registry (intent launches); a missing key is a normal condition the
            // viewer degrades from, not an error worth logging.
            if (uriPath.startsWith(PLAYLIST_PREFIX)) {
                val key = uriPath.removePrefix(PLAYLIST_PREFIX).removeSuffix(".json")
                val json = PlaylistRegistry.get(key) ?: servePlaylistFallback(key)
                return if (json != null) {
                    newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                        addHeader("Cache-Control", "no-cache")
                    }
                } else {
                    notFound()
                }
            }

            if (uriPath.split('/').any { it.startsWith(".") }) {
                return forbidden()
            }

            val requested = File(baseDir, uriPath).canonicalFile
            if (
                requested != baseCanonical &&
                !requested.path.startsWith(baseCanonical.path + File.separator)
            ) {
                return forbidden()
            } else if (requested.isDirectory) {
                val index = File(requested, "index.html")
                val indexHtm = File(requested, "index.htm")
                return when {
                    index.exists() -> serveFile(session, index)
                    indexHtm.exists() -> serveFile(session, indexHtm)
                    else -> forbidden()
                }
            } else if (requested.exists()) {
                return serveFile(session, requested)
            } else {
                return notFound()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "serve failed", t)
            internalError(t)
        }
    }

    private fun serveDynamicIndex(): Response {
        val html = HubIndexServer.generateIndexHtml(appContext)
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveHealth(): Response {
        // Counts come from the TTL-cached snapshot — health polls never rescan.
        val json = runCatching {
            val snapshot = MediaLibraryIndex.snapshot()
            val toursCount = TourStorage.sharedToursDir()
                .listFiles()?.count { it.isDirectory && !it.name.startsWith(".") } ?: 0
            """{"status":"ok","toursCount":$toursCount,"mediaCount":${snapshot.mediaCount},""" +
                """"videosCount":${snapshot.videos.size},"imagesCount":${snapshot.images.size},""" +
                """"modelsCount":${snapshot.models.size},""" +
                """"storageGranted":${snapshot.storageGranted},""" +
                """"version":"${com.igalia.wolvic.BuildConfig.VERSION_NAME}"}"""
        }.getOrDefault(HEALTH_JSON)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
            addHeader("Cache-Control", "no-cache")
        }
    }

    private fun serveLaunch(session: IHTTPSession): Response {
        val target = session.parameters["target"]?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return badRequest("Missing target")
        val safeTarget = target.takeIf { !it.contains("://") && !it.startsWith("/") }
            ?: return badRequest("Invalid target")
        val ts = session.parameters["ts"]?.firstOrNull() ?: System.currentTimeMillis().toString()
        val encodedTarget = URLEncoder.encode(safeTarget, Charsets.UTF_8.name()).replace("+", "%20")
        val url = "${baseUrl()}/$encodedTarget?ts=$ts"
        val html = """
            <!DOCTYPE html>
            <html><head>
            <meta charset="utf-8"/>
            <meta http-equiv="refresh" content="0; url=$url" />
            <title>Framatome VR</title>
            <script>window.location.replace("$url");</script>
            </head>
            <body style="font-family:sans-serif;background:#081E3F;color:white;display:flex;align-items:center;justify-content:center;height:100vh;">
            <div>Launching Framatome VR…</div>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveFile(session: IHTTPSession, file: File): Response {
        val mime = URLConnection.guessContentTypeFromName(file.name) ?: defaultMime(file.name)

        if (mime == "text/html") {
            return serveHtmlWithVROverride(file)
        }

        val fileLength = file.length()
        val rangeHeader = session.headers["range"]
        if (rangeHeader != null) {
            val range = parseRange(rangeHeader, fileLength)
                ?: return newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE,
                    "text/plain",
                    "invalid range"
                ).apply {
                    addHeader("Content-Range", "bytes */$fileLength")
                }

            // RandomAccessFile.seek is an explicit O(1) lseek; route the body
            // through the channel so closing the response stream (NanoHTTPD does
            // this even on client disconnect) closes the underlying file. Any
            // failure before handoff closes the RAF so it can't leak.
            val raf = RandomAccessFile(file, "r")
            val stream = try {
                raf.seek(range.first)
                BufferedInputStream(java.nio.channels.Channels.newInputStream(raf.channel))
            } catch (t: Throwable) {
                runCatching { raf.close() }
                throw t
            }
            return newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mime,
                stream,
                range.last - range.first + 1
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Range", "bytes ${range.first}-${range.last}/$fileLength")
            }
        }

        val fis = FileInputStream(file)
        val len = min(fileLength, Int.MAX_VALUE.toLong())
        return newFixedLengthResponse(Response.Status.OK, mime, fis, len).apply {
            addHeader("Accept-Ranges", "bytes")
        }
    }

    private fun serveHtmlWithVROverride(file: File): Response {
        val tourRoot = file.parentFile ?: baseDir
        val tourUuid = TourHtmlInjector.findTourGogglesUuid(
            tourRoot = tourRoot,
            cache = gogglesUuidCache,
            logInfo = { message -> Log.i(TAG, message) },
            logWarn = { message, ex -> Log.w(TAG, message, ex) }
        )
        val html = TourHtmlInjector.injectSkinOverrides(file.readText(Charsets.UTF_8), tourUuid)
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveAsset(assetPath: String): Response {
        return try {
            // Bundled assets (viewer.js, the model viewer + ~1.9MB three.js
            // vendor, CSS, wasm) are immutable for the process lifetime, so cache
            // the bytes once instead of re-reading + re-allocating per request.
            val bytes = assetCache[assetPath] ?: run {
                val read = appContext.assets.open(assetPath).use { it.readBytes() }
                cacheAsset(assetPath, read)
                read
            }
            val mime = URLConnection.guessContentTypeFromName(assetPath) ?: defaultMime(assetPath)
            newFixedLengthResponse(
                Response.Status.OK,
                mime,
                java.io.ByteArrayInputStream(bytes),
                bytes.size.toLong()
            ).apply { addHeader("Cache-Control", "no-cache") }
        } catch (e: java.io.FileNotFoundException) {
            notFound()
        }
    }

    private fun cacheAsset(path: String, bytes: ByteArray) {
        // Skip pathologically large assets; bound the total cache footprint.
        if (bytes.size > ASSET_CACHE_MAX_BYTES) return
        synchronized(assetCache) {
            assetCache[path] = bytes
            var total = assetCache.values.sumOf { it.size.toLong() }
            val it = assetCache.entries.iterator()
            while (total > ASSET_CACHE_MAX_BYTES && it.hasNext()) {
                val e = it.next()
                total -= e.value.size.toLong()
                it.remove()
            }
        }
    }

    private fun serveMediaFile(session: IHTTPSession, relativePath: String): Response {
        val rel = relativePath.trim().trimStart('/')
        if (rel.isEmpty() || rel.split('/').any { it == ".." }) {
            return forbidden()
        }
        val requested = File(mediaRootCanonical, rel).canonicalFile
        if (
            requested != mediaRootCanonical &&
            !requested.path.startsWith(mediaRootCanonical.path + File.separator)
        ) {
            return forbidden()
        }
        if (!requested.isFile) {
            return notFound()
        }
        return serveFile(session, requested)
    }

    /** Hook for computed playlists (e.g. the hub's section playlists). */
    private fun servePlaylistFallback(key: String): String? {
        return HubPlaylists.jsonFor(appContext, key)
    }

    private fun serveRawResource(resId: Int, mime: String): Response {
        val stream = appContext.resources.openRawResource(resId)
        val bytes = stream.readBytes()
        stream.close()
        return newFixedLengthResponse(
            Response.Status.OK,
            mime,
            java.io.ByteArrayInputStream(bytes),
            bytes.size.toLong()
        )
    }

    private fun forbidden(): Response = newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "forbidden")

    private fun notFound(): Response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")

    private fun internalError(t: Throwable): Response = newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        "text/plain",
        t.message ?: "internal error"
    )

    private fun badRequest(message: String): Response = newFixedLengthResponse(
        Response.Status.BAD_REQUEST,
        "text/plain",
        message
    )

    private fun defaultMime(name: String): String = when {
        name.endsWith(".js", ignoreCase = true) -> "application/javascript"
        name.endsWith(".css", ignoreCase = true) -> "text/css"
        name.endsWith(".json", ignoreCase = true) -> "application/json"
        name.endsWith(".xml", ignoreCase = true) -> "application/xml"
        name.endsWith(".wasm", ignoreCase = true) -> "application/wasm"
        name.endsWith(".data", ignoreCase = true) -> "application/octet-stream"
        name.endsWith(".bin", ignoreCase = true) -> "application/octet-stream"
        name.endsWith(".ktx2", ignoreCase = true) -> "image/ktx2"
        name.endsWith(".basis", ignoreCase = true) -> "application/octet-stream"
        name.endsWith(".png", ignoreCase = true) -> "image/png"
        name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        name.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
        name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        name.endsWith(".webm", ignoreCase = true) -> "video/webm"
        name.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
        name.endsWith(".wav", ignoreCase = true) -> "audio/wav"
        name.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
        name.endsWith(".woff", ignoreCase = true) -> "font/woff"
        name.endsWith(".woff2", ignoreCase = true) -> "font/woff2"
        name.endsWith(".htm", ignoreCase = true) || name.endsWith(".html", ignoreCase = true) -> "text/html"
        else -> "application/octet-stream"
    }

    private fun parseRange(header: String, fileLength: Long): LongRange? {
        if (!header.startsWith("bytes=") || fileLength <= 0L) {
            return null
        }

        val value = header.removePrefix("bytes=").substringBefore(',')
        val (startRaw, endRaw) = value.split('-', limit = 2).let {
            if (it.size != 2) return null
            it[0] to it[1]
        }

        val start = when {
            startRaw.isBlank() -> {
                val suffixLength = endRaw.toLongOrNull() ?: return null
                (fileLength - suffixLength).coerceAtLeast(0L)
            }
            else -> startRaw.toLongOrNull() ?: return null
        }
        val end = when {
            endRaw.isBlank() -> fileLength - 1
            startRaw.isBlank() -> fileLength - 1
            else -> endRaw.toLongOrNull() ?: return null
        }

        if (start < 0L || end < start || start >= fileLength) {
            return null
        }

        return start..min(end, fileLength - 1)
    }

    companion object {
        private const val TAG = "FramatomeVRServer"
        private const val HOST = "127.0.0.1"
        const val PORT: Int = 18080
        private const val HEALTH_JSON = """{"status":"ok"}"""
        // Total bundled-asset cache cap (three.js vendor + viewer/model JS+CSS ≈ 2.5MB).
        private const val ASSET_CACHE_MAX_BYTES = 24L * 1024 * 1024

        // Route prefixes (no leading slash, matched after removePrefix("/")).
        private const val VIEWER_PREFIX = "__viewer__/"
        private const val VIEWER_ASSET_DIR = "media-viewer"
        private val MEDIA_PREFIX = ImmersiveIntentFactory.MEDIA_ROUTE_PREFIX.removePrefix("/")
        private val PLAYLIST_PREFIX = ImmersiveIntentFactory.PLAYLIST_ROUTE_PREFIX.removePrefix("/")
        private val THUMBS_PREFIX = ImmersiveIntentFactory.THUMBS_ROUTE_PREFIX.removePrefix("/")
        private var server: LocalWebServer? = null
        private val started = AtomicBoolean(false)

        fun ensureRunning(context: Context) {
            if (started.get()) return
            synchronized(this) {
                if (started.get()) return
                val appCtx = context.applicationContext
                val dir = sharedToursDir()
                // Never let a bind/permission failure propagate: this is invoked
                // from Application.onCreate, so an uncaught exception here would
                // crash the whole player before any UI. On failure we leave
                // `started` false so the next tour launch retries cleanly.
                try {
                    if (!dir.exists()) dir.mkdirs()
                    // Standalone hub: make sure the media library root exists too.
                    // Data/ stays launcher-owned (its ingest creates and manages it).
                    File(dir.parentFile, "Library").takeIf { !it.exists() }?.mkdirs()
                    Log.d(TAG, "ensureRunning baseDir=${dir.absolutePath}")
                    val srv = LocalWebServer(dir, appCtx)
                    srv.start(SOCKET_READ_TIMEOUT, false)
                    server = srv
                    started.set(true)
                    Log.i(TAG, "Server started on ${baseUrl()} serving ${dir.absolutePath}")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to start local web server on ${baseUrl()}", t)
                    runCatching { server?.stop() }
                    server = null
                    started.set(false)
                }
            }
        }

        /** True once the local web server is bound and serving. */
        fun isRunning(): Boolean = started.get()

        fun buildTourUrl(folderName: String, entryRelative: String): String {
            val entry = entryRelative.trim().trimStart('/').ifBlank { "index.html" }
            val encodedFolder = URLEncoder.encode(folderName, Charsets.UTF_8.name()).replace("+", "%20")
            val encodedEntry = entry.split('/').joinToString("/") { segment ->
                URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
            }
            return com.framatome.vr.content.ImmersiveIntentFactory.appendVrAutoStartParam(
                "${baseUrl()}/$encodedFolder/$encodedEntry"
            )
        }

        private fun sharedToursDir(): File = TourStorage.sharedToursDir()

        fun stop() {
            synchronized(this) {
                server?.stop()
                server = null
                started.set(false)
            }
        }

        fun baseUrl(): String = "http://$HOST:$PORT"
    }
}
