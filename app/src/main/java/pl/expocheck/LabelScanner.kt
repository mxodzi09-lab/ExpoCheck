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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

private val ScannerNavy = Color(0xFF0D2B35)
private val ScannerInk = Color(0xFF13242A)
private val ScannerMuted = Color(0xFF66767C)
private val ScannerLine = Color(0xFFE3E7E5)
private val ScannerGreen = Color(0xFF16764B)

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
    var priceVotes by remember {
        mutableStateOf<Map<String, PriceVote>>(emptyMap())
    }
    var catalogCandidate by remember { mutableStateOf("") }
    var catalogHits by remember { mutableIntStateOf(0) }
    var acceptedCatalog by remember {
        mutableStateOf(online.catalogNumber)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (permissionGranted) {
            LiveCameraPreview(
                modifier = Modifier.fillMaxSize(),
                onScan = { frame ->
                    val framePrices =
                        PriceParser.detectedPrices(frame)

                    priceVotes =
                        updatePriceVotes(priceVotes, framePrices)

                    val stablePrices = priceVotes.values
                        .filter {
                            it.hits >= 2 &&
                                it.misses <= 2
                        }
                        .map { it.price }
                        .groupBy {
                            String.format(
                                Locale.US,
                                "%.2f",
                                it.value,
                            )
                        }
                        .map { (_, group) ->
                            group.maxByOrNull {
                                if (it.unit.isNotBlank()) 1
                                else 0
                            } ?: group.first()
                        }
                        .take(3)

                    val shownPrices =
                        if (stablePrices.isNotEmpty()) {
                            stablePrices
                        } else {
                            framePrices.take(3)
                        }

                    val rawCatalog = frame.catalogNumber
                    if (rawCatalog.matches(Regex("100\\d{6}"))) {
                        if (rawCatalog == catalogCandidate) {
                            catalogHits += 1
                        } else {
                            catalogCandidate = rawCatalog
                            catalogHits = 1
                        }

                        if (catalogHits >= 2) {
                            acceptedCatalog = rawCatalog
                        }
                    }

                    scan = frame.copy(
                        price = shownPrices.firstOrNull()?.value,
                        unit = shownPrices.firstOrNull()?.unit.orEmpty(),
                        prices = shownPrices,
                        catalogNumber = acceptedCatalog,
                    )
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
                        permissionLauncher.launch(
                            Manifest.permission.CAMERA
                        )
                    }
                ) {
                    Text("Zezwól na aparat")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.background(
                    Color.Black.copy(alpha = 0.50f),
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
                color = Color.Black.copy(alpha = 0.50f),
            ) {
                Text(
                    "TYLKO DUŻE CENY",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(
                        horizontal = 13.dp,
                        vertical = 9.dp,
                    ),
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(
                    start = 22.dp,
                    end = 22.dp,
                    bottom = 50.dp,
                )
                .fillMaxWidth()
                .height(330.dp)
                .clip(RoundedCornerShape(26.dp))
                .border(
                    width = 2.dp,
                    color = Color.White.copy(alpha = 0.86f),
                    shape = RoundedCornerShape(26.dp),
                ),
        ) {
            Text(
                "Umieść dużą cenę w ramce",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(
                        Color.Black.copy(alpha = 0.52f),
                        RoundedCornerShape(
                            bottomStart = 14.dp,
                            bottomEnd = 14.dp,
                        ),
                    )
                    .padding(
                        horizontal = 14.dp,
                        vertical = 7.dp,
                    ),
            )
        }

        BigPricePanel(
            modifier = Modifier.align(Alignment.BottomCenter),
            scan = scan,
            isStable = priceVotes.values.any { it.hits >= 2 },
            onConfirm = {
                val page = PageSnapshot(
                    name = if (scan.catalogNumber.isBlank()) {
                        "Zeskanowana cenówka"
                    } else {
                        "Produkt ${scan.catalogNumber}"
                    },
                    catalogNumber = scan.catalogNumber,
                    unit = scan.unit,
                )
                onOnlineResolved(page)
                onConfirmed(scan)
            },
        )
    }
}

@Composable
private fun BigPricePanel(
    modifier: Modifier,
    scan: LabelScan,
    isStable: Boolean,
    onConfirm: () -> Unit,
) {
    val prices = PriceParser.detectedPrices(scan)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xF7FFFFFF),
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 11.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = ScannerNavy,
            ) {
                Box(
                    modifier = Modifier.size(38.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.DocumentScanner,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        prices.isEmpty() ->
                            "Szukam dużej ceny…"

                        prices.size == 1 ->
                            "${PriceParser.money(prices[0].value)} " +
                                prices[0].unit

                        else ->
                            prices.joinToString("  •  ") {
                                "${PriceParser.money(it.value)} " +
                                    it.unit
                            }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ScannerInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    buildString {
                        if (scan.catalogNumber.isNotBlank()) {
                            append("Kod ${scan.catalogNumber} • ")
                        }
                        append(
                            when {
                                prices.isEmpty() ->
                                    "ignoruję małe liczby i tekst"

                                isStable ->
                                    "odczyt potwierdzony"

                                else ->
                                    "potwierdzam odczyt…"
                            }
                        )
                    },
                    color = ScannerMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (prices.isNotEmpty() && isStable) {
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
                val rotation =
                    imageProxy.imageInfo.rotationDegrees
                val uprightWidth =
                    if (rotation == 90 || rotation == 270) {
                        mediaImage.height
                    } else {
                        mediaImage.width
                    }
                val uprightHeight =
                    if (rotation == 90 || rotation == 270) {
                        mediaImage.width
                    } else {
                        mediaImage.height
                    }

                val tokens = textResult?.textBlocks.orEmpty()
                    .flatMap { block ->
                        block.lines.flatMap { line ->
                            line.elements.mapNotNull { element ->
                                element.boundingBox?.let { box ->
                                    val centerX =
                                        (box.left + box.right) / 2.0
                                    val centerY =
                                        (box.top + box.bottom) / 2.0

                                    val insideUsefulArea =
                                        centerX in
                                            (uprightWidth * 0.04)..
                                            (uprightWidth * 0.96) &&
                                        centerY in
                                            (uprightHeight * 0.07)..
                                            (uprightHeight * 0.84)

                                    if (!insideUsefulArea) {
                                        null
                                    } else {
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
