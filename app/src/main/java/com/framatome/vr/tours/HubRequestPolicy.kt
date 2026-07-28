package com.framatome.vr.tours

import java.util.Locale
import java.util.UUID

/** Security boundary between trusted hub pages and delivered tour/media code. */
internal object HubRequestPolicy {
  const val HUB_HOST = "localhost"
  const val REQUEST_HEADER = "x-framatome-request"

  /**
   * Proof that a mutating request came from hub markup this process served,
   * rather than from delivered tour code or any other page the engine loaded.
   *
   * Regenerated per process start and never persisted: the hub page and the
   * server that validates it share a lifetime, so a restart reissues both
   * together. A fixed literal would be guessable by anything that manages to
   * reach the loopback port, which is the one thing this check exists to stop.
   */
  val REQUEST_VALUE: String = UUID.randomUUID().toString()

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
