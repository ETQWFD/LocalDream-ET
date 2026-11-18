package io.github.xororz.localdream.service

import android.content.Context
import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * et.248：生成进度与剩余时间的真实化。
 *
 * 现场核验到的两个事实决定了这里的设计：
 *  1. 引擎只有在请求体里 `show_diffusion_process=true` 时才会按步上报
 *     `{step,total_steps}`，而这个开关默认是关的——所以进度条一直停在 0%，
 *     "预计剩余时间"因为 `progress > 1%` 的条件永远不成立，界面上根本不出现；
 *  2. MNN 的 SDXL / Anima / DiT 这些管线（低配机常走的路径）在 C++ 里
 *     一次进度回调都没有发（`PipelineSdxlMnn.hpp` / `PipelineAnima.hpp` 里
 *     没有任何 progress 调用），所以哪怕把开关打开，这些模型也拿不到步数。
 *
 * 因此这里按"有步数用步数、没步数用时间"双轨给出进度：
 *  - 按 (分辨率, 步数, 是否 ultrafix, CPU/GPU) 记录历史真实耗时（指数滑动平均），
 *    下次同档位直接用历史值估；
 *  - 没有历史时用保守的经验公式（按百万像素数 × 步数 × 后端系数）估一个上限；
 *  - 时间曲线渐近到 95%，绝不提前报 100%，真实完成时才到 100%。
 */
object EtaModel {

    private const val PREFS = "eta_profile"
    private const val ALPHA = 0.4f

    fun key(width: Int, height: Int, steps: Int, ultrafix: Boolean, backend: String): String =
        "${width}x${height}|$steps|${if (ultrafix) "uf" else "nf"}|$backend"

    /** 经验兜底：低端 CPU 出 1024x1024 / 20 步大约几分钟量级。 */
    private fun heuristic(width: Int, height: Int, steps: Int, backend: String): Long {
        val megaPx = max(0.09f, (width.toFloat() * height.toFloat()) / 1_048_576f)
        val factor = if (backend == "cpu") 150_000f else 55_000f
        val ms = factor * megaPx * (steps.coerceIn(1, 100) / 20f)
        return ms.toLong().coerceIn(15_000L, 45L * 60_000L)
    }

    /** 本次生成的预计耗时（毫秒）。优先用同档位历史值。 */
    fun estimate(ctx: Context, key: String, width: Int, height: Int, steps: Int, backend: String): Long {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hist = sp.getLong(key, 0L)
        return if (hist > 3_000L) hist else heuristic(width, height, steps, backend)
    }

    /** 生成成功后回写真实耗时（滑动平均，样本不会互相污染）。 */
    fun record(ctx: Context, key: String, elapsedMs: Long) {
        if (elapsedMs < 1_000L || elapsedMs > 6L * 60L * 60L * 1000L) return
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = sp.getLong(key, 0L)
        val v = if (old <= 0L) elapsedMs else (ALPHA * elapsedMs + (1f - ALPHA) * old).toLong()
        sp.edit().putLong(key, v).apply()
    }

    /**
     * 没有任何步数信息时的时间进度：p = 0.95 * (1 - e^(-1.6 t / est))。
     * 到预计耗时时约 86%，两倍预计耗时时约 94%，永不触顶 100%。
     */
    fun timeFraction(elapsedMs: Long, estimateMs: Long): Float {
        if (estimateMs <= 0L) return 0f
        val r = min(6f, elapsedMs.toFloat() / estimateMs.toFloat())
        return min(0.95f, 0.95f * (1f - exp(-1.6f * r)))
    }

    /** 已经过了预计时间还没完，就不再虚报进度上限，改为缓慢爬升。 */
    fun overdueFraction(elapsedMs: Long, estimateMs: Long): Float {
        if (elapsedMs <= estimateMs) return 0f
        val over = (elapsedMs - estimateMs).toFloat() / max(1f, estimateMs.toFloat())
        return (0.86f + 0.10f * (1f - exp(-1.5f * over))).coerceAtMost(0.97f)
    }
}

/** 一次生成内的进度聚合：步数进度与时间进度取较大值，且只允许单调不减。 */
class ProgressTracker(private val estimateMs: Long) {
    private val startMs = SystemClock.elapsedRealtime()
    private var bestStep = 0f
    private var emitted = 0f

    fun noteStep(fraction: Float) {
        if (fraction > bestStep && fraction <= 1f) bestStep = fraction
    }

    val elapsedMs: Long get() = SystemClock.elapsedRealtime() - startMs

    /** 当前应当展示的进度（0..0.97）。返回与上次相同表示不必再刷新界面。 */
    fun tick(): Float {
        val t = elapsedMs
        val timeP = if (t > estimateMs) {
            max(EtaModel.timeFraction(t, estimateMs), EtaModel.overdueFraction(t, estimateMs))
        } else {
            EtaModel.timeFraction(t, estimateMs)
        }
        val p = max(bestStep, timeP).coerceAtMost(0.97f)
        return if (p + 0.004f > emitted) {
            emitted = p
            p
        } else {
            -1f
        }
    }
}
