package com.example.datausage

import android.content.Intent
import android.net.TrafficStats
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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

@Composable
fun Root() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasUsageAccess(ctx)) }
    LaunchedEffect(granted) {
        while (!granted) { delay(1000); granted = hasUsageAccess(ctx) }
    }
    if (granted) UsageScreen() else {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("📶", fontSize = 56.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                tl("使用状況へのアクセスを許可してください", "Please allow usage access"),
                fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                tl(
                    "通信量と画面使用時間の取得に必要です。設定でこのアプリをオンにしてください。",
                    "This is needed to read data usage and screen time. Turn this app on in Settings."
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text(tl("設定を開く", "Open settings")) }
        }
    }
}

@Composable
fun UsageScreen() {
    val ctx = LocalContext.current
    var mode by remember { mutableIntStateOf(0) }
    var offset by remember { mutableIntStateOf(0) }
    var sortBy by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var detailOpen by remember { mutableStateOf(false) }
    var detailApp by remember { mutableStateOf<AppUsage?>(null) }
    var data by remember { mutableStateOf(Loaded()) }
    var speed by remember { mutableStateOf(0L to 0L) }
    var batt by remember { mutableStateOf(readBattery(ctx)) }
    var tick by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    BackHandler(enabled = showSettings || detailOpen) {
        if (detailOpen) detailOpen = false else showSettings = false
    }

    // 履歴の保存（前日までを追記）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { History.sync(ctx) } }
        tick++
    }
    LaunchedEffect(mode, offset, tick) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { loadAll(ctx, mode, offset) }.getOrNull() }
                ?.let { data = it }
            batt = readBattery(ctx)
            if (offset < 0) break
            delay(3000)
        }
    }
    LaunchedEffect(Unit) {
        var rx = TrafficStats.getTotalRxBytes()
        var tx = TrafficStats.getTotalTxBytes()
        while (true) {
            delay(1000)
            val nr = TrafficStats.getTotalRxBytes()
            val nt = TrafficStats.getTotalTxBytes()
            speed = (nr - rx).coerceAtLeast(0L) to (nt - tx).coerceAtLeast(0L)
            rx = nr; tx = nt
        }
    }

    val page = if (showSettings) 1 else if (detailOpen) 2 else 0
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState > initialState) {
                val inn = slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(320))
                val out = slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(200))
                inn togetherWith out
            } else {
                val inn = slideInHorizontally(tween(320)) { -it / 4 } + fadeIn(tween(320))
                val out = slideOutHorizontally(tween(320)) { it / 3 } + fadeOut(tween(200))
                inn togetherWith out
            }
        },
        label = "page"
    ) { p ->
        when (p) {
            1 -> SettingsScreen { showSettings = false }
            2 -> detailApp?.let { AppDetailScreen(it) { detailOpen = false } }
            else -> MainList(
                state = listState, data = data, speed = speed, batt = batt,
                mode = mode, offset = offset, sortBy = sortBy,
                onMode = { m -> mode = m; offset = 0 },
                onOffset = { o -> offset = o },
                onSort = { sortBy = 1 - sortBy },
                onSettings = { showSettings = true },
                onApp = { a -> detailApp = a; detailOpen = true }
            )
        }
    }
}

@Composable
fun MainList(
    state: LazyListState, data: Loaded, speed: Pair<Long, Long>, batt: Batt,
    mode: Int, offset: Int, sortBy: Int,
    onMode: (Int) -> Unit, onOffset: (Int) -> Unit, onSort: () -> Unit,
    onSettings: () -> Unit, onApp: (AppUsage) -> Unit
) {
    val sorted = if (sortBy == 0) data.apps.sortedByDescending { it.total }
    else data.apps.sortedByDescending { it.fgMs }
    val max = (data.apps.maxOfOrNull { it.total } ?: 1L).coerceAtLeast(1L).toFloat()

    LazyColumn(
        Modifier.fillMaxSize().pointerInput(offset) {
            // 左右スワイプで期間を移動
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    if (total > 160f) onOffset(offset - 1)
                    else if (total < -160f && offset < 0) onOffset(offset + 1)
                },
                onDragCancel = { total = 0f }
            ) { _, d -> total += d }
        },
        state = state,
        contentPadding = pagePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Header(speed, data.apps, batt, onSettings) }
        item { SummaryCard(data.roll) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(tl("日", "Day"), tl("週", "Week"), tl("月", "Month")).forEachIndexed { i, s ->
                    Pill(s, mode == i) { onMode(i) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onOffset(offset - 1) }) { Text("◀") }
                AnimatedContent(
                    targetState = mode to offset,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        if (targetState.first != initialState.first) {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                        } else {
                            val dir = if (targetState.second < initialState.second) -1 else 1
                            val inn = slideInHorizontally(tween(260)) { dir * it / 2 } + fadeIn(tween(260))
                            val out = slideOutHorizontally(tween(260)) { -dir * it / 2 } + fadeOut(tween(160))
                            inn togetherWith out
                        }
                    },
                    label = "period"
                ) { (m, o) ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(periodLabel(m, o), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                }
                TextButton(onClick = { onOffset(offset + 1) }, enabled = offset < 0) { Text("▶") }
            }
        }
        if (mode > 0 && data.days.isNotEmpty()) item { DayChart(data.days, mode) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tl("アプリ別（タップで詳細）", "By app (tap for details)"),
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                )
                Pill(
                    if (sortBy == 0) tl("通信量順", "By data") else tl("使用時間順", "By screen time"),
                    true
                ) { onSort() }
            }
        }
        if (sorted.isEmpty()) item {
            Text(
                tl(
                    "この期間のデータがありません（OSが保持している期間より前の可能性があります）",
                    "No data for this period (it may be older than what Android keeps)."
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
            )
        }
        items(sorted, key = { it.pkg }) { a ->
            AppRow(a, max, Modifier.animateItem()) { onApp(a) }
        }
    }
}

@Composable
fun Stat(label: String, value: String, fg: Color) {
    Column {
        Text(label, color = fg.copy(alpha = 0.75f), fontSize = 11.sp)
        Text(value, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Header(speed: Pair<Long, Long>, list: List<AppUsage>, batt: Batt, onSettings: () -> Unit) {
    val pal = LocalPal.current
    val fg = pal.onHdr
    val shape = RoundedCornerShape(32.dp)
    val m = Ui.mult.toDouble()
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(pal.hdr1.copy(alpha = 0.92f), pal.hdr2.copy(alpha = 0.88f))))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)))
            .border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.05f))),
                shape
            )
            .padding(20.dp)
    ) {
        Text(
            "⚙", fontSize = 22.sp, color = fg,
            modifier = Modifier.align(Alignment.TopEnd).clickable { onSettings() }.padding(4.dp)
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tl("リアルタイム速度", "Live speed"), color = fg.copy(alpha = 0.8f), fontSize = 12.sp)
                if (Ui.mult != 1f) {
                    Text(
                        tl("  見かけ ×${multLabel(Ui.mult)}", "  Display ×${multLabel(Ui.mult)}"),
                        color = fg.copy(alpha = 0.8f), fontSize = 11.sp
                    )
                }
            }
            Text("↓ ${fmtD(speed.first * m)}/s", color = fg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("↑ ${fmtD(speed.second * m)}/s", color = fg.copy(alpha = 0.9f), fontSize = 16.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(tl("この期間の合計", "Total this period"), fmt(list.sumOf { it.total }), fg)
                Stat("Wi-Fi", fmt(list.sumOf { it.wifi }), fg)
                Stat(tl("モバイル", "Mobile"), fmt(list.sumOf { it.mobile }), fg)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "🔋 ${batt.pct}%" +
                    (if (batt.charging) tl(" ⚡充電中", " ⚡ Charging") else "") +
                    tl("  ・  ", "  ·  ") + "🌡 ${"%.1f".format(batt.tempC)}°C",
                color = fg.copy(alpha = 0.9f), fontSize = 13.sp
            )
        }
    }
}

@Composable
fun SumCol(label: String, w: Long, m: Long, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val pal = LocalPal.current
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = cs.onSurfaceVariant)
        Text(fmt(w + m), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = pal.wifi)) { append("● ") }
                append("${fmt(w)}  ")
                withStyle(SpanStyle(color = pal.mobile)) { append("● ") }
                append(fmt(m))
            },
            fontSize = 11.sp, color = cs.onSurfaceVariant
        )
    }
}

@Composable
fun SummaryCard(r: Roll) {
    val cs = MaterialTheme.colorScheme
    Glass(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SumCol(tl("直近7日の合計", "Last 7 days"), r.w7, r.m7, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(56.dp).background(cs.onSurface.copy(alpha = 0.15f)))
            Spacer(Modifier.width(16.dp))
            SumCol(tl("直近30日の合計", "Last 30 days"), r.w30, r.m30, Modifier.weight(1f))
        }
    }
}

@Composable
fun DayChart(days: List<DayTotal>, mode: Int) {
    val cs = MaterialTheme.colorScheme
    val pal = LocalPal.current
    val loc = appLocale()
    val wf = remember(loc) { SimpleDateFormat("E", loc) }
    val dd = remember(loc) { SimpleDateFormat("d", loc) }
    val max = (days.maxOfOrNull { it.wifi + it.mobile } ?: 1L).coerceAtLeast(1L).toFloat()
    Glass(Modifier.fillMaxWidth(), radius = 24.dp) {
        Column(Modifier.padding(14.dp)) {
            Text(
                tl("日ごとの通信量（最大 ${fmt(max.toLong())}）", "Daily data (max ${fmt(max.toLong())})"),
                fontSize = 12.sp, color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().height(90.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                days.forEach { d ->
                    val tot = d.wifi + d.mobile
                    val hh by animateFloatAsState(maxOf(2f, 90f * tot / max), tween(500), label = "bar")
                    Box(Modifier.weight(1f).height(hh.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                        if (tot > 0) Column(Modifier.fillMaxSize()) {
                            if (d.mobile > 0) Box(Modifier.weight(d.mobile.toFloat()).fillMaxWidth().background(pal.mobile))
                            if (d.wifi > 0) Box(Modifier.weight(d.wifi.toFloat()).fillMaxWidth().background(pal.wifi))
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                days.forEachIndexed { i, d ->
                    val label = if (mode == 1) wf.format(Date(d.ms))
                    else if (i % 5 == 0) dd.format(Date(d.ms)) else ""
                    Text(
                        label, Modifier.weight(1f), textAlign = TextAlign.Center,
                        fontSize = 9.sp, color = cs.onSurfaceVariant, maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun AppRow(a: AppUsage, max: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pal = LocalPal.current
    val frac by animateFloatAsState((a.total / max).coerceIn(0f, 1f), tween(500), label = "frac")
    Glass(modifier.fillMaxWidth(), radius = 24.dp, onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (a.icon != null) {
                Image(a.icon, null, Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)))
            } else {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        a.shownLabel, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(fmt(a.total), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = cs.primary)
                }
                Text(a.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                    if (a.total > 0) {
                        Row(Modifier.fillMaxWidth(frac).fillMaxHeight()) {
                            if (a.wifi > 0) Box(Modifier.weight(a.wifi.toFloat()).fillMaxHeight().background(pal.wifi))
                            if (a.mobile > 0) Box(Modifier.weight(a.mobile.toFloat()).fillMaxHeight().background(pal.mobile))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = pal.wifi)) { append("● ") }
                            append("${fmt(a.wifi)}   ")
                            withStyle(SpanStyle(color = pal.mobile)) { append("● ") }
                            append(fmt(a.mobile))
                        },
                        fontSize = 11.sp, color = cs.onSurfaceVariant
                    )
                    Text("🕒 ${fmtTime(a.fgMs)}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}
// END