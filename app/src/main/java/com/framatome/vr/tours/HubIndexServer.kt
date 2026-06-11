package com.framatome.vr.tours

import android.content.Context
import android.util.Log
import com.framatome.vr.content.MediaViewerUrls
import com.framatome.vr.content.library.ContentBadges
import com.framatome.vr.content.library.LibraryItem
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Generates the player's in-VR content hub at `/`: 3DVista tours plus the
 * shared media library (videos & images), all launching locally on :18080 so
 * Framatome Player works as a standalone media player without the launcher.
 */
object HubIndexServer {
    private const val TAG = "HubIndexServer"

    /** Cards rendered eagerly per section; the rest hide behind "Show all". */
    private const val SECTION_CHUNK = 24

    data class TourInfo(
        val name: String,
        val launchUrl: String,
        val thumbnailUrl: String?,
        val sizeMB: String,
        val lastModified: String
    )

    // Tour folder sizes are a full directory walk — cache per dir mtime so the
    // hub doesn't re-walk every tour on every request.
    private val tourSizeCache = ConcurrentHashMap<String, Pair<Long, String>>()

    // The hub HTML is rebuilt only when the content (or its enablement) changes,
    // not on every GET /. Stamp = tours+media filesystem mtimes; the short TTL
    // also picks up settings-sheet enable/disable toggles within a few seconds.
    private const val HTML_TTL_MS = 3_000L
    @Volatile private var cachedHtml: String? = null
    @Volatile private var cachedHtmlStamp = 0L
    @Volatile private var cachedHtmlAtMs = 0L

    fun generateIndexHtml(context: Context): String {
        // Discover tours from the SAME directory LocalWebServer serves so every
        // card the hub renders resolves at runtime instead of 404-ing.
        val toursRoot = TourStorage.sharedToursDir()
        if (!toursRoot.exists()) {
            toursRoot.mkdirs()
        }

        val stamp = hubStamp(toursRoot)
        val now = System.currentTimeMillis()
        cachedHtml?.let { html ->
            if (cachedHtmlStamp == stamp && now - cachedHtmlAtMs < HTML_TTL_MS) {
                return html
            }
        }

        val html = renderIndexHtml(context, toursRoot)
        cachedHtml = html
        cachedHtmlStamp = stamp
        cachedHtmlAtMs = now
        return html
    }

    /** Cheap content-change signal for the HTML cache: tour dir mtimes + the
     *  media library stamp. No directory walk, no scan. */
    private fun hubStamp(toursRoot: File): Long {
        var s = toursRoot.lastModified()
        toursRoot.listFiles()?.forEach { child ->
            if (child.isDirectory && !child.name.startsWith(".")) s = s * 31 + child.lastModified()
        }
        return s * 31 + MediaLibraryIndex.rootsStamp()
    }

    private fun renderIndexHtml(context: Context, toursRoot: File): String {
        val settingsStore = (context.applicationContext as? android.app.Application)
            ?.let(::TourSettingsStore)
        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

        val tours = TourCatalog.scan(toursRoot) { relativePath ->
            settingsStore?.isEnabled(relativePath) ?: true
        }
            .filter { it.enabled }
            .map { tour ->
                val dir = File(tour.absolutePath)
                val encodedBase = encodePath(tour.relativePath)
                val thumbUrl = tour.thumbnailPath?.let { thumb ->
                    "/$encodedBase/${encodePath(File(thumb).name)}"
                }
                val launchUrl = "/$encodedBase/${encodePath(tour.entryFileName)}"
                TourInfo(
                    name = tour.name,
                    launchUrl = launchUrl,
                    thumbnailUrl = thumbUrl,
                    sizeMB = cachedDirSize(dir),
                    lastModified = dateFormat.format(Date(dir.lastModified()))
                )
            }

        val snapshot = MediaLibraryIndex.snapshot()
        Log.i(TAG, "Generating hub: ${tours.size} tours, ${snapshot.videos.size} videos, ${snapshot.images.size} images")

        val tourCards = if (tours.isEmpty()) {
            buildEmptyState()
        } else {
            tours.joinToString("\n") { tour -> buildTourCard(tour) }
        }

        return buildPage(
            tourCount = tours.size,
            videoCount = snapshot.videos.size,
            imageCount = snapshot.images.size,
            modelCount = snapshot.models.size,
            tourCards = tourCards,
            videoSection = buildMediaSection(
                title = "Videos",
                gridId = "grid-videos",
                items = snapshot.videos,
                playlistKey = HubPlaylists.KEY_VIDEOS,
                emptyHint = "360 and flat videos delivered to FramatomeVR/Library or Data appear here.",
                dateFormat = dateFormat
            ),
            imageSection = buildMediaSection(
                title = "Images",
                gridId = "grid-images",
                items = snapshot.images,
                playlistKey = HubPlaylists.KEY_IMAGES,
                emptyHint = "360 panoramas and photos delivered to FramatomeVR/Library or Data appear here.",
                dateFormat = dateFormat
            ),
            modelSection = buildMediaSection(
                title = "3D Models",
                gridId = "grid-models",
                items = snapshot.models,
                playlistKey = HubPlaylists.KEY_MODELS,
                emptyHint = "GLB/glTF models (SolidWorks XR exports with exploded views) appear here — see MODEL_PIPELINE.md.",
                dateFormat = dateFormat
            ),
            storageBanner = if (snapshot.storageGranted) "" else buildStorageBanner()
        )
    }

    private fun cachedDirSize(dir: File): String {
        val stamp = dir.lastModified()
        tourSizeCache[dir.absolutePath]?.let { (cachedStamp, formatted) ->
            if (cachedStamp == stamp) return formatted
        }
        val sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val formatted = formatSize(sizeBytes)
        tourSizeCache[dir.absolutePath] = stamp to formatted
        return formatted
    }

    private fun formatSize(bytes: Long): String = String.format(
        Locale.US,
        "%.1f MB",
        bytes / (1024.0 * 1024.0)
    )

    /* ----------------------------------------------------------- sections -- */

    private fun buildMediaSection(
        title: String,
        gridId: String,
        items: List<LibraryItem>,
        playlistKey: String,
        emptyHint: String,
        dateFormat: SimpleDateFormat
    ): String {
        val body = if (items.isEmpty()) {
            """<div class="section-empty">$emptyHint</div>"""
        } else {
            val cards = items.mapIndexedNotNull { index, item ->
                buildMediaCard(item, playlistKey, index, hidden = index >= SECTION_CHUNK, dateFormat)
            }.joinToString("\n")
            val showMore = if (items.size > SECTION_CHUNK) {
                """<button class="btn-scan show-more" onclick="showMore('$gridId', this)">Show all ${items.size}</button>"""
            } else {
                ""
            }
            """<div class="tour-grid" id="$gridId">
$cards
</div>
$showMore"""
        }
        return """
<div class="section-bar"><h2>$title <span class="section-count">${items.size}</span></h2></div>
$body
"""
    }

    private fun buildMediaCard(
        item: LibraryItem,
        playlistKey: String,
        index: Int,
        hidden: Boolean,
        dateFormat: SimpleDateFormat
    ): String? {
        val src = HubPlaylists.itemId(item) ?: return null
        val launchUrl = MediaViewerUrls.build(
            type = item.contentType.token,
            src = src,
            title = item.title,
            projection = item.projection,
            stereo = item.stereo,
            right = item.rightEyeFile()?.let { MediaViewerUrls.mediaRelativePath(it.absolutePath) },
            id = src,
            playlistKey = playlistKey,
            playlistIndex = index
        )
        val badge = escapeHtml(ContentBadges.labelFor(item.contentType, item.projection))
        val thumbUrl = ThumbnailStore.thumbUrlFor(item)
        val safeTitle = escapeHtml(item.title)
        val isModel = item.contentType == com.framatome.vr.content.ContentType.Model3D
        val description = item.description?.trim()?.takeIf { it.isNotBlank() }
            ?.let { """<p class="card-desc">${escapeHtml(it)}</p>""" } ?: ""
        val meta = buildString {
            append("<span class=\"meta-item\">")
            append(formatSize(item.path.length()))
            append("</span>")
            append("<span class=\"meta-item\">")
            append(dateFormat.format(Date(item.path.lastModified())))
            append("</span>")
        }
        val hiddenClass = if (hidden) " hidden-card" else ""
        val thumbInner = if (thumbUrl != null) {
            """<img loading="lazy" src="$thumbUrl" alt="" onerror="thumbFallback(this)">"""
        } else {
            mediaPlaceholderIcon(isModel)
        }
        return """
        <div class="tour-card$hiddenClass" onclick="launchMedia('${escapeJs(launchUrl)}', this)">
            <div class="card-thumb media-thumb${if (thumbUrl == null) " no-thumb" else ""}">
                $thumbInner
                <div class="card-badge">$badge</div>
            </div>
            <div class="card-body">
                <h3>$safeTitle</h3>
                $description
                <div class="card-meta">$meta</div>
            </div>
            <div class="card-launch">
                <div class="launch-btn">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
                    ${if (isModel) "View" else "Play"}
                </div>
            </div>
        </div>
        """
    }

    private fun mediaPlaceholderIcon(isModel: Boolean = false): String = if (isModel) {
        """
        <div class="no-thumb-icon">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round">
                <path d="M12 2 3 7v10l9 5 9-5V7l-9-5z"/><path d="M3 7l9 5 9-5"/><path d="M12 12v10"/>
            </svg>
        </div>
        """
    } else {
        """
        <div class="no-thumb-icon">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/>
            </svg>
        </div>
        """
    }

    private fun buildStorageBanner(): String = """
    <div class="storage-banner">
        <strong>Storage access required.</strong>
        Framatome Player needs All Files Access to list the media library. Grant it via ArborXR
        or adb: <code>adb shell appops set com.framatome.vr.pro MANAGE_EXTERNAL_STORAGE allow</code>
    </div>
    """

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
            <h2>Ready for Content</h2>
            <p>Push 3DVista tour packages or media zips to get started.</p>
            <div class="empty-instructions">
                <div class="instruction-step">
                    <div class="step-num">1</div>
                    <div>
                        <strong>Export your content</strong>
                        <p>From 3DVista, export as a web package (.zip) — or bundle videos/images (optionally with a manifest.json) into a media zip</p>
                    </div>
                </div>
                <div class="instruction-step">
                    <div class="step-num">2</div>
                    <div>
                        <strong>Deliver to the headset</strong>
                        <p>Push zips to <code>FramatomeVR/Data/</code> (ArborXR Files), or drop extracted tours in <code>${TourStorage.displaySharedToursPath()}</code> and media in <code>FramatomeVR/Library/</code></p>
                    </div>
                </div>
                <div class="instruction-step">
                    <div class="step-num">3</div>
                    <div>
                        <strong>Rescan</strong>
                        <p>Content appears on launch or when you refresh this menu</p>
                    </div>
                </div>
            </div>
            <button class="btn-primary" onclick="window.location.reload()">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 4v6h6M23 20v-6h-6"/><path d="M20.49 9A9 9 0 005.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 013.51 15"/></svg>
                Scan for Content
            </button>
        </div>
    """

    private fun buildPage(
        tourCount: Int,
        videoCount: Int,
        imageCount: Int,
        modelCount: Int,
        tourCards: String,
        videoSection: String,
        imageSection: String,
        modelSection: String,
        storageBanner: String
    ): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<meta name="description" content="Framatome VR Pro - Immersive Training Platform">
<title>Framatome VR Pro</title>
<style>
/* Brand tokens — keep in sync with core-ui FramatomeColors and the launcher's
   glass design language (bg_launcher_window.xml). See BRANDING.md. */
:root{
  --navy:#081E3F;--midnight:#0B1730;--surface:#163761;
  --blue:#003F87;--blue-light:#0082CA;
  --orange:#F04E23;--orange-light:#FF7A30;--orange-deep:#C73D16;--orange-glow:rgba(240,78,35,.15);
  --panel-white:#F8FBFF;--card-border:#D8E1EE;--ink:#11233D;--ink-soft:#55657B;
  --glass:rgba(255,255,255,.12);--glass-soft:rgba(255,255,255,.08);--glass-border:rgba(255,255,255,.18);
  --text:#FFFFFF;--text-dim:rgba(216,227,243,.62);--text-mid:rgba(216,227,243,.85);
  --steel:#B8C7DC;--radius:16px;--radius-lg:24px;
}
*{margin:0;padding:0;box-sizing:border-box}
html{scroll-behavior:smooth}
body{
  font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',sans-serif;
  background:linear-gradient(135deg,var(--midnight) 0%,var(--blue) 52%,var(--surface) 100%) fixed;
  color:var(--text);min-height:100vh;
  padding:0 0 12px;overflow-x:hidden;
}
/* Frosted launcher-window glows: white top-right, orange tint bottom-left. */
body::before,body::after{
  content:'';position:fixed;border-radius:50%;pointer-events:none;z-index:0;
}
body::before{
  top:-140px;right:-120px;width:480px;height:480px;
  background:radial-gradient(circle,rgba(255,255,255,.14),transparent 70%);
}
body::after{
  bottom:-180px;left:-160px;width:560px;height:560px;
  background:radial-gradient(circle,rgba(240,78,35,.16),transparent 70%);
}
.header,.main,.storage-banner,.footer{position:relative;z-index:1}
.accent-stripe{
  position:fixed;left:0;right:0;bottom:0;height:6px;z-index:2;
  background:linear-gradient(90deg,var(--orange),var(--orange-light));
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
  border:1px solid rgba(216,227,243,.1);
}
.logo-mark img{width:100%;height:100%;object-fit:cover}
.logo-text h1{font-size:28px;font-weight:700;letter-spacing:-.5px;line-height:1.1}
.logo-text .tagline{font-size:13px;color:var(--text-dim);font-weight:500;letter-spacing:.5px;text-transform:uppercase;margin-top:2px}
.header-right{display:flex;align-items:center;gap:12px}
.header-stat{
  background:var(--glass);border:1px solid var(--glass-border);
  backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);
  border-radius:14px;padding:10px 16px;text-align:center;min-width:74px;
}
.header-stat .num{font-size:22px;font-weight:700;color:#FFD9CC}
.header-stat .label{font-size:11px;color:var(--text-mid);text-transform:uppercase;letter-spacing:.5px}

/* --- Storage banner --- */
.storage-banner{
  margin:24px 48px 0;padding:16px 20px;border-radius:var(--radius);
  background:rgba(240,78,35,.16);border:1px solid rgba(255,255,255,.22);
  backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);
  color:var(--text-mid);font-size:14px;line-height:1.6;
}
.storage-banner strong{color:var(--orange)}
.storage-banner code{
  background:rgba(0,0,0,.35);padding:2px 8px;border-radius:6px;
  font-size:12px;font-family:monospace;
}

/* --- Main --- */
.main{padding:32px 48px 48px}

/* --- Section header --- */
.section-bar{
  display:flex;justify-content:space-between;align-items:center;
  margin:32px 0 24px;padding-bottom:16px;
  border-bottom:1px solid rgba(216,227,243,.08);
}
.section-bar:first-child{margin-top:0}
.section-bar h2{font-size:20px;font-weight:600;color:var(--text)}
.section-count{
  display:inline-block;margin-left:10px;padding:2px 12px;border-radius:999px;
  background:var(--glass);border:1px solid var(--glass-border);color:var(--text);
  font-size:13px;font-weight:700;vertical-align:2px;
}
.section-empty{
  padding:28px;border-radius:var(--radius);color:var(--text-mid);
  background:var(--glass-soft);border:1px solid var(--glass-border);font-size:14px;
}
.btn-scan{
  background:var(--glass);border:1px solid var(--glass-border);
  backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);
  color:var(--text);padding:10px 20px;border-radius:12px;
  cursor:pointer;font-size:14px;font-weight:600;
  display:flex;align-items:center;gap:8px;transition:all .15s;
}
.btn-scan:hover{background:rgba(255,255,255,.2);border-color:rgba(255,255,255,.3)}
.show-more{margin:20px auto 0}

/* --- Card grid --- */
.tour-grid{
  display:grid;grid-template-columns:repeat(auto-fill,minmax(340px,1fr));
  gap:20px;
}
.tour-card{
  background:var(--panel-white);border-radius:var(--radius-lg);overflow:hidden;
  cursor:pointer;transition:transform .2s ease,box-shadow .2s ease;
  box-shadow:0 6px 22px rgba(2,8,20,.35);
  border:1px solid var(--card-border);
  display:flex;flex-direction:column;
}
.tour-card:hover{
  transform:translateY(-6px) scale(1.01);
  box-shadow:0 16px 44px rgba(2,8,20,.5);
  border-color:rgba(240,78,35,.45);
}
.tour-card:active{transform:translateY(-2px) scale(.995)}
.hidden-card{display:none}

.card-thumb{
  height:200px;background-size:cover;background-position:center;
  position:relative;overflow:hidden;
}
.card-thumb::after{
  content:'';position:absolute;bottom:0;left:0;right:0;height:60px;
  background:linear-gradient(transparent,rgba(8,23,48,.42));
}
.card-thumb.no-thumb{
  background:linear-gradient(135deg,var(--surface),var(--navy));
  display:flex;align-items:center;justify-content:center;
}
.media-thumb{background:linear-gradient(135deg,var(--surface),var(--navy))}
.media-thumb img{width:100%;height:100%;object-fit:cover;display:block}
.no-thumb-icon{color:var(--steel);opacity:.5;display:flex;align-items:center;justify-content:center;height:100%}
.card-badge{
  position:absolute;top:12px;right:12px;z-index:1;
  background:var(--glass);backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);
  padding:4px 10px;border-radius:999px;font-size:11px;
  font-weight:700;color:#FFFFFF;letter-spacing:.5px;
  text-transform:uppercase;border:1px solid var(--glass-border);
}
.card-body{padding:20px 20px 0;flex:1}
.card-body h3{font-size:18px;font-weight:600;margin-bottom:8px;line-height:1.3;color:var(--ink)}
.card-desc{
  font-size:13px;color:var(--ink-soft);line-height:1.45;margin:-2px 0 8px;
  display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;
}
.card-meta{display:flex;flex-wrap:wrap;gap:12px;align-items:center}
.meta-item{
  font-size:12px;color:var(--ink-soft);display:flex;
  align-items:center;gap:4px;
}
.meta-item svg{color:var(--ink-soft)}
.card-launch{padding:16px 20px 20px}
.launch-btn{
  background:linear-gradient(135deg,var(--orange),var(--orange-deep));
  color:white;border:none;border-radius:12px;
  padding:12px 0;font-size:14px;font-weight:600;
  display:flex;align-items:center;justify-content:center;gap:8px;
  transition:opacity .15s;width:100%;
}
.tour-card:hover .launch-btn{opacity:.95}

/* --- Loading overlay --- */
.loading-overlay{
  display:none;position:fixed;inset:0;z-index:9999;
  background:rgba(11,23,48,.9);backdrop-filter:blur(14px);
  flex-direction:column;align-items:center;justify-content:center;
}
.loading-overlay.active{display:flex}
.loading-spinner{
  width:48px;height:48px;border:3px solid rgba(216,227,243,.15);
  border-top-color:var(--orange);border-radius:50%;
  animation:spin .8s linear infinite;margin-bottom:24px;
}
@keyframes spin{to{transform:rotate(360deg)}}
.loading-text{font-size:18px;font-weight:600;margin-bottom:4px}
.loading-sub{font-size:14px;color:var(--text-dim)}

/* --- Empty state --- */
.empty-state{
  text-align:center;padding:64px 48px;
  background:var(--glass-soft);border-radius:var(--radius-lg);
  border:1px solid var(--glass-border);
  backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);
  grid-column:1/-1;
}
.empty-icon{color:var(--steel);opacity:.5;margin-bottom:24px}
.empty-state h2{font-size:24px;font-weight:600;margin-bottom:8px}
.empty-state>p{color:var(--text-dim);font-size:16px;margin-bottom:32px}
.empty-instructions{
  display:flex;flex-direction:column;gap:16px;
  max-width:460px;margin:0 auto 32px;text-align:left;
}
.instruction-step{
  display:flex;align-items:flex-start;gap:16px;
  background:rgba(0,0,0,.15);padding:16px;border-radius:var(--radius);
}
.step-num{
  min-width:32px;height:32px;border-radius:50%;
  background:var(--orange-glow);color:var(--orange);
  display:flex;align-items:center;justify-content:center;
  font-weight:700;font-size:14px;border:1px solid rgba(240,78,35,.3);
}
.instruction-step strong{font-size:14px;display:block;margin-bottom:2px}
.instruction-step p{font-size:13px;color:var(--text-dim);margin:0}
.instruction-step code{
  background:rgba(0,0,0,.3);padding:2px 6px;border-radius:4px;
  font-size:12px;font-family:monospace;
}
.btn-primary{
  background:linear-gradient(135deg,var(--orange),var(--orange-deep));
  border:none;color:white;padding:14px 32px;border-radius:14px;
  cursor:pointer;font-size:15px;font-weight:600;
  display:inline-flex;align-items:center;gap:8px;
  box-shadow:0 4px 16px rgba(240,78,35,.3);transition:all .15s;
}
.btn-primary:hover{box-shadow:0 6px 24px rgba(240,78,35,.4);transform:translateY(-1px)}

/* --- Footer --- */
.footer{
  padding:32px 48px;text-align:center;color:var(--text-dim);
  font-size:12px;border-top:1px solid rgba(216,227,243,.06);
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
  background:radial-gradient(circle,rgba(240,78,35,.12) 0%,transparent 70%);
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
  box-shadow:0 0 60px rgba(240,78,35,.2),0 8px 32px rgba(0,0,0,.5);
  border:2px solid rgba(240,78,35,.15);
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
  font-size:13px;color:rgba(216,227,243,.3);
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
    <div class="loading-text" id="loadingText">Launching...</div>
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
        <div class="header-stat">
            <div class="num">$videoCount</div>
            <div class="label">Videos</div>
        </div>
        <div class="header-stat">
            <div class="num">$imageCount</div>
            <div class="label">Images</div>
        </div>
        <div class="header-stat">
            <div class="num">$modelCount</div>
            <div class="label">Models</div>
        </div>
    </div>
</div>
$storageBanner

<div class="main">
    <div class="section-bar">
        <h2>Your Tours <span class="section-count">$tourCount</span></h2>
        <button class="btn-scan" onclick="window.location.reload()">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 4v6h6M23 20v-6h-6"/><path d="M20.49 9A9 9 0 005.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 013.51 15"/></svg>
            Rescan
        </button>
    </div>

    <div class="tour-grid">
$tourCards
    </div>

$videoSection

$imageSection

$modelSection
</div>

<div class="footer">
    Framatome VR Pro
    <span>&middot;</span>
    WebXR Immersive
    <span>&middot;</span>
    Tours &middot; 360/VR180 &middot; Media Library
</div>
<div class="accent-stripe" aria-hidden="true"></div>

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
function showLaunchOverlay(card, verb) {
  var overlay = document.getElementById('loadingOverlay');
  var text = document.getElementById('loadingText');
  var name = card && card.querySelector('h3');
  text.textContent = verb + (name ? ' ' + name.textContent : '') + '...';
  overlay.classList.add('active');
}
function launchTour(url, card) {
  showLaunchOverlay(card, 'Launching');
  setTimeout(function() { window.location.href = url; }, 400);
}
function launchMedia(url, card) {
  showLaunchOverlay(card, 'Opening');
  setTimeout(function() { window.location.href = url; }, 400);
}
function showMore(gridId, btn) {
  var grid = document.getElementById(gridId);
  if (!grid) return;
  var hidden = grid.querySelectorAll('.hidden-card');
  for (var i = 0; i < hidden.length; i++) hidden[i].classList.remove('hidden-card');
  if (btn) btn.style.display = 'none';
}
function thumbFallback(img) {
  img.style.display = 'none';
  var parent = img.parentElement;
  if (parent && !parent.querySelector('.no-thumb-icon')) {
    parent.classList.add('no-thumb');
    parent.insertAdjacentHTML('afterbegin',
      '<div class="no-thumb-icon"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/></svg></div>');
  }
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

    private fun escapeJs(s: String): String = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")

    private fun encodePath(path: String): String = path
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString("/") { segment ->
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }
}
