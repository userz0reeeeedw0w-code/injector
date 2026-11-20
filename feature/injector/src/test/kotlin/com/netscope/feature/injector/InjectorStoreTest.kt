package com.netscope.feature.injector

import com.netscope.feature.injector.data.InjectorConfig
import com.netscope.feature.injector.data.InjectorScripts
import com.netscope.feature.injector.data.InjectorStore
import com.netscope.feature.injector.data.OverlayDataExtractor
import com.netscope.feature.injector.data.VisitLogEntry
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.HeaderEntry
import com.netscope.shared.model.HttpMethod
import com.netscope.shared.model.TimingMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectorStoreTest {

  @Test
  fun `all injector config and source toggles default to OFF`() {
    val cfg = InjectorConfig()
    assertFalse(cfg.masterEnabled)
    assertFalse(cfg.unlockSelection)
    assertFalse(cfg.forceZoom)
    assertFalse(cfg.remoteDebug)
    assertFalse(cfg.logVisits)
    assertTrue(InjectorScripts.DEFAULT_SOURCES.all { !it.enabled })
  }

  @Test
  fun `extractTgWebAppData decodes initData parameter from WebApp URL`() {
    val url = "https://miniapp.example.com/index.html#tgWebAppData=query_id%3DAAH%26user%3Dtest&tgWebAppVersion=7.0"
    assertEquals("query_id=AAH&user=test", InjectorStore.extractTgWebAppData(url))
  }

  @Test
  fun `buildInjectPayload wraps script body and init code safely`() {
    val payload = InjectorScripts.buildInjectPayload("window.eruda={init:function(){}};", "eruda.init();")
    assertTrue(payload.contains("window.eruda"))
    assertTrue(payload.contains("eruda.init();"))
    assertTrue(payload.startsWith("(function(){try{"))
  }

  @Test
  fun `overlay defaults include VK packages and offline eruda-style devtools`() {
    val cfg = InjectorConfig()
    assertTrue(cfg.overlayTargetPackages.contains("com.vkontakte.android"))
    assertFalse(cfg.overlayOnlyInTargets)
    assertFalse(com.netscope.feature.injector.overlay.InjectorOverlayService.isRunning)
    assertTrue(InjectorScripts.DEFAULT_SOURCES.any { it.id == InjectorScripts.LOCAL_CONSOLE_ID })
    assertTrue(InjectorScripts.BUILTIN_DEVTOOLS_JS.contains("__ns_box"))
    assertTrue(InjectorScripts.BUILTIN_DEVTOOLS_JS.contains("__ns_net_list"))
    assertTrue(InjectorScripts.BUILTIN_DEVTOOLS_JS.contains("XMLHttpRequest"))
    assertTrue(InjectorScripts.buildInjectPayload("1;", "2;", forceShow = true).contains("__netscope_force_show=true"))
  }

  @Test
  fun `overlay data extractor formats network row and extracts tokens`() {
    val tx =
      CapturedTransaction(
        id = "1",
        sessionId = "s1",
        timestampEpochMs = 1000L,
        appPackage = "com.vkontakte.android",
        appName = "VK",
        method = HttpMethod.GET,
        host = "game.vk-apps.com",
        path = "/api/v1/me",
        fullUrl = "https://game.vk-apps.com/api/v1/me?vk_user_id=12345&sign=abcXYZ",
        statusCode = 200,
        requestHeaders = listOf(HeaderEntry("Authorization", "Bearer secret_jwt")),
        responseContentType = "application/json; charset=utf-8",
        responseBody = """{"access_token":"tok_999","uid":42}""",
        responseSizeBytes = 3372L,
        timings = TimingMetrics(totalMs = 58L),
      )

    val row = OverlayDataExtractor.formatNetworkRow(tx)
    assertEquals("me", row.name)
    assertEquals("GET", row.method)
    assertEquals("200", row.status)
    assertEquals("json", row.type)
    assertEquals("3372", row.size)
    assertEquals("58ms", row.time)
    assertTrue(OverlayDataExtractor.isVkRelatedTransaction(tx))

    val curl = OverlayDataExtractor.buildCurlCommand(tx)
    assertTrue(curl.contains("curl -X GET"))
    assertTrue(curl.contains("Authorization: Bearer secret_jwt"))

    val artifacts =
      OverlayDataExtractor.extractArtifacts(
        listOf(tx),
        listOf(VisitLogEntry(1L, "https://vk.com/app", "Game", "query_id=1")),
      )
    assertTrue(artifacts.any { it.label == "Authorization" && it.value == "Bearer secret_jwt" })
    assertTrue(artifacts.any { it.label == "vk_user_id" && it.value == "12345" })
    assertTrue(artifacts.any { it.label == "access_token" && it.value == "tok_999" })
    assertTrue(artifacts.any { it.category == "WebApp initData" && it.value == "query_id=1" })
  }
}
