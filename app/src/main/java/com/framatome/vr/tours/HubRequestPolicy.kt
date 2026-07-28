package com.framatome.vr.tours

import java.util.Locale

/** Security boundary between trusted hub pages and delivered tour/media code. */
internal object HubRequestPolicy {
  const val HUB_HOST = "localhost"
  const val REQUEST_HEADER = "x-framatome-request"
  const val REQUEST_VALUE = "hub"

  fun isTrustedHubHost(hostHeader: String?): Boolean {
    val host = hostHeader
      ?.substringBefore(':')
      ?.trim()
      ?.lowercase(Locale.US)
    return host == HUB_HOST
  }

  fun canMutateOperatorState(hostHeader: String?, requestHeader: String?): Boolean =
    isTrustedHubHost(hostHeader) && requestHeader == REQUEST_VALUE

  val hubResponseHeaders: Map<String, String> = mapOf(
    "Cache-Control" to "no-store, no-cache, max-age=0",
    "Pragma" to "no-cache",
    "Content-Security-Policy" to "frame-ancestors 'none'",
    "X-Frame-Options" to "DENY"
  )
}
