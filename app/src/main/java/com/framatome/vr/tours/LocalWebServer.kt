package com.framatome.vr.tours

import android.content.Context
import android.util.Log
import com.igalia.wolvic.R
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.text.Charsets

class LocalWebServer private constructor(
    private val baseDir: File,
    private val appContext: Context
) : NanoHTTPD(HOST, PORT) {
    private val baseCanonical: File = baseDir.canonicalFile

    override fun serve(session: IHTTPSession): Response {
        return try {
            val decodedUri = URLDecoder.decode(session.uri, Charsets.UTF_8.name())
            val pathOnly = decodedUri.substringBefore('?')
            Log.d(TAG, "serve ${session.method} $decodedUri from ${session.remoteIpAddress}")

            if (pathOnly.removePrefix("/") == "launch") {
                return serveLaunch(session)
            }

            val uriPath = pathOnly.removePrefix("/")
            if (uriPath.isEmpty() || uriPath == "index.html") {
                return serveDynamicIndex()
            }

            if (uriPath == "__assets__/vr-goggles.png") {
                return serveRawResource(R.raw.framatome_vr_goggles, "image/png")
            }

            if (uriPath == "__assets__/app-icon.png") {
                return serveRawResource(R.raw.framatome_app_icon, "image/png")
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
        val html = TourIndexServer.generateIndexHtml(appContext)
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
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
            <body style="font-family:sans-serif;background:#0a1f44;color:white;display:flex;align-items:center;justify-content:center;height:100vh;">
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

            val fis = FileInputStream(file).apply { skipFully(range.first) }
            return newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mime,
                fis,
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
        var html = file.readText(Charsets.UTF_8)
        val vrInjection = """
<style>
#framatome-vr-btn{
  position:fixed;bottom:28px;right:28px;z-index:999999;
  width:120px;height:120px;
  background:radial-gradient(ellipse at 50% 40%,rgba(10,31,68,.92),rgba(5,15,35,.96));
  border:2px solid rgba(255,95,5,.35);border-radius:28px;
  cursor:pointer;display:none;flex-direction:column;align-items:center;justify-content:center;gap:6px;
  padding:10px;
  box-shadow:0 8px 40px rgba(255,95,5,.35),0 0 80px rgba(255,95,5,.12),inset 0 1px 0 rgba(255,255,255,.08);
  transition:transform .15s ease,box-shadow .15s ease;
  animation:framatomeVRPulse 3s ease-in-out infinite;
}
#framatome-vr-btn:hover{
  transform:scale(1.08);
  box-shadow:0 10px 50px rgba(255,95,5,.5),0 0 100px rgba(255,95,5,.2),inset 0 1px 0 rgba(255,255,255,.12);
}
#framatome-vr-btn:active{transform:scale(.95)}
#framatome-vr-btn.fvr-hidden{display:none !important}
#framatome-vr-btn img{width:72px;height:72px;object-fit:contain;pointer-events:none;filter:drop-shadow(0 2px 8px rgba(255,95,5,.3))}
#framatome-vr-btn span{
  color:white;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
  font-size:13px;font-weight:700;letter-spacing:.8px;text-transform:uppercase;
  text-shadow:0 1px 4px rgba(0,0,0,.5);pointer-events:none;
}
@keyframes framatomeVRPulse{
  0%,100%{box-shadow:0 8px 40px rgba(255,95,5,.35),0 0 80px rgba(255,95,5,.12),inset 0 1px 0 rgba(255,255,255,.08)}
  50%{box-shadow:0 8px 40px rgba(255,95,5,.55),0 0 100px rgba(255,95,5,.22),inset 0 1px 0 rgba(255,255,255,.12)}
}
#framatome-vr-home{
  position:fixed;bottom:32px;left:32px;z-index:999999;
  width:56px;height:56px;border-radius:16px;
  background:rgba(0,28,61,.85);border:1px solid rgba(230,237,245,.15);
  color:rgba(230,237,245,.8);cursor:pointer;
  display:flex;align-items:center;justify-content:center;
  backdrop-filter:blur(8px);transition:all .15s;
}
#framatome-vr-home:hover{background:rgba(0,59,112,.9);border-color:rgba(255,95,5,.3)}
</style>
<script>
(function(){
  var vrBtn, homeBtn, xrSession = null;

  function createUI(){
    vrBtn = document.createElement('button');
    vrBtn.id = 'framatome-vr-btn';
    var img = document.createElement('img');
    img.src = '/__assets__/vr-goggles.png';
    img.alt = 'Enter VR';
    var lbl = document.createElement('span');
    lbl.textContent = 'Enter VR';
    vrBtn.appendChild(img);
    vrBtn.appendChild(lbl);
    vrBtn.onclick = enterVR;
    document.body.appendChild(vrBtn);

    homeBtn = document.createElement('button');
    homeBtn.id = 'framatome-vr-home';
    homeBtn.title = 'Back to Tour Menu';
    homeBtn.innerHTML = '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>';
    homeBtn.onclick = function(){ window.location.href = '/'; };
    document.body.appendChild(homeBtn);
  }

  function checkVR(){
    if(!navigator.xr) return;
    navigator.xr.isSessionSupported('immersive-vr').then(function(ok){
      if(ok && vrBtn){
        vrBtn.style.display = 'flex';
        document.documentElement.setAttribute('data-framatome-vr','ready');
      }
    });
  }

  function enterVR(){
    if(!navigator.xr || xrSession) return;
    navigator.xr.requestSession('immersive-vr').then(function(session){
      xrSession = session;
      vrBtn.classList.add('fvr-hidden');
      session.addEventListener('end', function(){
        xrSession = null;
        vrBtn.classList.remove('fvr-hidden');
      });
      var existing = document.querySelector('canvas');
      if(existing && existing.getContext){
        var gl = existing.getContext('webgl2') || existing.getContext('webgl');
        if(gl) session.updateRenderState({baseLayer: new XRWebGLLayer(session, gl)});
      }
    }).catch(function(e){ console.warn('Framatome VR session request failed:', e); });
  }

  if(document.readyState === 'loading'){
    document.addEventListener('DOMContentLoaded', function(){ createUI(); checkVR(); });
  } else {
    createUI(); checkVR();
  }
})();
</script>"""
        val insertPoint = html.indexOf("</head>", ignoreCase = true)
        html = if (insertPoint >= 0) {
            html.substring(0, insertPoint) + vrInjection + html.substring(insertPoint)
        } else {
            vrInjection + html
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
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
        private var server: LocalWebServer? = null
        private val started = AtomicBoolean(false)

        fun ensureRunning(context: Context) {
            if (started.get()) return
            synchronized(this) {
                if (started.get()) return
                val appCtx = context.applicationContext
                val dir = TourStorage.appStorageRoot(appCtx)
                if (!dir.exists()) dir.mkdirs()
                Log.d(TAG, "ensureRunning baseDir=${dir.absolutePath}")
                val srv = LocalWebServer(dir, appCtx)
                srv.start(SOCKET_READ_TIMEOUT, false)
                server = srv
                started.set(true)
                Log.i(TAG, "Server started on ${baseUrl()} serving ${dir.absolutePath}")
            }
        }

        fun buildTourUrl(relativePath: String, entryFileName: String): String {
            val targetPath = (relativePath.trimEnd('/') + "/" + entryFileName).trimStart('/')
            val encodedPath = URLEncoder.encode(targetPath, Charsets.UTF_8.name()).replace("+", "%20")
            return "${baseUrl()}/$encodedPath"
        }

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

private fun FileInputStream.skipFully(bytesToSkip: Long) {
    var remaining = bytesToSkip
    while (remaining > 0L) {
        val skipped = skip(remaining)
        if (skipped <= 0L) {
            if (read() == -1) {
                break
            }
            remaining--
        } else {
            remaining -= skipped
        }
    }
}
