package pl.expocheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PremiumNavy = Color(0xFF0D2B35)
private val PremiumInk = Color(0xFF13242A)
private val PremiumMuted = Color(0xFF66767C)
private val PremiumCanvas = Color(0xFFF7F8F6)
private val PremiumLine = Color(0xFFE3E7E5)
private val PremiumGold = Color(0xFFC59B52)
private val PremiumGreen = Color(0xFF16764B)
private val PremiumRed = Color(0xFFB63A32)
private val PremiumAmber = Color(0xFF9A6B16)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ExpoCheckTheme {
                ExpoCheckRoot()
            }
        }
    }
}

private enum class Screen { ONBOARDING, HOME, BROWSER, SCANNER, REVIEW, HISTORY }

@Composable
private fun ExpoCheckRoot() {
    val context = LocalContext.current
    val store = remember { RecordStore(context) }
    var nickname by remember { mutableStateOf(store.nickname) }
    var records by remember { mutableStateOf(store.loadRecords()) }
    var screen by remember {
        mutableStateOf(if (nickname.isBlank()) Screen.ONBOARDING else Screen.HOME)
    }
    var browserUrl by remember { mutableStateOf("https://komfort.pl") }
    var browserCode by remember { mutableStateOf("") }
    var pageSnapshot by remember { mutableStateOf(PageSnapshot()) }
    var labelScan by remember { mutableStateOf(LabelScan()) }
    var scannerReturn by remember { mutableStateOf(Screen.HOME) }

    when (screen) {
        Screen.ONBOARDING -> NicknameScreen(
            initial = nickname,
            onSaved = {
                nickname = it
                store.nickname = it
                screen = Screen.HOME
            },
        )

        Screen.HOME -> HomeScreen(
            nickname = nickname,
            records = records,
            onOpenScanner = {
                pageSnapshot = PageSnapshot()
                labelScan = LabelScan()
                scannerReturn = Screen.HOME
                screen = Screen.SCANNER
            },
            onOpenSeed = { seed ->
                browserUrl = seed.url
                browserCode = seed.catalogNumber
                screen = Screen.BROWSER
            },
            onHistory = { screen = Screen.HISTORY },
            onChangeNick = { screen = Screen.ONBOARDING },
        )

        Screen.BROWSER -> KomfortBrowserScreen(
            initialUrl = browserUrl,
            initialCode = browserCode,
            onBack = { screen = Screen.HOME },
            onScanLabel = {
                pageSnapshot = it
                labelScan = LabelScan()
                scannerReturn = Screen.BROWSER
                screen = Screen.SCANNER
            },
        )

        Screen.SCANNER -> LabelScannerScreen(
            online = pageSnapshot,
            onBack = { screen = scannerReturn },
            onOnlineResolved = { pageSnapshot = it },
            onConfirmed = {
                labelScan = it
                screen = Screen.REVIEW
            },
        )

        Screen.REVIEW -> ReviewScreen(
            nickname = nickname,
            page = pageSnapshot,
            label = labelScan,
            onBack = { screen = Screen.SCANNER },
            onSaved = { record ->
                store.saveRecord(record)
                records = store.loadRecords()
                screen = Screen.HOME
            },
        )

        Screen.HISTORY -> HistoryScreen(
            records = records,
            onBack = { screen = Screen.HOME },
            onDelete = { id ->
                store.deleteRecord(id)
                records = store.loadRecords()
            },
        )
    }
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(50.dp),
        shape = RoundedCornerShape(17.dp),
        color = PremiumNavy,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(27.dp),
            )
        }
    }
}

@Composable
private fun NicknameScreen(
    initial: String,
    onSaved: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PremiumCanvas)
            .padding(horizontal = 24.dp, vertical = 34.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                BrandMark()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "ExpoCheck",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = PremiumInk,
                    )
                    Text(
                        "Proste sprawdzanie cen i ekspozycji.",
                        color = PremiumMuted,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, PremiumLine),
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        PremiumStepHeader(
                            currentStep = 1,
                            title = "Twój profil",
                            subtitle = "Nick pojawi się przy zapisanych produktach.",
                        )
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it.take(24) },
                            label = { Text("Nick") },
                            placeholder = { Text("np. Konrad") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            }

            Button(
                onClick = { onSaved(value.trim()) },
                enabled = value.trim().length >= 2,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Przejdź dalej", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    nickname: String,
    records: List<ProductRecord>,
    onOpenScanner: () -> Unit,
    onOpenSeed: (SeedProduct) -> Unit,
    onHistory: () -> Unit,
    onChangeNick: () -> Unit,
) {
    val done = records.count { it.status == CheckStatus.DONE }
    val problems = records.count {
        it.status in listOf(
            CheckStatus.WRONG_PRICE,
            CheckStatus.MISSING_PRICE,
            CheckStatus.TO_CHECK,
        )
    }

    Scaffold(
        containerColor = PremiumCanvas,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "ExpoCheck",
                        fontWeight = FontWeight.Bold,
                        color = PremiumInk,
                    )
                },
                actions = {
                    Surface(
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .clickable(onClick = onChangeNick),
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, PremiumLine),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                tint = PremiumNavy,
                            )
                            Text(
                                nickname,
                                style = MaterialTheme.typography.labelLarge,
                                color = PremiumInk,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = PremiumCanvas,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Start") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onHistory,
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("Historia") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Dzień dobry, $nickname",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = PremiumInk,
                )
                Text(
                    "Skieruj aparat na duże cyfry ceny. Mały tekst zostanie pominięty.",
                    color = PremiumMuted,
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, PremiumLine),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        PremiumStepHeader(
                            currentStep = 1,
                            title = "Nowe sprawdzenie",
                            subtitle = "Duża cena → potwierdzenie → zapis.",
                        )

                        StepDots(currentStep = 1)

                        Button(
                            onClick = onOpenScanner,
                            modifier = Modifier.fillMaxWidth().height(58.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                            Text(
                                "  Skanuj dużą cenę",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MinimalStatCard(
                        label = "Zrobione",
                        value = done.toString(),
                        icon = Icons.Default.CheckCircle,
                        accent = PremiumGreen,
                        modifier = Modifier.weight(1f),
                    )
                    MinimalStatCard(
                        label = "Do poprawy",
                        value = problems.toString(),
                        icon = Icons.Default.ReportProblem,
                        accent = PremiumRed,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Text(
                    "Ostatnia aktywność",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = PremiumInk,
                )
            }

            if (records.isEmpty()) {
                item {
                    EmptyActivityCard()
                }
            } else {
                items(records.take(3), key = { it.id }) { record ->
                    RecentRecordCard(record)
                }
            }

            item {
                Text(
                    "Produkty testowe",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = PremiumInk,
                )
                Text(
                    "Szybkie otwarcie strony do testów.",
                    color = PremiumMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            items(SeedProducts.items) { product ->
                SeedProductCard(product = product, onClick = { onOpenSeed(product) })
            }

            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun PremiumStepHeader(
    currentStep: Int,
    title: String,
    subtitle: String,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = PremiumNavy,
        ) {
            Box(
                modifier = Modifier.size(38.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    currentStep.toString(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = PremiumInk,
            )
            Text(
                subtitle,
                color = PremiumMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StepDots(currentStep: Int) {
    val labels = listOf("Produkt", "Ceny", "Zapis")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index + 1 <= currentStep
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            if (active) PremiumNavy else PremiumLine,
                            RoundedCornerShape(10.dp),
                        )
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) PremiumInk else PremiumMuted,
                )
            }
        }
    }
}

@Composable
private fun MinimalStatCard(
    label: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, PremiumLine),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = PremiumInk,
            )
            Text(label, color = PremiumMuted)
        }
    }
}

@Composable
private fun EmptyActivityCard() {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, PremiumLine),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                "Jeszcze nic tu nie ma",
                fontWeight = FontWeight.SemiBold,
                color = PremiumInk,
            )
            Text(
                "Pierwszy zapisany produkt pojawi się w tym miejscu.",
                color = PremiumMuted,
            )
        }
    }
}

@Composable
private fun RecentRecordCard(record: ProductRecord) {
    val match = PriceParser.pricesMatch(record.page, record.label)
    val accent = when (match) {
        true -> PremiumGreen
        false -> PremiumRed
        null -> PremiumAmber
    }
    val caption = when (match) {
        true -> "Cena prawidłowa"
        false -> "Niezgodność ceny"
        null -> "Do sprawdzenia"
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, PremiumLine),
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = accent.copy(alpha = 0.12f),
            ) {
                Box(
                    modifier = Modifier.size(42.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (match == true) Icons.Default.CheckCircle
                        else Icons.Default.ReportProblem,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.page.name.ifBlank { "Produkt" },
                    fontWeight = FontWeight.SemiBold,
                    color = PremiumInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Nr ${record.page.catalogNumber.ifBlank { "—" }}",
                    color = PremiumMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                caption,
                color = accent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SeedProductCard(
    product: SeedProduct,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, PremiumLine),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = PremiumNavy.copy(alpha = 0.08f),
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = PremiumNavy,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Column(
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                ) {
                    Text(
                        product.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        color = PremiumInk,
                    )
                    Text(
                        "Nr kat. ${product.catalogNumber}",
                        color = PremiumMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HorizontalDivider(color = PremiumLine)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${PriceParser.money(product.currentPrice)} ${product.unit}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = PremiumInk,
                    modifier = Modifier.weight(1f),
                )
                product.discountPercent?.let {
                    AssistChip(
                        onClick = {},
                        label = { Text("-$it%") },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewScreen(
    nickname: String,
    page: PageSnapshot,
    label: LabelScan,
    onBack: () -> Unit,
    onSaved: (ProductRecord) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { RecordStore(context) }
    val matches = PriceParser.pricesMatch(page, label)
    val scannedPrices = PriceParser.detectedPrices(label)
    val labelOnlyMode =
        page.currentPrice == null &&
            scannedPrices.isNotEmpty()

    var status by remember {
        mutableStateOf(
            when {
                labelOnlyMode -> CheckStatus.DONE
                matches == true -> CheckStatus.DONE
                matches == false -> CheckStatus.WRONG_PRICE
                else -> CheckStatus.TO_CHECK
            }
        )
    }
    var note by remember { mutableStateOf("") }
    var photoPath by remember { mutableStateOf("") }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            photoPath = runCatching { store.copyPhoto(uri) }.getOrDefault("")
        }
    }

    Scaffold(
        containerColor = PremiumCanvas,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Podsumowanie",
                        fontWeight = FontWeight.Bold,
                        color = PremiumInk,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wróć")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = PremiumCanvas,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, PremiumLine),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        PremiumStepHeader(
                            currentStep = 3,
                            title = "Zapis produktu",
                            subtitle = "Sprawdź wynik i wybierz status.",
                        )
                        StepDots(currentStep = 3)
                        ResultHero(matches = matches, page = page, label = label)
                    }
                }
            }

            item {
                PremiumSectionCard(title = "Produkt") {
                    Text(
                        page.name.ifBlank { "Produkt Komfort" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = PremiumInk,
                    )
                    DetailRow("Numer online", page.catalogNumber.ifBlank { "—" })
                    DetailRow("Numer cenówki", label.catalogNumber.ifBlank { "—" })
                    if (label.ean.isNotBlank()) {
                        DetailRow("EAN", label.ean)
                    }
                    if (
                        page.catalogNumber.isNotBlank() &&
                        label.catalogNumber.isNotBlank() &&
                        page.catalogNumber != label.catalogNumber
                    ) {
                        Text(
                            "Numery produktów są różne.",
                            color = PremiumRed,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            item {
                PremiumSectionCard(title = "Status") {
                    CheckStatus.entries.forEach { option ->
                        FilterChip(
                            selected = status == option,
                            onClick = { status = option },
                            label = { Text(option.label) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                PremiumSectionCard(title = "Notatka") {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(400) },
                        placeholder = {
                            Text("np. brakuje oznaczenia promocji")
                        },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }

            item {
                PremiumSectionCard(title = "Zdjęcie ekspozycji") {
                    if (photoPath.isNotBlank()) {
                        AsyncImage(
                            model = File(photoPath),
                            contentDescription = "Zdjęcie ekspozycji",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                        )
                    }
                    FilledTonalButton(
                        onClick = { photoPicker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = null)
                        Text(
                            if (photoPath.isBlank()) "  Dodaj zdjęcie"
                            else "  Zmień zdjęcie"
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        onSaved(
                            ProductRecord(
                                nickname = nickname,
                                page = page,
                                label = label,
                                status = status,
                                note = note.trim(),
                                exposurePhotoPath = photoPath,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Text("  Zapisz produkt", fontWeight = FontWeight.SemiBold)
                }
            }

            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun PremiumSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, PremiumLine),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = PremiumInk,
            )
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = PremiumMuted)
        Text(
            value,
            color = PremiumInk,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ResultHero(
    matches: Boolean?,
    page: PageSnapshot,
    label: LabelScan,
) {
    val accent = when (matches) {
        true -> PremiumGreen
        false -> PremiumRed
        null -> PremiumAmber
    }
    val background = accent.copy(alpha = 0.09f)
    val labelOnly =
        page.currentPrice == null &&
            PriceParser.detectedPrices(label).isNotEmpty()

    val headline = when {
        labelOnly -> "Cena odczytana"
        matches == true -> "Cena prawidłowa"
        matches == false -> "Wykryto niezgodność"
        else -> "Wymaga sprawdzenia"
    }
    val comparisons = PriceParser.comparePrices(page, label)
    val shelfPrices = PriceParser.detectedPrices(label)

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = background,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                headline,
                color = accent,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            comparisons.forEach { comparison ->
                val matched = comparison.matchedLabelPrice
                PriceComparisonRow(
                    title = comparison.online.label,
                    online = "${PriceParser.money(comparison.online.value)} ${comparison.online.unit}",
                    shelf = matched?.let {
                        "${PriceParser.money(it.value)} ${it.unit.ifBlank { comparison.online.unit }}"
                    } ?: "brak",
                    matched = matched != null,
                )
            }

            if (comparisons.isEmpty() && shelfPrices.isNotEmpty()) {
                Text(
                    "Odczytane ceny: " + shelfPrices.joinToString(" • ") {
                        "${PriceParser.money(it.value)} ${it.unit.ifBlank { page.unit }}"
                    },
                    color = PremiumInk,
                )
            }

            page.discountPercent?.let {
                Text(
                    "Promocja online: -$it%",
                    color = PremiumGold,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PriceComparisonRow(
    title: String,
    online: String,
    shelf: String,
    matched: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = PremiumMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            PriceMiniCard(
                label = "ONLINE",
                value = online,
                modifier = Modifier.weight(1f),
            )
            PriceMiniCard(
                label = "CENÓWKA",
                value = shelf,
                modifier = Modifier.weight(1f),
                accent = if (matched) PremiumGreen else PremiumRed,
            )
        }
    }
}

@Composable
private fun PriceMiniCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = PremiumNavy,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.20f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = PremiumMuted,
            )
            Text(
                value,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(
    records: List<ProductRecord>,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Scaffold(
        containerColor = PremiumCanvas,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Historia",
                        fontWeight = FontWeight.Bold,
                        color = PremiumInk,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wróć")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = PremiumCanvas,
                ),
            )
        },
    ) { padding ->
        if (records.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Nie zapisano jeszcze żadnego produktu.", color = PremiumMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    HistoryCard(record = record, onDelete = { onDelete(record.id) })
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    record: ProductRecord,
    onDelete: () -> Unit,
) {
    val format = remember {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("pl-PL"))
    }

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, PremiumLine),
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        record.page.name.ifBlank { "Produkt" },
                        fontWeight = FontWeight.Bold,
                        color = PremiumInk,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Nr kat. ${record.page.catalogNumber.ifBlank { "—" }}",
                        color = PremiumMuted,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Usuń")
                }
            }

            PriceParser.comparablePagePrices(record.page).forEach { online ->
                DetailRow(
                    online.label,
                    "${PriceParser.money(online.value)} ${online.unit}",
                )
            }

            val shelfPrices = PriceParser.detectedPrices(record.label)
            if (shelfPrices.isNotEmpty()) {
                Text(
                    "Cenówka: " + shelfPrices.joinToString(" • ") {
                        "${PriceParser.money(it.value)} ${it.unit.ifBlank { record.page.unit }}"
                    },
                    color = PremiumInk,
                )
            }

            AssistChip(onClick = {}, label = { Text(record.status.label) })

            Text(
                "${record.nickname} • ${format.format(Date(record.createdAt))}",
                color = PremiumMuted,
                style = MaterialTheme.typography.bodySmall,
            )

            if (record.note.isNotBlank()) {
                Text(record.note, color = PremiumInk)
            }

            if (record.exposurePhotoPath.isNotBlank()) {
                AsyncImage(
                    model = File(record.exposurePhotoPath),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
            }
        }
    }
}

@Composable
private fun ExpoCheckTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.lightColorScheme(
        primary = PremiumNavy,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCEBEF),
        onPrimaryContainer = PremiumInk,
        secondary = PremiumGold,
        secondaryContainer = Color(0xFFF5EAD7),
        background = PremiumCanvas,
        onBackground = PremiumInk,
        surface = Color.White,
        onSurface = PremiumInk,
        surfaceContainerHigh = Color(0xFFF0F3F1),
        outlineVariant = PremiumLine,
        error = PremiumRed,
    )
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
