package moe.ouom.neriplayer.core.api.lx

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.lx.LxCustomSource
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class LxScriptRuntime(
    context: Context,
    private val source: LxCustomSource,
    private val client: OkHttpClient,
    private val statusChanged: (LxSourceRuntimeStatus) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initResult = CompletableDeferred<LxSourceCapabilities>()
    private val actionResults = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val networkCalls = ConcurrentHashMap<String, Call>()
    private var webView: WebView? = null

    suspend fun initialize(): LxSourceCapabilities {
        if (initResult.isCompleted) return initResult.await()
        statusChanged(LxSourceRuntimeStatus.Initializing)
        withContext(Dispatchers.Main.immediate) {
            if (webView == null) createWebView()
        }
        return try {
            withTimeout(INIT_TIMEOUT_MS) { initResult.await() }
                .also { statusChanged(LxSourceRuntimeStatus.Ready(it)) }
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            statusChanged(LxSourceRuntimeStatus.Failed(message))
            throw error
        }
    }

    suspend fun resolveMusicUrl(
        platform: String,
        quality: String,
        musicInfo: JSONObject
    ): String {
        initialize()
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        actionResults[requestId] = deferred
        val packet = JSONObject()
            .put("requestKey", requestId)
            .put("source", platform)
            .put("action", "musicUrl")
            .put(
                "info",
                JSONObject()
                    .put("type", quality)
                    .put("musicInfo", musicInfo)
            )
        evaluate("window.__lxDispatch(${JSONObject.quote(packet.toString())})")
        return try {
            withTimeout(ACTION_TIMEOUT_MS) { deferred.await() }
        } finally {
            actionResults.remove(requestId)
        }
    }

    fun close() {
        networkCalls.values.forEach(Call::cancel)
        networkCalls.clear()
        actionResults.values.forEach { it.cancel() }
        actionResults.clear()
        mainHandler.post {
            webView?.apply {
                removeJavascriptInterface(BRIDGE_NAME)
                stopLoading()
                destroy()
            }
            webView = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val bridge = Bridge()
        webView = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            addJavascriptInterface(bridge, BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(source.script) { result ->
                        if (result == null && !initResult.isCompleted) {
                            failInitialization("LX source script evaluation failed")
                        }
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        NPLogger.w(
                            TAG,
                            "${source.name}: ${consoleMessage.message()} @${consoleMessage.lineNumber()}"
                        )
                    }
                    return true
                }
            }
            loadDataWithBaseURL(
                "https://lx-runtime.invalid/",
                RUNTIME_HTML.replace(
                    "__SOURCE_INFO_BASE64__",
                    Base64.encodeToString(
                        sourceInfoJson().toString().toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP
                    )
                ),
                "text/html",
                "UTF-8",
                null
            )
        }
    }

    private fun sourceInfoJson(): JSONObject = JSONObject()
        .put("name", source.name)
        .put("description", source.description)
        .put("author", source.author)
        .put("homepage", source.homepage)
        .put("version", source.version)
        .put("rawScript", source.script)

    private fun evaluate(script: String) {
        mainHandler.post { webView?.evaluateJavascript(script, null) }
    }

    private fun failInitialization(message: String) {
        if (!initResult.isCompleted) initResult.completeExceptionally(IllegalStateException(message))
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onInited(raw: String) {
            runCatching { parseCapabilities(raw) }
                .onSuccess { capabilities ->
                    if (!initResult.isCompleted) initResult.complete(capabilities)
                }
                .onFailure { error -> failInitialization(error.message ?: "Invalid LX source capabilities") }
        }

        @JavascriptInterface
        fun onScriptError(message: String) {
            NPLogger.w(TAG, "LX source ${source.name} error: ${message.take(1_024)}")
            failInitialization(message.take(1_024))
        }

        @JavascriptInterface
        fun onActionResult(requestId: String, success: Boolean, result: String) {
            val deferred = actionResults[requestId] ?: return
            if (success) {
                val url = result.trim().removeSurrounding("\"")
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    deferred.complete(url)
                } else {
                    deferred.completeExceptionally(IllegalStateException("LX source returned an invalid URL"))
                }
            } else {
                deferred.completeExceptionally(IllegalStateException(result.take(1_024)))
            }
        }

        @JavascriptInterface
        fun request(requestId: String, url: String, optionsJson: String) {
            runCatching { buildRequest(url, optionsJson) }
                .onSuccess { request ->
                    val timeout = runCatching {
                        JSONObject(optionsJson).optLong("timeout", DEFAULT_HTTP_TIMEOUT_MS)
                    }.getOrDefault(DEFAULT_HTTP_TIMEOUT_MS).coerceIn(1_000L, MAX_HTTP_TIMEOUT_MS)
                    val call = client.newBuilder()
                        .callTimeout(timeout, TimeUnit.MILLISECONDS)
                        .build()
                        .newCall(request)
                    networkCalls[requestId] = call
                    call.enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) {
                            networkCalls.remove(requestId)
                            deliverHttpResult(requestId, null, e.message ?: "Request failed")
                        }

                        override fun onResponse(call: Call, response: okhttp3.Response) {
                            networkCalls.remove(requestId)
                            response.use {
                                val bytes = response.body.bytes()
                                if (bytes.size > MAX_RESPONSE_BYTES) {
                                    deliverHttpResult(requestId, null, "Response is too large")
                                    return
                                }
                                val headers = JSONObject()
                                response.headers.names().forEach { name ->
                                    headers.put(name.lowercase(), response.headers.values(name).joinToString(", "))
                                }
                                val packet = JSONObject()
                                    .put("statusCode", response.code)
                                    .put("statusMessage", response.message)
                                    .put("headers", headers)
                                    .put("bodyText", bytes.toString(Charsets.UTF_8))
                                    .put("rawBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                                deliverHttpResult(requestId, packet, null)
                            }
                        }
                    })
                }
                .onFailure { error -> deliverHttpResult(requestId, null, error.message ?: "Invalid request") }
        }

        @JavascriptInterface
        fun cancelRequest(requestId: String) {
            networkCalls.remove(requestId)?.cancel()
        }

        @JavascriptInterface
        fun md5(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        @JavascriptInterface
        fun randomBytes(size: Int): String {
            val bytes = ByteArray(size.coerceIn(0, 4_096))
            java.security.SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        @JavascriptInterface
        fun aesEncrypt(dataBase64: String, mode: String, keyBase64: String, ivBase64: String): String {
            val key = Base64.decode(keyBase64, Base64.DEFAULT)
            val iv = Base64.decode(ivBase64, Base64.DEFAULT)
            val transformation = when (mode.lowercase()) {
                "aes-128-ecb", "aes-192-ecb", "aes-256-ecb" -> "AES/ECB/PKCS5Padding"
                "aes-128-ctr", "aes-192-ctr", "aes-256-ctr" -> "AES/CTR/NoPadding"
                else -> "AES/CBC/PKCS5Padding"
            }
            val cipher = Cipher.getInstance(transformation)
            val keySpec = SecretKeySpec(key, "AES")
            if (transformation.contains("/ECB/")) {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
            }
            return Base64.encodeToString(
                cipher.doFinal(Base64.decode(dataBase64, Base64.DEFAULT)),
                Base64.NO_WRAP
            )
        }

        @JavascriptInterface
        fun rsaEncrypt(dataBase64: String, publicKeyPem: String): String {
            val keyBytes = Base64.decode(
                publicKeyPem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace(Regex("\\s+"), ""),
                Base64.DEFAULT
            )
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
            val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            return Base64.encodeToString(
                cipher.doFinal(Base64.decode(dataBase64, Base64.DEFAULT)),
                Base64.NO_WRAP
            )
        }

        @JavascriptInterface
        fun deflate(dataBase64: String): String = transformCompressed(dataBase64, inflate = false)

        @JavascriptInterface
        fun inflate(dataBase64: String): String = transformCompressed(dataBase64, inflate = true)
    }

    private fun buildRequest(url: String, optionsJson: String): Request {
        require(url.startsWith("http://") || url.startsWith("https://")) { "Unsupported request URL" }
        val options = JSONObject(optionsJson.ifBlank { "{}" })
        val method = options.optString("method", "GET").uppercase()
        val builder = Request.Builder().url(url)
        options.optJSONObject("headers")?.let { headers ->
            headers.keys().forEach { name -> builder.header(name, headers.optString(name)) }
        }
        val body = when {
            options.has("body") && !options.isNull("body") -> {
                options.optString("body").toRequestBody(
                    builder.build().header("Content-Type")?.toMediaTypeOrNull()
                )
            }
            options.optJSONObject("form") != null -> {
                FormBody.Builder().apply {
                    val form = options.getJSONObject("form")
                    form.keys().forEach { key -> add(key, form.optString(key)) }
                }.build()
            }
            options.optJSONObject("formData") != null -> {
                MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                    val formData = options.getJSONObject("formData")
                    formData.keys().forEach { key -> addFormDataPart(key, formData.optString(key)) }
                }.build()
            }
            method in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody()
            else -> null
        }
        return builder.method(method, body).build()
    }

    private fun deliverHttpResult(requestId: String, packet: JSONObject?, error: String?) {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("error", error)
            .put("response", packet)
        evaluate("window.__lxResolveHttp(${JSONObject.quote(payload.toString())})")
    }

    private fun parseCapabilities(raw: String): LxSourceCapabilities {
        val root = JSONObject(raw)
        val sourcesObject = root.optJSONObject("sources") ?: root
        val sources = buildMap {
            sourcesObject.keys().forEach { sourceName ->
                val info = sourcesObject.optJSONObject(sourceName) ?: return@forEach
                if (info.optString("type", "music") != "music") return@forEach
                val actions = info.optJSONArray("actions").toStringSet()
                val qualities = info.optJSONArray("qualitys").toStringList()
                if ("musicUrl" in actions && qualities.isNotEmpty()) {
                    put(sourceName, LxSourceCapability(actions, qualities))
                }
            }
        }
        require(sources.isNotEmpty()) { "LX source does not expose a playable music source" }
        return LxSourceCapabilities(sources)
    }

    private fun JSONArray?.toStringList(): List<String> = buildList {
        val array = this@toStringList ?: return@buildList
        for (index in 0 until array.length()) add(array.optString(index))
    }.filter(String::isNotBlank).distinct()

    private fun JSONArray?.toStringSet(): Set<String> = toStringList().toSet()

    private fun transformCompressed(dataBase64: String, inflate: Boolean): String {
        val output = ByteArrayOutputStream()
        val input = Base64.decode(dataBase64, Base64.DEFAULT)
        if (inflate) {
            InflaterOutputStream(output).use { it.write(input) }
        } else {
            DeflaterOutputStream(output).use { it.write(input) }
        }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private companion object {
        const val TAG = "NERI-LXSource"
        const val BRIDGE_NAME = "lxNative"
        const val INIT_TIMEOUT_MS = 15_000L
        const val ACTION_TIMEOUT_MS = 30_000L
        const val DEFAULT_HTTP_TIMEOUT_MS = 15_000L
        const val MAX_HTTP_TIMEOUT_MS = 60_000L
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

        val RUNTIME_HTML = """
            <!doctype html><html><head><meta charset="utf-8"></head><body><script>
            (() => {
              'use strict';
              const sourceInfo = JSON.parse(new TextDecoder().decode(
                Uint8Array.from(atob('__SOURCE_INFO_BASE64__'), char => char.charCodeAt(0))
              ));
              const callbacks = new Map();
              let requestHandler = null;
              let initialized = false;
              let requestSequence = 0;

              const fromBase64 = value => {
                const binary = atob(value || '');
                const bytes = new Uint8Array(binary.length);
                for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                return new LxBuffer(bytes);
              };
              const toBase64 = value => {
                const bytes = LxBuffer.from(value);
                let binary = '';
                for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                return btoa(binary);
              };
              class LxBuffer extends Uint8Array {
                static from(value, encoding = 'utf8') {
                  if (value instanceof Uint8Array) return new LxBuffer(value);
                  if (Array.isArray(value)) return new LxBuffer(value);
                  const text = String(value ?? '');
                  if (encoding === 'base64') return fromBase64(text);
                  if (encoding === 'hex') {
                    const result = new LxBuffer(Math.floor(text.length / 2));
                    for (let i = 0; i < result.length; i++) result[i] = parseInt(text.slice(i * 2, i * 2 + 2), 16);
                    return result;
                  }
                  return new LxBuffer(new TextEncoder().encode(text));
                }
                static alloc(size, fill = 0) { const value = new LxBuffer(size); value.fill(fill); return value; }
                static concat(values) {
                  const buffers = values.map(value => LxBuffer.from(value));
                  const result = new LxBuffer(buffers.reduce((total, value) => total + value.length, 0));
                  let offset = 0;
                  buffers.forEach(value => { result.set(value, offset); offset += value.length; });
                  return result;
                }
                toString(encoding = 'utf8') {
                  if (encoding === 'base64') return toBase64(this);
                  if (encoding === 'hex') return Array.from(this).map(value => value.toString(16).padStart(2, '0')).join('');
                  return new TextDecoder().decode(this);
                }
              }
              window.Buffer = LxBuffer;

              window.__lxResolveHttp = raw => {
                const packet = JSON.parse(raw);
                const callback = callbacks.get(packet.requestId);
                if (!callback) return;
                callbacks.delete(packet.requestId);
                if (packet.error) {
                  callback(new Error(packet.error), null, null);
                  return;
                }
                const data = packet.response;
                const rawBuffer = fromBase64(data.rawBase64);
                let body = data.bodyText;
                try { body = JSON.parse(body); } catch (_) {}
                const response = {
                  statusCode: data.statusCode,
                  statusMessage: data.statusMessage,
                  headers: data.headers || {},
                  bytes: rawBuffer.length,
                  raw: rawBuffer,
                  body,
                };
                callback(null, response, body);
              };

              window.__lxDispatch = async raw => {
                const packet = JSON.parse(raw);
                try {
                  if (typeof requestHandler !== 'function') throw new Error('Request event is not defined');
                  const result = await requestHandler.call(window.lx, {
                    source: packet.source,
                    action: packet.action,
                    info: packet.info,
                  });
                  if (typeof result !== 'string' || result.length > 2048 || !/^https?:/.test(result)) {
                    throw new Error('LX source returned an invalid music URL');
                  }
                  lxNative.onActionResult(packet.requestKey, true, JSON.stringify(result));
                } catch (error) {
                  lxNative.onActionResult(packet.requestKey, false, error?.message || String(error));
                }
              };

              window.lx = {
                EVENT_NAMES: { request: 'request', inited: 'inited', updateAlert: 'updateAlert' },
                request(url, options = {}, callback) {
                  const id = 'http_' + (++requestSequence) + '_' + Date.now();
                  callbacks.set(id, typeof callback === 'function' ? callback : () => {});
                  lxNative.request(id, String(url), JSON.stringify(options || {}));
                  return () => { callbacks.delete(id); lxNative.cancelRequest(id); };
                },
                send(eventName, data) {
                  if (eventName === 'inited') {
                    if (initialized) return Promise.reject(new Error('Script is inited'));
                    initialized = true;
                    lxNative.onInited(JSON.stringify(data || {}));
                    return Promise.resolve();
                  }
                  if (eventName === 'updateAlert') return Promise.resolve();
                  return Promise.reject(new Error('Unsupported event: ' + eventName));
                },
                on(eventName, handler) {
                  if (eventName !== 'request' || typeof handler !== 'function') {
                    return Promise.reject(new Error('Unsupported event: ' + eventName));
                  }
                  requestHandler = handler;
                  return Promise.resolve();
                },
                utils: {
                  crypto: {
                    md5: value => lxNative.md5(String(value)),
                    randomBytes: size => fromBase64(lxNative.randomBytes(Number(size))),
                    aesEncrypt: (data, mode, key, iv) => fromBase64(lxNative.aesEncrypt(toBase64(data), String(mode), toBase64(key), toBase64(iv))),
                    rsaEncrypt: (data, key) => {
                      const value = LxBuffer.from(data);
                      const padded = LxBuffer.concat([LxBuffer.alloc(Math.max(0, 128 - value.length)), value]);
                      return fromBase64(lxNative.rsaEncrypt(toBase64(padded), String(key)));
                    },
                  },
                  buffer: { from: (...args) => LxBuffer.from(...args), bufToString: (buf, format) => LxBuffer.from(buf).toString(format) },
                  zlib: {
                    inflate: data => Promise.resolve(fromBase64(lxNative.inflate(toBase64(data)))),
                    deflate: data => Promise.resolve(fromBase64(lxNative.deflate(toBase64(data)))),
                  },
                },
                currentScriptInfo: sourceInfo,
                version: '2.0.0',
                env: 'desktop',
              };
              window.addEventListener('error', event => lxNative.onScriptError(event.message || 'Script error'));
              window.addEventListener('unhandledrejection', event => lxNative.onScriptError(event.reason?.message || String(event.reason)));
            })();
            </script></body></html>
        """.trimIndent()
    }
}
