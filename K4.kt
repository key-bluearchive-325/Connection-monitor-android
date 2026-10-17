package com.example.datausage

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(v, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- 設定画面 ----------
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
    val setMode = { i: Int ->
        Ui.mode = i
        prefs.edit().putInt("mode", i).apply()
    }
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
                        Pill(s, Ui.mode == i) { setMode(i) }
                    }
                }
                Pill("AMOLED（真っ黒）", Ui.mode == 3) { setMode(3) }
                Text(
                    "AMOLEDは背景が完全な黒になり、有機ELディスプレイの省電力に有利です。",
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
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

// ---------- アプリ詳細画面 ----------
@Composable
fun AppDetailScreen(app: AppUsage, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val pm = ctx.packageManager
    var n by remember { mutableIntStateOf(7) }
    var d by remember { mutableStateOf<Detail?>(null) }
    val info = remember(app.pkg) { runCatching { pm.getPackageInfo(app.pkg, 0) }.getOrNull() }
    val df = remember { SimpleDateFormat("yyyy/M/d HH:mm", Locale.JAPAN) }
    val wf = remember { SimpleDateFormat("E", Locale.JAPAN) }
    val dd = remember { SimpleDateFormat("d", Locale.JAPAN) }
    BackHandler { onBack() }

    LaunchedEffect(app.pkg, n) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { loadDetail(ctx, app.pkg, n) }.getOrNull() }
                ?.let { d = it }
            delay(5000)
        }
    }

    val rows = d?.rows ?: emptyList()
    val tw = rows.sumOf { it.wifi }
    val tm = rows.sumOf { it.mobile }
    val tf = rows.sumOf { it.fgMs }
    val maxDay = rows.maxByOrNull { it.wifi + it.mobile }
    val chart = rows.map { r ->
        DayTotal(if (n <= 7) wf.format(Date(r.ms)) else dd.format(Date(r.ms)), r.wifi, r.mobile)
    }
    val ai = info?.applicationInfo

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("◀ 戻る") }
                Spacer(Modifier.width(8.dp))
                Text("アプリの詳細", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (app.icon != null) {
                        Image(app.icon, null, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
                    } else {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceVariant))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(app.label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(app.pkg, fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            GSection("アプリ情報") {
                if (info != null) {
                    InfoRow("バージョン", "${info.versionName ?: "-"} (${info.longVersionCode})")
                    InfoRow("インストール日", df.format(Date(info.firstInstallTime)))
                    InfoRow("最終更新", df.format(Date(info.lastUpdateTime)))
                    if (ai != null) {
                        InfoRow("UID", ai.uid.toString())
                        InfoRow("対象SDK", ai.targetSdkVersion.toString())
                        InfoRow("種類", if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) "システムアプリ" else "ユーザーアプリ")
                    }
                } else {
                    Text(
                        "このアプリの情報を取得できません（削除済み、またはシステム/共有UIDの可能性があります）。",
                        fontSize = 13.sp, color = cs.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30, 90).forEach { v -> Pill("${v}日", n == v) { n = v } }
            }
        }
        item {
            GSection("直近${n}日の合計") {
                Text(fmt(tw + tm), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = WifiColor)) { append("● ") }
                        append("Wi-Fi ${fmt(tw)}   ")
                        withStyle(SpanStyle(color = MobileColor)) { append("● ") }
                        append("モバイル ${fmt(tm)}")
                    },
                    fontSize = 13.sp, color = cs.onSurfaceVariant
                )
                InfoRow("1日あたり平均", fmt((tw + tm) / maxOf(1, rows.size)))
                InfoRow(
                    "最も多かった日",
                    if (maxDay != null && maxDay.wifi + maxDay.mobile > 0) "${maxDay.label}  ${fmt(maxDay.wifi + maxDay.mobile)}" else "-"
                )
                InfoRow("画面使用時間", if (tf > 0) fmtTime(tf) else "-")
                Text(
                    "※ 画面使用時間はOSの仕様上、直近約7日分のみ集計されます。",
                    fontSize = 11.sp, color = cs.onSurfaceVariant
                )
            }
        }
        if (chart.isNotEmpty()) item { DayChart(chart, if (n <= 7) 1 else 2) }
        item {
            val sw = d?.savedWifi ?: 0L
            val sm = d?.savedMobile ?: 0L
            GSection("保存済み期間の累計") {
                Text(fmt(sw + sm), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Wi-Fi ${fmt(sw)} / モバイル ${fmt(sm)}（前日までの保存データ分）",
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
            }
        }
        if (info != null) item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("アプリ情報を開く") }
                Button(onClick = {
                    val li = pm.getLaunchIntentForPackage(app.pkg)
                    if (li != null) ctx.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else Toast.makeText(ctx, "このアプリは起動できません", Toast.LENGTH_SHORT).show()
                }) { Text("起動") }
            }
        }
        item { Text("日別の内訳", fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        items(rows.filter { it.wifi + it.mobile > 0 || it.fgMs > 0 }.reversed(), key = { it.ms }) { r ->
            Glass(Modifier.fillMaxWidth(), radius = 16.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(r.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = WifiColor)) { append("● ") }
                                append("${fmt(r.wifi)}  ")
                                withStyle(SpanStyle(color = MobileColor)) { append("● ") }
                                append(fmt(r.mobile))
                            },
                            fontSize = 11.sp, color = cs.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmt(r.wifi + r.mobile), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = cs.primary)
                        Text("🕒 ${fmtTime(r.fgMs)}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
// END