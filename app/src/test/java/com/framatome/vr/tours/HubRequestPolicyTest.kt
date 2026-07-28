package com.framatome.vr.tours

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubRequestPolicyTest {
  @Test
  fun `only localhost is the trusted hub origin`() {
    assertTrue(HubRequestPolicy.isTrustedHubHost("localhost"))
    assertTrue(HubRequestPolicy.isTrustedHubHost("LOCALHOST:18080"))
    assertFalse(HubRequestPolicy.isTrustedHubHost("127.0.0.1:18080"))
    assertFalse(HubRequestPolicy.isTrustedHubHost("localhost.example"))
    assertFalse(HubRequestPolicy.isTrustedHubHost(null))
  }

  @Test
  fun `operator mutation requires trusted origin and exact proof header`() {
    val token = HubRequestPolicy.REQUEST_VALUE

    assertTrue(HubRequestPolicy.canMutateOperatorState("localhost:18080", token))
    assertFalse(HubRequestPolicy.canMutateOperatorState("localhost:18080", null))
    assertFalse(HubRequestPolicy.canMutateOperatorState("localhost:18080", token.uppercase()))
    assertFalse(HubRequestPolicy.canMutateOperatorState("127.0.0.1:18080", token))
    assertFalse(HubRequestPolicy.canMutateOperatorState("malicious.invalid", token))
  }

  @Test
  fun `proof header is an unguessable per-process token, not a fixed literal`() {
    val token = HubRequestPolicy.REQUEST_VALUE

    // The pre-hardening literal must no longer open the operator API.
    assertFalse(HubRequestPolicy.canMutateOperatorState("localhost:18080", "hub"))
    assertTrue(token.length >= 32)
    assertTrue(HubRequestPolicy.REQUEST_VALUE === token)
  }

  @Test
  fun `hub responses disable framing and caching`() {
    val headers = HubRequestPolicy.hubResponseHeaders

    assertTrue(headers["Content-Security-Policy"]?.contains("frame-ancestors 'none'") == true)
    assertTrue(headers["X-Frame-Options"] == "DENY")
    assertTrue(headers["Cache-Control"]?.contains("no-store") == true)
  }
}
