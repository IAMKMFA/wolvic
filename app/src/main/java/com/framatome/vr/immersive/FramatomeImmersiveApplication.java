package com.framatome.vr.immersive;

import com.igalia.wolvic.VRBrowserApplication;

/**
 * Framatome-owned application entry point.
 *
 * Keep the upstream immersive engine behind this wrapper so branding and
 * product wiring can live in Framatome space without renaming the engine
 * packages we still want to patch from upstream.
 */
public class FramatomeImmersiveApplication extends VRBrowserApplication {
}
