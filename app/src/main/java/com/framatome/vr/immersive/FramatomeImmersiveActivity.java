package com.framatome.vr.immersive;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;

import com.framatome.vr.tours.FramatomeInitializer;
import com.igalia.wolvic.VRBrowserActivity;

/**
 * Framatome-owned activity entry point for the immersive WebXR viewer.
 *
 * Routes all launcher content through {@link MediaLaunchRouter}. Tours and 2D/360
 * media render immersively through the WebXR pipeline; only "coming soon" and
 * unsupported types (or a flagged native fallback) terminate before the shell.
 */
public class FramatomeImmersiveActivity extends VRBrowserActivity {

    // Prompt for All-Files-Access at most once per activity instance so we never
    // loop when the operator returns from Settings without granting.
    private boolean storagePromptShown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent intent = getIntent();
        if (MediaLaunchRouter.handleNativeLaunch(this, intent)) {
            return;
        }
        MediaLaunchRouter.prepareImmersiveContentIntent(this, intent);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        if (MediaLaunchRouter.handleNativeLaunch(this, intent)) {
            return;
        }
        MediaLaunchRouter.prepareImmersiveContentIntent(this, intent);
        if (MediaLaunchRouter.shouldRelaunchKiosk(intent)) {
            finish();
            startActivity(intent);
            return;
        }
        super.onNewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureAllFilesAccess();
    }

    /**
     * The Player reads/serves and now ingests the shared {@code /sdcard/FramatomeVR}
     * content, which requires All-Files-Access (MANAGE_EXTERNAL_STORAGE) on
     * Android 11+. ArborXR normally grants this via managed policy; this is the
     * fallback for a bare install where it wasn't. If already granted, we stay
     * silent and just nudge ingest to pick up anything dropped while backgrounded;
     * otherwise we bounce the operator to the grant screen once.
     */
    private void ensureAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        if (Environment.isExternalStorageManager()) {
            FramatomeInitializer.requestReconcile();
            return;
        }
        if (storagePromptShown) {
            return;
        }
        storagePromptShown = true;
        try {
            startActivity(new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (ActivityNotFoundException ignored) {
                // No storage-settings surface available (unusual on Quest); the
                // in-hub banner still instructs granting via adb/ArborXR.
            }
        }
    }
}
