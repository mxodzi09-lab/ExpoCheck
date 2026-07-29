package pl.expocheck

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Najpierw pobiera kartę produktu bezpośrednio przez HTTP.
 * Ukryty WebView uruchamia się wyłącznie jako plan awaryjny.
 */
@Composable
fun AutoKomfortLookup(
    code: String,
    onState: (String) -> Unit,
    onResolved: (PageSnapshot) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val normalized = remember(code) { code.filter(Char::isDigit).take(14) }
    if (normalized.length < 8) return

    var completed by remember(normalized) { mutableStateOf(false) }
    var useWebFallback by remember(normalized) { mutableStateOf(false) }

    LaunchedEffect(normalized) {
        completed = false
        useWebFallback = false
        onState("Sprawdzam produkt $normalized na Komfort.pl…")

        val remote = KomfortRemoteLookup.resolve(normalized)
        if (remote != null) {
            completed = true
            onResolved(remote)
            onState("Aktualne ceny zostały pobrane.")
            return@LaunchedEffect
        }

        onState("Otwieram kartę produktu…")
        useWebFallback = true

        delay(16_000)
        if (!completed) {
            onError(
                "Nie udało się pobrać cen. Utrzymaj kod w kadrze " +
                    "albo sprawdź połączenie z internetem."
            )
        }
    }

    if (useWebFallback && !completed) {
        val webView = remember(normalized) {
            createFallbackWebView(
                context = context,
                code = normalized,
                onPayload = { payload ->
                    val parsed = runCatching {
                        PriceParser.parsePageJson(payload)
                    }.getOrNull()

                    if (
                        parsed != null &&
                        parsed.currentPrice != null &&
                        (
                            parsed.catalogNumber == normalized ||
                                parsed.url.contains(normalized)
                        )
                    ) {
                        completed = true
                        onResolved(parsed)
                        onState("Aktualne ceny zostały pobrane.")
                    }
                },
                onFailure = { message ->
                    if (!completed) onError(message)
                },
            )
        }

        DisposableEffect(webView) {
            onDispose {
                webView.stopLoading()
                webView.destroy()
            }
        }

        AndroidView(
            factory = { webView },
            modifier = Modifier.size(2.dp).alpha(0.01f),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createFallbackWebView(
    context: Context,
    code: String,
    onPayload: (String) -> Unit,
    onFailure: (String) -> Unit,
): WebView {
    val handler = Handler(Looper.getMainLooper())
    val encoded = URLEncoder.encode(code, StandardCharsets.UTF_8.name())
    val searchUrl = "https://komfort.pl/search?q=$encoded"

    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadsImagesAutomatically = false
        settings.userAgentString =
            settings.userAgentString + " ExpoCheck/0.5.1"

        webChromeClient = WebChromeClient()
        addJavascriptInterface(
            FallbackBridge { payload ->
                handler.post { onPayload(payload) }
            },
            "ExpoCheckAutoBridge",
        )

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("/p/") && url.contains(code)) {
                    installProductReader(view)
                } else {
                    installSearchNavigator(view, code)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                super.onReceivedError(
                    view,
                    errorCode,
                    description,
                    failingUrl,
                )
                handler.post {
                    onFailure(
                        "Błąd strony Komfortu: " +
                            description.orEmpty().ifBlank {
                                "brak połączenia"
                            }
                    )
                }
            }
        }

        loadUrl(searchUrl)
    }
}

private class FallbackBridge(
    private val callback: (String) -> Unit,
) {
    @JavascriptInterface
    fun onProduct(payload: String) = callback(payload)
}

private fun installSearchNavigator(
    webView: WebView,
    code: String,
) {
    val safeCode = code.filter(Char::isDigit)
    val script = """
        (function() {
          var wanted = '$safeCode';

          function findProduct() {
            try {
              var links = Array.prototype.slice.call(
                document.querySelectorAll('a[href*="/p/"]')
              );

              var exact = links.find(function(a) {
                var href = a.href || '';
                var text = (a.innerText || '') + ' ' +
                           (a.getAttribute('aria-label') || '');
                return href.indexOf(wanted) >= 0 ||
                       text.indexOf(wanted) >= 0;
              });

              if (exact && exact.href) {
                location.replace(exact.href);
              }
            } catch (e) {}
          }

          if (!window.__expoCheckSearch051) {
            window.__expoCheckSearch051 = true;
            setInterval(findProduct, 500);
            var observer = new MutationObserver(findProduct);
            if (document.body) {
              observer.observe(
                document.body,
                {subtree:true, childList:true, characterData:true}
              );
            }
          }
          findProduct();
        })();
    """.trimIndent()
    webView.evaluateJavascript(script, null)
}

private fun installProductReader(webView: WebView) {
    val script = """
        (function() {
          function sendProduct() {
            try {
              var titleNode = document.querySelector('h1');
              var imageNode =
                document.querySelector('meta[property="og:image"]');
              var data = {
                title: titleNode ? titleNode.innerText : document.title,
                body: document.body ? document.body.innerText : '',
                url: location.href,
                image: imageNode ? imageNode.content : ''
              };
              ExpoCheckAutoBridge.onProduct(JSON.stringify(data));
            } catch (e) {}
          }

          if (!window.__expoCheckReader051) {
            window.__expoCheckReader051 = true;
            setInterval(sendProduct, 1200);
            var observer = new MutationObserver(function() {
              setTimeout(sendProduct, 250);
            });
            if (document.body) {
              observer.observe(
                document.body,
                {subtree:true, childList:true, characterData:true}
              );
            }
          }
          sendProduct();
        })();
    """.trimIndent()
    webView.evaluateJavascript(script, null)
}
