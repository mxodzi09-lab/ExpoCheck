package pl.expocheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
    var screen by remember { mutableStateOf(if (nickname.isBlank()) Screen.ONBOARDING else Screen.HOME) }
    var browserUrl by remember { mutableStateOf("https://komfort.pl") }
    var browserCode by remember { mutableStateOf("") }
    var pageSnapshot by remember { mutableStateOf(PageSnapshot()) }
    var labelScan by remember { mutableStateOf(LabelScan()) }

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
                browserUrl = "https://komfort.pl"
                browserCode = ""
                screen = Screen.BROWSER
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
                screen = Screen.SCANNER
            },
        )

        Screen.SCANNER -> LabelScannerScreen(
            online = pageSnapshot,
            onBack = { screen = Screen.BROWSER },
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
private fun NicknameScreen(
    initial: String,
    onSaved: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(shape = RoundedCornerShape(30.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    modifier = Modifier.size(58.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.padding(14.dp))
                }
                Text("Witaj w ExpoCheck", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Wpisz nick. Będzie zapisywany przy sprawdzonych produktach.")
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(24) },
                    label = { Text("Twój nick") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onSaved(value.trim()) },
                    enabled = value.trim().length >= 2,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Zacznij pracę")
                }
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
    val problems = records.count { it.status in listOf(CheckStatus.WRONG_PRICE, CheckStatus.MISSING_PRICE, CheckStatus.TO_CHECK) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ExpoCheck") },
                actions = {
                    IconButton(onClick = onChangeNick) {
                        Icon(Icons.Default.Person, contentDescription = "Zmień nick")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Cześć, $nickname 👋", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Skanuj stronę i cenówkę — wynik zobaczysz od razu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("Sprawdzone", done.toString(), Icons.Default.CheckCircle, Modifier.weight(1f))
                    StatCard("Do poprawy", problems.toString(), Icons.Default.ReportProblem, Modifier.weight(1f))
                }
            }

            item {
                Button(onClick = onOpenScanner, modifier = Modifier.fillMaxWidth().height(58.dp)) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Text("  Skanuj produkt i cenówkę")
                }
            }

            item {
                Text("Produkty testowe", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Aktualne dane startowe z Komfort.pl — otwarcie strony odświeży cenę.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            items(SeedProducts.items) { product ->
                SeedProductCard(product = product, onClick = { onOpenSeed(product) })
            }

            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SeedProductCard(product: SeedProduct, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.padding(10.dp))
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(product.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text("Nr kat. ${product.catalogNumber}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${PriceParser.money(product.currentPrice)} ${product.unit}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                product.discountPercent?.let {
                    AssistChip(onClick = {}, label = { Text("-$it%") })
                }
            }
            product.installationPrice?.let {
                Text("Przy zakupie montażu: ${PriceParser.money(it)} ${product.unit}")
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
    var status by remember {
        mutableStateOf(
            when (matches) {
                true -> CheckStatus.DONE
                false -> CheckStatus.WRONG_PRICE
                null -> CheckStatus.TO_CHECK
            }
        )
    }
    var note by remember { mutableStateOf("") }
    var photoPath by remember { mutableStateOf("") }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) photoPath = runCatching { store.copyPhoto(uri) }.getOrDefault("")
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Wynik porównania") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Wróć") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ResultHero(matches = matches, page = page, label = label)
            }

            item {
                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(page.name.ifBlank { "Produkt Komfort" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Nr strony: ${page.catalogNumber.ifBlank { "—" }}")
                        Text("Nr cenówki: ${label.catalogNumber.ifBlank { "—" }}")
                        if (label.ean.isNotBlank()) Text("EAN: ${label.ean}")
                        if (page.catalogNumber.isNotBlank() && label.catalogNumber.isNotBlank() && page.catalogNumber != label.catalogNumber) {
                            Text("Uwaga: numery produktów są różne!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Text("Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CheckStatus.entries.chunked(2).forEach { rowStatuses ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowStatuses.forEach { option ->
                                val selected = status == option
                                if (selected) {
                                    Button(onClick = { status = option }, modifier = Modifier.weight(1f)) { Text(option.label) }
                                } else {
                                    OutlinedButton(onClick = { status = option }, modifier = Modifier.weight(1f)) { Text(option.label) }
                                }
                            }
                            if (rowStatuses.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(400) },
                    label = { Text("Notatka") },
                    placeholder = { Text("np. brakuje czerwonej cenówki promocyjnej") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Zdjęcie ekspozycji", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (photoPath.isNotBlank()) {
                            AsyncImage(
                                model = File(photoPath),
                                contentDescription = "Zdjęcie ekspozycji",
                                modifier = Modifier.fillMaxWidth().height(220.dp),
                            )
                        }
                        FilledTonalButton(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.AddAPhoto, contentDescription = null)
                            Text(if (photoPath.isBlank()) "  Dodaj zdjęcie" else "  Zmień zdjęcie")
                        }
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
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Text("  Zapisz produkt")
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun ResultHero(matches: Boolean?, page: PageSnapshot, label: LabelScan) {
    val color = when (matches) {
        true -> Color(0xFF177A4A)
        false -> Color(0xFFB3261E)
        null -> Color(0xFF7C5A13)
    }
    val background = when (matches) {
        true -> Color(0xFFE1F5EA)
        false -> Color(0xFFFFE8E5)
        null -> Color(0xFFFFF3D5)
    }
    val headline = when (matches) {
        true -> "Cena prawidłowa"
        false -> "Cena się nie zgadza"
        null -> "Wymaga sprawdzenia"
    }
    val difference = if (page.currentPrice != null && label.price != null) label.price - page.currentPrice else null

    Card(colors = CardDefaults.cardColors(containerColor = background), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(headline, color = color, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Strona: ${PriceParser.money(page.currentPrice)} ${page.unit}", style = MaterialTheme.typography.titleLarge)
            Text("Cenówka: ${PriceParser.money(label.price)} ${label.unit}", style = MaterialTheme.typography.titleLarge)
            difference?.let {
                val prefix = if (it > 0) "+" else ""
                Text("Różnica: $prefix${PriceParser.money(it)}", color = color, fontWeight = FontWeight.Bold)
            }
            page.discountPercent?.let { Text("Promocja online: -$it%") }
            page.lowest30Price?.let { Text("Najniższa cena z 30 dni: ${PriceParser.money(it)} ${page.unit}") }
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
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Historia produktów") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Wróć") }
                },
            )
        },
    ) { padding ->
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nie zapisano jeszcze żadnego produktu.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
private fun HistoryCard(record: ProductRecord, onDelete: () -> Unit) {
    val format = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("pl", "PL")) }
    Card(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(record.page.name.ifBlank { "Produkt" }, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Nr kat. ${record.page.catalogNumber.ifBlank { "—" }}")
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Usuń") }
            }
            Text("Strona: ${PriceParser.money(record.page.currentPrice)} ${record.page.unit}")
            Text("Cenówka: ${PriceParser.money(record.label.price)} ${record.label.unit}")
            AssistChip(onClick = {}, label = { Text(record.status.label) })
            Text("${record.nickname} • ${format.format(Date(record.createdAt))}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (record.note.isNotBlank()) Text(record.note)
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
        primary = Color(0xFF102B36),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD7EAF1),
        onPrimaryContainer = Color(0xFF001F28),
        secondary = Color(0xFF8B5E08),
        secondaryContainer = Color(0xFFFFDEA3),
        background = Color(0xFFF4F6F7),
        surface = Color.White,
        surfaceContainerHigh = Color(0xFFE9EEF0),
        error = Color(0xFFB3261E),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
