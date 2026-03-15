package com.framatome.vr.tours

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.text.Charsets

class LocalWebServer private constructor(private val baseDir: File) : NanoHTTPD(HOST, PORT) {
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
            val requested = File(baseDir, uriPath).canonicalFile
            if (!requested.path.startsWith(baseCanonical.path)) {
                return forbidden()
            } else if (requested.isDirectory) {
                val index = File(requested, "index.html")
                val indexHtm = File(requested, "index.htm")
                return when {
                    index.exists() -> serveFile(index)
                    indexHtm.exists() -> serveFile(indexHtm)
                    else -> forbidden()
                }
            } else if (requested.exists()) {
                return serveFile(requested)
            } else {
                return notFound()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "serve failed", t)
            internalError(t)
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
            <body style="font-family:sans-serif;background:#0a1f44;color:white;display:flex;align-items:center;justify-content:center;height:100vh;">
            <div>Launching Framatome VR…</div>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveFile(file: File): Response {
        val mime = URLConnection.guessContentTypeFromName(file.name) ?: defaultMime(file.name)

        if (mime == "text/html") {
            return serveHtmlWithVROverride(file)
        }

        val fis = FileInputStream(file)
        val len = min(file.length(), Int.MAX_VALUE.toLong())
        return newFixedLengthResponse(Response.Status.OK, mime, fis, len)
    }

    private fun serveHtmlWithVROverride(file: File): Response {
        var html = file.readText(Charsets.UTF_8)
        val vrOverrideScript = """
<script>
(function(){
  var patched=false;
  function patchVR(){
    if(patched)return;
    var sels=['.vr-button','#vr-button','.enter-vr','.vr_button',
      '.viewer-vr-btn','.toolbar-vr','[data-action="vr"]',
      'button[title*="VR"]','div[title*="VR"]',
      '[class*="cardboard"]','[class*="gyro"]'];
    for(var i=0;i<sels.length;i++){
      var els=document.querySelectorAll(sels[i]);
      for(var j=0;j<els.length;j++){
        els[j].addEventListener('click',function(e){
          e.stopPropagation();e.stopImmediatePropagation();e.preventDefault();
          if(navigator.xr){
            navigator.xr.requestSession('immersive-vr').catch(function(){
              window.location.href='framatome-vr://enter-vr';
            });
          } else {
            window.location.href='framatome-vr://enter-vr';
          }
        },true);
        patched=true;
      }
    }
    var all=document.querySelectorAll('button,div,a,span,img,svg,i');
    for(var k=0;k<all.length;k++){
      var el=all[k];
      var cn=(el.className||'').toString().toLowerCase();
      var ti=(el.title||'').toLowerCase();
      var ar=(el.getAttribute('aria-label')||'').toLowerCase();
      if(cn.indexOf('vr')!==-1||ti.indexOf('vr')!==-1||ar.indexOf('vr')!==-1){
        el.addEventListener('click',function(e){
          e.stopPropagation();e.stopImmediatePropagation();e.preventDefault();
          if(navigator.xr){
            navigator.xr.requestSession('immersive-vr').catch(function(){
              window.location.href='framatome-vr://enter-vr';
            });
          } else {
            window.location.href='framatome-vr://enter-vr';
          }
        },true);
        patched=true;
      }
    }
  }
  document.addEventListener('DOMContentLoaded',function(){setTimeout(patchVR,1000);});
  window.addEventListener('load',function(){setTimeout(patchVR,2000);});
})();
</script>"""
        val insertPoint = html.indexOf("</head>", ignoreCase = true)
        html = if (insertPoint >= 0) {
            html.substring(0, insertPoint) + vrOverrideScript + html.substring(insertPoint)
        } else {
            vrOverrideScript + html
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun forbidden(): Response = newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "forbidden")
    private fun notFound(): Response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
    private fun internalError(t: Throwable): Response = newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR, "text/plain", t.message ?: "internal error"
    )
    private fun badRequest(message: String): Response = newFixedLengthResponse(
        Response.Status.BAD_REQUEST, "text/plain", message
    )

    private fun defaultMime(name: String): String = when {
        name.endsWith(".js", ignoreCase = true) -> "application/javascript"
        name.endsWith(".css", ignoreCase = true) -> "text/css"
        name.endsWith(".json", ignoreCase = true) -> "application/json"
        name.endsWith(".wasm", ignoreCase = true) -> "application/wasm"
        name.endsWith(".data", ignoreCase = true) -> "application/octet-stream"
        name.endsWith(".png", ignoreCase = true) -> "image/png"
        name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        name.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
        name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        name.endsWith(".htm", ignoreCase = true) || name.endsWith(".html", ignoreCase = true) -> "text/html"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "FramatomeVRServer"
        private const val HOST = "localhost"
        const val PORT: Int = 18080
        private var server: LocalWebServer? = null
        private val started = AtomicBoolean(false)

        fun ensureRunning(context: Context) {
            if (started.get()) return
            synchronized(this) {
                if (started.get()) return
                val dir = File(context.getExternalFilesDir(null), "tours")
                if (!dir.exists()) dir.mkdirs()
                Log.d(TAG, "ensureRunning baseDir=${dir.absolutePath}")
                val srv = LocalWebServer(dir)
                srv.start(SOCKET_READ_TIMEOUT, false)
                server = srv
                started.set(true)
                Log.i(TAG, "Server started on ${baseUrl()} serving ${dir.absolutePath}")
            }
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
