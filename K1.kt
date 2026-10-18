package com.example.datausage

import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.util.Locale

const val NS = "com.example.datausage"
const val PRESET_COUNT = 4
val ACCENTS = listOf(0xFF5B6CFFL, 0xFF9B5CFFL, 0xFF0EA5A4L, 0xFFE5484DL, 0xFFF59E0BL, 0xFF30A46CL)
val WifiColor = Color(0xFF4FC3F7)
val MobileColor = Color(0xFFFFB74D)

object Ui {
    var accent by mutableIntStateOf(0)
    var mode by mutableIntStateOf(0)    // 0=自動 1=ライト 2=ダーク 3=AMOLED
    var palette by mutableIntStateOf(0) // 0=標準 1=Material You 2=モノクロ
    var mult by mutableFloatStateOf(1f) // 速度表示の倍率（見かけだけ）
    var lang by mutableIntStateOf(0)    // 0=自動 1=日本語 2=English (UK)
}

fun isJa(): Boolean = when (Ui.lang) {
    1 -> true
    2 -> false
    else -> Locale.getDefault().language == "ja"
}

/** 日本語 / 英語（イギリス英語）の切り替え */
fun tl(ja: String, en: String): String = if (isJa()) ja else en

fun appLocale(): Locale = if (isJa()) Locale.JAPAN else Locale.UK

fun presetLabels(): List<String> = listOf(
    tl("📶 DataUsageViewer（標準）", "📶 DataUsageViewer (default)"),
    tl("⇅ 通信モニター", "⇅ Traffic Monitor"),
    "◯ Data Ring",
    "⚡ Bolt"
)

data class Pal(
    val ac: Color, val ac2: Color,
    val hdr1: Color, val hdr2: Color,
    val blobs: List<Color>,
    val wifi: Color, val mobile: Color
) {
    val onHdr: Color
        get() = if ((hdr1.luminance() + hdr2.luminance()) / 2f > 0.45f) Color.Black else Color.White
}

val LocalPal = compositionLocalOf {
    Pal(
        Color(0xFF5B6CFF), Color(0xFF9B5CFF), Color(0xFF5B6CFF), Color(0xFF9B5CFF),
        listOf(Color(0xFF5B6CFF), Color(0xFF9B5CFF), Color(0xFF0EA5A4)), WifiColor, MobileColor
    )
}

class MainActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 時計・通知アイコンの領域（ステータスバー）まで画面を表示
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        val p = getSharedPreferences("s", 0)
        Ui.accent = p.getInt("accent", 0).coerceIn(0, ACCENTS.size - 1)
        Ui.mode = p.getInt("mode", 0)
        Ui.palette = p.getInt("palette", 0)
        Ui.mult = p.getFloat("mult", 1f)
        Ui.lang = p.getInt("lang", 0)
        applyRefreshRate(this, p.getBoolean("hz", true))
        if (p.getBoolean("notif", false)) {
            runCatching { startForegroundService(Intent(this, SpeedService::class.java)) }
        }
        setContent { AppTheme { Root() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val amoled = Ui.mode == 3
    val dark = amoled || when (Ui.mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val tr = if (dark) Color(0x1FFFFFFF) else Color(0x14000000)
    val bgc = if (amoled) Color.Black else if (dark) Color(0xFF0B0D14) else Color(0xFFEEF1FA)
    val n = ACCENTS.size
    val a = Color(ACCENTS[Ui.accent])
    val a2 = Color(ACCENTS[(Ui.accent + 1) % n])
    val textMain = if (dark) Color(0xFFF1F3F9) else Color(0xFF14171F)
    val textSub = if (dark) Color(0xFFB4BBCC) else Color(0xFF5E667A)

    val (cs, pal) = when {
        Ui.palette == 1 && Build.VERSION.SDK_INT >= 31 -> {
            val b = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            val c = b.copy(
                background = if (amoled) Color.Black else b.background,
                surface = if (amoled) Color.Black else b.surface,
                surfaceVariant = tr
            )
            c to Pal(
                c.primary, c.tertiary, c.primaryContainer, c.tertiaryContainer,
                listOf(c.primary, c.tertiary, c.secondary), c.primary, c.tertiary
            )
        }
        Ui.palette == 2 -> {
            val hi = if (dark) Color(0xFFEDEDED) else Color(0xFF222222)
            val lo = if (dark) Color(0xFF9A9A9A) else Color(0xFF6A6A6A)
            val c = if (dark) darkColorScheme(
                primary = hi, onPrimary = Color.Black, background = bgc, surface = bgc,
                onBackground = textMain, onSurface = textMain,
                onSurfaceVariant = textSub, surfaceVariant = tr
            ) else lightColorScheme(
                primary = hi, onPrimary = Color.White, background = bgc, surface = bgc,
                onBackground = textMain, onSurface = textMain,
                onSurfaceVariant = textSub, surfaceVariant = tr
            )
            c to Pal(
                hi, lo,
                if (dark) Color(0xFF4A4A4A) else Color(0xFF2B2B2B),
                if (dark) Color(0xFF262626) else Color(0xFF555555),
                listOf(
                    Color(0xFF9A9A9A).copy(alpha = 0.5f),
                    Color(0xFFBBBBBB).copy(alpha = 0.5f),
                    Color(0xFF777777).copy(alpha = 0.5f)
                ),
                if (dark) Color(0xFFEDEDED) else Color(0xFF3A3A3A),
                if (dark) Color(0xFF8A8A8A) else Color(0xFF9A9A9A)
            )
        }
        else -> {
            val c = if (dark) darkColorScheme(
                primary = a, onPrimary = Color.White, background = bgc,
                surface = if (amoled) Color.Black else Color(0xFF171B24),
                onBackground = textMain, onSurface = textMain,
                onSurfaceVariant = textSub, surfaceVariant = tr
            ) else lightColorScheme(
                primary = a, onPrimary = Color.White, background = bgc, surface = Color.White,
                onBackground = textMain, onSurface = textMain,
                onSurfaceVariant = textSub, surfaceVariant = tr
            )
            c to Pal(
                a, a2, a, a2,
                listOf(a, Color(ACCENTS[(Ui.accent + 2) % n]), Color(ACCENTS[(Ui.accent + 4) % n])),
                WifiColor, MobileColor
            )
        }
    }

    // ステータスバー／ナビゲーションバーのアイコン色をテーマに合わせる
    SideEffect {
        val w = (ctx as? Activity)?.window
        if (w != null) {
            val c = WindowCompat.getInsetsController(w, w.decorView)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(colorScheme = cs) {
        CompositionLocalProvider(LocalPal provides pal) {
            GlassBackground(dark, amoled, cs.background, pal.blobs) {
                Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = cs.onSurface, content = content)
            }
        }
    }
}

@Composable
fun GlassBackground(dark: Boolean, amoled: Boolean, base: Color, blobs: List<Color>, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(base)
            .drawBehind {
                if (amoled) return@drawBehind
                val a = if (dark) 0.50f else 0.42f
                val w = size.width
                val h = size.height
                fun blob(c: Color, x: Float, y: Float, r: Float) {
                    val o = Offset(x, y)
                    drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a * c.alpha), Color.Transparent), o, r), r, o)
                }
                blob(blobs[0], w * 0.1f, h * 0.08f, w * 0.95f)
                blob(blobs[1], w * 1.0f, h * 0.42f, w * 0.85f)
                blob(blobs[2], w * 0.1f, h * 0.9f, w * 0.95f)
            }
    ) { content() }
}

/** 画面の上下（時計・ナビゲーションバー）に被らないための余白 */
@Composable
fun pagePadding(): PaddingValues {
    val sd = WindowInsets.safeDrawing.asPaddingValues()
    return PaddingValues(16.dp, sd.calculateTopPadding() + 16.dp, 16.dp, sd.calculateBottomPadding() + 16.dp)
}

@Composable
fun Glass(
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val amoled = Ui.mode == 3
    val dark = MaterialTheme.colorScheme.onSurface.luminance() > 0.5f
    val shape = RoundedCornerShape(radius)
    val rim = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.40f else 0.95f),
            Color.White.copy(alpha = 0.04f),
            Color.White.copy(alpha = if (dark) 0.18f else 0.60f)
        )
    )
    val sheen = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = if (amoled) 0.07f else if (dark) 0.14f else 0.45f), Color.Transparent)
    )
    Box(
        modifier.clip(shape)
            .background(Color.White.copy(alpha = if (amoled) 0.06f else if (dark) 0.07f else 0.40f))
            .background(sheen)
            .border(1.dp, rim, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) { content() }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.onSurface.luminance() > 0.5f
    val ac = LocalPal.current.ac
    val shape = RoundedCornerShape(50)
    val bg by animateColorAsState(
        if (selected) ac.copy(alpha = 0.9f) else Color.White.copy(alpha = if (dark) 0.10f else 0.5f),
        tween(250), label = "pill"
    )
    val fg by animateColorAsState(
        if (selected) (if (ac.luminance() > 0.55f) Color.Black else Color.White) else MaterialTheme.colorScheme.onSurface,
        tween(250), label = "pillfg"
    )
    Box(
        Modifier.clip(shape)
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.25f else 0.8f), shape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = fg)
    }
}

/** 選択肢のボタンを折り返して並べる */
@Composable
fun PillRows(labels: List<String>, sel: Int, perRow: Int, onSel: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.indices.chunked(perRow).forEach { idxs ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                idxs.forEach { i -> Pill(labels[i], sel == i) { onSel(i) } }
            }
        }
    }
}

/** 右にスワイプで戻る */
fun Modifier.swipeBack(onBack: () -> Unit): Modifier = pointerInput(Unit) {
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = { if (total > 200f) onBack() },
        onDragCancel = { total = 0f }
    ) { _, d -> total += d }
}

data class AppUsage(
    val label: String, val pkg: String,
    val wifi: Long, val mobile: Long, val fgMs: Long,
    val icon: ImageBitmap?
) {
    val total get() = wifi + mobile
    val shownLabel: String
        get() = if (pkg.startsWith("uid:")) tl("システム/その他 ($pkg)", "System / other ($pkg)") else label
}

data class DayTotal(val ms: Long, val wifi: Long, val mobile: Long)
data class Roll(val w7: Long = 0, val m7: Long = 0, val w30: Long = 0, val m30: Long = 0)
data class Loaded(
    val apps: List<AppUsage> = emptyList(),
    val days: List<DayTotal> = emptyList(),
    val roll: Roll = Roll()
)
data class Batt(val pct: Int, val charging: Boolean, val tempC: Float)

fun readBattery(ctx: Context): Batt {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val lv = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
    return Batt(
        if (lv < 0) 0 else lv * 100 / sc,
        st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL,
        temp / 10f
    )
}

fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    return ops.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
    ) == AppOpsManager.MODE_ALLOWED
}

/** 小数点2桁（例: 4.54MB） */
fun fmtD(b: Double): String = when {
    b >= 1073741824.0 -> String.format(Locale.US, "%.2fGB", b / 1073741824.0)
    b >= 1048576.0 -> String.format(Locale.US, "%.2fMB", b / 1048576.0)
    b >= 1024.0 -> String.format(Locale.US, "%.2fKB", b / 1024.0)
    else -> String.format(Locale.US, "%.0fB", b)
}

fun fmt(b: Long): String = fmtD(b.toDouble())

fun multLabel(m: Float): String = if (m >= 1f) m.toInt().toString() else m.toString()

fun fmtTime(ms: Long): String {
    val m = ms / 60000
    return when {
        m >= 60 -> tl("${m / 60}時間${m % 60}分", "${m / 60}h ${m % 60}m")
        m >= 1 -> tl("${m}分", "$m min")
        ms > 0 -> tl("1分未満", "<1 min")
        else -> "-"
    }
}

// ---------- 120Hz / アイコン / ショートカット ----------
@Suppress("DEPRECATION")
fun applyRefreshRate(act: Activity, enabled: Boolean) {
    val d = act.windowManager.defaultDisplay
    val cur = d.mode
    val best = d.supportedModes
        .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        .maxByOrNull { it.refreshRate } ?: return
    val lp = act.window.attributes
    lp.preferredDisplayModeId = if (enabled) best.modeId else 0
    lp.preferredRefreshRate = if (enabled) best.refreshRate else 0f
    act.window.attributes = lp
}

fun setLauncherPreset(ctx: Context, sel: Int) {
    val pm = ctx.packageManager
    fun cn(i: Int) = ComponentName(ctx.packageName, "$NS.Alias${i + 1}")
    pm.setComponentEnabledSetting(cn(sel), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    (0 until PRESET_COUNT).filter { it != sel }.forEach {
        pm.setComponentEnabledSetting(cn(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}

fun pinShortcut(ctx: Context, name: String, emoji: String, bg: Int): Boolean {
    val sm = ctx.getSystemService(ShortcutManager::class.java)
    if (!sm.isRequestPinShortcutSupported) return false
    val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = bg
    c.drawRoundRect(0f, 0f, 192f, 192f, 48f, 48f, p)
    p.color = 0xFFFFFFFF.toInt()
    p.textSize = 104f
    p.textAlign = Paint.Align.CENTER
    val y = 96f - (p.descent() + p.ascent()) / 2
    c.drawText(emoji.ifBlank { "📶" }, 96f, y, p)
    val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
    val info = ShortcutInfo.Builder(ctx, "c${System.currentTimeMillis()}")
        .setShortLabel(name.ifBlank { tl("通信量", "Data usage") })
        .setIcon(Icon.createWithBitmap(bmp))
        .setIntent(intent)
        .build()
    return sm.requestPinShortcut(info, null)
}
// END