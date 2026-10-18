package com.example.datausage

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

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
    var notif by remember { mutableStateOf(prefs.getBoolean("notif", false)) }
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
    val setPalette = { i: Int ->
        Ui.palette = i
        prefs.edit().putInt("palette", i).apply()
        if (i == 1 && Build.VERSION.SDK_INT < 31) {
            Toast.makeText(
                ctx,
                tl("Material YouはAndroid 12以降で使えます（標準色で表示します）", "Material You needs Android 12 or later (using default colours)."),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val setMult = { v: Float ->
        Ui.mult = v
        prefs.edit().putFloat("mult", v).apply()
    }
    val setLang = { i: Int ->
        Ui.lang = i
        prefs.edit().putInt("lang", i).apply()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        notif = ok
        if (ok) setSpeedNotif(ctx, true)
        else Toast.makeText(
            ctx,
            tl("通知の許可が必要です。端末の設定で許可してください", "Notification permission is required. Please allow it in system settings."),
            Toast.LENGTH_LONG
        ).show()
    }
    BackHandler { onBack() }

    LazyColumn(
        Modifier.fillMaxSize().swipeBack(onBack),
        contentPadding = pagePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(tl("◀ 戻る", "◀ Back")) }
                Spacer(Modifier.width(8.dp))
                Text(tl("設定", "Settings"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            GSection(tl("言語", "Language")) {
                PillRows(listOf("自動 / Auto", "日本語", "English (UK)"), Ui.lang, 2) { setLang(it) }
                Text(
                    tl(
                        "「自動」は端末の言語が日本語なら日本語、それ以外はイギリス英語で表示します。",
                        "Auto uses Japanese when your device language is Japanese, and British English otherwise."
                    ),
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
            }
        }
        item {
            GSection(tl("テーマ", "Theme")) {
                PillRows(
                    listOf(
                        tl("自動", "Auto"), tl("ライト", "Light"), tl("ダーク", "Dark"),
                        tl("AMOLED（真っ黒）", "AMOLED (pure black)")
                    ),
                    Ui.mode, 3
                ) { setMode(it) }
                Text(tl("配色", "Colour scheme"), fontSize = 12.sp, color = cs.onSurfaceVariant)
                PillRows(
                    listOf(tl("標準", "Default"), "Material You", tl("モノクロ", "Monochrome")),
                    Ui.palette, 2
                ) { setPalette(it) }
                Text(
                    tl(
                        "Material Youは壁紙から色を自動で作ります（Android 12以降）。",
                        "Material You builds its colours from your wallpaper (Android 12 or later)."
                    ),
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
                Text(
                    tl("アクセントカラー（「標準」の配色で使われます）", "Accent colour (used by the Default scheme)"),
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
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
            GSection(tl("リアルタイム速度の表示倍率（見かけだけ）", "Live speed display multiplier (cosmetic only)")) {
                Text(
                    tl(
                        "速度の表示だけを変えます。実際の通信量や合計には影響しません。通知の速度表示にも反映されます。",
                        "Only changes how the speed is displayed. Real usage and totals are not affected. It also applies to the speed in the notification."
                    ),
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
                val opts = listOf(0.1f, 0.5f, 1f, 2f, 10f, 100f, 1000f, 10000f, 114514f)
                PillRows(opts.map { "×${multLabel(it)}" }, opts.indexOf(Ui.mult), 3) { setMult(opts[it]) }
            }
        }
        item {
            GSection(tl("通知", "Notification")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tl("通知で速度と今日の通信量を表示", "Show speed and today's usage in a notification"))
                        Text(
                            tl(
                                "常駐通知で、速度は1秒ごと、今日の合計は約15秒ごとに更新します。画面オフ中は更新を止めて電池を節約します。",
                                "A persistent notification. Speed updates every second and today's total about every 15 seconds. Updates pause while the screen is off to save battery."
                            ),
                            fontSize = 12.sp, color = cs.onSurfaceVariant
                        )
                    }
                    Switch(checked = notif, onCheckedChange = { v ->
                        if (!v) {
                            notif = false
                            setSpeedNotif(ctx, false)
                        } else if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notif = true
                            setSpeedNotif(ctx, true)
                        }
                    })
                }
            }
        }
        item {
            GSection(tl("表示", "Display")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tl("高リフレッシュレート（最大120Hz）", "High refresh rate (up to 120 Hz)"))
                        Text(
                            tl(
                                "対応端末でスクロールが滑らかになります。バッテリー消費は少し増えます。",
                                "Smoother scrolling on supported devices. Uses slightly more battery."
                            ),
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
            GSection(tl("履歴の保存", "History")) {
                Text(
                    tl(
                        "毎日の通信量をこの端末内に自動保存します（アプリを開いたときに前日までを追加）。保存済み: ${histDays}日分",
                        "Daily usage is saved automatically on this device (previous days are added when you open the app). Saved: $histDays days."
                    ),
                    fontSize = 13.sp, color = cs.onSurfaceVariant
                )
                Button(onClick = {
                    History.clear(ctx)
                    histDays = 0
                    Toast.makeText(
                        ctx,
                        tl("消去しました。次回起動時にOSの履歴から再取得します", "Cleared. It will be re-imported from Android's history the next time you open the app."),
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text(tl("保存データを消去", "Clear saved data")) }
            }
        }
        item {
            GSection(tl("ホーム画面のアイコンと名前（プリセット）", "Home screen icon and name (presets)")) {
                presetLabels().forEachIndexed { i, label ->
                    val select = {
                        preset = i
                        prefs.edit().putInt("preset", i).apply()
                        setLauncherPreset(ctx, i)
                        Toast.makeText(
                            ctx,
                            tl("反映まで数秒〜数十秒かかります", "It may take a few seconds to update."),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    Row(Modifier.fillMaxWidth().clickable { select() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = preset == i, onClick = { select() })
                        Text(label)
                    }
                }
            }
        }
        item {
            GSection(tl("好きな名前・アイコンでホーム画面に追加", "Add to home screen with a custom name and icon")) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(tl("アプリ名", "App name")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = emoji, onValueChange = { emoji = it.take(4) },
                    label = { Text(tl("アイコンの絵文字", "Icon emoji")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
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
                    Toast.makeText(
                        ctx,
                        if (ok) tl("追加の確認画面で許可してください", "Confirm in the prompt to add it.")
                        else tl("このホーム画面は非対応です", "This launcher doesn't support it."),
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text(tl("ホーム画面に追加", "Add to home screen")) }
            }
        }
        item {
            GSection(tl("パッケージ名", "Package name")) {
                Text(
                    tl(
                        "アプリ内からは変更できません。GitHub の Actions → Build APK → Run workflow で、アプリ名とパッケージ名を入力してビルドし直してください（別のアプリとしてインストールされ、保存した履歴も別になります）。",
                        "This can't be changed inside the app. In GitHub, go to Actions → Build APK → Run workflow, enter the app name and package name, and build again (it installs as a separate app with its own history)."
                    ),
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
    val pal = LocalPal.current
    val pm = ctx.packageManager
    var n by remember { mutableIntStateOf(7) }
    var d by remember { mutableStateOf<Detail?>(null) }
    val info = remember(app.pkg) { runCatching { pm.getPackageInfo(app.pkg, 0) }.getOrNull() }
    val loc = appLocale()
    val df = remember(loc) { SimpleDateFormat(if (isJa()) "yyyy/M/d HH:mm" else "d MMM yyyy, HH:mm", loc) }
    val rf = remember(loc) { SimpleDateFormat(if (isJa()) "M/d(E)" else "EEE d MMM", loc) }
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
    val chart = rows.map { r -> DayTotal(r.ms, r.wifi, r.mobile) }
    val ai = info?.applicationInfo
    val sw = d?.savedWifi ?: 0L
    val sm = d?.savedMobile ?: 0L

    LazyColumn(
        Modifier.fillMaxSize().swipeBack(onBack),
        contentPadding = pagePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(tl("◀ 戻る", "◀ Back")) }
                Spacer(Modifier.width(8.dp))
                Text(tl("アプリの詳細", "App details"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                        Text(app.shownLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(app.pkg, fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            GSection(tl("アプリ情報", "App info")) {
                if (info != null) {
                    InfoRow(tl("バージョン", "Version"), "${info.versionName ?: "-"} (${info.longVersionCode})")
                    InfoRow(tl("インストール日", "Installed"), df.format(Date(info.firstInstallTime)))
                    InfoRow(tl("最終更新", "Last updated"), df.format(Date(info.lastUpdateTime)))
                    if (ai != null) {
                        InfoRow("UID", ai.uid.toString())
                        InfoRow(tl("対象SDK", "Target SDK"), ai.targetSdkVersion.toString())
                        InfoRow(
                            tl("種類", "Type"),
                            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) tl("システムアプリ", "System app")
                            else tl("ユーザーアプリ", "User app")
                        )
                    }
                } else {
                    Text(
                        tl(
                            "このアプリの情報を取得できません（削除済み、またはシステム/共有UIDの可能性があります）。",
                            "Can't read this app's info (it may be uninstalled, or a system / shared UID)."
                        ),
                        fontSize = 13.sp, color = cs.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30, 90).forEach { v ->
                    Pill(tl("${v}日", "$v days"), n == v) { n = v }
                }
            }
        }
        item {
            GSection(tl("直近${n}日の合計", "Last $n days")) {
                Text(fmt(tw + tm), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = pal.wifi)) { append("● ") }
                        append("Wi-Fi ${fmt(tw)}   ")
                        withStyle(SpanStyle(color = pal.mobile)) { append("● ") }
                        append("${tl("モバイル", "Mobile")} ${fmt(tm)}")
                    },
                    fontSize = 13.sp, color = cs.onSurfaceVariant
                )
                InfoRow(tl("1日あたり平均", "Daily average"), fmt((tw + tm) / maxOf(1, rows.size)))
                InfoRow(
                    tl("最も多かった日", "Busiest day"),
                    if (maxDay != null && maxDay.wifi + maxDay.mobile > 0)
                        "${rf.format(Date(maxDay.ms))}  ${fmt(maxDay.wifi + maxDay.mobile)}"
                    else "-"
                )
                InfoRow(tl("画面使用時間", "Screen time"), if (tf > 0) fmtTime(tf) else "-")
                Text(
                    tl(
                        "※ 画面使用時間はOSの仕様上、直近約7日分のみ集計されます。",
                        "Note: Android only keeps screen time for about the last 7 days."
                    ),
                    fontSize = 11.sp, color = cs.onSurfaceVariant
                )
            }
        }
        if (chart.isNotEmpty()) item { DayChart(chart, if (n <= 7) 1 else 2) }
        item {
            GSection(tl("保存済み期間の累計", "Saved total")) {
                Text(fmt(sw + sm), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    tl(
                        "Wi-Fi ${fmt(sw)} / モバイル ${fmt(sm)}（前日までの保存データ分）",
                        "Wi-Fi ${fmt(sw)} / Mobile ${fmt(sm)} (saved data up to yesterday)"
                    ),
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
                }) { Text(tl("アプリ情報を開く", "Open app info")) }
                Button(onClick = {
                    val li = pm.getLaunchIntentForPackage(app.pkg)
                    if (li != null) ctx.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else Toast.makeText(
                        ctx, tl("このアプリは起動できません", "This app can't be launched."), Toast.LENGTH_SHORT
                    ).show()
                }) { Text(tl("起動", "Launch")) }
            }
        }
        item { Text(tl("日別の内訳", "Daily breakdown"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        items(rows.filter { it.wifi + it.mobile > 0 || it.fgMs > 0 }.reversed(), key = { it.ms }) { r ->
            Glass(Modifier.fillMaxWidth(), radius = 16.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(rf.format(Date(r.ms)), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = pal.wifi)) { append("● ") }
                                append("${fmt(r.wifi)}  ")
                                withStyle(SpanStyle(color = pal.mobile)) { append("● ") }
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