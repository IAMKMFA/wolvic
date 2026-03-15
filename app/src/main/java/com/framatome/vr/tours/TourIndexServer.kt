package com.framatome.vr.tours

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Generates an index.html for the tour list that the Wolvic browser displays
 * as its home page. When a user taps a tour, it navigates to the tour's
 * localhost URL, which the VR engine renders. This replaces the Compose-based
 * tour list since we're inside Wolvic's VR browser context.
 */
object TourIndexServer {
    private const val TAG = "TourIndexServer"

    fun generateIndexHtml(context: Context): String {
        val toursDir = File(context.getExternalFilesDir(null), "tours")
        if (!toursDir.exists()) toursDir.mkdirs()

        val tours = toursDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.mapNotNull { dir ->
                val entry = when {
                    File(dir, "index.html").exists() -> "index.html"
                    File(dir, "index.htm").exists() -> "index.htm"
                    else -> null
                }
                entry?.let { dir.name to it }
            }
            ?.sortedBy { it.first }
            ?: emptyList()

        Log.i(TAG, "Generating index with ${tours.size} tours")

        val tourCards = if (tours.isEmpty()) {
            """
            <div class="empty-state">
                <h2>No tours detected</h2>
                <p>Push your 3DVista exports via ArborXR Files to<br>
                <code>/sdcard/FramatomeVR/Tours/</code><br>
                then reload this page.</p>
                <button onclick="window.location.reload()">Rescan</button>
            </div>
            """
        } else {
            tours.joinToString("\n") { (name, entry) ->
                val thumbExists = File(toursDir, "$name/thumbnail.jpg").exists() ||
                                  File(toursDir, "$name/thumbnail.png").exists()
                val thumbTag = if (thumbExists) {
                    val ext = if (File(toursDir, "$name/thumbnail.jpg").exists()) "jpg" else "png"
                    """<div class="card-thumb" style="background-image:url('/$name/thumbnail.$ext')"></div>"""
                } else {
                    """<div class="card-thumb no-thumb"><span>No preview</span></div>"""
                }
                """
                <div class="tour-card" onclick="window.location.href='/$name/$entry'">
                    $thumbTag
                    <div class="card-body">
                        <h3>$name</h3>
                        <p class="card-meta">Launch locally (VR ready)</p>
                    </div>
                </div>
                """
            }
        }

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Framatome VR</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
  background:#001C3D;color:#E6EDF5;min-height:100vh;padding:40px 32px}
h1{font-size:36px;font-weight:800;margin-bottom:4px}
.subtitle{font-size:18px;color:rgba(230,237,245,.75);margin-bottom:32px}
.hero{background:linear-gradient(135deg,rgba(0,59,112,.95),#01162B);
  border-radius:24px;padding:32px;margin-bottom:40px}
.hero h2{font-size:28px;font-weight:700;margin-bottom:4px}
.hero .sub{font-size:16px;color:rgba(230,237,245,.85)}
.hero .stats{margin-top:20px;display:flex;align-items:center;gap:16px}
.hero .play-badge{background:rgba(255,95,5,.2);border-radius:50%;
  width:48px;height:48px;display:flex;align-items:center;justify-content:center;
  font-size:24px;color:#FF5F05;font-weight:bold}
.section-header{display:flex;justify-content:space-between;align-items:center;
  margin-bottom:16px}
.section-header h2{font-size:22px;font-weight:600}
.section-header button{background:rgba(255,95,5,.15);border:1px solid rgba(255,95,5,.3);
  color:#FF5F05;padding:10px 20px;border-radius:12px;cursor:pointer;
  font-size:16px;font-weight:500}
.section-header button:hover{background:rgba(255,95,5,.25)}
hr{border:none;border-top:1px solid rgba(230,237,245,.15);margin-bottom:24px}
.tour-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:24px}
.tour-card{background:rgba(10,47,84,.85);border-radius:20px;overflow:hidden;
  cursor:pointer;transition:transform .15s,box-shadow .15s;box-shadow:0 2px 8px rgba(0,0,0,.3)}
.tour-card:hover{transform:translateY(-4px);box-shadow:0 8px 24px rgba(0,0,0,.5)}
.card-thumb{height:180px;background-size:cover;background-position:center;
  position:relative}
.card-thumb.no-thumb{background:linear-gradient(180deg,rgba(10,47,84,1),rgba(0,28,61,1));
  display:flex;align-items:center;justify-content:center}
.card-thumb.no-thumb span{color:#8FA1B8;font-size:16px}
.card-body{padding:20px}
.card-body h3{font-size:20px;font-weight:600;margin-bottom:6px}
.card-meta{font-size:14px;color:rgba(230,237,245,.65)}
.empty-state{text-align:center;padding:60px;background:rgba(10,47,84,.3);
  border-radius:24px}
.empty-state h2{font-size:22px;margin-bottom:12px}
.empty-state p{color:rgba(230,237,245,.75);line-height:1.6;margin-bottom:20px}
.empty-state code{background:rgba(0,0,0,.3);padding:4px 8px;border-radius:6px;font-size:14px}
.empty-state button{background:#FF5F05;border:none;color:white;padding:12px 28px;
  border-radius:12px;cursor:pointer;font-size:16px;font-weight:600}
</style>
</head>
<body>
<h1>Framatome VR</h1>
<p class="subtitle">Training &bull; Walkdowns &bull; Digital Twin</p>

<div class="hero">
  <h2>Framatome Training VR</h2>
  <p class="sub">Hazard Recognition</p>
  <div class="stats">
    <div class="play-badge">&#9654;</div>
    <div>
      <strong style="font-size:20px">${tours.size} Walkdowns</strong><br>
      <span style="color:rgba(230,237,245,.65);font-size:14px">/Android/data/com.framatome.vr.pro/files/tours/</span>
    </div>
  </div>
</div>

<div class="section-header">
  <h2>Available Tours</h2>
  <button onclick="window.location.reload()">Tour Scan</button>
</div>
<hr>

<div class="tour-grid">
$tourCards
</div>

</body>
</html>
        """.trimIndent()
    }

    fun writeIndexFile(context: Context) {
        val toursDir = File(context.getExternalFilesDir(null), "tours")
        if (!toursDir.exists()) toursDir.mkdirs()
        val indexFile = File(toursDir, "index.html")
        indexFile.writeText(generateIndexHtml(context))
        Log.i(TAG, "Tour index written to ${indexFile.absolutePath}")
    }
}
