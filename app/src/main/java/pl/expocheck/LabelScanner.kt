package pl.expocheck

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

@Composable
fun LabelScannerScreen(
    online: PageSnapshot,
    onBack: () -> Unit,
    onOnlineResolved: (PageSnapshot) -> Unit,
    onConfirmed: (LabelScan) -> Unit,
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var scan by remember { mutableStateOf(LabelScan()) }
    var resolvedOnline by remember { mutableStateOf(online) }
    var lookupCode by remember { mutableStateOf("") }
    var lookupMessage by remember { mutableStateOf("Skieruj aparat na kod produktu i ceny") }
    var lastEanCandidate by remember { mutableStateOf("") }
    var eanHits by remember { mutableIntStateOf(0) }
    var priceVotes by remember { mutableStateOf<Map<String, PriceVote>>(emptyMap()) }
    var catalogCandidate by remember { mutableStateOf("") }
    var catalogHits by remember { mutableIntStateOf(0) }
    var acceptedCatalog by remember { mutableStateOf(online.catalogNumber) }

    LaunchedEffect(online) {
        if (
            online.catalogNumber.isNotBlank() ||
            online.currentPrice != null
        ) {
            resolvedOnline = online
            if (online.catalogNumber.isNotBlank()) {
                acceptedCatalog = online.catalogNumber
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { permissionGranted = it },
    )

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Jeśli skaner wykrył kod innego produktu niż aktualnie załadowany,
    // nie porównujemy cen z poprzednim produktem podczas pobierania nowych danych.
    val effectiveOnline = when {
        acceptedCatalog.isNotBlank() &&
            resolvedOnline.catalogNumber.isNotBlank() &&
            acceptedCatalog != resolvedOnline.catalogNumber ->
            PageSnapshot(catalogNumber = acceptedCatalog)

        else -> resolvedOnline
    }

    if (
        lookupCode.isNotBlank() &&
        (
            effectiveOnline.currentPrice == null ||
            effectiveOnline.catalogNumber != lookupCode
        )
    ) {
        AutoKomfortLookup(
            code = lookupCode,
            onState = { lookupMessage = it },
            onResolved = { page ->
                resolvedOnline = page
                lookupMessage = "Pobrano: ${page.name.ifBlank { page.catalogNumber }}"
                onOnlineResolved(page)
            },
            onError = { lookupMessage = it },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permissionGranted) {
            LiveCameraPreview(
                modifier = Modifier.fillMaxSize(),
                onScan = { newScan ->
                    val currentExpected = PriceParser.comparablePagePrices(effectiveOnline)
                    val framePrices = PriceParser.detectedPrices(newScan)

                    priceVotes = updatePriceVotes(priceVotes, framePrices)
                    val stablePrices = priceVotes.values
                        .filter { it.hits >= 2 && it.misses <= 2 }
                        .map { it.price }

                    // Po pobraniu strony pokazujemy wyłącznie ceny, które rzeczywiście
                    // występują online. Przed pobraniem strony pokazujemy maksymalnie
                    // trzy stabilne odczyty, a nie wszystkie przypadkowe kombinacje OCR.
                    val mergedPrices = if (currentExpected.isNotEmpty()) {
                        (stablePrices + framePrices)
                            .filter { shelf ->
                                currentExpected.any { online ->
                                    abs(online.value - shelf.value) < 0.011
                                }
                            }
                    } else {
                        stablePrices
                    }
                        .groupBy { String.format(Locale.US, "%.2f", it.value) }
                        .map { (_, group) ->
                            group.maxByOrNull { if (it.unit.isNotBlank()) 1 else 0 } ?: group.first()
                        }
                        .sortedBy { it.value }
                        .take(4)

                    val rawCatalog = newScan.catalogNumber
                    if (rawCatalog.matches(Regex("100\\d{6}"))) {
                        if (rawCatalog == catalogCandidate) {
                            catalogHits += 1
                        } else {
                            catalogCandidate = rawCatalog
                            catalogHits = 1
                        }

                        // Numer musi pojawić się poprawnie w co najmniej trzech klatkach.
                        // To chroni przed pomyłkami typu 100344178 zamiast 100344378.
                        if (catalogHits >= 3 && rawCatalog != acceptedCatalog) {
                            acceptedCatalog = rawCatalog
                            resolvedOnline = PageSnapshot(catalogNumber = rawCatalog)
                            lookupCode = rawCatalog
                            lookupMessage = "Potwierdzono produkt $rawCatalog — pobieram ceny ze strony…"
                            priceVotes = emptyMap()
                        } else if (catalogHits < 3 && acceptedCatalog.isBlank()) {
                            lookupMessage = "Rozpoznaję kod $rawCatalog (${catalogHits}/3)…"
                        }
                    }

                    val primary = mergedPrices.firstOrNull()?.value ?: scan.price
                    val primaryUnit = mergedPrices.firstOrNull()?.unit.orEmpty().ifBlank { scan.unit }

                    scan = newScan.copy(
                        price = primary,
                        unit = primaryUnit,
                        prices = mergedPrices,
                        catalogNumber = acceptedCatalog,
                        ean = newScan.ean.ifBlank { scan.ean },
                    )

                    // Dopiero potwierdzony numer katalogowy uruchamia pobieranie strony.
                    val catalog = acceptedCatalog
                    if (
                        catalog.matches(Regex("100\\d{6}")) &&
                        (
                            catalog != resolvedOnline.catalogNumber ||
                            resolvedOnline.currentPrice == null
                        ) &&
                        lookupCode != catalog
                    ) {
                        lookupCode = catalog
                        lookupMessage = "Rozpoznano produkt $catalog — szukam ceny na stronie…"
                    } else if (catalog.isBlank() && newScan.ean.isNotBlank()) {
                        // EAN może czasem nie być numerem katalogowym, dlatego wymagamy
                        // dwóch kolejnych zgodnych odczytów przed uruchomieniem wyszukiwania.
                        if (newScan.ean == lastEanCandidate) {
                            eanHits += 1
                        } else {
                            lastEanCandidate = newScan.ean
                            eanHits = 1
                        }
                        if (
                            eanHits >= 2 &&
                            resolvedOnline.currentPrice == null &&
                            lookupCode != newScan.ean
                        ) {
                            lookupCode = newScan.ean
                            lookupMessage = "Rozpoznano EAN ${newScan.ean} — szukam produktu…"
                        }
                    }
                },
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Aplikacja potrzebuje dostępu do aparatu.", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Zezwól na aparat")
                }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0x99000000), RoundedCornerShape(50)),
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Wróć", tint = Color.White)
        }

        val onlinePrices = PriceParser.comparablePagePrices(effectiveOnline)
        val shelfPrices = PriceParser.detectedPrices(scan)
        val comparisons = PriceParser.comparePrices(effectiveOnline, scan)
        val matchedCount = comparisons.count { it.matchedLabelPrice != null }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xF5FFFFFF)),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FlashOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "  Skanowanie cenówki na żywo",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    val shownCode = effectiveOnline.catalogNumber
                        .ifBlank { scan.catalogNumber.ifBlank { lookupCode } }
                    Text("Produkt: ${shownCode.ifBlank { "jeszcze nieodczytany" }}")

                    if (effectiveOnline.name.isNotBlank()) {
                        Text(
                            effectiveOnline.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    if (onlinePrices.isEmpty()) {
                        Text(
                            lookupMessage,
                            color = Color(0xFF6B7378),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        Text(
                            "Ceny pobrane ze strony Komfortu",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        onlinePrices.forEach { price ->
                            Text("• ${price.label}: ${PriceParser.money(price.value)} ${price.unit}")
                        }
                        effectiveOnline.discountPercent?.let {
                            Text("• Promocja online: -$it%")
                        }
                    }

                    Text(
                        "Pewne ceny odczytane z cenówki",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (shelfPrices.isEmpty()) {
                        Text("• jeszcze żadnej")
                    } else {
                        shelfPrices.take(6).forEach { price ->
                            Text(
                                "• ${PriceParser.money(price.value)} " +
                                    price.unit.ifBlank { effectiveOnline.unit }
                            )
                        }
                    }

                    if (scan.catalogNumber.isNotBlank()) {
                        Text("Nr z cenówki: ${scan.catalogNumber}")
                    }
                    if (scan.ean.isNotBlank()) {
                        Text("EAN: ${scan.ean}")
                    }

                    comparisons.forEach { comparison ->
                        val matched = comparison.matchedLabelPrice != null
                        val icon = if (matched) "✓" else "○"
                        val color = if (matched) Color(0xFF177A4A) else Color(0xFF8A6517)
                        val suffix = if (matched) "zgodna" else "brak dopasowania"
                        Text(
                            "$icon ${comparison.online.label}: " +
                                "${PriceParser.money(comparison.online.value)} — $suffix",
                            color = color,
                        )
                    }

                    val resultText: String
                    val resultColor: Color
                    when {
                        scan.catalogNumber.isBlank() && scan.ean.isBlank() -> {
                            resultText = "Pokaż aparatowi kod produktu oraz całą cenówkę"
                            resultColor = Color(0xFF6B7378)
                        }
                        onlinePrices.isEmpty() -> {
                            resultText = lookupMessage
                            resultColor = Color(0xFF6B7378)
                        }
                        shelfPrices.isEmpty() -> {
                            resultText = "Kod znaleziony. Teraz skieruj aparat na wszystkie ceny"
                            resultColor = Color(0xFF6B7378)
                        }
                        matchedCount == onlinePrices.size -> {
                            resultText = "Wszystkie ceny się zgadzają"
                            resultColor = Color(0xFF177A4A)
                        }
                        matchedCount > 0 -> {
                            resultText =
                                "Zgodne: $matchedCount/${onlinePrices.size}. Pokaż aparatowi całą cenówkę"
                            resultColor = Color(0xFF8A6517)
                        }
                        else -> {
                            resultText = "Odczytane ceny nie pasują do cen ze strony"
                            resultColor = Color(0xFFB3261E)
                        }
                    }
                    Text(
                        resultText,
                        color = resultColor,
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Button(
                        onClick = {
                            onOnlineResolved(effectiveOnline)
                            onConfirmed(scan)
                        },
                        enabled = shelfPrices.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Text("  Zatwierdź odczyt")
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveCameraPreview(
    modifier: Modifier = Modifier,
    onScan: (LabelScan) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(
                    cameraExecutor,
                    LabelAnalyzer { result ->
                        ContextCompat.getMainExecutor(context).execute { onScan(result) }
                    },
                )
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}


private data class PriceVote(
    val price: DetectedPrice,
    val hits: Int,
    val misses: Int = 0,
)

private fun updatePriceVotes(
    previous: Map<String, PriceVote>,
    framePrices: List<DetectedPrice>,
): Map<String, PriceVote> {
    val frameByKey = framePrices
        .filter { it.value in 0.01..99_999.99 }
        .associateBy { String.format(Locale.US, "%.2f", it.value) }
    val result = mutableMapOf<String, PriceVote>()

    previous.forEach { (key, vote) ->
        val current = frameByKey[key]
        if (current != null) {
            result[key] = PriceVote(
                price = if (current.unit.isNotBlank()) current else vote.price,
                hits = (vote.hits + 1).coerceAtMost(6),
                misses = 0,
            )
        } else {
            val misses = vote.misses + 1
            val hits = (vote.hits - 1).coerceAtLeast(0)
            if (misses <= 3 && hits > 0) {
                result[key] = vote.copy(hits = hits, misses = misses)
            }
        }
    }

    frameByKey.forEach { (key, price) ->
        if (key !in result) {
            result[key] = PriceVote(price = price, hits = 1)
        }
    }
    return result
}

private class LabelAnalyzer(
    private val onResult: (LabelScan) -> Unit,
) : ImageAnalysis.Analyzer {
    private val processing = AtomicBoolean(false)
    private val textRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_QR_CODE,
            )
            .build(),
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (!processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            processing.set(false)
            imageProxy.close()
            return
        }

        val input = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees,
        )
        val textTask = textRecognizer.process(input)
        val barcodeTask = barcodeScanner.process(input)

        Tasks.whenAllComplete(textTask, barcodeTask).addOnCompleteListener {
            try {
                val textResult = if (textTask.isSuccessful) textTask.result else null
                val text = textResult?.text.orEmpty()
                val tokens = textResult?.textBlocks.orEmpty().flatMap { block ->
                    block.lines.flatMap { line ->
                        line.elements.mapNotNull { element ->
                            element.boundingBox?.let { box ->
                                OcrToken(
                                    text = element.text,
                                    left = box.left,
                                    top = box.top,
                                    right = box.right,
                                    bottom = box.bottom,
                                )
                            }
                        }
                    }
                }

                val barcodes = if (barcodeTask.isSuccessful) {
                    barcodeTask.result.orEmpty().mapNotNull { it.rawValue }
                } else {
                    emptyList()
                }

                onResult(PriceParser.parseLabel(text, barcodes, tokens))
            } finally {
                processing.set(false)
                imageProxy.close()
            }
        }
    }
}
