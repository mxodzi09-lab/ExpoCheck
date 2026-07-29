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
 * Niewidoczny czytnik strony Komfortu.
 *
 * Gdy skaner zauważy numer produktu na cenówce, ten komponent:
 * 1. otwiera wyszukiwarkę Komfortu,
 * 2. znajduje kartę produktu zawierającą numer,
 * 3. odczytuje z niej ceny, jednostkę i promocję,
 * 4. przekazuje wynik z powrotem do skanera.
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

    val seed = remember(normalized) {
        SeedProducts.items.firstOrNull { it.catalogNumber == normalized }
    }
    var completed by remember(normalized) { mutableStateOf(false) }

    LaunchedEffect(normalized) {
        completed = false
        onState("Rozpoznano kod $normalized — pobieram ceny z Komfort.pl…")

        if (seed != null) {
            completed = true
            onResolved(
                PageSnapshot(
                    name = seed.name,
                    catalogNumber = seed.catalogNumber,
                    currentPrice = seed.currentPrice,
                    unit = seed.unit,
                    installationPrice = seed.installationPrice,
                    lowest30Price = seed.lowest30Price,
                    discountPercent = seed.discountPercent,
                    savings = seed.savings,
                    url = seed.url,
                )
            )
            onState("Pobrano ceny produktu ${seed.catalogNumber}")
            return@LaunchedEffect
        }

        delay(22_000)
        if (!completed) {
            onError("Nie udało się automatycznie otworzyć produktu $normalized. Przytrzymaj kod w kadrze albo spróbuj ponownie.")
        }
    }

    if (seed == null) {
        val webView = remember(normalized) {
            createAutoLookupWebView(
                context = context,
                code = normalized,
                onPayload = { payload ->
                    val parsed = runCatching { PriceParser.parsePageJson(payload) }.getOrNull()
                    if (
                        parsed != null &&
                        parsed.currentPrice != null &&
                        (
                            parsed.catalogNumber == normalized ||
                            parsed.url.contains(normalized)
                        )
                    ) {
                        if (!completed) {
                            completed = true
                            onResolved(parsed)
                            onState("Pobrano ceny ze strony Komfortu")
                        }
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
            modifier = Modifier.size(1.dp).alpha(0.01f),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createAutoLookupWebView(
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
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.userAgentString = settings.userAgentString + " ExpoCheck/0.5.0"
        webChromeClient = WebChromeClient()
        addJavascriptInterface(
            AutoLookupBridge { payload -> handler.post { onPayload(payload) } },
            "ExpoCheckAutoBridge",
        )

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("/p/")) {
                    installAutoProductReader(view)
                } else {
                    installAutoSearchNavigator(view, code)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                handler.post {
                    onFailure("Błąd strony Komfortu: ${description.orEmpty().ifBlank { "brak połączenia" }}")
                }
            }
        }

        loadUrl(searchUrl)
    }
}

private class AutoLookupBridge(
    private val callback: (String) -> Unit,
) {
    @JavascriptInterface
    fun onProduct(payload: String) = callback(payload)
}

private fun installAutoSearchNavigator(webView: WebView, code: String) {
    val safeCode = code.filter(Char::isDigit)
    val script = """
        (function() {
          var wanted = '$safeCode';

          function findCard() {
            try {
              if (location.pathname.indexOf('/p/') >= 0) return;

              var links = Array.prototype.slice.call(
                document.querySelectorAll('a[href*="/p/"]')
              );

              var exact = links.find(function(a) {
                var href = (a.href || '');
                var text = (a.innerText || '') + ' ' + (a.getAttribute('aria-label') || '');
                return href.indexOf(wanted) >= 0 || text.indexOf(wanted) >= 0;
              });

              if (exact && exact.href) {
                location.replace(exact.href);
              }
            } catch (e) {}
          }

          if (!window.__expoCheckAutoSearch) {
            window.__expoCheckAutoSearch = true;
            setInterval(findCard, 900);
            var observer = new MutationObserver(findCard);
            if (document.body) {
              observer.observe(document.body, {subtree:true, childList:true, characterData:true});
            }
          }
          findCard();
        })();
    """.trimIndent()
    webView.evaluateJavascript(script, null)
}

private fun installAutoProductReader(webView: WebView) {
    val script = """
        (function() {
          function sendProduct() {
            try {
              var titleNode = document.querySelector('h1');
              var imageNode = document.querySelector('meta[property="og:image"]');
              var data = {
                title: titleNode ? titleNode.innerText : document.title,
                body: document.body ? document.body.innerText : '',
                url: location.href,
                image: imageNode ? imageNode.content : ''
              };
              ExpoCheckAutoBridge.onProduct(JSON.stringify(data));
            } catch (e) {}
          }

          if (!window.__expoCheckAutoReader) {
            window.__expoCheckAutoReader = true;
            var timer = null;
            var observer = new MutationObserver(function() {
              clearTimeout(timer);
              timer = setTimeout(sendProduct, 500);
            });
            if (document.body) {
              observer.observe(document.body, {subtree:true, childList:true, characterData:true});
            }
            setInterval(sendProduct, 2200);
          }
          sendProduct();
        })();
    """.trimIndent()
    webView.evaluateJavascript(script, null)
}
