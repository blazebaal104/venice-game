// Day and night from the real clock: the sun's true position over Venice for today's date and time (NOAA solar
// position equations), the moon (approximate orbit and phase), and the light that follows from them - sun colour
// warming at golden hour, the blue hour, the night sky, how many windows are lit at this hour.
package com.blazebaal.venice.render

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

object DayCycle {
    const val LAT = 45.437; const val LON = 12.333

    /** outputs (linear HDR), refreshed by update() */
    val sunDir = FloatArray(3); val sunCol = FloatArray(3)          // the main light: the sun, or the moon at night
    val skyAmb = FloatArray(3); val groundAmb = FloatArray(3)
    val zenith = FloatArray(3); val horizon = FloatArray(3)
    /** sun direction even below the horizon (for the twilight glow) */
    val trueSun = FloatArray(3)
    var sunElev = 30f; private set       // degrees
    var moonElev = 0f; private set
    var moonIllum = 0.5f; private set
    /** 0 day .. 1 full night */
    var night = 0f; private set
    /** 0..1: the orange band on the horizon around sunset and dawn */
    var dusk = 0f; private set
    /** share of the windows lit from inside (0 by day) */
    var windows = 0f; private set
    /** 0..1 stars visible */
    var stars = 0f; private set
    /** exposure boost (the eye adapts to the dark) */
    var exposure = 1f; private set
    /** local decimal hour shown in game */
    var hour = 12f; private set

    /** hourOverride: NaN = real time; otherwise the local hour to show (settings / testing) */
    fun update(hourOverride: Float = Float.NaN) {
        val cal = Calendar.getInstance()
        if (!hourOverride.isNaN()) {
            val h = ((hourOverride % 24f) + 24f) % 24f
            cal.set(Calendar.HOUR_OF_DAY, h.toInt()); cal.set(Calendar.MINUTE, ((h - h.toInt()) * 60).toInt()); cal.set(Calendar.SECOND, 0)
        }
        hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f + cal.get(Calendar.SECOND) / 3600f
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = cal.timeInMillis }
        val doy = utc.get(Calendar.DAY_OF_YEAR)
        val hUtc = utc.get(Calendar.HOUR_OF_DAY) + utc.get(Calendar.MINUTE) / 60.0 + utc.get(Calendar.SECOND) / 3600.0
        val (sAz, sEl, decl) = solar(doy, hUtc)
        sunElev = sEl.toFloat()
        dir(sAz, sEl, trueSun)
        // moon: lags the sun by its age (one lap of the sky per synodic month); declination swings +-28 deg
        val days = cal.timeInMillis / 86_400_000.0 - 10_962.76      // days since the new moon of 6 Jan 2000 18:14 UTC
        val age = ((days % 29.530588) + 29.530588) % 29.530588
        moonIllum = ((1 - cos(2 * PI * age / 29.530588)) / 2).toFloat()
        val mDecl = Math.toRadians(23.4 * sin(2 * PI * (doy - 80) / 365.25 + 2 * PI * age / 29.530588 + PI))
        val (mAz, mEl) = horizontal(LAT, Math.toRadians(hourAngle(doy, hUtc - age / 29.530588 * 24.0)), mDecl)
        moonElev = mEl.toFloat()

        val e = sunElev
        fun ss(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
        // ---- the sun's light: dims and reddens through more air near the horizon
        val sunI = ss(-3f, 6f, e) * (0.5f + 0.5f * ss(6f, 35f, e))
        val warm = 1f - ss(3f, 28f, e)
        val sc = floatArrayOf(3.3f * (1f + 0.15f * warm), 2.95f * (1f - 0.3f * warm), 2.45f * (1f - 0.62f * warm))
        // ---- moonlight: cold, faint, by phase and height
        val moonI = 0.075f * (0.15f + 0.85f * moonIllum) * ss(0f, 12f, moonElev) * (1f - ss(-4f, 2f, e))
        if (sunI * 3f >= moonI || moonElev <= 0f) {
            dir(sAz, max(sEl, 2.0), sunDir)
            for (i in 0..2) sunCol[i] = sc[i] * sunI
            if (sunI * 3f < moonI * 2f) for (i in 0..2) sunCol[i] = max(sunCol[i], moonI * floatArrayOf(0.75f, 0.9f, 1.3f)[i])
        } else {
            dir(mAz, mEl, sunDir)
            val mc = floatArrayOf(0.75f, 0.9f, 1.3f)
            for (i in 0..2) sunCol[i] = mc[i] * moonI
        }
        // ---- sky: day, sunset, blue hour (-6..-1), nautical dusk, night
        fun key(out: FloatArray, day: FloatArray, set: FloatArray, blue: FloatArray, nightC: FloatArray) {
            for (i in 0..2) out[i] = when {
                e >= 12f -> day[i]
                e >= 0f -> set[i] + (day[i] - set[i]) * ss(0f, 12f, e)
                e >= -6f -> blue[i] + (set[i] - blue[i]) * ss(-6f, 0f, e)
                else -> nightC[i] + (blue[i] - nightC[i]) * ss(-14f, -6f, e)
            }
        }
        key(zenith, floatArrayOf(0.16f, 0.30f, 0.62f), floatArrayOf(0.11f, 0.18f, 0.42f), floatArrayOf(0.035f, 0.055f, 0.15f), floatArrayOf(0.004f, 0.007f, 0.02f))
        key(horizon, floatArrayOf(0.62f, 0.68f, 0.76f), floatArrayOf(0.92f, 0.62f, 0.44f), floatArrayOf(0.20f, 0.17f, 0.27f), floatArrayOf(0.022f, 0.028f, 0.045f))
        key(skyAmb, floatArrayOf(0.34f, 0.42f, 0.56f), floatArrayOf(0.30f, 0.30f, 0.40f), floatArrayOf(0.09f, 0.10f, 0.17f), floatArrayOf(0.014f, 0.019f, 0.036f))
        key(groundAmb, floatArrayOf(0.20f, 0.18f, 0.15f), floatArrayOf(0.20f, 0.15f, 0.11f), floatArrayOf(0.05f, 0.05f, 0.07f), floatArrayOf(0.009f, 0.010f, 0.016f))
        // moonlight lifts the night a little
        val ml = moonI * 3f
        for (i in 0..2) { skyAmb[i] += ml * floatArrayOf(0.12f, 0.15f, 0.22f)[i]; horizon[i] += ml * floatArrayOf(0.05f, 0.06f, 0.09f)[i]; zenith[i] += ml * floatArrayOf(0.03f, 0.04f, 0.07f)[i] }
        night = 1f - ss(-10f, 2f, e)
        dusk = ss(-9f, -1f, e) * (1f - ss(2f, 14f, e))
        stars = 1f - ss(-13f, -5f, e)
        exposure = 1f + 1.1f * night
        // windows: lit as the light goes; most in the evening, few after midnight, some again before dawn
        val h = hour
        val share = when {
            h >= 17f && h < 23f -> 0.55f
            h >= 23f || h < 1f -> 0.42f
            h < 5f -> 0.12f
            h < 7.5f -> 0.3f
            else -> 0.45f
        }
        windows = share * (1f - ss(-2f, 5f, e))
    }

    fun isDay() = sunElev > -3f

    private fun dir(az: Double, el: Double, out: FloatArray) {
        val a = Math.toRadians(az); val l = Math.toRadians(el)
        out[0] = (sin(a) * cos(l)).toFloat(); out[1] = (cos(a) * cos(l)).toFloat(); out[2] = sin(l).toFloat()
    }

    private fun gamma(doy: Int, hUtc: Double) = 2 * PI / 365 * (doy - 1 + (hUtc - 12) / 24)
    /** hour angle (deg) at Venice for UTC decimal hour */
    private fun hourAngle(doy: Int, hUtc: Double): Double {
        val g = gamma(doy, hUtc)
        val eqt = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val tst = hUtc * 60 + eqt + 4 * LON
        return tst / 4 - 180
    }
    /** azimuth (deg from north, clockwise), elevation (deg), declination (rad) */
    private fun solar(doy: Int, hUtc: Double): Triple<Double, Double, Double> {
        val g = gamma(doy, hUtc)
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val (az, el) = horizontal(LAT, Math.toRadians(hourAngle(doy, hUtc)), decl)
        return Triple(az, el, decl)
    }
    private fun horizontal(latDeg: Double, ha: Double, decl: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg)
        val sinEl = sin(phi) * sin(decl) + cos(phi) * cos(decl) * cos(ha)
        val el = Math.toDegrees(asin(sinEl.coerceIn(-1.0, 1.0)))
        val az = Math.toDegrees(atan2(sin(ha), cos(ha) * sin(phi) - tan(decl) * cos(phi))) + 180.0
        return Pair(az, el)
    }
}
