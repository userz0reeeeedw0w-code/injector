package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.netscope.core.data.local.NetScopeDatabase
import com.netscope.core.data.repository.TrafficRepository
import com.netscope.core.proxy.ca.CaCertificateManager
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.HttpMethod
import com.netscope.shared.model.TimingMetrics
import com.netscope.shared.model.TlsStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `app_name matches NetScope`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("NetScope", context.getString(R.string.app_name))
  }

  @Test
  fun `CaCertificateManager generates valid X509 Root CA even when system BC provider exists`() {
    val dummyBc = object : java.security.Provider("BC", 1.0, "Simulated Android stripped BC") {}
    val priorBc = java.security.Security.getProvider("BC")
    if (priorBc == null) {
      java.security.Security.addProvider(dummyBc)
    }
    try {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val caManager = CaCertificateManager(context)
      val ca = caManager.getOrCreateCa()
      assertTrue(ca.certificate.basicConstraints >= 0)
      assertTrue(ca.fingerprintSha256.isNotBlank())
      assertTrue(caManager.publicCrtFile.exists())
      val intent = caManager.createInstallCaIntent()
      assertNotNull(intent)
    } finally {
      if (priorBc == null) {
        java.security.Security.removeProvider("BC")
      }
    }
  }

  @Test
  fun `Room database records and queries session transactions`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = NetScopeDatabase.createInMemoryForTest(context)
    try {
      val repo = TrafficRepository(db.sessionDao(), db.trafficDao())
      val session = repo.startSession("Unit Test Session", caEnabled = true)
      repo.recordTransaction(
        CapturedTransaction(
          id = "tx-robo-1",
          sessionId = session.id,
          timestampEpochMs = System.currentTimeMillis(),
          appPackage = context.packageName,
          appName = "NetScope",
          method = HttpMethod.GET,
          scheme = "https",
          host = "httpbin.org",
          port = 443,
          path = "/get",
          fullUrl = "https://httpbin.org/get",
          statusCode = 200,
          statusMessage = "OK",
          tlsStatus = TlsStatus.MITM_DECRYPTED,
          responseBody = """{"ok":true}""",
          responseSizeBytes = 11L,
          timings = TimingMetrics(totalMs = 25L),
        )
      )

      val sessions = repo.sessions.first()
      assertEquals(1, sessions.size)
      assertEquals(1, sessions.first().totalRequests)

      val txs = repo.recentTransactions.first()
      assertEquals(1, txs.size)
      assertEquals("httpbin.org", txs.first().host)
    } finally {
      db.close()
    }
  }
}
