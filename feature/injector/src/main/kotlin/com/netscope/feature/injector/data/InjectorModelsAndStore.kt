package com.netscope.feature.injector.data

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class JsSourceItem(
  val id: String,
  val name: String,
  val url: String,
  val initCode: String = "",
  val enabled: Boolean = false,
  val cachedBytes: Int = 0,
)

@Serializable
data class InjectorConfig(
  val masterEnabled: Boolean = false,
  val unlockSelection: Boolean = false,
  val forceZoom: Boolean = false,
  val remoteDebug: Boolean = false,
  val logVisits: Boolean = false,
  val disclaimerAccepted: Boolean = false,
  val overlayTargetPackages: String = "com.vkontakte.android,com.vk.im,com.vk.vkvideo",
  val overlayOnlyInTargets: Boolean = false,
)

@Serializable
data class VisitLogEntry(
  val timestampSec: Long,
  val url: String,
  val title: String = "",
  val initData: String = "",
)

object InjectorScripts {
  const val LOCAL_CONSOLE_ID = "netscope_console"
  const val LOCAL_CONSOLE_URL = "local://netscope-console"

  const val JS_UNLOCK_SELECTION =
    "(function(){try{var s=document.createElement('style');" +
      "s.innerHTML='*{-webkit-user-select:text!important;user-select:text!important;" +
      "-webkit-touch-callout:default!important;}';" +
      "(document.head||document.documentElement).appendChild(s);}catch(e){}})();"

  const val JS_FORCE_ZOOM =
    "(function(){try{var m=document.querySelector('meta[name=viewport]');" +
      "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
      "m.setAttribute('content','width=device-width, initial-scale=1, maximum-scale=6, user-scalable=yes');" +
      "}catch(e){}})();"

  const val JS_INSPECTOR_INFO =
    "(function(){try{" +
      "var w=window.Telegram&&window.Telegram.WebApp||{};" +
      "var lsKeys=[];try{for(var i=0;i<localStorage.length;i++)lsKeys.push(localStorage.key(i));}catch(_){}" +
      "var ssKeys=[];try{for(var j=0;j<sessionStorage.length;j++)ssKeys.push(sessionStorage.key(j));}catch(_){}" +
      "return JSON.stringify({" +
      "title:document.title," +
      "url:location.href," +
      "userAgent:navigator.userAgent," +
      "cookiesCount:document.cookie?document.cookie.split(';').length:0," +
      "localStorageKeys:lsKeys," +
      "sessionStorageKeys:ssKeys," +
      "telegramWebApp:{" +
      "version:w.version||null," +
      "platform:w.platform||null," +
      "colorScheme:w.colorScheme||null," +
      "initData:w.initData||null," +
      "user:(w.initDataUnsafe&&w.initDataUnsafe.user)||null" +
      "}" +
      "},null,2);}catch(e){return 'err: '+e;}})();"

  /**
   * Встроенная автономная DevTools-консоль (работает 100% без CDN и при жёстком CSP):
   * перехватывает console.log/warn/error, fetch, XMLHttpRequest, cookies/localStorage и позволяет выполнять JS.
   */
  const val BUILTIN_DEVTOOLS_JS =
    """
    (function(){
      try {
        if (window.__nsPanelInit) {
          var existing = document.getElementById('__ns_box');
          if (existing) existing.style.display = 'flex';
          return;
        }
        window.__nsPanelInit = true;
        var logs = [];
        function addLog(kind, msg) {
          var t = new Date().toTimeString().slice(0,8);
          logs.unshift('[' + t + ' ' + kind + '] ' + String(msg));
          if (logs.length > 80) logs.pop();
          var out = document.getElementById('__ns_logs');
          if (out) out.textContent = logs.join('\n');
        }
        ['log','warn','error','info'].forEach(function(k){
          var orig = console[k];
          console[k] = function(){
            try {
              var txt = Array.prototype.slice.call(arguments).map(function(a){
                return typeof a === 'object' ? JSON.stringify(a) : String(a);
              }).join(' ');
              addLog(k.toUpperCase(), txt);
            } catch(_){}
            if (orig) return orig.apply(console, arguments);
          };
        });
        if (window.fetch) {
          var _f = window.fetch;
          window.fetch = function(input, init){
            var u = typeof input === 'string' ? input : (input && input.url) || '';
            addLog('FETCH', ((init && init.method) || 'GET') + ' ' + u);
            return _f.apply(this, arguments).then(function(r){
              addLog('RESP', r.status + ' ' + u);
              return r;
            });
          };
        }
        var root = document.documentElement || document.body;
        var btn = document.createElement('div');
        btn.id = '__ns_fab';
        btn.textContent = 'NS JS';
        btn.style.cssText = 'position:fixed;right:12px;bottom:14px;z-index:2147483647;background:#0b1220;color:#00e5ff;border:2px solid #00e5ff;border-radius:999px;padding:8px 12px;font:bold 12px monospace;box-shadow:0 4px 16px rgba(0,0,0,.5);';
        var box = document.createElement('div');
        box.id = '__ns_box';
        box.style.cssText = 'position:fixed;left:8px;right:8px;bottom:56px;height:46vh;z-index:2147483647;background:rgba(11,18,32,0.96);color:#e2e8f0;border:1px solid #00e5ff;border-radius:12px;display:flex;flex-direction:column;padding:8px;font:12px monospace;box-sizing:border-box;';
        box.innerHTML =
          '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">' +
          '<b style="color:#00e5ff">NetScope DevTools (Офлайн)</b>' +
          '<div><button id="__ns_info" style="margin-right:4px;background:#1e293b;color:#fff;border:0;border-radius:6px;padding:4px 8px">Storage</button>' +
          '<button id="__ns_hide" style="background:#334155;color:#fff;border:0;border-radius:6px;padding:4px 8px">Свернуть</button></div></div>' +
          '<div style="display:flex;gap:4px;margin-bottom:6px">' +
          '<input id="__ns_in" placeholder="JS код (напр. document.title)" style="flex:1;background:#172134;color:#fff;border:1px solid #334155;border-radius:6px;padding:6px;font:12px monospace"/>' +
          '<button id="__ns_run" style="background:#00e5ff;color:#060c16;border:0;border-radius:6px;padding:6px 10px;font-weight:bold">Run</button></div>' +
          '<pre id="__ns_logs" style="flex:1;overflow:auto;margin:0;white-space:pre-wrap;word-break:break-all;font:11px monospace;color:#34d399"></pre>';
        root.appendChild(box);
        root.appendChild(btn);
        btn.onclick = function(){ box.style.display = (box.style.display === 'none') ? 'flex' : 'none'; };
        document.getElementById('__ns_hide').onclick = function(){ box.style.display = 'none'; };
        document.getElementById('__ns_run').onclick = function(){
          var code = document.getElementById('__ns_in').value;
          try { addLog('EVAL', JSON.stringify(eval(code))); } catch(e) { addLog('ERR', e); }
        };
        document.getElementById('__ns_info').onclick = function(){
          addLog('COOKIE', document.cookie || '(пусто)');
          try { addLog('LS_KEYS', Object.keys(localStorage).join(', ') || '(пусто)'); } catch(e){}
        };
        addLog('READY', location.href);
      } catch(e) { console.error(e); }
    })();
    """

  val DEFAULT_SOURCES: List<JsSourceItem> =
    listOf(
      JsSourceItem(
        id = "eruda",
        name = "eruda DevTools",
        url = "https://cdn.jsdelivr.net/npm/eruda",
        initCode = "if(window.eruda){if(!window.__netscope_eruda){window.eruda.init();window.__netscope_eruda=true;}if(window.__netscope_force_show&&window.eruda.show){window.eruda.show();}}",
        enabled = false,
      ),
      JsSourceItem(
        id = "vconsole",
        name = "vConsole",
        url = "https://unpkg.com/vconsole@3/dist/vconsole.min.js",
        initCode = "if(window.VConsole&&!window.__netscope_vconsole){window.__netscope_vconsole=new window.VConsole();}",
        enabled = false,
      ),
      JsSourceItem(
        id = LOCAL_CONSOLE_ID,
        name = "Консоль NetScope (офлайн)",
        url = LOCAL_CONSOLE_URL,
        initCode = "",
        enabled = false,
        cachedBytes = BUILTIN_DEVTOOLS_JS.toByteArray(Charsets.UTF_8).size,
      ),
    )

  fun buildInjectPayload(scriptBody: String, initCode: String, forceShow: Boolean = false): String {
    val flagPrefix = if (forceShow) "window.__netscope_force_show=true;" else "window.__netscope_force_show=false;"
    return "(function(){try{$flagPrefix$scriptBody\n;$initCode}catch(e){console.error('NetScope Injector:',e);}})();"
  }
}

class InjectorStore(context: Context) {
  private val appCtx = context.applicationContext
  private val prefs = appCtx.getSharedPreferences("netscope_injector_prefs", Context.MODE_PRIVATE)
  private val cacheDir = File(appCtx.filesDir, "injector_js_cache").apply { mkdirs() }
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
  }

  private val httpClient =
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .build()

  private val _config = MutableStateFlow(loadConfig())
  val config: StateFlow<InjectorConfig> = _config.asStateFlow()

  private val _sources = MutableStateFlow(loadSources())
  val sources: StateFlow<List<JsSourceItem>> = _sources.asStateFlow()

  private val _visits = MutableStateFlow(loadVisits())
  val visits: StateFlow<List<VisitLogEntry>> = _visits.asStateFlow()

  fun updateConfig(transform: (InjectorConfig) -> InjectorConfig) {
    val updated = transform(_config.value)
    _config.value = updated
    prefs.edit().putString(KEY_CONFIG, json.encodeToString(InjectorConfig.serializer(), updated)).apply()
  }

  fun toggleSource(id: String, enabled: Boolean) {
    val updated = _sources.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    saveSources(updated)
  }

  fun addCustomSource(url: String, initCode: String = ""): JsSourceItem {
    val cleanUrl = url.trim()
    val name =
      cleanUrl.substringAfterLast('/').substringBefore('?').take(24).ifBlank { "Свой JS" }
    val item =
      JsSourceItem(
        id = "u${System.currentTimeMillis()}",
        name = name,
        url = cleanUrl,
        initCode = initCode.trim(),
        enabled = true,
      )
    saveSources(_sources.value + item)
    return item
  }

  fun removeSource(id: String) {
    cacheFileFor(id).delete()
    saveSources(_sources.value.filterNot { it.id == id })
  }

  fun resetSourcesToDefault() {
    cacheDir.listFiles()?.forEach { it.delete() }
    saveSources(InjectorScripts.DEFAULT_SOURCES)
  }

  fun getCachedScript(sourceId: String): String? {
    if (sourceId == InjectorScripts.LOCAL_CONSOLE_ID) {
      return InjectorScripts.BUILTIN_DEVTOOLS_JS
    }
    val file = cacheFileFor(sourceId)
    if (!file.exists()) return null
    return runCatching {
      val b64 = file.readText(Charsets.UTF_8)
      String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()
  }

  suspend fun fetchAndCacheSource(source: JsSourceItem): Result<Int> =
    withContext(Dispatchers.IO) {
      if (source.url == InjectorScripts.LOCAL_CONSOLE_URL || source.id == InjectorScripts.LOCAL_CONSOLE_ID) {
        val bytes = InjectorScripts.BUILTIN_DEVTOOLS_JS.toByteArray(Charsets.UTF_8).size
        return@withContext Result.success(bytes)
      }
      runCatching {
        val urlsToTry =
          when (source.id) {
            "eruda" ->
              listOf(
                source.url,
                "https://cdnjs.cloudflare.com/ajax/libs/eruda/3.4.1/eruda.min.js",
                "https://unpkg.com/eruda/eruda.js",
              )
            "vconsole" ->
              listOf(
                source.url,
                "https://cdn.jsdelivr.net/npm/vconsole@3/dist/vconsole.min.js",
              )
            else -> listOf(source.url)
          }.distinct()

        var body: String? = null
        var lastErr: Throwable? = null
        for (candidate in urlsToTry) {
          val attempt = runCatching {
            val req =
              Request.Builder()
                .url(candidate)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            httpClient.newCall(req).execute().use { resp ->
              if (!resp.isSuccessful) error("HTTP ${resp.code} ${resp.message}")
              val text = resp.body?.string()?.takeIf { it.isNotBlank() } ?: error("Пустой JS-файл")
              val trimmed = text.trimStart()
              if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
                error("Сервер вернул HTML вместо JS")
              }
              text
            }
          }
          if (attempt.isSuccess) {
            body = attempt.getOrNull()
            break
          } else {
            lastErr = attempt.exceptionOrNull()
          }
        }
        val finalBody = body ?: throw (lastErr ?: IllegalStateException("Не удалось скачать скрипт"))
        val encoded = Base64.encodeToString(finalBody.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        cacheFileFor(source.id).writeText(encoded, Charsets.UTF_8)
        val byteCount = finalBody.toByteArray(Charsets.UTF_8).size
        saveSources(
          _sources.value.map {
            if (it.id == source.id) it.copy(cachedBytes = byteCount) else it
          }
        )
        byteCount
      }
    }

  fun logVisit(url: String, title: String) {
    if (url.isBlank() || url == "about:blank") return
    val current = _visits.value
    if (current.firstOrNull()?.url == url) return
    val initData = extractTgWebAppData(url)
    val entry =
      VisitLogEntry(
        timestampSec = System.currentTimeMillis() / 1000L,
        url = url,
        title = title,
        initData = initData,
      )
    val updated = (listOf(entry) + current).take(200)
    _visits.value = updated
    prefs
      .edit()
      .putString(KEY_VISITS, json.encodeToString(ListSerializer(VisitLogEntry.serializer()), updated))
      .apply()
  }

  fun clearVisits() {
    _visits.value = emptyList()
    prefs.edit().remove(KEY_VISITS).apply()
  }

  fun exportVisitsJson(): String =
    json.encodeToString(ListSerializer(VisitLogEntry.serializer()), _visits.value)

  private fun cacheFileFor(id: String): File {
    val safe = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return File(cacheDir, "$safe.b64")
  }

  private fun saveSources(list: List<JsSourceItem>) {
    val synced =
      list.map { item ->
        val file = cacheFileFor(item.id)
        if (item.id == InjectorScripts.LOCAL_CONSOLE_ID) {
          item.copy(cachedBytes = InjectorScripts.BUILTIN_DEVTOOLS_JS.toByteArray(Charsets.UTF_8).size)
        } else if (file.exists() && item.cachedBytes == 0) {
          val decodedLen = getCachedScript(item.id)?.toByteArray(Charsets.UTF_8)?.size ?: 0
          item.copy(cachedBytes = decodedLen)
        } else item
      }
    _sources.value = synced
    prefs
      .edit()
      .putString(KEY_SOURCES, json.encodeToString(ListSerializer(JsSourceItem.serializer()), synced))
      .apply()
  }

  private fun loadConfig(): InjectorConfig {
    val raw = prefs.getString(KEY_CONFIG, null) ?: return InjectorConfig()
    return runCatching { json.decodeFromString(InjectorConfig.serializer(), raw) }
      .getOrDefault(InjectorConfig())
  }

  private fun loadSources(): List<JsSourceItem> {
    val raw = prefs.getString(KEY_SOURCES, null) ?: return InjectorScripts.DEFAULT_SOURCES
    return runCatching {
        val parsed = json.decodeFromString(ListSerializer(JsSourceItem.serializer()), raw)
        if (parsed.isEmpty()) InjectorScripts.DEFAULT_SOURCES
        else {
          // Убеждаемся, что встроенная офлайн-консоль присутствует в списке источников
          val hasBuiltin = parsed.any { it.id == InjectorScripts.LOCAL_CONSOLE_ID }
          val base = if (hasBuiltin) parsed else parsed + InjectorScripts.DEFAULT_SOURCES.last()
          // Обновляем initCode для eruda на актуальный
          base.map {
            if (it.id == "eruda") it.copy(initCode = InjectorScripts.DEFAULT_SOURCES.first().initCode)
            else it
          }
        }
      }
      .getOrDefault(InjectorScripts.DEFAULT_SOURCES)
  }

  private fun loadVisits(): List<VisitLogEntry> {
    val raw = prefs.getString(KEY_VISITS, null) ?: return emptyList()
    return runCatching {
        json.decodeFromString(ListSerializer(VisitLogEntry.serializer()), raw)
      }
      .getOrDefault(emptyList())
  }

  companion object {
    private const val KEY_CONFIG = "injector_config_v1"
    private const val KEY_SOURCES = "injector_sources_v1"
    private const val KEY_VISITS = "injector_visits_v1"

    fun extractTgWebAppData(url: String): String {
      val low = url.lowercase()
      val marker = "tgwebappdata="
      val idx = low.indexOf(marker)
      if (idx == -1) return ""
      val start = idx + marker.length
      val end = url.indexOf('&', start)
      val raw = if (end == -1) url.substring(start) else url.substring(start, end)
      return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }
  }
}
