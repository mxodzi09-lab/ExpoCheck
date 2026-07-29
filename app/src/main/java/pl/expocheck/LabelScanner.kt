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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

private val ScannerNavy = Color(0xFF0D2B35)
private val ScannerInk = Color(0xFF13242A)
private val ScannerMuted = Color(0xFF66767C)
private val ScannerLine = Color(0xFFE3E7E5)
private val ScannerGreen = Color(0xFF16764B)
private val ScannerRed = Color(0xFFB63A32)
private val ScannerAmber = Color(0xFF9A6B16)

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
    var lookupMessage by remember {
        mutableStateOf("Umieść kod produktu i ceny wewnątrz ramki.")
    }
    var lastEanCandidate by remember { mutableStateOf("") }
    var eanHits by remember { mutableIntStateOf(0) }
    var priceVotes by remember { mutableStateOf<Map<String, PriceVote>>(emptyMap()) }
    var catalogCandidate by remember { mutableStateOf("") }
    var catalogHits by remember { mutableIntStateOf(0) }
    var acceptedCatalog by remember { mutableStateOf(online.catalogNumber) }

    LaunchedEffect(online) {
        if (online.catalogNumber.isNotBlank() || online.currentPrice != null) {
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
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

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
                lookupMessage = "Ceny produktu zostały pobrane."
                onOnlineResolved(page)
            },
            onError = { lookupMessage = it },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (permissionGranted) {
            LiveCameraPreview(
                modifier = Modifier.fillMaxSize(),
                onScan = { newScan ->
                    val currentExpected =
                        PriceParser.comparablePagePrices(effectiveOnline)
                    val framePrices = PriceParser.detectedPrices(newScan)

                    priceVotes = updatePriceVotes(priceVotes, framePrices)
                    val stablePrices = priceVotes.values
                        .filter { it.hits >= 2 && it.misses <= 2 }
                        .map { it.price }

                    val mergedPrices = if (currentExpected.isNotEmpty()) {
                        (stablePrices + framePrices)
                            .filter { shelf ->
                                currentExpected.any { onlinePrice ->
                                    abs(onlinePrice.value - shelf.value) < 0.011
                                }
                            }
                    } else {
                        stablePrices
                    }
                        .groupBy {
                            String.format(Locale.US, "%.2f", it.value)
                        }
                        .map { (_, group) ->
                            group.maxByOrNull {
                                if (it.unit.isNotBlank()) 1 else 0
                            } ?: group.first()
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

                        if (catalogHits >= 2 && rawCatalog != acceptedCatalog) {
                            acceptedCatalog = rawCatalog
                            resolvedOnline =
                                PageSnapshot(catalogNumber = rawCatalog)
                            lookupCode = rawCatalog
                            lookupMessage =
                                "Pobieram aktualne ceny produktu $rawCatalog…"
                            priceVotes = emptyMap()
                        } else if (
                            catalogHits < 2 &&
                            acceptedCatalog.isBlank()
                        ) {
                            lookupMessage =
                                "Potwierdzam kod $rawCatalog • $catalogHits/2"
                        }
                    }

                    val primary =
                        mergedPrices.firstOrNull()?.value ?: scan.price
                    val primaryUnit =
                        mergedPrices.firstOrNull()?.unit.orEmpty()
                            .ifBlank { scan.unit }

                    scan = newScan.copy(
                        price = primary,
                        unit = primaryUnit,
                        prices = mergedPrices,
                        catalogNumber = acceptedCatalog,
                        ean = newScan.ean.ifBlank { scan.ean },
                    )

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
                        lookupMessage =
                            "Pobieram aktualne ceny produktu $catalog…"
                    } else if (
                        catalog.isBlank() &&
                        newScan.ean.isNotBlank()
                    ) {
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
                            lookupMessage =
                                "Rozpoznano EAN ${newScan.ean}. Szukam produktu…"
                        }
                    }
                },
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Aplikacja potrzebuje dostępu do aparatu.",
                    color = Color.White,
                )
                Button(
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                ) {
                    Text("Zezwól na aparat")
                }
            }
        }

        ScannerTopBar(
            onBack = onBack,
            currentStep = if (acceptedCatalog.isBlank()) 1 else 2,
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 30.dp)
                .fillMaxWidth()
                .height(270.dp)
                .clip(RoundedCornerShape(26.dp))
                .border(
                    width = 2.dp,
                    color = Color.White.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(26.dp),
                ),
        ) {
            Text(
                "Kod produktu i ceny",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(
                        Color.Black.copy(alpha = 0.48f),
                        RoundedCornerShape(
                            bottomStart = 14.dp,
                            bottomEnd = 14.dp,
                        ),
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }

        val onlinePrices =
            PriceParser.comparablePagePrices(effectiveOnline)
        val shelfPrices = PriceParser.detectedPrices(scan)
        val comparisons =
            PriceParser.comparePrices(effectiveOnline, scan)
        val matchedCount =
            comparisons.count { it.matchedLabelPrice != null }

        ScannerBottomSheet(
            modifier = Modifier.align(Alignment.BottomCenter),
            page = effectiveOnline,
            scan = scan,
            onlinePrices = onlinePrices,
            shelfPrices = shelfPrices,
            comparisons = comparisons,
            matchedCount = matchedCount,
            lookupMessage = lookupMessage,
            onConfirm = {
                onOnlineResolved(effectiveOnline)
                onConfirmed(scan)
            },
        )
    }
}

@Composable
private fun ScannerTopBar(
    onBack: () -> Unit,
    currentStep: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.background(
                Color.Black.copy(alpha = 0.48f),
                CircleShape,
            ),
        ) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "Wróć",
                tint = Color.White,
            )
        }
        Spacer(Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Black.copy(alpha = 0.48f),
        ) {
            Text(
                "Krok $currentStep z 3",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(
                    horizontal = 13.dp,
                    vertical = 9.dp,
                ),
            )
        }
    }
}

@Composable
private fun ScannerBottomSheet(
    modifier: Modifier,
    page: PageSnapshot,
    scan: LabelScan,
    onlinePrices: List<ComparablePagePrice>,
    shelfPrices: List<DetectedPrice>,
    comparisons: List<PriceComparison>,
    matchedCount: Int,
    lookupMessage: String,
    onConfirm: () -> Unit,
) {
    val code = page.catalogNumber.ifBlank { scan.catalogNumber }
    val isLoading = code.isNotBlank() && onlinePrices.isEmpty()
    val result = scannerResult(
        hasCode = code.isNotBlank(),
        onlineCount = onlinePrices.size,
        shelfCount = shelfPrices.size,
        matchedCount = matchedCount,
        lookupMessage = lookupMessage,
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xF7FFFFFF),
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 15.dp,
                vertical = 11.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = result.second.copy(alpha = 0.12f),
                ) {
                    Box(
                        modifier = Modifier.size(34.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.FlashOn,
                            contentDescription = null,
                            tint = result.second,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (code.isBlank()) {
                            "Skanuj kod i ceny"
                        } else {
                            "Produkt $code"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = ScannerInk,
                    )
                    Text(
                        when {
                            page.name.isNotBlank() -> page.name
                            else -> lookupMessage
                        },
                        color = ScannerMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (
                    onlinePrices.isNotEmpty() &&
                    shelfPrices.isNotEmpty()
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = result.second.copy(alpha = 0.10f),
                    ) {
                        Text(
                            if (matchedCount == onlinePrices.size) {
                                "ZGODNE"
                            } else {
                                "SPRAWDŹ"
                            },
                            color = result.second,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(
                                horizontal = 9.dp,
                                vertical = 6.dp,
                            ),
                        )
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = ScannerNavy,
                    trackColor = ScannerLine,
                )
            }

            if (onlinePrices.isNotEmpty()) {
                comparisons.take(3).forEach { comparison ->
                    CompactPriceLine(comparison)
                }
            } else if (shelfPrices.isNotEmpty()) {
                Text(
                    "Cenówka: " +
                        shelfPrices.take(3).joinToString(" • ") {
                            "${PriceParser.money(it.value)} " +
                                it.unit.ifBlank { page.unit }
                        },
                    color = ScannerInk,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    result.first,
                    modifier = Modifier.weight(1f),
                    color = result.second,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (
                    code.isNotBlank() &&
                    shelfPrices.isNotEmpty() &&
                    onlinePrices.isNotEmpty()
                ) {
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Text("  Dalej")
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactPriceLine(
    comparison: PriceComparison,
) {
    val shelf = comparison.matchedLabelPrice
    val matched = shelf != null
    val accent = if (matched) ScannerGreen else ScannerRed

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            comparison.online.label,
            modifier = Modifier.weight(1f),
            color = ScannerMuted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${PriceParser.money(comparison.online.value)} " +
                comparison.online.unit,
            color = ScannerNavy,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (matched) "✓" else "—",
            color = accent,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun scannerResult(
    hasCode: Boolean,
    onlineCount: Int,
    shelfCount: Int,
    matchedCount: Int,
    lookupMessage: String,
): Pair<String, Color> = when {
    !hasCode ->
        "Umieść kod produktu i ceny w ramce." to ScannerMuted

    onlineCount == 0 ->
        lookupMessage to ScannerMuted

    shelfCount == 0 ->
        "Ceny online gotowe. Pokaż całą cenówkę." to ScannerAmber

    matchedCount == onlineCount ->
        "Wszystkie wykryte ceny się zgadzają." to ScannerGreen

    matchedCount > 0 ->
        "Część cen się zgadza: $matchedCount z $onlineCount." to ScannerAmber

    else ->
        "Ceny na cenówce różnią się od strony." to ScannerRed
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
