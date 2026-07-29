package com.framatome.vr.tours

import android.content.Context
import android.os.Build
import android.util.Log
import com.framatome.vr.util.CrashLogger
import com.framatome.vr.util.IngestReconcileReport
import com.framatome.vr.util.StorageLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The evidence a support call runs on.
 *
 * Nobody can adb into a headset inside a customer's plant, and the Player is
 * now the only app on it — the launcher that used to write these files is
 * uninstalled during rollout. So anything we want to know after the fact has
 * to already be on disk, under the folder the customer's ArborXR admin can
 * browse. Three records live here: crashes, a heartbeat, and content-delivery
 * failures (the likeliest field problem, and previously the least durable
 * signal we had — it lived in one volatile string that a restart erased).
 *
 * [writeSupportBundle] packages all three with a short identifier the operator
 * can read to us over the phone, which is what turns "it stopped working" into
 * something actionable.
 */
object PlayerDiagnostics {
  private const val TAG = "PlayerDiagnostics"
  private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
  private const val FAILURE_LOG = "content-failures.jsonl"
  private const val MAX_FAILURE_LINES = 200
  private const val MAX_BUNDLES = 5

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

  @Volatile private var started = false

  /**
   * Install the crash handler and begin the heartbeat. Safe to call repeatedly;
   * only the first call takes effect.
   */
  fun start(context: Context) {
    val app = context.applicationContext
    synchronized(this) {
      if (started) return
      started = true
    }
    runCatching { CrashLogger(app).install() }
      .onFailure { Log.w(TAG, "Could not install crash handler", it) }
    scope.launch {
      while (isActive) {
        writeHeartbeat(app)
        delay(HEARTBEAT_INTERVAL_MS)
      }
    }
  }

  /**
   * A file an ArborXR admin can pull to see whether a headset is healthy
   * without anyone donning it.
   */
  private fun writeHeartbeat(context: Context) {
    runCatching {
      StorageLayout.logsDir.mkdirs()
      StorageLayout.healthFile.writeText(HubOperatorSettings.statusJson(context))
    }.onFailure { Log.w(TAG, "Could not write heartbeat", it) }
  }

  /**
   * Append content-delivery failures so they outlive the process.
   *
   * Only failures and out-of-space are recorded — a healthy reconcile writing a
   * line every few seconds would bury the interesting ones and grow without
   * bound on a kiosk that runs for months.
   */
  fun recordIngestFailures(pipeline: String, report: IngestReconcileReport) {
    if (!report.hasFailures) return
    runCatching {
      val now = System.currentTimeMillis()
      val entry = JSONObject().apply {
        put("ts", now)
        put("at", timestamp.format(Date(now)))
        put("pipeline", pipeline)
        put("failed", report.failed)
        put("outOfSpace", report.outOfSpace)
        put("messages", JSONArray().apply { report.failureMessages.forEach { put(it) } })
      }
      StorageLayout.logsDir.mkdirs()
      val log = File(StorageLayout.logsDir, FAILURE_LOG)
      log.appendText(entry.toString() + "\n")
      trim(log)
    }.onFailure { Log.w(TAG, "Could not record ingest failure", it) }
  }

  private fun trim(log: File) {
    val lines = runCatching { log.readLines() }.getOrNull() ?: return
    if (lines.size <= MAX_FAILURE_LINES) return
    log.writeText(lines.takeLast(MAX_FAILURE_LINES).joinToString("\n", postfix = "\n"))
  }

  /** Everything we would ask for on a support call, plus how to quote it back. */
  data class SupportBundle(val supportId: String, val file: File, val failureCount: Int)

  fun writeSupportBundle(context: Context): SupportBundle? = runCatching {
    val app = context.applicationContext
    val now = System.currentTimeMillis()
    // Short enough to read aloud, long enough not to collide between headsets
    // in the same rollout; also embedded in the file so we can match them up.
    val supportId = "FVR-" + java.lang.Long.toString(now, 36).uppercase(Locale.US).takeLast(5)
    val failures = recentFailures()
    val bundle = JSONObject().apply {
      put("supportId", supportId)
      put("generatedAt", timestamp.format(Date(now)))
      put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
      put("status", JSONObject(HubOperatorSettings.statusJson(app)))
      put("contentFailures", failures)
      put("crashReports", JSONArray().apply { crashFiles().forEach { put(it.name) } })
    }
    StorageLayout.logsDir.mkdirs()
    val file = File(StorageLayout.logsDir, "support-$supportId.json")
    file.writeText(bundle.toString(2))
    pruneBundles()
    SupportBundle(supportId, file, failures.length())
  }.onFailure { Log.w(TAG, "Could not write support bundle", it) }.getOrNull()

  private fun recentFailures(): JSONArray {
    val log = File(StorageLayout.logsDir, FAILURE_LOG)
    val lines = runCatching { log.readLines() }.getOrNull().orEmpty()
    return JSONArray().apply {
      lines.takeLast(50).forEach { line ->
        runCatching { put(JSONObject(line)) }
      }
    }
  }

  private fun crashFiles(): List<File> =
    StorageLayout.logsDir
      .listFiles { f -> f.isFile && f.name.startsWith("crash-") }
      ?.sortedByDescending { it.lastModified() }
      ?.take(5)
      .orEmpty()

  private fun pruneBundles() {
    val bundles = StorageLayout.logsDir
      .listFiles { f -> f.isFile && f.name.startsWith("support-") && f.name.endsWith(".json") }
      ?: return
    if (bundles.size <= MAX_BUNDLES) return
    bundles.sortedByDescending { it.lastModified() }
      .drop(MAX_BUNDLES)
      .forEach { runCatching { it.delete() } }
  }
}
