package com.framatome.vr.immersive;

import android.content.Intent;
import android.os.Bundle;

import com.igalia.wolvic.VRBrowserActivity;

/**
 * Framatome-owned activity entry point for the immersive WebXR viewer.
 *
 * Routes all launcher content through {@link MediaLaunchRouter}. Tours and 2D/360
 * media render immersively through the WebXR pipeline; only "coming soon" and
 * unsupported types (or a flagged native fallback) terminate before the shell.
 */
public class FramatomeImmersiveActivity extends VRBrowserActivity {

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
}
