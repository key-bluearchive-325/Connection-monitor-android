package com.example.datausage

import android.app.Activity
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

// ---------- 日付ヘルパー ----------
fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun addDays(ms: Long, n: Int): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    add(Calendar.DAY_OF_YEAR, n)
}.timeInMillis

fun dayKey(ms: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
}

/** mode: 0=日, 1=週(月曜始まり), 2=月 / offset: 0=今, -1=ひとつ前 */
fun rangeOf(mode: Int, offset: Int): Pair<Long, Long> {
    val c = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    when (mode) {
        0 -> c.add(Calendar.DAY_OF_YEAR, offset)
        1 -> { c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); c.add(Calendar.WEEK_OF_YEAR, offset) }
        else -> { c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, offset) }
    }
    val s = c.timeInMillis
    when (mode) {
        0 -> c.add(Calendar.DAY_OF_YEAR, 1)
        1 -> c.add(Calendar.WEEK_OF_YEAR, 1)
        else -> c.add(Calendar.MONTH, 1)
    }
    return s to minOf(c.timeInMillis, System.currentTimeMillis())
}

fun periodLabel(mode: Int, offset: Int): String {
    val c = Calendar.getInstance().apply { timeInMillis = rangeOf(mode, offset).first }
    return when (mode) {
        0 -> SimpleDateFormat("M月d日(E)", Locale.JAPAN).format(c.time)
        1 -> {
            val f = SimpleDateFormat("M/d", Locale.JAPAN)
            val st = f.format(c.time)
            c.add(Calendar.DAY_OF_YEAR, 6)
            "$st 〜 ${f.format(c.time)}"
        }
        else -> SimpleDateFormat("yyyy年M月", Locale.JAPAN).format(c.time)
    }
}

// ---------- OSからの取得 ----------
private fun sumByUid(nsm: NetworkStatsManager, type: Int, start: Long, end: Long): Map<Int, Long> {
    val map = HashMap<Int, Long>()
    val stats = nsm.querySummary(type, null, start, end)
    val bucket = NetworkStats.Bucket()
    while (stats.hasNextBucket()) {
        stats.getNextBucket(bucket)
        map[bucket.uid] = (map[bucket.uid] ?: 0L) + bucket.rxBytes + bucket.txBytes
    }
    stats.close()
    return map
}

private val iconCache = HashMap<String, ImageBitmap?>()

private fun iconOf(ctx: Context, pkg: String): ImageBitmap? = synchronized(iconCache) {
    iconCache.getOrPut(pkg) {
        runCatching {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
}

/** 指定期間のアプリ別データ: pkg -> [wifi, mobile, 画面使用時間ms] */
fun queryDay(ctx: Context, s: Long, e: Long, withFg: Boolean): HashMap<String, LongArray> {
    val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
    val pm = ctx.packageManager
    val cache = HashMap<Int, String>()
    fun keyOf(uid: Int) = cache.getOrPut(uid) { pm.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid" }
    val m = HashMap<String, LongArray>()
    runCatching { sumByUid(nsm, ConnectivityManager.TYPE_WIFI, s, e) }.getOrNull()
        ?.forEach { (u, v) -> m.getOrPut(keyOf(u)) { LongArray(3) }[0] += v }
    runCatching { sumByUid(nsm, ConnectivityManager.TYPE_MOBILE, s, e) }.getOrNull()
        ?.forEach { (u, v) -> m.getOrPut(keyOf(u)) { LongArray(3) }[1] += v }
    if (withFg) {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        runCatching { usm.queryAndAggregateUsageStats(s, e) }.getOrNull()?.forEach { (p, st) ->
            if (st.totalTimeInForeground > 0) m.getOrPut(p) { LongArray(3) }[2] = st.totalTimeInForeground
        }
    }
    m.values.removeAll { a -> a.all { it == 0L } }
    return m
}

// ---------- 履歴の保存 ----------
object History {
    @Volatile private var data: ConcurrentHashMap<Int, HashMap<String, LongArray>>? = null
    private val running = AtomicBoolean(false)

    private fun file(ctx: Context) = File(ctx.filesDir, "history.txt")

    fun all(ctx: Context): ConcurrentHashMap<Int, HashMap<String, LongArray>> {
        data?.let { return it }
        synchronized(this) {
            data?.let { return it }
            val h = ConcurrentHashMap<Int, HashMap<String, LongArray>>()
            runCatching {
                file(ctx).forEachLine { line ->
                    val p = line.split('|')
                    if (p.size == 5) {
                        h.getOrPut(p[0].toInt()) { HashMap() }[p[1]] =
                            longArrayOf(p[2].toLong(), p[3].toLong(), p[4].toLong())
                    }
                }
            }
            data = h
            return h
        }
    }

    private fun save(ctx: Context) {
        val h = data ?: return
        val sb = StringBuilder()
        for ((k, m) in h) for ((pkg, a) in m) {
            sb.append(k).append('|').append(pkg).append('|')
                .append(a[0]).append('|').append(a[1]).append('|').append(a[2]).append('\n')
        }
        file(ctx).writeText(sb.toString())
    }

    /** 前日までの日別データを保存（最大120日前まで遡って取得） */
    fun sync(ctx: Context) {
        if (!running.compareAndSet(false, true)) return
        try {
            val h = all(ctx)
            val prefs = ctx.getSharedPreferences("s", 0)
            val today = startOfDay(System.currentTimeMillis())
            var cur = maxOf(prefs.getLong("lastSync", 0L), addDays(today, -120))
            var changed = false
            while (cur < today) {
                val next = addDays(cur, 1)
                val key = dayKey(cur)
                if (!h.containsKey(key)) {
                    val m = queryDay(ctx, cur, next, cur >= addDays(today, -6))
                    if (m.isNotEmpty()) { h[key] = m; changed = true }
                }
                cur = next
            }
            prefs.edit().putLong("lastSync", today).apply()
            if (changed) save(ctx)
        } finally {
            running.set(false)
        }
    }

    fun days(ctx: Context): Int = all(ctx).size

    fun clear(ctx: Context) {
        all(ctx).clear()
        file(ctx).delete()
        ctx.getSharedPreferences("s", 0).edit().remove("lastSync").apply()
    }
}

/** 各日のデータ。保存済みを優先し、無ければOSから取得（fallback=true時） */
fun dayMaps(ctx: Context, start: Long, end: Long, fallback: Boolean): List<Pair<Long, Map<String, LongArray>>> {
    val out = ArrayList<Pair<Long, Map<String, LongArray>>>()
    val now = System.currentTimeMillis()
    val today = startOfDay(now)
    var cur = start
    while (cur < end) {
        val next = addDays(cur, 1)
        val m: Map<String, LongArray> =
            if (cur >= today) queryDay(ctx, cur, minOf(next, now), true)
            else History.all(ctx)[dayKey(cur)]
                ?: if (fallback) queryDay(ctx, cur, next, cur >= addDays(today, -6)) else emptyMap()
        out.add(cur to m)
        cur = next
    }
    return out
}

fun loadAll(ctx: Context, mode: Int, offset: Int): Loaded {
    val (start, end) = rangeOf(mode, offset)
    val pm = ctx.packageManager
    val byPkg = HashMap<String, LongArray>()
    val chart = ArrayList<DayTotal>()
    val wf = SimpleDateFormat("E", Locale.JAPAN)
    for ((ds, m) in dayMaps(ctx, start, end, true)) {
        var w = 0L
        var mo = 0L
        for ((pkg, a) in m) {
            val t = byPkg.getOrPut(pkg) { LongArray(3) }
            t[0] += a[0]; t[1] += a[1]; t[2] += a[2]
            w += a[0]; mo += a[1]
        }
        val label = if (mode == 1) wf.format(Date(ds))
        else Calendar.getInstance().apply { timeInMillis = ds }.get(Calendar.DAY_OF_MONTH).toString()
        chart.add(DayTotal(label, w, mo))
    }
    val apps = byPkg.mapNotNull { (pkg, a) ->
        if (a.all { it == 0L }) return@mapNotNull null
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull() ?: if (pkg.startsWith("uid:")) "システム/その他 ($pkg)" else pkg
        AppUsage(label, pkg, a[0], a[1], a[2], iconOf(ctx, pkg))
    }

    // 直近7日 / 30日の合計（今日を含む）
    val now = System.currentTimeMillis()
    val r = dayMaps(ctx, addDays(startOfDay(now), -29), now, false)
    var w7 = 0L; var m7 = 0L; var w30 = 0L; var m30 = 0L
    r.forEachIndexed { i, p ->
        var w = 0L
        var mo = 0L
        for (a in p.second.values) { w += a[0]; mo += a[1] }
        w30 += w; m30 += mo
        if (i >= r.size - 7) { w7 += w; m7 += mo }
    }
    return Loaded(apps, chart, Roll(w7, m7, w30, m30))
}
// END
