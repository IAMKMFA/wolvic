package com.framatome.vr.immersive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.framatome.vr.content.ContentType
import com.framatome.vr.content.ImmersiveIntentFactory
import com.framatome.vr.content.MediaLaunchPayload
import com.framatome.vr.coreui.ViewerGalleryItem
import com.framatome.vr.flatvideo.NativeFlatVideoPlayerActivity
import com.framatome.vr.nativeimage2d.Native2DImageViewerActivity
import com.framatome.vr.nativeimage360.Native360ImageGalleryItem
import com.framatome.vr.nativeimage360.Native360ImageViewerActivity
import com.framatome.vr.nativevideo360.Native360VideoPlayerActivity
import com.framatome.vr.tours.LocalWebServer
import com.framatome.vr.tours.PlaylistRegistry
import java.io.File

/**
 * Routes launcher content intents to the correct in-app viewer inside Framatome Player.
 */
object MediaLaunchRouter {
  private const val TAG = "MediaLaunchRouter"

  /**
   * When true, 2D/360 image and video are rendered immersively through the
   * bundled WebXR media viewer (true per-eye stereo + head tracking), reusing
   * the same Gecko/OpenXR pipeline as tours. When false, the legacy flat
   * native Activities are used. Kept as a flag so the native path stays a
   * one-line fallback if a specific codec/profile needs it.
   */
  private const val USE_IMMERSIVE_WEB_VIEWER = true

  @JvmStatic
  fun isContentLaunch(intent: Intent?): Boolean {
    if (intent == null) return false
    return MediaLaunchPayload.isContentLaunch(intent)
  }

  /**
   * Handles launches that terminate in a standalone native Activity (or an
   * error toast) without continuing into the immersive WebXR renderer.
   *
   * @return true when the launch was fully handled here and the caller must
   *   not proceed to load the WebXR shell.
   */
  @JvmStatic
  fun handleNativeLaunch(activity: Activity, intent: Intent): Boolean {
    val payload = MediaLaunchPayload.fromIntent(intent) ?: return false
    return when (payload.contentType) {
      // Tours and (when enabled) all 2D/360 media render in the WebXR viewer:
      // defer to prepareImmersiveContentIntent by returning false here.
      ContentType.ThreeDVistaTour -> false
      ContentType.Image360 -> launchImmersiveOrNative(activity, payload) { launch360Image(activity, payload) }
      ContentType.Video360 -> launchImmersiveOrNative(activity, payload) { launch360Video(activity, payload) }
      ContentType.Image2D -> launchImmersiveOrNative(activity, payload) { launch2DImage(activity, payload) }
      ContentType.Video2D -> launchImmersiveOrNative(activity, payload) { launchFlatVideo(activity, payload) }
      // 3D models render in the bundled WebXR model viewer (model.html); there
      // is no native fallback Activity for them.
      ContentType.Model3D -> {
        if (payload.mediaViewerUrl() != null) {
          false
        } else {
          Toast.makeText(activity, "This model is outside the FramatomeVR library", Toast.LENGTH_LONG).show()
          activity.finish()
          true
        }
      }
      // Point clouds render in the bundled WebXR cloud viewer (cloud.html);
      // no native fallback Activity, same as 3D models.
      ContentType.PointCloud -> {
        if (payload.mediaViewerUrl() != null) {
          false
        } else {
          Toast.makeText(activity, "This point cloud is outside the FramatomeVR library", Toast.LENGTH_LONG).show()
          activity.finish()
          true
        }
      }
      ContentType.Unsupported -> {
        Toast.makeText(activity, "Unsupported media type", Toast.LENGTH_LONG).show()
        activity.finish()
        true
      }
    }
  }

  private inline fun launchImmersiveOrNative(
    activity: Activity,
    payload: MediaLaunchPayload,
    nativeLaunch: () -> Unit
  ): Boolean {
    // Prefer the immersive WebXR viewer, but only if we can build a viewer URL
    // for this payload; otherwise fall back to the native Activity.
    if (USE_IMMERSIVE_WEB_VIEWER && payload.mediaViewerUrl() != null) return false
    nativeLaunch()
    activity.finish()
    return true
  }

  /**
   * Ensures the player web server is running and normalizes the WebXR launch
   * URL for the current content (tour or immersive media) into the intent.
   */
  @JvmStatic
  fun prepareImmersiveContentIntent(context: Context, intent: Intent): Boolean {
    val payload = MediaLaunchPayload.fromIntent(intent) ?: return false

    LocalWebServer.ensureRunning(context.applicationContext)
    val url = if (payload.contentType == ContentType.ThreeDVistaTour) {
      ImmersiveIntentFactory.resolveTourLaunchUrl(intent, payload)
    } else {
      // Publish the (already windowed) gallery so the viewer can navigate
      // prev/next without relaunching. The registry is process-level, so this
      // works even when ensureRunning is still retrying a failed bind.
      val playlistKey = payload.playlistJson()?.let { json ->
        payload.playlistKey().also { key -> PlaylistRegistry.publish(key, json) }
      }
      payload.mediaViewerUrl(playlistKey)
        ?: ImmersiveIntentFactory.resolveMediaViewerUrl(intent, payload)
    } ?: return false

    ImmersiveIntentFactory.applyImmersiveLaunchExtras(intent, url)
    Log.i(TAG, "Prepared immersive launch (${payload.contentType.token}): $url")
    return true
  }

  /** Kiosk content switches need a full activity restart when the player is already running. */
  @JvmStatic
  fun shouldRelaunchKiosk(intent: Intent): Boolean {
    MediaLaunchPayload.fromIntent(intent) ?: return false
    return intent.getBooleanExtra(ImmersiveIntentFactory.EXTRA_KIOSK, false)
  }

  private fun launch360Image(activity: Activity, payload: MediaLaunchPayload) {
    val gallery = gallery360Items(payload)
    val viewerIntent = Native360ImageViewerActivity.createIntent(
      context = activity,
      title = payload.title,
      path = File(payload.primaryPath),
      projection = payload.projection,
      stereo = payload.stereo,
      rightEyePath = payload.rightEyePath?.let(::File),
      badge = payload.badge.orEmpty().ifBlank { "360 IMAGE" },
      itemId = payload.contentId,
      source = payload.source,
      galleryItems = gallery,
      galleryIndex = payload.galleryIndex
    )
    activity.startActivity(viewerIntent)
  }

  private fun launch360Video(activity: Activity, payload: MediaLaunchPayload) {
    val gallery = galleryItems(payload)
    val viewerIntent = Native360VideoPlayerActivity.createIntent(
      context = activity,
      title = payload.title,
      path = File(payload.primaryPath),
      projection = payload.projection,
      stereo = payload.stereo,
      badge = payload.badge.orEmpty().ifBlank { "360 VIDEO" },
      itemId = payload.contentId,
      source = payload.source,
      galleryItems = gallery,
      galleryIndex = payload.galleryIndex
    )
    activity.startActivity(viewerIntent)
  }

  private fun launch2DImage(activity: Activity, payload: MediaLaunchPayload) {
    val gallery = galleryItems(payload)
    val viewerIntent = Native2DImageViewerActivity.createIntent(
      context = activity,
      title = payload.title,
      path = File(payload.primaryPath),
      badge = payload.badge.orEmpty().ifBlank { "IMAGE" },
      itemId = payload.contentId,
      source = payload.source,
      galleryItems = gallery,
      galleryIndex = payload.galleryIndex
    )
    activity.startActivity(viewerIntent)
  }

  private fun launchFlatVideo(activity: Activity, payload: MediaLaunchPayload) {
    val gallery = galleryItems(payload)
    val viewerIntent = NativeFlatVideoPlayerActivity.createIntent(
      context = activity,
      title = payload.title,
      path = File(payload.primaryPath),
      badge = payload.badge.orEmpty().ifBlank { "VIDEO" },
      itemId = payload.contentId,
      source = payload.source,
      galleryItems = gallery,
      galleryIndex = payload.galleryIndex
    )
    activity.startActivity(viewerIntent)
  }

  private fun gallery360Items(payload: MediaLaunchPayload): List<Native360ImageGalleryItem> {
    if (payload.galleryPaths.isEmpty()) return emptyList()
    return payload.galleryPaths.mapIndexed { index, path ->
      Native360ImageGalleryItem(
        title = payload.galleryTitles.getOrNull(index).orEmpty().ifBlank { File(path).nameWithoutExtension },
        path = File(path),
        projection = payload.galleryProjections.getOrNull(index).nullIfBlank(),
        stereo = payload.galleryStereos.getOrNull(index).nullIfBlank(),
        rightEyePath = payload.galleryRightEyePaths.getOrNull(index).nullIfBlank()?.let(::File),
        badge = payload.galleryBadges.getOrNull(index).orEmpty().ifBlank { "360 IMAGE" },
        thumbnailPath = payload.galleryThumbnailPaths.getOrNull(index).nullIfBlank()?.let(::File),
        itemId = payload.galleryItemIds.getOrNull(index).nullIfBlank(),
        source = payload.gallerySources.getOrNull(index).nullIfBlank()
      )
    }
  }

  private fun galleryItems(payload: MediaLaunchPayload): List<ViewerGalleryItem> {
    if (payload.galleryPaths.isEmpty()) return emptyList()
    return payload.galleryPaths.mapIndexed { index, path ->
      ViewerGalleryItem(
        title = payload.galleryTitles.getOrNull(index).orEmpty().ifBlank { File(path).nameWithoutExtension },
        badge = payload.galleryBadges.getOrNull(index).orEmpty(),
        path = path,
        thumbnailPath = payload.galleryThumbnailPaths.getOrNull(index).nullIfBlank(),
        projection = payload.galleryProjections.getOrNull(index).nullIfBlank(),
        stereo = payload.galleryStereos.getOrNull(index).nullIfBlank(),
        rightEyePath = payload.galleryRightEyePaths.getOrNull(index).nullIfBlank(),
        itemId = payload.galleryItemIds.getOrNull(index).nullIfBlank(),
        source = payload.gallerySources.getOrNull(index).nullIfBlank()
      )
    }
  }

  private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}
