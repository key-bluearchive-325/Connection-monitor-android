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
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

const val NS = "com.example.datausage"
val PRESET_LABELS = listOf("📶 データ通信量(標準)", "⇅ 通信モニター", "◯ Data Ring", "⚡ Bolt")
val ACCENTS = listOf(0xFF5B6CFFL, 0xFF9B5CFFL, 0xFF0EA5A4L, 0xFFE5484DL, 0xFFF59E0BL, 0xFF30A46CL)
val WifiColor = Color(0xFF4FC3F7)
val MobileColor = Color(0xFFFFB74D)

object Ui {
    var accent by mutableIntStateOf(0)
    var mode by mutableIntStateOf(0) // 0=自動 1=ライト 2=ダーク 3=AMOLED
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("s", 0)
        Ui.accent = p.getInt("accent", 0).coerceIn(0, ACCENTS.size - 1)
        Ui.mode = p.getInt("mode", 0)
        applyRefreshRate(this, p.getBoolean("hz", true))
        setContent { AppTheme { Root() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val amoled = Ui.mode == 3
    val dark = amoled || when (Ui.mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ac = Color(ACCENTS[Ui.accent])
    val cs = if (dark) darkColorScheme(
        primary = ac, onPrimary = Color.White,
        background = if (amoled) Color.Black else Color(0xFF0B0D14),
        surface = if (amoled) Color.Black else Color(0xFF171B24),
        onBackground = Color(0xFFF1F3F9), onSurface = Color(0xFFF1F3F9),
        onSurfaceVariant = Color(0xFFB4BBCC), surfaceVariant = Color(0x1FFFFFFF)
    ) else lightColorScheme(
        primary = ac, onPrimary = Color.White,
        background = Color(0xFFEEF1FA), surface = Color.White,
        onBackground = Color(0xFF14171F), onSurface = Color(0xFF14171F),
        onSurfaceVariant = Color(0xFF5E667A), surfaceVariant = Color(0x14000000)
    )
    val n = ACCENTS.size
    val blobs = listOf(ac, Color(ACCENTS[(Ui.accent + 2) % n]), Color(ACCENTS[(Ui.accent + 4) % n]))
    MaterialTheme(colorScheme = cs) {
        GlassBackground(dark, amoled, blobs) {
            Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = cs.onSurface, content = content)
        }
    }
}

@Composable
fun GlassBackground(dark: Boolean, amoled: Boolean, blobs: List<Color>, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(if (amoled) Color.Black else if (dark) Color(0xFF0B0D14) else Color(0xFFEEF1FA))
            .drawBehind {
                if (amoled) return@drawBehind
                val a = if (dark) 0.50f else 0.42f
                val w = size.width
                val h = size.height
                fun blob(c: Color, x: Float, y: Float, r: Float) {
                    val o = Offset(x, y)
                    drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), o, r), r, o)
                }
                blob(blobs[0], w * 0.1f, h * 0.08f, w * 0.95f)
                blob(blobs[1], w * 1.0f, h * 0.42f, w * 0.85f)
                blob(blobs[2], w * 0.1f, h * 0.9f, w * 0.95f)
            }
    ) { content() }
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
    val ac = Color(ACCENTS[Ui.accent])
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .background(if (selected) ac.copy(alpha = 0.9f) else Color.White.copy(alpha = if (dark) 0.10f else 0.5f))
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.25f else 0.8f), shape)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
        )
    }
}

data class AppUsage(
    val label: String, val pkg: String,
    val wifi: Long, val mobile: Long, val fgMs: Long,
    val icon: ImageBitmap?
) {
    val total get() = wifi + mobile
}

data class DayTotal(val label: String, val wifi: Long, val mobile: Long)
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
    val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
    return Batt(
        if (lv < 0) 0 else lv * 100 / sc,
        st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL,
        t / 10f
    )
}

fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    return ops.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
    ) == AppOpsManager.MODE_ALLOWED
}

/** 小数点2桁（例: 4.54MB） */
fun fmt(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2fGB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.US, "%.2fMB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format(Locale.US, "%.2fKB", b / 1024.0)
    else -> "${b}B"
}

fun fmtTime(ms: Long): String {
    val m = ms / 60000
    return when {
        m >= 60 -> "${m / 60}時間${m % 60}分"
        m >= 1 -> "${m}分"
        ms > 0 -> "1分未満"
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
    PRESET_LABELS.indices.filter { it != sel }.forEach {
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
        .setShortLabel(name.ifBlank { "通信量" })
        .setIcon(Icon.createWithBitmap(bmp))
        .setIntent(intent)
        .build()
    return sm.requestPinShortcut(info, null)
}
// END