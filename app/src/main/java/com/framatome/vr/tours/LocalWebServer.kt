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
import org.json.JSONObject
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
                if (isTrustedHubOrigin(session)) return forbidden()
                return serveLaunch(session)
            }

            if (pathOnly == "/health") {
                return serveHealth()
            }

            if (pathOnly == "/__rescan__") {
                if (!isTrustedHubOrigin(session)) return forbidden()
                return serveContentRefresh(session)
            }

            if (pathOnly.startsWith("/__operator__/")) {
                if (!isTrustedHubOrigin(session)) return forbidden()
                return serveOperatorSettings(session, pathOnly)
            }

            val uriPath = pathOnly.removePrefix("/")
            if (uriPath.isEmpty() || uriPath == "index.html") {
                if (!isTrustedHubOrigin(session)) return redirectToHub()
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
                if (isTrustedHubOrigin(session)) return redirectToContent(uriPath)
                val assetRel = uriPath.removePrefix(VIEWER_PREFIX).ifBlank { "index.html" }
                if (assetRel.split('/').any { it == ".." }) return forbidden()
                return serveAsset("$VIEWER_ASSET_DIR/$assetRel")
            }

            // Raw media files streamed to the viewer from the shared storage tree.
            if (uriPath.startsWith(MEDIA_PREFIX)) {
                if (isTrustedHubOrigin(session)) return redirectToContent(uriPath)
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
                if (isTrustedHubOrigin(session)) return redirectToContent(uriPath)
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

            // The localhost origin is reserved for the trusted generated hub.
            // Delivered tour HTML/JS is only served on 127.0.0.1 so it cannot
            // become same-origin with operator maintenance APIs.
            if (isTrustedHubOrigin(session)) {
                return redirectToContent(uriPath)
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
        return newFixedLengthResponse(Response.Status.OK, "text/html", html).apply {
            HubRequestPolicy.hubResponseHeaders.forEach { (name, value) ->
                addHeader(name, value)
            }
        }
    }

    private fun redirectToHub(): Response =
        newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "Opening Framatome VR…").apply {
            addHeader("Location", hubBaseUrl())
            addHeader("Cache-Control", "no-store")
        }

    private fun redirectToContent(uriPath: String): Response {
        val encodedPath = uriPath
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
            }
        return newFixedLengthResponse(
            Response.Status.REDIRECT,
            "text/plain",
            "Opening content…"
        ).apply {
            addHeader("Location", "${baseUrl()}/$encodedPath")
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun serveContentRefresh(session: IHTTPSession): Response {
        val status = when (session.method) {
            Method.POST -> {
                if (!HubRequestPolicy.canMutateOperatorState(
                        session.headers["host"],
                        session.headers[HubRequestPolicy.REQUEST_HEADER]
                    )) {
                    return forbidden()
                }
                FramatomeInitializer.startContentRefresh()
            }
            Method.GET -> FramatomeInitializer.contentRefreshStatus()
            else -> return badRequest("Use GET or POST for content refresh")
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            status.toJson()
        ).apply {
            addHeader("Cache-Control", "no-store, no-cache, max-age=0")
            addHeader("Pragma", "no-cache")
        }
    }

    private fun serveOperatorSettings(session: IHTTPSession, path: String): Response {
        if (path == "/__operator__/status") {
            if (session.method != Method.GET) {
                return badRequest("Use GET for operator status")
            }
            return jsonResponse(HubOperatorSettings.statusJson(appContext))
        }
        if (session.method != Method.POST) {
            return badRequest("Use POST for operator actions")
        }
        if (!HubRequestPolicy.canMutateOperatorState(
                session.headers["host"],
                session.headers[HubRequestPolicy.REQUEST_HEADER]
            )) {
            return forbidden()
        }

        val result = when (path) {
            "/__operator__/storage" -> HubOperatorSettings.openStorageSettings(appContext)
            "/__operator__/previews" -> HubOperatorSettings.clearPreviews(appContext)
            "/__operator__/support" -> HubOperatorSettings.createSupportBundle(appContext)
            "/__operator__/tour" -> HubOperatorSettings.setTourEnabled(
                context = appContext,
                relativePath = session.parameters["path"]?.firstOrNull(),
                enabled = when (session.parameters["enabled"]?.firstOrNull()) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            )
            else -> return notFound()
        }
        return jsonResponse(
            body = result.toJson(),
            status = if (result.success) Response.Status.OK else Response.Status.BAD_REQUEST
        )
    }

    private fun serveHealth(): Response {
        val result = runCatching {
            val status = HubIndexServer.contentStatus(appContext)
            val servicesReady = FramatomeInitializer.contentServicesReady()
            val serviceError = FramatomeInitializer.contentServicesError()
            val scanError = MediaLibraryIndex.lastScanError()
            val healthy = status.storageGranted && servicesReady && scanError == null
            val json = JSONObject().apply {
                put("status", if (healthy) "ok" else "degraded")
                put("toursCount", status.toursCount)
                put("mediaCount", status.mediaCount)
                put("videosCount", status.videosCount)
                put("imagesCount", status.imagesCount)
                put("modelsCount", status.modelsCount)
                put("cloudsCount", status.cloudsCount)
                put("storageGranted", status.storageGranted)
                put("contentServicesReady", servicesReady)
                serviceError?.let { put("contentServiceError", it) }
                scanError?.let { put("libraryScanError", it) }
                put("revision", status.revision)
                put("version", com.igalia.wolvic.BuildConfig.VERSION_NAME)
            }.toString()
            Pair(json, healthy)
        }.getOrElse { error ->
            Pair(
                JSONObject()
                    .put("status", "error")
                    .put("message", error.message ?: "Health check failed")
                    .toString(),
                false
            )
        }
        return newFixedLengthResponse(
            if (result.second) Response.Status.OK else Response.Status.SERVICE_UNAVAILABLE,
            "application/json",
            result.first
        ).apply {
            addHeader("Cache-Control", "no-store, no-cache, max-age=0")
        }
    }

    private fun serveLaunch(session: IHTTPSession): Response {
        val target = session.parameters["target"]?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return badRequest("Missing target")
        val safeTarget = target.takeIf { !it.contains("://") && !it.startsWith("/") }
            ?: return badRequest("Invalid target")
        val ts = session.parameters["ts"]
            ?.firstOrNull()
            ?.takeIf { it.matches(SAFE_TIMESTAMP) }
            ?: System.currentTimeMillis().toString()
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
            // The lookup MUST hold the same lock as writers: with accessOrder=true
            // a LinkedHashMap get() is a structural mutation (it relinks the LRU
            // list), and GeckoView fetches a page's modules on parallel request
            // threads — unsynchronized gets can corrupt the map.
            val bytes = synchronized(assetCache) { assetCache[assetPath] } ?: run {
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

    /**
     * A branded page with a way back, rather than a white screen reading
     * "forbidden". These are reached in a headset, where a browser's usual
     * escapes do not exist: without the Back button the operator is stuck.
     * The code is here so it can be read out to support.
     */
    private fun errorPage(status: Response.Status, code: String, title: String, detail: String): Response =
        newFixedLengthResponse(status, "text/html; charset=utf-8", """
<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title><style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#0B1730;color:#fff;font-family:'Segoe UI',Roboto,system-ui,sans-serif;text-align:center;padding:8vh 6vw}
.card{max-width:640px}h1{font-size:30px;margin:0 0 14px}p{color:#B8C7DC;font-size:17px;line-height:1.5;margin:0 0 10px}
.code{display:inline-block;margin:18px 0 24px;padding:6px 14px;border-radius:999px;border:1px solid rgba(255,255,255,.24);
color:#F04E23;font-weight:700;letter-spacing:.08em;font-size:14px}
a{display:inline-block;padding:15px 30px;border-radius:999px;background:#F04E23;color:#fff;
text-decoration:none;font-weight:700;font-size:17px}
</style></head><body><div class="card">
<h1>${escapeHtml(title)}</h1>
<p>${escapeHtml(detail)}</p>
<div class="code">Reference $code</div><div>
<a href="${hubBaseUrl()}/">Back to Library</a>
</div></div></body></html>
        """.trimIndent())

    private fun forbidden(): Response = errorPage(
        Response.Status.FORBIDDEN,
        "FVR-403",
        "This content cannot be opened here",
        "The item asked for something the Player does not allow. Return to the " +
            "library and open it from there."
    )

    private fun notFound(): Response = errorPage(
        Response.Status.NOT_FOUND,
        "FVR-404",
        "This content is not on the headset",
        "The file may still be copying, or the package may be incomplete. " +
            "Check for new content in Settings, then try again."
    )

    private fun internalError(t: Throwable): Response {
        // The exception text used to be the page body. It belongs in the log,
        // not in front of a customer.
        Log.w(TAG, "Request failed", t)
        return errorPage(
            Response.Status.INTERNAL_ERROR,
            "FVR-500",
            "This content could not be opened",
            "Something went wrong while opening it. Create a support report in " +
                "Settings if this keeps happening."
        )
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun badRequest(message: String): Response = newFixedLengthResponse(
        Response.Status.BAD_REQUEST,
        "text/plain",
        message
    )

    private fun jsonResponse(
        body: String,
        status: Response.Status = Response.Status.OK
    ): Response = newFixedLengthResponse(status, "application/json", body).apply {
        addHeader("Cache-Control", "no-store, no-cache, max-age=0")
        addHeader("Pragma", "no-cache")
    }

    private fun isTrustedHubOrigin(session: IHTTPSession): Boolean {
        return HubRequestPolicy.isTrustedHubHost(session.headers["host"])
    }

    private fun defaultMime(name: String): String = when {
        name.endsWith(".js", ignoreCase = true) -> "application/javascript"
        name.endsWith(".css", ignoreCase = true) -> "text/css"
        // Gecko rejects <track> sources that are not served as text/vtt.
        name.endsWith(".vtt", ignoreCase = true) -> "text/vtt"
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
        private val SAFE_TIMESTAMP = Regex("^[0-9]{1,20}$")
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
                    // Standalone hub: make sure the Player's media library root exists too.
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

        fun hubBaseUrl(): String = "http://${HubRequestPolicy.HUB_HOST}:$PORT"
    }
}
