package com.framatome.vr.tours

import android.content.Context
import android.util.Log
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TourIndexServer {
    private const val TAG = "TourIndexServer"

    data class TourInfo(
        val name: String,
        val launchUrl: String,
        val thumbnailUrl: String?,
        val sizeMB: String,
        val lastModified: String
    )

    fun generateIndexHtml(context: Context): String {
        TourStorage.ensureAppDirs(context)
        val appStorageRoot = TourStorage.appStorageRoot(context)
        val settingsStore = (context.applicationContext as? android.app.Application)
            ?.let(::TourSettingsStore)
        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

        val tours = TourCatalog.scan(appStorageRoot) { relativePath ->
            settingsStore?.isEnabled(relativePath) ?: true
        }
            .filter { it.enabled }
            .map { tour ->
                val dir = File(tour.absolutePath)
                val encodedBase = encodePath(tour.relativePath)
                val thumbUrl = tour.thumbnailPath?.let { thumb ->
                    "/$encodedBase/${encodePath(File(thumb).name)}"
                }
                val sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                val sizeMB = String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0))
                val lastMod = dateFormat.format(Date(dir.lastModified()))
                val launchUrl = "/$encodedBase/${encodePath(tour.entryFileName)}"

                TourInfo(tour.name, launchUrl, thumbUrl, sizeMB, lastMod)
            }

        Log.i(TAG, "Generating index with ${tours.size} tours")

        val tourCards = if (tours.isEmpty()) {
            buildEmptyState()
        } else {
            tours.joinToString("\n") { tour -> buildTourCard(tour) }
        }

        return buildPage(tours.size, tourCards)
    }

    private fun buildTourCard(tour: TourInfo): String {
        val safeName = escapeHtml(tour.name)
        val thumbStyle = if (tour.thumbnailUrl != null) {
            "background-image:url('${tour.thumbnailUrl}')"
        } else {
            ""
        }
        val thumbClass = if (tour.thumbnailUrl != null) "card-thumb" else "card-thumb no-thumb"

        return """
        <div class="tour-card" onclick="launchTour('${tour.launchUrl}', this)">
            <div class="$thumbClass" style="$thumbStyle">
                ${if (tour.thumbnailUrl == null) """<div class="no-thumb-icon">
                    <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                        <path d="M15 10l-4 4l6 6l4-16l-18 7l4 2l2 6l3-4"/>
                    </svg>
                </div>""" else ""}
                <div class="card-badge">WebXR</div>
            </div>
            <div class="card-body">
                <h3>$safeName</h3>
                <div class="card-meta">
                    <span class="meta-item">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="20" rx="2"/><path d="M7 2v20M17 2v20M2 12h20M2 7h5M2 17h5M17 7h5M17 17h5"/></svg>
                        Immersive Tour
                    </span>
                    <span class="meta-item">${tour.sizeMB}</span>
                    <span class="meta-item">${tour.lastModified}</span>
                </div>
            </div>
            <div class="card-launch">
                <div class="launch-btn">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
                    Launch
                </div>
            </div>
        </div>
        """
    }

    private fun buildEmptyState(): String = """
        <div class="empty-state">
            <div class="empty-icon">
                <img src="/__assets__/app-icon.png" alt="Framatome VR Pro" style="width:96px;height:96px;border-radius:20px;opacity:.7">
            </div>
            <h2>Ready for Tours</h2>
            <p>Push your 3DVista or WebXR tour packages to get started.</p>
            <div class="empty-instructions">
                <div class="instruction-step">
                    <div class="step-num">1</div>
                    <div>
                        <strong>Export your tour</strong>
                        <p>From 3DVista, export as a web package (.zip)</p>
                    </div>
                </div>
                <div class="instruction-step">
                    <div class="step-num">2</div>
                    <div>
                        <strong>Deliver to the headset</strong>
                        <p>Push extracted tours or zips to <code>FramatomeVR/Tours/</code> or <code>${TourStorage.displayAppFilesPath()}</code></p>
                    </div>
                </div>
                <div class="instruction-step">
                    <div class="step-num">3</div>
                    <div>
                        <strong>Rescan</strong>
                        <p>Tours appear on launch or when you refresh this menu</p>
                    </div>
                </div>
            </div>
            <button class="btn-primary" onclick="window.location.reload()">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 4v6h6M23 20v-6h-6"/><path d="M20.49 9A9 9 0 005.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 013.51 15"/></svg>
                Scan for Tours
            </button>
        </div>
    """

    private fun buildPage(tourCount: Int, tourCards: String): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<meta name="description" content="Framatome VR Pro - Immersive Training Platform">
<title>Framatome VR Pro</title>
<style>
:root{
  --navy:#001C3D;--navy-mid:#0A2F54;--navy-light:#103A60;
  --blue:#003B70;--orange:#FF5F05;--orange-glow:rgba(255,95,5,.15);
  --text:#E6EDF5;--text-dim:rgba(230,237,245,.6);--text-mid:rgba(230,237,245,.8);
  --steel:#8FA1B8;--radius:16px;--radius-lg:24px;
}
*{margin:0;padding:0;box-sizing:border-box}
html{scroll-behavior:smooth}
body{
  font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',sans-serif;
  background:var(--navy);color:var(--text);min-height:100vh;
  padding:0;overflow-x:hidden;
}

/* --- Header --- */
.header{
  padding:48px 48px 0;
  display:flex;align-items:center;justify-content:space-between;
}
.header-left{display:flex;align-items:center;gap:20px}
.logo-mark{
  width:56px;height:56px;border-radius:14px;
  overflow:hidden;
  box-shadow:0 4px 16px rgba(0,0,0,.3);
  border:1px solid rgba(230,237,245,.1);
}
.logo-mark img{width:100%;height:100%;object-fit:cover}
.logo-text h1{font-size:28px;font-weight:700;letter-spacing:-.5px;line-height:1.1}
.logo-text .tagline{font-size:13px;color:var(--text-dim);font-weight:500;letter-spacing:.5px;text-transform:uppercase;margin-top:2px}
.header-right{display:flex;align-items:center;gap:12px}
.header-stat{
  background:var(--navy-mid);border:1px solid rgba(230,237,245,.08);
  border-radius:12px;padding:10px 16px;text-align:center;
}
.header-stat .num{font-size:22px;font-weight:700;color:var(--orange)}
.header-stat .label{font-size:11px;color:var(--text-dim);text-transform:uppercase;letter-spacing:.5px}

/* --- Main --- */
.main{padding:32px 48px 48px}

/* --- Section header --- */
.section-bar{
  display:flex;justify-content:space-between;align-items:center;
  margin-bottom:24px;padding-bottom:16px;
  border-bottom:1px solid rgba(230,237,245,.08);
}
.section-bar h2{font-size:20px;font-weight:600;color:var(--text-mid)}
.btn-scan{
  background:var(--orange-glow);border:1px solid rgba(255,95,5,.25);
  color:var(--orange);padding:10px 20px;border-radius:12px;
  cursor:pointer;font-size:14px;font-weight:600;
  display:flex;align-items:center;gap:8px;transition:all .15s;
}
.btn-scan:hover{background:rgba(255,95,5,.25);border-color:rgba(255,95,5,.4)}

/* --- Tour grid --- */
.tour-grid{
  display:grid;grid-template-columns:repeat(auto-fill,minmax(340px,1fr));
  gap:20px;
}
.tour-card{
  background:var(--navy-mid);border-radius:var(--radius-lg);overflow:hidden;
  cursor:pointer;transition:transform .2s ease,box-shadow .2s ease;
  box-shadow:0 2px 12px rgba(0,0,0,.25);
  border:1px solid rgba(230,237,245,.05);
  display:flex;flex-direction:column;
}
.tour-card:hover{
  transform:translateY(-6px) scale(1.01);
  box-shadow:0 12px 40px rgba(0,0,0,.45);
  border-color:rgba(255,95,5,.2);
}
.tour-card:active{transform:translateY(-2px) scale(.995)}

.card-thumb{
  height:200px;background-size:cover;background-position:center;
  position:relative;overflow:hidden;
}
.card-thumb::after{
  content:'';position:absolute;bottom:0;left:0;right:0;height:60px;
  background:linear-gradient(transparent,var(--navy-mid));
}
.card-thumb.no-thumb{
  background:linear-gradient(135deg,var(--navy-light),var(--navy));
  display:flex;align-items:center;justify-content:center;
}
.no-thumb-icon{color:var(--steel);opacity:.4}
.card-badge{
  position:absolute;top:12px;right:12px;z-index:1;
  background:rgba(0,0,0,.6);backdrop-filter:blur(8px);
  padding:4px 10px;border-radius:8px;font-size:11px;
  font-weight:700;color:var(--orange);letter-spacing:.5px;
  text-transform:uppercase;border:1px solid rgba(255,95,5,.2);
}
.card-body{padding:20px 20px 0;flex:1}
.card-body h3{font-size:18px;font-weight:600;margin-bottom:8px;line-height:1.3}
.card-meta{display:flex;flex-wrap:wrap;gap:12px;align-items:center}
.meta-item{
  font-size:12px;color:var(--text-dim);display:flex;
  align-items:center;gap:4px;
}
.meta-item svg{color:var(--steel)}
.card-launch{padding:16px 20px 20px}
.launch-btn{
  background:linear-gradient(135deg,var(--orange),#E05500);
  color:white;border:none;border-radius:12px;
  padding:12px 0;font-size:14px;font-weight:600;
  display:flex;align-items:center;justify-content:center;gap:8px;
  transition:opacity .15s;width:100%;
}
.tour-card:hover .launch-btn{opacity:.95}

/* --- Loading overlay --- */
.loading-overlay{
  display:none;position:fixed;inset:0;z-index:9999;
  background:rgba(0,28,61,.92);backdrop-filter:blur(12px);
  flex-direction:column;align-items:center;justify-content:center;
}
.loading-overlay.active{display:flex}
.loading-spinner{
  width:48px;height:48px;border:3px solid rgba(230,237,245,.15);
  border-top-color:var(--orange);border-radius:50%;
  animation:spin .8s linear infinite;margin-bottom:24px;
}
@keyframes spin{to{transform:rotate(360deg)}}
.loading-text{font-size:18px;font-weight:600;margin-bottom:4px}
.loading-sub{font-size:14px;color:var(--text-dim)}

/* --- Empty state --- */
.empty-state{
  text-align:center;padding:64px 48px;
  background:var(--navy-mid);border-radius:var(--radius-lg);
  border:1px dashed rgba(230,237,245,.12);
}
.empty-icon{color:var(--steel);opacity:.5;margin-bottom:24px}
.empty-state h2{font-size:24px;font-weight:600;margin-bottom:8px}
.empty-state>p{color:var(--text-dim);font-size:16px;margin-bottom:32px}
.empty-instructions{
  display:flex;flex-direction:column;gap:16px;
  max-width:420px;margin:0 auto 32px;text-align:left;
}
.instruction-step{
  display:flex;align-items:flex-start;gap:16px;
  background:rgba(0,0,0,.15);padding:16px;border-radius:var(--radius);
}
.step-num{
  min-width:32px;height:32px;border-radius:50%;
  background:var(--orange-glow);color:var(--orange);
  display:flex;align-items:center;justify-content:center;
  font-weight:700;font-size:14px;border:1px solid rgba(255,95,5,.3);
}
.instruction-step strong{font-size:14px;display:block;margin-bottom:2px}
.instruction-step p{font-size:13px;color:var(--text-dim);margin:0}
.instruction-step code{
  background:rgba(0,0,0,.3);padding:2px 6px;border-radius:4px;
  font-size:12px;font-family:monospace;
}
.btn-primary{
  background:linear-gradient(135deg,var(--orange),#E05500);
  border:none;color:white;padding:14px 32px;border-radius:14px;
  cursor:pointer;font-size:15px;font-weight:600;
  display:inline-flex;align-items:center;gap:8px;
  box-shadow:0 4px 16px rgba(255,95,5,.3);transition:all .15s;
}
.btn-primary:hover{box-shadow:0 6px 24px rgba(255,95,5,.4);transform:translateY(-1px)}

/* --- Footer --- */
.footer{
  padding:32px 48px;text-align:center;color:var(--text-dim);
  font-size:12px;border-top:1px solid rgba(230,237,245,.06);
  margin-top:48px;
}
.footer span{margin:0 8px}

/* --- Intro overlay --- */
.intro-overlay{
  position:fixed;inset:0;z-index:10000;
  background:radial-gradient(ellipse at 50% 40%,#0a1e3d 0%,#000a18 60%,#000 100%);
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  transition:opacity .8s ease,visibility .8s ease;
  overflow:hidden;
}
.intro-overlay.fade-out{opacity:0;visibility:hidden;pointer-events:none}
.intro-particles{position:absolute;inset:0;overflow:hidden}
.intro-particle{
  position:absolute;width:3px;height:3px;border-radius:50%;
  background:var(--orange);opacity:0;
  animation:particleDrift linear infinite;
}
@keyframes particleDrift{
  0%{transform:translateY(100vh) scale(0);opacity:0}
  10%{opacity:.7}
  90%{opacity:.4}
  100%{transform:translateY(-20vh) scale(1.2);opacity:0}
}
.intro-glow{
  position:absolute;width:300px;height:300px;border-radius:50%;
  background:radial-gradient(circle,rgba(255,95,5,.12) 0%,transparent 70%);
  animation:glowPulse 3s ease-in-out infinite;
  pointer-events:none;
}
@keyframes glowPulse{
  0%,100%{transform:scale(1);opacity:.6}
  50%{transform:scale(1.3);opacity:1}
}
.intro-logo-wrap{
  perspective:800px;margin-bottom:32px;
  animation:logoReveal 1.2s cubic-bezier(.16,1,.3,1) forwards;
  opacity:0;
}
@keyframes logoReveal{
  0%{opacity:0;transform:rotateY(-40deg) rotateX(10deg) scale(.6)}
  60%{opacity:1}
  100%{opacity:1;transform:rotateY(0) rotateX(0) scale(1)}
}
.intro-logo{
  width:120px;height:120px;border-radius:28px;
  overflow:hidden;
  box-shadow:0 0 60px rgba(255,95,5,.2),0 8px 32px rgba(0,0,0,.5);
  border:2px solid rgba(255,95,5,.15);
  animation:logoFloat 4s ease-in-out infinite;
  animation-delay:1.2s;
}
.intro-logo img{width:100%;height:100%;object-fit:cover}
@keyframes logoFloat{
  0%,100%{transform:translateY(0)}
  50%{transform:translateY(-8px)}
}
.intro-title{
  font-size:36px;font-weight:800;letter-spacing:8px;text-transform:uppercase;
  color:var(--text);opacity:0;
  animation:titleReveal 1s ease forwards;
  animation-delay:.6s;
}
@keyframes titleReveal{
  0%{opacity:0;letter-spacing:24px;filter:blur(8px)}
  100%{opacity:1;letter-spacing:8px;filter:blur(0)}
}
.intro-subtitle{
  font-size:16px;font-weight:500;color:var(--orange);
  letter-spacing:3px;text-transform:uppercase;
  margin-top:8px;opacity:0;
  animation:subtitleReveal .8s ease forwards;
  animation-delay:1.4s;
}
@keyframes subtitleReveal{
  0%{opacity:0;transform:translateY(12px)}
  100%{opacity:1;transform:translateY(0)}
}
.intro-line{
  width:0;height:2px;background:linear-gradient(90deg,transparent,var(--orange),transparent);
  margin-top:24px;
  animation:lineExpand 1s ease forwards;
  animation-delay:1s;
}
@keyframes lineExpand{
  0%{width:0;opacity:0}
  100%{width:200px;opacity:1}
}
.intro-skip{
  position:absolute;bottom:40px;
  font-size:13px;color:rgba(230,237,245,.3);
  letter-spacing:1px;text-transform:uppercase;
  opacity:0;animation:skipFadeIn .5s ease forwards;
  animation-delay:2s;cursor:pointer;
}
@keyframes skipFadeIn{
  to{opacity:1}
}
</style>
</head>
<body>

<div class="intro-overlay" id="introOverlay" onclick="dismissIntro()">
    <div class="intro-particles" id="introParticles"></div>
    <div class="intro-glow"></div>
    <div class="intro-logo-wrap">
        <div class="intro-logo">
            <img src="/__assets__/app-icon.png" alt="Framatome VR Pro">
        </div>
    </div>
    <div class="intro-title">Framatome VR Pro</div>
    <div class="intro-subtitle">Immersive Training Platform</div>
    <div class="intro-line"></div>
    <div class="intro-skip">Tap to continue</div>
</div>

<div class="loading-overlay" id="loadingOverlay">
    <div class="loading-spinner"></div>
    <div class="loading-text" id="loadingText">Launching Tour...</div>
    <div class="loading-sub">Preparing immersive environment</div>
</div>

<div class="header">
    <div class="header-left">
        <div class="logo-mark">
            <img src="/__assets__/app-icon.png" alt="Framatome VR Pro">
        </div>
        <div class="logo-text">
            <h1>Framatome VR Pro</h1>
            <div class="tagline">Immersive Training Platform</div>
        </div>
    </div>
    <div class="header-right">
        <div class="header-stat">
            <div class="num">$tourCount</div>
            <div class="label">Tours</div>
        </div>
    </div>
</div>

<div class="main">
    <div class="section-bar">
        <h2>Your Tours</h2>
        <button class="btn-scan" onclick="window.location.reload()">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 4v6h6M23 20v-6h-6"/><path d="M20.49 9A9 9 0 005.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 013.51 15"/></svg>
            Rescan
        </button>
    </div>

    <div class="tour-grid">
$tourCards
    </div>
</div>

<div class="footer">
    Framatome VR Pro v1.0
    <span>&middot;</span>
    WebXR Immersive
    <span>&middot;</span>
    Quest 3
</div>

<script>
(function(){
  var pc = document.getElementById('introParticles');
  if (pc) {
    for (var i = 0; i < 30; i++) {
      var p = document.createElement('div');
      p.className = 'intro-particle';
      p.style.left = Math.random() * 100 + '%';
      p.style.animationDuration = (4 + Math.random() * 6) + 's';
      p.style.animationDelay = (Math.random() * 3) + 's';
      p.style.width = p.style.height = (2 + Math.random() * 3) + 'px';
      p.style.opacity = String(0.2 + Math.random() * 0.5);
      pc.appendChild(p);
    }
  }
  setTimeout(dismissIntro, 4500);
})();
var introDismissed = false;
function dismissIntro() {
  if (introDismissed) return;
  introDismissed = true;
  var o = document.getElementById('introOverlay');
  if (o) o.classList.add('fade-out');
}
function launchTour(url, card) {
  var overlay = document.getElementById('loadingOverlay');
  var text = document.getElementById('loadingText');
  var name = card.querySelector('h3');
  if (name) text.textContent = 'Launching ' + name.textContent + '...';
  overlay.classList.add('active');
  setTimeout(function() { window.location.href = url; }, 400);
}
</script>
</body>
</html>
    """.trimIndent()

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun encodePath(path: String): String = path
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString("/") { segment ->
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }
}
