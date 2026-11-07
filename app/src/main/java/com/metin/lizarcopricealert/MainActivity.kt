package com.metin.lizarcopricealert

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val SITE = "https://lizarco.com/"
private const val CHANNEL_ID = "price_changes"
private const val PREFS = "lizarco"
private const val CRAWL_LIMIT = 250
private const val PRODUCT_LIMIT = 5000

data class Product(val id: String, val name: String, val price: Long, val url: String)
data class Alert(val product: String, val oldPrice: Long, val newPrice: Long, val time: Long)

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        scheduleChecker(this)
        setContent { App() }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(loadProducts(ctx)) }
    var all by remember { mutableStateOf(emptyList<Product>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        try {
            all = withContext(Dispatchers.IO) { fetchAllProducts() }
        } catch (e: Exception) {
            error = "دریافت محصولات ناموفق بود: ${e.message ?: "خطای شبکه"}"
        }
        loading = false
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("هشدار قیمت لیزارکو", fontWeight = FontWeight.Bold) }) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, icon = { Text("+") }, label = { Text("اضافه کردن محصول") })
                    NavigationBarItem(tab == 1, { tab = 1 }, icon = { Text("🔔") }, label = { Text("اطلاعیه") })
                }
            }
        ) { pad ->
            if (tab == 0) {
                AddProductScreen(pad, all, selected, loading, error, { refreshKey++ }) { p ->
                    selected = (selected + p).distinctBy { it.id }
                    saveProducts(ctx, selected)
                }
            } else {
                AlertsScreen(pad, loadAlerts(ctx))
            }
        }
    }
}

@Composable
private fun AddProductScreen(
    pad: PaddingValues,
    all: List<Product>,
    selected: List<Product>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onAdd: (Product) -> Unit
) {
    var q by remember { mutableStateOf("") }
    val shown = all.filter { it.name.contains(q, ignoreCase = true) }
    Column(Modifier.padding(pad).fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), label = { Text("جستجوی محصول") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRefresh, enabled = !loading) { Text("به‌روزرسانی") }
        }
        Spacer(Modifier.height(8.dp))
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("در حال پیدا کردن محصولات سایت...", modifier = Modifier.padding(vertical = 6.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        Text("محصولات پیدا شده: ${shown.size}", modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn {
            items(shown, key = { it.id }) { p ->
                val exists = selected.any { it.id == p.id }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold)
                            Text("${formatPrice(p.price)} تومان")
                        }
                        Button(enabled = !exists, onClick = { onAdd(p) }) {
                            Text(if (exists) "در حال پیگیری" else "افزودن")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertsScreen(pad: PaddingValues, alerts: List<Alert>) {
    Column(Modifier.padding(pad).fillMaxSize().padding(12.dp)) {
        Text("تغییرات قیمت", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (alerts.isEmpty()) Text("هنوز تغییری ثبت نشده است.")
        LazyColumn {
            items(alerts) { a ->
                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(a.product, fontWeight = FontWeight.Bold)
                        Text("قیمت قبلی: ${formatPrice(a.oldPrice)} تومان")
                        Text("قیمت جدید: ${formatPrice(a.newPrice)} تومان")
                        Text(if (a.newPrice > a.oldPrice) "افزایش قیمت" else "کاهش قیمت")
                    }
                }
            }
        }
    }
}

/**
 * Finds product-detail links across the whole reachable site, not only the homepage.
 * It follows internal HTML links, sitemap.xml when available, and paginated/category pages.
 */
private fun fetchAllProducts(): List<Product> {
    val productUrls = LinkedHashSet<String>()
    val pages = ArrayDeque<String>()
    val seenPages = HashSet<String>()
    pages.add(SITE)

    // Try common sitemap locations first.
    for (sitemap in listOf("${SITE}sitemap.xml", "${SITE}sitemap_index.xml")) {
        try {
            val d = Jsoup.connect(sitemap).userAgent(USER_AGENT).timeout(12000).get()
            d.select("loc").forEach { loc ->
                val u = normalizeUrl(loc.text())
                if (u.contains("/product-detail/")) productUrls.add(u)
                else if (isInternal(u)) pages.add(u)
            }
        } catch (_: Exception) { }
    }

    var crawled = 0
    while (pages.isNotEmpty() && crawled < CRAWL_LIMIT && productUrls.size < PRODUCT_LIMIT) {
        val url = normalizeUrl(pages.removeFirst())
        if (!seenPages.add(url)) continue
        val doc = try {
            Jsoup.connect(url).userAgent(USER_AGENT).timeout(18000).get()
        } catch (_: Exception) {
            continue
        }
        crawled++
        doc.select("a[href]").forEach { a ->
            val target = normalizeUrl(a.absUrl("href"))
            if (target.isBlank() || !isInternal(target)) return@forEach
            if (target.contains("/product-detail/")) productUrls.add(target)
            else if (isCrawlablePage(target) && !seenPages.contains(target) && pages.size < CRAWL_LIMIT) pages.add(target)
        }
    }

    // Read the current price and title from each product page. This avoids relying on
    // the product card markup and also handles products that only appear on category pages.
    val result = LinkedHashMap<String, Product>()
    for (url in productUrls.take(PRODUCT_LIMIT)) {
        try {
            val doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(15000).get()
            val name = extractProductName(doc) ?: continue
            val price = extractProductPrice(doc) ?: continue
            result[url] = Product(url, name, price, url)
        } catch (_: Exception) { }
    }
    return result.values.toList().sortedBy { it.name }
}

private const val USER_AGENT = "Mozilla/5.0 (Android) LizarcoPriceAlert/2.0"

private fun extractProductName(doc: Document): String? {
    val candidates = listOf(
        doc.selectFirst("meta[property=og:title]")?.attr("content"),
        doc.selectFirst("h1")?.text(),
        doc.selectFirst("title")?.text()
    )
    return candidates.firstOrNull { !it.isNullOrBlank() }
        ?.trim()?.replace(Regex("\\s+"), " ")
        ?.removeSuffix(" | لیزارکو")
}

private fun extractProductPrice(doc: Document): Long? {
    // Prefer elements whose class/id/label contains price.
    val candidates = doc.select("[class*=price], [id*=price], .woocommerce-Price-amount, .price")
        .map { it.text() }
        .filter { it.isNotBlank() }
    for (text in candidates) parsePrice(text)?.let { return it }
    return parsePrice(doc.body().text())
}

private fun isInternal(url: String): Boolean = url.startsWith(SITE) && !url.contains("#")

private fun isCrawlablePage(url: String): Boolean {
    if (!isInternal(url)) return false
    if (url.contains("/product-detail/")) return false
    val lower = url.lowercase(Locale.ROOT)
    return !lower.endsWith(".jpg") && !lower.endsWith(".jpeg") && !lower.endsWith(".png") &&
        !lower.endsWith(".webp") && !lower.endsWith(".pdf") && !lower.contains("/cart") &&
        !lower.contains("/checkout") && !lower.contains("/login") && !lower.contains("/account")
}

private fun normalizeUrl(raw: String): String {
    if (raw.isBlank()) return ""
    return raw.trim().substringBefore("#")
}

private fun parsePrice(text: String): Long? {
    val normalized = text
        .replace('٬', ',').replace('،', ',').replace('٫', '.')
        .replace("تومان", "").replace("ریال", "")
        .map { when (it) {
            '۰'->'0'; '۱'->'1'; '۲'->'2'; '۳'->'3'; '۴'->'4';
            '۵'->'5'; '۶'->'6'; '۷'->'7'; '۸'->'8'; '۹'->'9'; else->it
        }}.joinToString("")
    return Regex("\\d{3,}(?:,\\d{3})*")
        .findAll(normalized)
        .mapNotNull { it.value.replace(",", "").toLongOrNull() }
        .maxOrNull()
}

private fun formatPrice(n: Long): String = NumberFormat.getNumberInstance(Locale("fa", "IR")).format(n)

private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

private fun saveProducts(ctx: Context, ps: List<Product>) {
    prefs(ctx).edit().putString("products", ps.joinToString("\n") {
        listOf(it.id, it.name.replace("|", " "), it.price, it.url).joinToString("|")
    }).apply()
}

private fun loadProducts(ctx: Context): List<Product> = prefs(ctx).getString("products", "")!!
    .lines().filter { it.isNotBlank() }.mapNotNull { x ->
        val p = x.split("|")
        if (p.size >= 4) Product(p[0], p[1], p[2].toLongOrNull() ?: return@mapNotNull null, p[3]) else null
    }

private fun loadAlerts(ctx: Context): List<Alert> = prefs(ctx).getString("alerts", "")!!
    .lines().filter { it.isNotBlank() }.mapNotNull { x ->
        val p = x.split("|")
        if (p.size >= 4) Alert(p[0], p[1].toLong(), p[2].toLong(), p[3].toLong()) else null
    }

private fun saveAlerts(ctx: Context, a: List<Alert>) {
    prefs(ctx).edit().putString("alerts", a.take(100).joinToString("\n") {
        listOf(it.product.replace("|", " "), it.oldPrice, it.newPrice, it.time).joinToString("|")
    }).apply()
}

class PriceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val watched = loadProducts(applicationContext)
        if (watched.isEmpty()) return@withContext Result.success()
        try {
            val current = fetchAllProducts().associateBy { it.id }
            val alerts = loadAlerts(applicationContext).toMutableList()
            val updated = watched.map { old ->
                val now = current[old.id]
                if (now != null && now.price != old.price) {
                    alerts.add(0, Alert(old.name, old.price, now.price, System.currentTimeMillis()))
                    sendNotification(applicationContext, old.name, old.price, now.price)
                    now
                } else old
            }
            saveProducts(applicationContext, updated)
            saveAlerts(applicationContext, alerts)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

private fun scheduleChecker(ctx: Context) {
    val request = PeriodicWorkRequestBuilder<PriceWorker>(12, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
        .build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        "lizarco_price_check", ExistingPeriodicWorkPolicy.KEEP, request
    )
}

private fun createChannel(ctx: Context) {
    val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "تغییر قیمت", NotificationManager.IMPORTANCE_HIGH)
    )
}

private fun sendNotification(ctx: Context, name: String, old: Long, now: Long) {
    createChannel(ctx)
    val body = "قیمت قبلی: ${formatPrice(old)} تومان\nقیمت جدید: ${formatPrice(now)} تومان"
    val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("تغییر قیمت: $name")
        .setContentText("${formatPrice(old)} ← ${formatPrice(now)} تومان")
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setAutoCancel(true)
        .build()
    (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .notify(name.hashCode(), notification)
}
