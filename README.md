# NetScope — Autonomous Android Network Inspector & API Client

NetScope is a non-root Android network traffic monitor, local VPN/MITM debugging proxy, and Postman-style API client built with Kotlin, Jetpack Compose, Material 3, Netty, OkHttp, Room, and SQLCipher.

---

## 1. Module Architecture

- **`:core:shared`** — Pure-Kotlin KMP-ready domain models, analytics aggregators, HAR 1.2 / JSON / cURL exporters, JSON pretty-printer, and `{{variable}}` template engine (zero Android dependencies; directly shareable with Kotlin Multiplatform Desktop/iOS targets).
- **`:core:data`** — Encrypted Room database (`SQLCipher` + passphrase wrapped via `AndroidKeyStore` AES-256-GCM), DataStore preferences, scheduled retention cleanup, and `PdfDocument` audit report generator.
- **`:core:proxy`** — BouncyCastle X.509 v3 Root CA generator, dynamic per-host leaf certificate generator, Netty HTTP/1.1 + HTTPS MITM + WebSocket proxy, and SSL-pinning compatibility inspector.
- **`:core:network`** — `NetScopeVpnService` (isolated `android:process=":proxy"`), IPv4/UDP DNS `TunPacketForwarder`, cross-process `ProxyIpcBridge`, and `ApiClientExecutor` (OkHttp + `EventListener` DNS/TLS/TTFB timings).
- **`:feature:traffic`** — Live packet inspector, multi-dimensional filters, timing waterfall bottom sheet, and draggable/collapsible `OverlayMonitorService` (`SYSTEM_ALERT_WINDOW`).
- **`:feature:apiclient`** — Request builder (GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS), params/headers/cookies/auth/body editors, collections, environments, and cURL export.
- **`:feature:analytics`** — Dashboard telemetry, Jetpack Compose `Canvas` charts (top domains & status distribution), and SAF / `FileProvider` exports (JSON, HAR 1.2, PDF).
- **`:feature:settings`** — 3-step onboarding & CA setup, searchable history & replay, retention schedule, and optional rooted Frida port diagnostics.
- **`:app`** — Navigation Compose (`NavHost` + adaptive `NavigationBar` / `NavigationRail`), `NetScopeViewModel`, and Material 3 Cyber-Telemetry theme.

---

## 2. Installing the Local CA Certificate (Android 7+)

1. Open **Settings → Install User CA** (or complete Step 2 of Onboarding).
2. Confirm with your device PIN / biometrics.
3. Alternatively, tap **Export .crt** and install manually via:
   `Android Settings → Security → Encryption & credentials → Install a certificate → CA certificate`.
4. **For your own Android apps under test**, Android 7.0+ ignores user CAs by default unless configured in `res/xml/network_security_config.xml` for debug builds:

```xml
<network-security-config>
    <debug-overrides>
        <trust-anchors>
            <certificates src="user" />
            <certificates src="system" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

---

## 3. Security Disclaimer & Technical Limitations (Read Carefully)

- **User CA Security Risk:** Installing any user Root CA widens TLS trust on your device. Remove the `NetScope Local CA` from Android Credential Storage when you finish auditing.
- **SSL Certificate Pinning (Non-Root):** On non-root devices, NetScope **cannot** decrypt traffic from third-party apps that enforce certificate pinning or reject user-installed CAs. NetScope automatically detects TLS handshake rejections and switches those hosts to **Compatibility Passthrough** so the target app continues functioning without breaking. Inspecting pinned traffic requires either a debuggable/patched APK or a rooted test device with external instrumentation (e.g., Frida).
- **Google Play & Privacy Policy:** NetScope captures **only** local traffic on the user's own device after explicit VPN consent (`VpnService.prepare`) and displays a persistent foreground notification. No data is transmitted to external analytics or third-party servers.
- **Cross-Platform / iOS Note:** `:core:shared` contains platform-agnostic Kotlin models and exporters for Android + Desktop KMP sharing. On **iOS**, background system-wide MITM capture without jailbreak is restricted by iOS sandbox/NetworkExtension entitlements; therefore, an iOS companion build is scoped strictly to the **API Client** and **Exported Session Viewer (JSON/HAR)**.

---

## 4. Building & Signing Release APK

1. Generate or provide a release keystore (`my-upload-key.jks`):
   ```bash
   keytool -genkey -v -keystore my-upload-key.jks \
     -keyalg RSA -keysize 2048 -validity 10000 -alias upload
   ```
2. Export signing credentials as environment variables (do **not** commit passwords):
   ```bash
   export KEYSTORE_PATH="$(pwd)/my-upload-key.jks"
   export STORE_PASSWORD="your_store_password"
   export KEY_PASSWORD="your_key_password"
   ```
3. Build the signed release APK or App Bundle:
   ```bash
   gradle :app:assembleRelease
   # Or for Google Play AAB:
   gradle :app:bundleRelease
   ```
4. Run unit and Robolectric tests:
   ```bash
   gradle :core:shared:test :app:testDebugUnitTest
   ```
