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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun LabelScannerScreen(
    online: PageSnapshot,
    onBack: () -> Unit,
    onConfirmed: (LabelScan) -> Unit,
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var scan by remember { mutableStateOf(LabelScan()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { permissionGranted = it },
    )

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permissionGranted) {
            LiveCameraPreview(
                modifier = Modifier.fillMaxSize(),
                onScan = { newScan ->
                    // Nie kasujemy już znalezionego kodu lub jednostki przez słabszą kolejną klatkę.
                    scan = newScan.copy(
                        price = newScan.price ?: scan.price,
                        unit = newScan.unit.ifBlank { scan.unit },
                        catalogNumber = newScan.catalogNumber.ifBlank { scan.catalogNumber },
                        ean = newScan.ean.ifBlank { scan.ean },
                    )
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
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(Color(0x99000000), RoundedCornerShape(50)),
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Wróć", tint = Color.White)
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xF5FFFFFF)),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FlashOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("  Skanowanie cenówki na żywo", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Produkt online: ${online.catalogNumber.ifBlank { "nieodczytany" }}")
                    Text(
                        "Cena strony: ${PriceParser.money(online.currentPrice)} ${online.unit}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "Cena z cenówki: ${PriceParser.money(scan.price)} ${scan.unit}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (scan.catalogNumber.isNotBlank()) Text("Nr z cenówki: ${scan.catalogNumber}")
                    if (scan.ean.isNotBlank()) Text("EAN: ${scan.ean}")

                    val match = PriceParser.pricesMatch(online, scan)
                    val resultText = when (match) {
                        true -> "Cena się zgadza"
                        false -> "Cena się NIE zgadza"
                        null -> "Skieruj aparat na cenę i kod produktu"
                    }
                    val resultColor = when (match) {
                        true -> Color(0xFF177A4A)
                        false -> Color(0xFFB3261E)
                        null -> Color(0xFF6B7378)
                    }
                    Text(resultText, color = resultColor, style = MaterialTheme.typography.titleMedium)

                    Button(
                        onClick = { onConfirmed(scan) },
                        enabled = scan.price != null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
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
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor, LabelAnalyzer { result ->
                    ContextCompat.getMainExecutor(context).execute { onScan(result) }
                })
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

private class LabelAnalyzer(
    private val onResult: (LabelScan) -> Unit,
) : ImageAnalysis.Analyzer {
    private val processing = AtomicBoolean(false)
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
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

        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val textTask = textRecognizer.process(input)
        val barcodeTask = barcodeScanner.process(input)
        Tasks.whenAllComplete(textTask, barcodeTask).addOnCompleteListener {
            try {
                val text = if (textTask.isSuccessful) textTask.result?.text.orEmpty() else ""
                val barcodes = if (barcodeTask.isSuccessful) barcodeTask.result.orEmpty().mapNotNull { it.rawValue } else emptyList()
                onResult(PriceParser.parseLabel(text, barcodes))
            } finally {
                processing.set(false)
                imageProxy.close()
            }
        }
    }
}
