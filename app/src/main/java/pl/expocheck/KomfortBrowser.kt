package pl.expocheck

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomfortBrowserScreen(
    initialUrl: String = "https://komfort.pl",
    initialCode: String = "",
    onBack: () -> Unit,
    onScanLabel: (PageSnapshot) -> Unit,
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf<PageSnapshot?>(null) }
    var input by remember { mutableStateOf(initialCode.ifBlank { initialUrl }) }
    var currentUrl by remember { mutableStateOf(initialUrl) }

    val webView = remember {
        createKomfortWebView(context) { payload ->
            runCatching { PriceParser.parsePageJson(payload) }
                .onSuccess { parsed ->
                    if (parsed.currentPrice != null || parsed.catalogNumber.isNotBlank()) snapshot = parsed
                    currentUrl = parsed.url.ifBlank { currentUrl }
                }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skan strony Komfort") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wróć")
                    }
                },
                actions = {
                    IconButton(onClick = { webView.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Odśwież")
                    }
                    IconButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))) }
                    }) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = "Otwórz zewnętrznie")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Numer produktu albo adres") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val target = resolveKomfortUrl(input)
                        currentUrl = target
                        webView.loadUrl(target)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Otwórz") }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        if (view.url == null) view.loadUrl(initialUrl)
                    },
                )
            }

            snapshot?.let { page ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Odczytano ze strony", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(page.name.ifBlank { "Produkt Komfort" }, style = MaterialTheme.typography.titleMedium)
                        Text("Nr kat.: ${page.catalogNumber.ifBlank { "—" }}")
                        Text(
                            "Bez montażu: ${PriceParser.money(page.currentPrice)} ${page.unit}",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        page.installationPrice?.let {
                            Text(
                                "Z montażem: ${PriceParser.money(it)} ${page.unit}",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        page.discountPercent?.let { Text("Promocja: -$it%") }
                        page.lowest30Price?.let { Text("Najniższa z 30 dni: ${PriceParser.money(it)} ${page.unit}") }

                        Button(
                            onClick = { onScanLabel(page) },
                            enabled = page.currentPrice != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                            Text("  Skanuj cenówkę i porównaj")
                        }
                    }
                }
            }
        }
    }
}

private fun resolveKomfortUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    SeedProducts.items.firstOrNull { it.catalogNumber == trimmed }?.let { return it.url }
    val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
    return "https://komfort.pl/search?q=$encoded"
}

@SuppressLint("SetJavaScriptEnabled")
private fun createKomfortWebView(
    context: Context,
    onPayload: (String) -> Unit,
): WebView {
    val handler = Handler(Looper.getMainLooper())
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.userAgentString = settings.userAgentString + " ExpoCheck/0.4"
        webChromeClient = WebChromeClient()
        addJavascriptInterface(PageBridge { payload -> handler.post { onPayload(payload) } }, "ExpoCheckBridge")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                installLivePageReader(view)
            }
        }
    }
}

private class PageBridge(private val callback: (String) -> Unit) {
    @JavascriptInterface
    fun onPage(payload: String) = callback(payload)
}

private fun installLivePageReader(webView: WebView) {
    val script = """
        (function() {
          function readPage() {
            try {
              var titleNode = document.querySelector('h1');
              var imageNode = document.querySelector('meta[property="og:image"]');
              var data = {
                title: titleNode ? titleNode.innerText : document.title,
                body: document.body ? document.body.innerText : '',
                url: location.href,
                image: imageNode ? imageNode.content : ''
              };
              ExpoCheckBridge.onPage(JSON.stringify(data));
            } catch (e) {}
          }
          if (!window.__expoCheckReaderInstalled) {
            window.__expoCheckReaderInstalled = true;
            var timer = null;
            var observer = new MutationObserver(function() {
              clearTimeout(timer);
              timer = setTimeout(readPage, 700);
            });
            if (document.body) observer.observe(document.body, {subtree:true, childList:true, characterData:true});
            setInterval(readPage, 3500);
          }
          readPage();
        })();
    """.trimIndent()
    webView.evaluateJavascript(script, null)
}
