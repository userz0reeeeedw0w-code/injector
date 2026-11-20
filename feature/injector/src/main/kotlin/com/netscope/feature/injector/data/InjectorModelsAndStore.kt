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
   * Лёгкий JS-перехватчик fetch + XMLHttpRequest для моста WebView -> NetScope (без показа UI).
   */
  const val JS_NETWORK_BRIDGE_HOOK =
    """
    (function(){
      try {
        if (window.__nsNetHooked) return;
        window.__nsNetHooked = true;
        window.__nsNetRecords = window.__nsNetRecords || [];
        function shortName(u) {
          try {
            var p = new URL(u, location.href).pathname.replace(/\/+$/,'').split('/');
            return (p[p.length-1] || new URL(u, location.href).hostname || u).slice(0,28);
          } catch(_) { return String(u).slice(0,28); }
        }
        function emitNet(rec) {
          try {
            window.__nsNetRecords.unshift(rec);
            if (window.__nsNetRecords.length > 100) window.__nsNetRecords.pop();
            if (window.__nsUpdateNetUI) window.__nsUpdateNetUI();
            if (window.__nsNativeBridge && window.__nsNativeBridge.onNetwork) {
              window.__nsNativeBridge.onNetwork(JSON.stringify(rec));
            }
          } catch(_){}
        }
        if (window.fetch) {
          var _origFetch = window.fetch;
          window.fetch = function(input, init) {
            var t0 = Date.now();
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            var method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();
            var reqHeaders = (init && init.headers) ? init.headers : {};
            var reqBody = (init && init.body) ? String(init.body) : '';
            return _origFetch.apply(this, arguments).then(function(resp) {
              var dt = Date.now() - t0;
              var ct = '';
              try { ct = resp.headers.get('content-type') || ''; } catch(_){}
              try {
                resp.clone().text().then(function(txt) {
                  emitNet({
                    name: shortName(url), url: url, method: method,
                    status: resp.status || 0, ct: ct, size: txt ? txt.length : 0,
                    time: dt, reqHeaders: reqHeaders, reqBody: reqBody, respBody: txt.slice(0, 12000)
                  });
                }).catch(function() {
                  emitNet({
                    name: shortName(url), url: url, method: method,
                    status: resp.status || 0, ct: ct, size: 0,
                    time: dt, reqHeaders: reqHeaders, reqBody: reqBody, respBody: ''
                  });
                });
              } catch(_) {}
              return resp;
            }).catch(function(err) {
              emitNet({
                name: shortName(url), url: url, method: method,
                status: 0, ct: 'err', size: 0, time: Date.now() - t0,
                reqHeaders: reqHeaders, reqBody: reqBody, respBody: String(err)
              });
              throw err;
            });
          };
        }
        if (window.XMLHttpRequest) {
          var _open = XMLHttpRequest.prototype.open;
          var _send = XMLHttpRequest.prototype.send;
          var _setH = XMLHttpRequest.prototype.setRequestHeader;
          XMLHttpRequest.prototype.open = function(m, u) {
            this.__ns = { method: (m || 'GET').toUpperCase(), url: String(u || ''), headers: {} };
            return _open.apply(this, arguments);
          };
          XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
            if (this.__ns) this.__ns.headers[k] = v;
            return _setH.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function(body) {
            var self = this;
            if (self.__ns) {
              self.__ns.t0 = Date.now();
              self.__ns.body = body ? String(body) : '';
              self.addEventListener('loadend', function() {
                try {
                  var dt = Date.now() - (self.__ns.t0 || Date.now());
                  var ct = self.getResponseHeader('content-type') || '';
                  var txt = '';
                  if (!self.responseType || self.responseType === 'text' || self.responseType === 'json') {
                    txt = typeof self.responseText === 'string' ? self.responseText : '';
                  }
                  emitNet({
                    name: shortName(self.__ns.url), url: self.__ns.url, method: self.__ns.method,
                    status: self.status || 0, ct: ct, size: txt ? txt.length : 0,
                    time: dt, reqHeaders: self.__ns.headers, reqBody: self.__ns.body, respBody: txt.slice(0, 12000)
                  });
                } catch(_){}
              });
            }
            return _send.apply(this, arguments);
          };
        }
      } catch(e) {}
    })();
    """

  /**
   * Встроенная автономная DevTools-консоль в стиле Eruda (работает 100% без CDN и при жёстком CSP):
   * вкладки Network (6-колоночная таблица + просмотр и копирование JSON), Console, Storage, Info.
   */
  val BUILTIN_DEVTOOLS_JS =
    JS_NETWORK_BRIDGE_HOOK + "\n" +
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
        var activeTab = 'net';
        var selectedNetIdx = -1;
        function copyText(txt) {
          try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
              navigator.clipboard.writeText(String(txt));
              return;
            }
          } catch(_){}
          try {
            var ta = document.createElement('textarea');
            ta.value = String(txt);
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
          } catch(_){}
        }
        function shortCt(ct) {
          var s = String(ct || '').toLowerCase();
          if (s.indexOf('json') >= 0) return 'json';
          if (s.indexOf('html') >= 0) return 'html';
          if (s.indexOf('javascript') >= 0) return 'js';
          if (s.indexOf('css') >= 0) return 'css';
          if (s.indexOf('image') >= 0) return 'img';
          return 'xhr';
        }
        function addLog(kind, msg) {
          var t = new Date().toTimeString().slice(0,8);
          logs.unshift('[' + t + ' ' + kind + '] ' + String(msg));
          if (logs.length > 100) logs.pop();
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
        var root = document.documentElement || document.body;
        var btn = document.createElement('div');
        btn.id = '__ns_fab';
        btn.textContent = '</>';
        btn.style.cssText = 'position:fixed;right:12px;bottom:14px;z-index:2147483647;background:#181a1f;color:#00e5ff;border:2px solid #00e5ff;border-radius:999px;padding:8px 11px;font:bold 12px monospace;box-shadow:0 4px 16px rgba(0,0,0,.6);';
        var box = document.createElement('div');
        box.id = '__ns_box';
        box.style.cssText = 'position:fixed;left:0;right:0;bottom:0;height:58vh;z-index:2147483647;background:#1e2022;color:#e2e8f0;border-top:1px solid #3a3d42;display:flex;flex-direction:column;font:11.5px monospace;box-sizing:border-box;';
        box.innerHTML =
          '<div style="display:flex;background:#242629;border-bottom:1px solid #3a3d42;overflow-x:auto">' +
          '<button data-t="net" class="__nst" style="flex:1;padding:8px 6px;background:#2c2f33;color:#fff;border:0;border-bottom:2px solid #5e97f6;font:bold 11.5px sans-serif">Network</button>' +
          '<button data-t="con" class="__nst" style="flex:1;padding:8px 6px;background:transparent;color:#9aa0a6;border:0;font:11.5px sans-serif">Console</button>' +
          '<button data-t="sto" class="__nst" style="flex:1;padding:8px 6px;background:transparent;color:#9aa0a6;border:0;font:11.5px sans-serif">Storage</button>' +
          '<button data-t="inf" class="__nst" style="flex:1;padding:8px 6px;background:transparent;color:#9aa0a6;border:0;font:11.5px sans-serif">Info</button>' +
          '<button id="__ns_hide" style="padding:8px 10px;background:transparent;color:#9aa0a6;border:0;font-weight:bold">▼</button>' +
          '</div>' +
          '<div id="__ns_p_net" style="flex:1;display:flex;flex-direction:column;overflow:hidden">' +
          '<div style="display:flex;justify-content:space-between;align-items:center;padding:4px 8px;background:#202225;border-bottom:1px solid #34373c">' +
          '<div><span style="color:#f87171">●</span> <button id="__ns_net_clr" style="background:transparent;color:#9aa0a6;border:0;font:12px sans-serif;margin-left:6px">⊘ Очистить</button></div>' +
          '<div><button id="__ns_net_copyall" style="background:#2d323a;color:#e2e8f0;border:0;border-radius:4px;padding:3px 8px;font:11px sans-serif">❐ Копировать все</button></div></div>' +
          '<div id="__ns_net_hdr" style="display:grid;grid-template-columns:2.2fr 0.8fr 0.8fr 0.7fr 0.9fr 0.9fr;background:#242629;color:#9aa0a6;border-bottom:1px solid #3a3d42;font-size:10.5px">' +
          '<div style="padding:4px;border-right:1px solid #34373c">Name</div><div style="padding:4px;border-right:1px solid #34373c">Meth</div><div style="padding:4px;border-right:1px solid #34373c">Status</div><div style="padding:4px;border-right:1px solid #34373c">Type</div><div style="padding:4px;border-right:1px solid #34373c">Size</div><div style="padding:4px">Time</div></div>' +
          '<div id="__ns_net_list" style="flex:1;overflow:auto"></div>' +
          '<div id="__ns_net_det" style="display:none;flex:1;flex-direction:column;overflow:hidden;background:#181a1f">' +
          '<div style="display:flex;gap:6px;padding:6px;background:#242629;border-bottom:1px solid #3a3d42">' +
          '<button id="__ns_det_back" style="background:#334155;color:#fff;border:0;border-radius:4px;padding:4px 8px">← Назад</button>' +
          '<button id="__ns_det_copy" style="background:#00e5ff;color:#060c16;border:0;border-radius:4px;padding:4px 8px;font-weight:bold">Копировать JSON</button>' +
          '<button id="__ns_det_url" style="background:#2d323a;color:#fff;border:0;border-radius:4px;padding:4px 8px">URL</button></div>' +
          '<pre id="__ns_det_txt" style="flex:1;overflow:auto;margin:0;padding:8px;white-space:pre-wrap;word-break:break-all;font:11px monospace;color:#cbd5e1"></pre></div></div>' +
          '<div id="__ns_p_con" style="flex:1;display:none;flex-direction:column;padding:6px;overflow:hidden">' +
          '<div style="display:flex;gap:4px;margin-bottom:6px">' +
          '<input id="__ns_in" placeholder="JS (напр. document.cookie)" style="flex:1;background:#171a1f;color:#fff;border:1px solid #3a3d42;border-radius:4px;padding:5px;font:11.5px monospace"/>' +
          '<button id="__ns_run" style="background:#5e97f6;color:#fff;border:0;border-radius:4px;padding:5px 10px;font-weight:bold">Run</button></div>' +
          '<pre id="__ns_logs" style="flex:1;overflow:auto;margin:0;white-space:pre-wrap;word-break:break-all;font:11px monospace;color:#34d399"></pre></div>' +
          '<div id="__ns_p_sto" style="flex:1;display:none;flex-direction:column;padding:6px;overflow:auto"></div>' +
          '<div id="__ns_p_inf" style="flex:1;display:none;flex-direction:column;padding:6px;overflow:auto"></div>';
        root.appendChild(box);
        root.appendChild(btn);

        function renderNet() {
          var list = document.getElementById('__ns_net_list');
          var det = document.getElementById('__ns_net_det');
          var hdr = document.getElementById('__ns_net_hdr');
          if (!list || !det) return;
          var recs = window.__nsNetRecords || [];
          if (selectedNetIdx >= 0 && recs[selectedNetIdx]) {
            var r = recs[selectedNetIdx];
            list.style.display = 'none';
            if (hdr) hdr.style.display = 'none';
            det.style.display = 'flex';
            var formatted = r.respBody || '';
            try { formatted = JSON.stringify(JSON.parse(formatted), null, 2); } catch(_){}
            document.getElementById('__ns_det_txt').textContent =
              r.method + ' ' + r.url + '\nStatus: ' + r.status + ' • ' + r.time + 'ms\n\n--- Headers ---\n' +
              JSON.stringify(r.reqHeaders || {}, null, 2) + '\n\n--- Body ---\n' + (r.reqBody || '(empty)') +
              '\n\n--- Response ---\n' + (formatted || '(empty)');
            return;
          }
          det.style.display = 'none';
          if (hdr) hdr.style.display = 'grid';
          list.style.display = 'block';
          list.innerHTML = '';
          recs.forEach(function(r, idx) {
            var row = document.createElement('div');
            var bg = (idx % 2 === 1) ? '#1b2333' : '#1e2022';
            var stCol = (r.status >= 200 && r.status < 300) ? '#cbd5e1' : (r.status >= 400 ? '#f87171' : '#9aa0a6');
            row.style.cssText = 'display:grid;grid-template-columns:2.2fr 0.8fr 0.8fr 0.7fr 0.9fr 0.9fr;background:' + bg + ';border-bottom:1px solid #2b2e33;font-size:11px;cursor:pointer';
            row.innerHTML =
              '<div style="padding:5px 4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;border-right:1px solid #2b2e33;color:#e2e8f0">' + r.name + '</div>' +
              '<div style="padding:5px 4px;border-right:1px solid #2b2e33;color:#9aa0a6">' + r.method + '</div>' +
              '<div style="padding:5px 4px;border-right:1px solid #2b2e33;color:' + stCol + '">' + (r.status || '---') + '</div>' +
              '<div style="padding:5px 4px;border-right:1px solid #2b2e33;color:#9aa0a6">' + shortCt(r.ct) + '</div>' +
              '<div style="padding:5px 4px;border-right:1px solid #2b2e33;color:#9aa0a6">' + r.size + '</div>' +
              '<div style="padding:5px 4px;color:#9aa0a6">' + r.time + 'ms</div>';
            row.onclick = function() { selectedNetIdx = idx; renderNet(); };
            list.appendChild(row);
          });
        }
        window.__nsUpdateNetUI = renderNet;

        function renderStorage() {
          var sto = document.getElementById('__ns_p_sto');
          if (!sto) return;
          var ls = {};
          try { for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); ls[k] = localStorage.getItem(k); } } catch(_){}
          sto.innerHTML = '<button id="__ns_copy_sto" style="align-self:flex-start;margin-bottom:6px;background:#00e5ff;color:#060c16;border:0;border-radius:4px;padding:4px 8px;font-weight:bold">Копировать Cookie + Storage</button>' +
            '<pre style="margin:0;white-space:pre-wrap;word-break:break-all;color:#cbd5e1">' +
            'Cookie:\n' + (document.cookie || '(пусто)') + '\n\nlocalStorage:\n' + JSON.stringify(ls, null, 2) + '</pre>';
          document.getElementById('__ns_copy_sto').onclick = function() {
            copyText(JSON.stringify({ url: location.href, cookie: document.cookie, localStorage: ls }, null, 2));
          };
        }

        function renderInfo() {
          var inf = document.getElementById('__ns_p_inf');
          if (!inf) return;
          var infoObj = { url: location.href, title: document.title, ua: navigator.userAgent, tg: window.Telegram && window.Telegram.WebApp ? window.Telegram.WebApp.initData : null };
          inf.innerHTML = '<button id="__ns_copy_inf" style="align-self:flex-start;margin-bottom:6px;background:#00e5ff;color:#060c16;border:0;border-radius:4px;padding:4px 8px;font-weight:bold">Копировать Info</button>' +
            '<pre style="margin:0;white-space:pre-wrap;word-break:break-all;color:#cbd5e1">' + JSON.stringify(infoObj, null, 2) + '</pre>';
          document.getElementById('__ns_copy_inf').onclick = function() { copyText(JSON.stringify(infoObj, null, 2)); };
        }

        var tabBtns = box.querySelectorAll('.__nst');
        tabBtns.forEach(function(tb) {
          tb.onclick = function() {
            activeTab = tb.getAttribute('data-t');
            tabBtns.forEach(function(b) {
              var on = b.getAttribute('data-t') === activeTab;
              b.style.background = on ? '#2c2f33' : 'transparent';
              b.style.color = on ? '#fff' : '#9aa0a6';
              b.style.borderBottom = on ? '2px solid #5e97f6' : '0';
              b.style.fontWeight = on ? 'bold' : 'normal';
            });
            document.getElementById('__ns_p_net').style.display = activeTab === 'net' ? 'flex' : 'none';
            document.getElementById('__ns_p_con').style.display = activeTab === 'con' ? 'flex' : 'none';
            document.getElementById('__ns_p_sto').style.display = activeTab === 'sto' ? 'flex' : 'none';
            document.getElementById('__ns_p_inf').style.display = activeTab === 'inf' ? 'flex' : 'none';
            if (activeTab === 'net') renderNet();
            if (activeTab === 'sto') renderStorage();
            if (activeTab === 'inf') renderInfo();
          };
        });

        btn.onclick = function(){ box.style.display = (box.style.display === 'none') ? 'flex' : 'none'; };
        document.getElementById('__ns_hide').onclick = function(){ box.style.display = 'none'; };
        document.getElementById('__ns_net_clr').onclick = function(){ window.__nsNetRecords = []; selectedNetIdx = -1; renderNet(); };
        document.getElementById('__ns_net_copyall').onclick = function(){ copyText(JSON.stringify(window.__nsNetRecords || [], null, 2)); };
        document.getElementById('__ns_det_back').onclick = function(){ selectedNetIdx = -1; renderNet(); };
        document.getElementById('__ns_det_copy').onclick = function(){
          var r = (window.__nsNetRecords || [])[selectedNetIdx];
          if (r) copyText(r.respBody || JSON.stringify(r, null, 2));
        };
        document.getElementById('__ns_det_url').onclick = function(){
          var r = (window.__nsNetRecords || [])[selectedNetIdx];
          if (r) copyText(r.url);
        };
        document.getElementById('__ns_run').onclick = function(){
          var code = document.getElementById('__ns_in').value;
          try { addLog('EVAL', JSON.stringify(eval(code))); } catch(e) { addLog('ERR', e); }
        };
        addLog('READY', location.href);
        renderNet();
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
