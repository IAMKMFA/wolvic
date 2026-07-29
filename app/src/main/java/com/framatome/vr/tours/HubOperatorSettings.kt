package com.framatome.vr.tours

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.igalia.wolvic.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Operator-facing status and maintenance actions used by the hub settings
 * panel. Actions are deliberately narrow: source content is never deleted.
 */
object HubOperatorSettings {
  data class ActionResult(
    val success: Boolean,
    val message: String,
    val changed: Boolean = false,
    val deletedPreviews: Int = 0,
    val supportId: String? = null
  ) {
    fun toJson(): String = JSONObject().apply {
      put("success", success)
      put("message", message)
      put("changed", changed)
      put("deletedPreviews", deletedPreviews)
      supportId?.let { put("supportId", it) }
    }.toString()
  }

  fun statusJson(context: Context): String {
    val app = context.applicationContext
    val snapshot = MediaLibraryIndex.snapshot()
    val tours = allTours(app)
    val config = FramatomeInitializer.deploymentConfig()
    val refresh = FramatomeInitializer.contentRefreshStatus()
    val externalStorage = Environment.getExternalStorageDirectory()
    val dropRoots = config?.dropRoots
      ?.map(::displayPath)
      ?.takeIf { it.isNotEmpty() }
      ?: listOf("/sdcard/FramatomeVR/Data/")

    return JSONObject().apply {
      put("version", BuildConfig.VERSION_NAME)
      put("packageName", app.packageName)
      put("serverPort", LocalWebServer.PORT)
      put("servicesReady", FramatomeInitializer.contentServicesReady())
      put("storageGranted", MediaLibraryIndex.isStorageGranted())
      put("freeBytes", runCatching { externalStorage.usableSpace }.getOrDefault(0L))
      put("rootPath", "/sdcard/FramatomeVR/")
      put("dataPath", dropRoots.first())
      put("dropRoots", JSONArray(dropRoots))
      put(
        "content",
        JSONObject().apply {
          put("toursCount", tours.count { it.enabled })
          put("collectionsCount", snapshot.collections.count { it.items.isNotEmpty() })
          put("videosCount", snapshot.videos.size)
          put("imagesCount", snapshot.images.size)
          put("modelsCount", snapshot.models.size)
          put("cloudsCount", snapshot.clouds.size)
          put("issuesCount", snapshot.issues.size)
          put("scannedAtMs", snapshot.scannedAtMs)
        }
      )
      put("refresh", JSONObject(refresh.toJson()))
      put(
        "tours",
        JSONArray().apply {
          tours.forEach { tour ->
            put(
              JSONObject().apply {
                put("name", tour.name)
                put("relativePath", tour.relativePath)
                put("enabled", tour.enabled)
              }
            )
          }
        }
      )
    }.toString()
  }

  fun setTourEnabled(
    context: Context,
    relativePath: String?,
    enabled: Boolean?
  ): ActionResult {
    val path = relativePath?.trim().orEmpty()
    if (path.isBlank() || enabled == null) {
      return ActionResult(false, "A valid tour and visibility state are required.")
    }
    val app = context.applicationContext as? Application
      ?: return ActionResult(false, "Tour settings are unavailable.")
    val store = TourSettingsStore(app)
    val tour = TourCatalog.scan(TourStorage.sharedToursDir(), store::isEnabled)
      .firstOrNull { it.relativePath == path }
      ?: return ActionResult(false, "That tour is no longer installed.")

    if (tour.enabled == enabled) {
      return ActionResult(true, "Tour visibility is already up to date.")
    }

    store.setEnabled(path, enabled)
    HubIndexServer.invalidate()
    return ActionResult(
      success = true,
      message = if (enabled) {
        "${tour.name} is now visible in the library."
      } else {
        "${tour.name} is hidden from the library."
      },
      changed = true
    )
  }

  fun clearPreviews(context: Context): ActionResult {
    val deleted = ThumbnailStore.clear(context.applicationContext)
    HubIndexServer.invalidate()
    return ActionResult(
      success = true,
      message = if (deleted == 1) {
        "One generated preview was cleared."
      } else {
        "$deleted generated previews were cleared."
      },
      changed = deleted > 0,
      deletedPreviews = deleted
    )
  }

  /**
   * Write everything support would ask for to a file the customer's ArborXR
   * admin can pull, and hand back an identifier they can read to us over the
   * phone. Without this, "it stopped working" is all we ever get.
   */
  fun createSupportBundle(context: Context): ActionResult {
    val bundle = PlayerDiagnostics.writeSupportBundle(context.applicationContext)
      ?: return ActionResult(
        success = false,
        message = "Could not save the report. Check that headset storage is " +
          "available and not full, then try again."
      )
    return ActionResult(
      success = true,
      message = "Report ${bundle.supportId} saved to FramatomeVR/Logs. " +
        "Quote this reference when you contact support.",
      changed = false,
      supportId = bundle.supportId
    )
  }

  fun openStorageSettings(context: Context): ActionResult {
    val app = context.applicationContext
    val intents = buildList {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        add(
          Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", app.packageName, null)
          )
        )
        add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
      }
      add(
        Intent(
          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.fromParts("package", app.packageName, null)
        )
      )
    }
    val opened = intents.any { intent ->
      runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
      }.isSuccess
    }
    return if (opened) {
      ActionResult(true, "Android storage settings opened.")
    } else {
      ActionResult(
        false,
        "Storage settings are unavailable. Grant All Files Access from ArborXR."
      )
    }
  }

  private fun allTours(context: Context): List<Tour> {
    val app = context.applicationContext as? Application
    val store = app?.let(::TourSettingsStore)
    return runCatching {
      TourCatalog.scan(TourStorage.sharedToursDir()) { relativePath ->
        store?.isEnabled(relativePath) ?: true
      }
    }.getOrDefault(emptyList())
  }

  private fun displayPath(file: File): String {
    val root = runCatching { Environment.getExternalStorageDirectory().canonicalFile }
      .getOrElse { Environment.getExternalStorageDirectory().absoluteFile }
    val canonical = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
    if (canonical == root) return "/sdcard/"
    val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
    if (canonical.path.startsWith(rootPrefix)) {
      val relative = canonical.path.removePrefix(rootPrefix).replace(File.separatorChar, '/')
      return "/sdcard/${relative.trim('/')}/"
    }
    return canonical.path.trimEnd(File.separatorChar) + File.separator
  }
}
