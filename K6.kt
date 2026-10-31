package com.example.datausage

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** 画面全体の大きさと、背景が流れる時間 */
object Scene {
    var size by mutableStateOf(Size.Zero)
    var t by mutableFloatStateOf(0f)
}

class RippleItem(val x: Float, val y: Float, val t0: Long)

object Ripples {
    var list by mutableStateOf(listOf<RippleItem>())
    var now by mutableLongStateOf(0L)
}

private const val RIPPLE_SEC = 1.6f
private const val RIPPLE_SPEED = 430f // dp / 秒

/** 水面の波紋で画面を揺らす（Android 13以降） */
private const val AGSL = """
uniform shader content;
uniform float4 rp[4];
uniform float dens;
half4 main(float2 p) {
    float2 off = float2(0.0, 0.0);
    for (int i = 0; i < 4; i++) {
        float amp = rp[i].w;
        if (amp > 0.0) {
            float2 d = p - rp[i].xy;
            float dist = length(d) + 0.001;
            float w = dist - rp[i].z;
            float sd = (w < 0.0 ? 75.0 : 22.0) * dens;
            float env = exp(-(w * w) / (sd * sd));
            float wave = sin(w * 6.2832 / (20.0 * dens));
            float fall = 1.0 / (1.0 + dist / (320.0 * dens));
            off += (d / dist) * wave * env * fall * amp;
        }
    }
    return content.eval(p + off);
}
"""

@RequiresApi(33)
object RippleFx {
    private val shader by lazy { RuntimeShader(AGSL) }

    fun effect(list: List<RippleItem>, now: Long, dens: Float): androidx.compose.ui.graphics.RenderEffect? {
        val arr = FloatArray(16)
        var any = false
        for (i in 0 until minOf(4, list.size)) {
            val r = list[list.size - 1 - i]
            val age = (now - r.t0) / 1000f
            if (age < 0f || age > RIPPLE_SEC) continue
            val fall = 1f - age / RIPPLE_SEC
            arr[i * 4] = r.x
            arr[i * 4 + 1] = r.y
            arr[i * 4 + 2] = age * RIPPLE_SPEED * dens
            arr[i * 4 + 3] = 11f * dens * fall * fall
            any = true
        }
        if (!any) return null
        shader.setFloatUniform("rp", arr)
        shader.setFloatUniform("dens", dens)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/** 背景：やわらかい光と、くっきりした光の玉（カードの裏でぼけて見える） */
fun DrawScope.drawScene(w: Float, h: Float, t: Float, cols: List<Color>, dark: Boolean) {
    val a = if (dark) 0.50f else 0.42f
    val s = t * 0.30f
    fun blob(c: Color, x: Float, y: Float, r: Float) {
        val o = Offset(x, y)
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a * c.alpha), Color.Transparent), o, r), r, o)
    }
    blob(cols[0], w * (0.12f + 0.10f * sin(s)), h * (0.10f + 0.05f * cos(s * 0.8f)), w * 0.95f)
    blob(cols[1], w * (0.98f + 0.06f * cos(s * 0.7f)), h * (0.42f + 0.06f * sin(s * 0.9f)), w * 0.85f)
    blob(cols[2], w * (0.10f + 0.08f * sin(s * 0.6f + 1f)), h * (0.90f + 0.04f * cos(s)), w * 0.95f)
    for (i in 0 until 7) {
        val f = i * 1.7f
        val cx = w * (0.5f + 0.42f * sin(s * (0.5f + 0.13f * i) + f))
        val cy = h * (0.5f + 0.45f * cos(s * (0.4f + 0.11f * i) + f * 1.3f))
        val r = w * (0.07f + 0.035f * (i % 3))
        val c = cols[i % 3]
        val o = Offset(cx, cy)
        drawCircle(
            Brush.linearGradient(
                listOf(c.copy(alpha = if (dark) 0.75f else 0.55f), c.copy(alpha = 0.18f)),
                Offset(cx - r, cy - r), Offset(cx + r, cy + r)
            ),
            r, o
        )
        drawCircle(Color.White.copy(alpha = 0.28f), r, o, style = Stroke(width = 2f))
    }
}

@Composable
fun GlassBackground(dark: Boolean, amoled: Boolean, base: Color, cols: List<Color>, content: @Composable () -> Unit) {
    val fx = Ui.fx
    val dens = LocalDensity.current.density
    val active by remember { derivedStateOf { Ripples.list.isNotEmpty() } }

    // 背景をゆっくり流す（約30fps）
    LaunchedEffect(fx) {
        if (fx == 0) {
            Scene.t = 0f
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            val n = withFrameNanos { it }
            if (n - last > 33_000_000L) {
                Scene.t = ((n / 1_000_000L) % 3_600_000L) / 1000f
                last = n
            }
        }
    }
    // 波紋の時間を進める
    LaunchedEffect(active) {
        while (Ripples.list.isNotEmpty()) {
            withFrameNanos { }
            val now = System.nanoTime() / 1_000_000L
            Ripples.now = now
            val keep = Ripples.list.filter { now - it.t0 < 1700L }
            if (keep.size != Ripples.list.size) Ripples.list = keep
        }
    }

    val distort = Build.VERSION.SDK_INT >= 33 && Ui.ripple

    Box(
        Modifier.fillMaxSize()
            .background(base)
            .onSizeChanged { Scene.size = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (!Ui.ripple) continue
                        for (c in ev.changes) {
                            if (c.pressed && !c.previousPressed) {
                                val now = System.nanoTime() / 1_000_000L
                                Ripples.now = now
                                Ripples.list = (Ripples.list + RippleItem(c.position.x, c.position.y, now)).takeLast(4)
                            }
                        }
                    }
                }
            }
    ) {
        // 背景と画面全体（ここが水面のように揺れる）
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                if (distort) {
                    val l = Ripples.list
                    val n = Ripples.now
                    renderEffect = if (l.isNotEmpty() && Build.VERSION.SDK_INT >= 33) RippleFx.effect(l, n, dens) else null
                }
            }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (!amoled) drawScene(size.width, size.height, Scene.t, cols, dark)
            }
            content()
        }
        // 触れた場所から広がる光の輪
        Canvas(Modifier.fillMaxSize()) {
            val list = Ripples.list
            val n = Ripples.now
            for (r in list) {
                val age = (n - r.t0) / 1000f
                if (age < 0f || age > RIPPLE_SEC) continue
                val p = age / RIPPLE_SEC
                val rad = age * RIPPLE_SPEED * density
                val fade = (1f - p) * (1f - p)
                val c = Offset(r.x, r.y)
                for (k in 0 until 3) {
                    val rr = rad - k * 20f * density
                    if (rr > 0f) {
                        drawCircle(
                            Color.White.copy(alpha = 0.55f * fade * (1f - k * 0.28f)),
                            rr, c, style = Stroke((3.2f - k) * density)
                        )
                    }
                }
                val gr = 90f * density * (0.5f + p)
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.30f * fade), Color.Transparent), c, gr),
                    gr, c
                )
            }
        }
    }
}

/** ガラスの表面：色味・光の反射・上端のハイライト・下端の影・縁の光 */
private fun DrawScope.drawGlassSurface(radius: Float, dark: Boolean, amoled: Boolean, blurOn: Boolean, tint: List<Color>?) {
    val w = size.width
    val h = size.height
    if (tint != null) {
        drawRect(Brush.linearGradient(tint, Offset.Zero, Offset(w, h)))
    } else {
        val a = if (amoled) 0.06f
        else if (dark) (if (blurOn) 0.05f else 0.08f)
        else (if (blurOn) 0.26f else 0.42f)
        drawRect(Color.White.copy(alpha = a))
    }
    drawRect(
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = if (dark) 0.18f else 0.55f),
                Color.Transparent,
                Color.White.copy(alpha = if (dark) 0.06f else 0.20f)
            ),
            Offset.Zero, Offset(w, h)
        )
    )
    drawRect(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = if (amoled) 0.08f else if (dark) 0.16f else 0.50f), Color.Transparent),
            0f, h * 0.35f
        )
    )
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.18f else 0.06f)),
            h * 0.65f, h
        )
    )
    val sw = 1.5f * density
    drawRoundRect(
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = if (dark) 0.55f else 1f),
                Color.White.copy(alpha = 0.05f),
                Color.White.copy(alpha = if (dark) 0.25f else 0.7f)
            ),
            Offset.Zero, Offset(w, h)
        ),
        topLeft = Offset(sw / 2f, sw / 2f),
        size = Size(w - sw, h - sw),
        cornerRadius = CornerRadius(radius - sw / 2f),
        style = Stroke(sw)
    )
}

/**
 * 透明なガラスのカード。
 * 背面の光の玉をぼかして映し（すりガラス）、少し拡大して屈折したように見せ、
 * 反射・縁の光・影で奥行きを出します。
 */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    big: Boolean = true,
    tint: List<Color>? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val pal = LocalPal.current
    val amoled = Ui.mode == 3
    val dark = cs.onSurface.luminance() > 0.5f
    val fx = Ui.fx
    val blurOn = !amoled && (fx == 2 || (fx == 1 && big))
    val shape = RoundedCornerShape(radius)
    val base = cs.background
    var pos by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier
            .onGloballyPositioned { pos = it.positionInRoot() }
            .then(
                if (fx > 0 && !amoled) Modifier.drawBehind {
                    // カードの外側にだけ、やわらかい影
                    val r = radius.toPx()
                    val hole = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, r, r)) }
                    clipPath(hole, clipOp = ClipOp.Difference) {
                        val off = 6.dp.toPx()
                        for (i in 1..6) {
                            val g = i * 2.dp.toPx()
                            drawRoundRect(
                                Color(0xFF101830).copy(alpha = if (dark) 0.05f else 0.035f),
                                topLeft = Offset(-g, off - g * 0.5f),
                                size = Size(size.width + 2 * g, size.height + 2 * g),
                                cornerRadius = CornerRadius(r + g)
                            )
                        }
                    }
                } else Modifier
            )
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        if (blurOn) {
            Canvas(Modifier.matchParentSize().blur(18.dp)) {
                val sz = Scene.size
                if (sz.width > 0f) {
                    drawRect(base)
                    withTransform({
                        scale(1.07f, 1.07f, Offset(size.width / 2f, size.height / 2f))
                        translate(-pos.x, -pos.y)
                    }) {
                        drawScene(sz.width, sz.height, Scene.t, pal.blobs, dark)
                    }
                }
            }
        }
        Box(
            Modifier.matchParentSize().drawBehind {
                drawGlassSurface(radius.toPx(), dark, amoled, blurOn, tint)
            }
        )
        content()
    }
}
// END