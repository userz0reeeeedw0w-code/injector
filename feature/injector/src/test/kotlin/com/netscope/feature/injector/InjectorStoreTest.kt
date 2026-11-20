package com.netscope.feature.injector

import com.netscope.feature.injector.data.InjectorConfig
import com.netscope.feature.injector.data.InjectorScripts
import com.netscope.feature.injector.data.InjectorStore
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
  fun `overlay defaults include VK packages and start inactive`() {
    val cfg = InjectorConfig()
    assertTrue(cfg.overlayTargetPackages.contains("com.vkontakte.android"))
    assertFalse(cfg.overlayOnlyInTargets)
    assertFalse(com.netscope.feature.injector.overlay.InjectorOverlayService.isRunning)
    assertTrue(InjectorScripts.DEFAULT_SOURCES.any { it.id == InjectorScripts.LOCAL_CONSOLE_ID })
    assertTrue(InjectorScripts.BUILTIN_DEVTOOLS_JS.contains("__ns_box"))
    assertTrue(InjectorScripts.buildInjectPayload("1;", "2;", forceShow = true).contains("__netscope_force_show=true"))
  }
}
