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
    assertTrue(HubRequestPolicy.canMutateOperatorState("localhost:18080", "hub"))
    assertFalse(HubRequestPolicy.canMutateOperatorState("localhost:18080", null))
    assertFalse(HubRequestPolicy.canMutateOperatorState("localhost:18080", "Hub"))
    assertFalse(HubRequestPolicy.canMutateOperatorState("127.0.0.1:18080", "hub"))
    assertFalse(HubRequestPolicy.canMutateOperatorState("malicious.invalid", "hub"))
  }

  @Test
  fun `hub responses disable framing and caching`() {
    val headers = HubRequestPolicy.hubResponseHeaders

    assertTrue(headers["Content-Security-Policy"]?.contains("frame-ancestors 'none'") == true)
    assertTrue(headers["X-Frame-Options"] == "DENY")
    assertTrue(headers["Cache-Control"]?.contains("no-store") == true)
  }
}
