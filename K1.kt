package com.example.datausage

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val NS = "com.example.datausage"
val PRESET_LABELS = listOf("📶 データ通信量(標準)", "⇅ 通信モニター", "◯ Data Ring", "⚡ Bolt")
val ACCENTS = listOf(0xFF5B6CFFL, 0xFF9B5CFFL, 0xFF0EA5A4L, 0xFFE5484DL, 0xFFF59E0BL, 0xFF30A46CL)
val WifiColor = Color(0xFF4FC3F7)
val MobileColor = Color(0xFFFFB74D)

object Ui {
    var accent by mutableIntStateOf(0)
    var mode by mutableIntStateOf(0) // 0=自動 1=ライト 2=ダーク
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
    val dark = when (Ui.mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ac = Color(ACCENTS[Ui.accent])
    val cs = if (dark) darkColorScheme(
        primary = ac, onPrimary = Color.White,
        background = Color(0xFF0B0D14), surface = Color(0xFF171B24),
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
        GlassBackground(dark, blobs) {
            Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = cs.onSurface, content = content)
        }
    }
}

@Composable
fun GlassBackground(dark: Boolean, blobs: List<Color>, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(if (dark) Color(0xFF0B0D14) else Color(0xFFEEF1FA))
            .drawBehind {
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
fun Glass(modifier: Modifier = Modifier, radius: Dp = 28.dp, content: @Composable () -> Unit) {
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
        listOf(Color.White.copy(alpha = if (dark) 0.14f else 0.45f), Color.Transparent)
    )
    Box(
        modifier.clip(shape)
            .background(Color.White.copy(alpha = if (dark) 0.07f else 0.40f))
            .background(sheen)
            .border(1.dp, rim, shape)
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

fun fmt(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
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

@Composable
fun GSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Glass(Modifier.fillMaxWidth(), radius = 24.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            content()
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val prefs = remember { ctx.getSharedPreferences("s", 0) }
    var hz by remember { mutableStateOf(prefs.getBoolean("hz", true)) }
    var preset by remember { mutableIntStateOf(prefs.getInt("preset", 0)) }
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("📶") }
    var scIdx by remember { mutableIntStateOf(0) }
    var histDays by remember { mutableIntStateOf(History.days(ctx)) }
    val scColors = ACCENTS + 0xFF111111L
    BackHandler { onBack() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("◀ 戻る") }
                Spacer(Modifier.width(8.dp))
                Text("設定", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            GSection("テーマ") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("自動", "ライト", "ダーク").forEachIndexed { i, s ->
                        Pill(s, Ui.mode == i) {
                            Ui.mode = i
                            prefs.edit().putInt("mode", i).apply()
                        }
                    }
                }
                Text("アクセントカラー", fontSize = 12.sp, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ACCENTS.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(Color(c))
                                .then(if (Ui.accent == i) Modifier.border(3.dp, cs.onSurface, CircleShape) else Modifier)
                                .clickable {
                                    Ui.accent = i
                                    prefs.edit().putInt("accent", i).apply()
                                }
                        )
                    }
                }
            }
        }
        item {
            GSection("表示") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("高リフレッシュレート（最大120Hz）")
                        Text(
                            "対応端末でスクロールが滑らかになります。バッテリー消費は少し増えます。",
                            fontSize = 12.sp, color = cs.onSurfaceVariant
                        )
                    }
                    Switch(checked = hz, onCheckedChange = { v ->
                        hz = v
                        prefs.edit().putBoolean("hz", v).apply()
                        (ctx as? Activity)?.let { a -> applyRefreshRate(a, v) }
                    })
                }
            }
        }
        item {
            GSection("履歴の保存") {
                Text(
                    "毎日の通信量をこの端末内に自動保存します（アプリを開いたときに前日までを追加）。保存済み: ${histDays}日分",
                    fontSize = 13.sp, color = cs.onSurfaceVariant
                )
                Button(onClick = {
                    History.clear(ctx)
                    histDays = 0
                    Toast.makeText(ctx, "消去しました。次回起動時にOSの履歴から再取得します", Toast.LENGTH_SHORT).show()
                }) { Text("保存データを消去") }
            }
        }
        item {
            GSection("ホーム画面のアイコンと名前（プリセット）") {
                PRESET_LABELS.forEachIndexed { i, label ->
                    val select = {
                        preset = i
                        prefs.edit().putInt("preset", i).apply()
                        setLauncherPreset(ctx, i)
                        Toast.makeText(ctx, "反映まで数秒〜数十秒かかります", Toast.LENGTH_SHORT).show()
                    }
                    Row(Modifier.fillMaxWidth().clickable { select() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = preset == i, onClick = { select() })
                        Text(label)
                    }
                }
            }
        }
        item {
            GSection("好きな名前・アイコンでホーム画面に追加") {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("アプリ名") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = emoji, onValueChange = { emoji = it.take(4) },
                    label = { Text("アイコンの絵文字") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    scColors.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                                .then(if (scIdx == i) Modifier.border(3.dp, cs.onSurface, CircleShape) else Modifier)
                                .clickable { scIdx = i }
                        )
                    }
                }
                Button(onClick = {
                    val ok = pinShortcut(ctx, name, emoji, Color(scColors[scIdx]).toArgb())
                    Toast.makeText(ctx, if (ok) "追加の確認画面で許可してください" else "このホーム画面は非対応です", Toast.LENGTH_SHORT).show()
                }) { Text("ホーム画面に追加") }
            }
        }
        item {
            GSection("パッケージ名") {
                Text(
                    "アプリ内からは変更できません。GitHub の Actions → Build APK → Run workflow で、アプリ名とパッケージ名を入力してビルドし直してください（別のアプリとしてインストールされ、保存した履歴も別になります）。",
                    fontSize = 13.sp, color = cs.onSurfaceVariant
                )
            }
        }
    }
}
// END
