package com.dashboard.app.dashboard_app.engine

import com.dashboard.app.dashboard_app.model.ElevationSample
import com.dashboard.app.dashboard_app.model.FieldNotePin
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.HillGrade
import com.dashboard.app.dashboard_app.model.HillSegment
import com.dashboard.app.dashboard_app.model.RouteEnergyEstimate
import com.dashboard.app.dashboard_app.model.TripRecord
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class ElevationPhysicsResult(
    val samples: List<ElevationSample>,
    val estimate: RouteEnergyEstimate
)

data class DecodedWeather(val desc: String, val moodTag: String)

object RoutePhysics {
    const val GRAVITY = 9.81
    const val CRR = 0.013
    const val CDA = 0.55
    const val RHO = 1.18
    const val ETA_DRIVE = 0.80
    const val ETA_REGEN = 0.45
    const val AUX_W = 25.0

    private fun degToRad(deg: Double): Double = deg * (PI / 180.0)

    fun haversineKm(a: GeoPointX, b: GeoPointX): Double {
        val r = 6371.0
        val dLat = degToRad(b.lat - a.lat)
        val dLon = degToRad(b.lng - a.lng)
        val sinLat = sin(dLat / 2.0)
        val sinLon = sin(dLon / 2.0)
        val h = sinLat * sinLat + cos(degToRad(a.lat)) * cos(degToRad(b.lat)) * sinLon * sinLon
        return 2.0 * r * atan2(sqrt(h), sqrt(max(0.0, 1.0 - h)))
    }

    fun distanceToPolylineM(point: GeoPointX, polyline: List<GeoPointX>): Double {
        if (polyline.isEmpty()) return Double.MAX_VALUE
        if (polyline.size == 1) return haversineKm(point, polyline[0]) * 1000.0
        val cosLat = cos(degToRad(point.lat)).coerceIn(0.2, 1.0)
        var minDistM = Double.MAX_VALUE
        for (i in 0 until polyline.lastIndex) {
            val a = polyline[i]
            val b = polyline[i + 1]
            val bx = (b.lng - a.lng) * 111320.0 * cosLat
            val by = (b.lat - a.lat) * 110540.0
            val px = (point.lng - a.lng) * 111320.0 * cosLat
            val py = (point.lat - a.lat) * 110540.0
            val len2 = bx * bx + by * by
            val t = if (len2 > 1e-6) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
            val distM = hypot(px - t * bx, py - t * by)
            if (distM < minDistM) minDistM = distM
        }
        return minDistM
    }

    fun bearingDeg(a: GeoPointX, b: GeoPointX): Double {
        val lat1 = degToRad(a.lat)
        val lat2 = degToRad(b.lat)
        val dLon = degToRad(b.lng - a.lng)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((atan2(y, x) * 180.0 / PI) + 360.0) % 360.0
    }

    fun normalizeSignedDeg(deg: Double): Double {
        var d = ((deg % 360.0) + 360.0) % 360.0
        if (d > 180.0) d -= 360.0
        return d
    }

    fun parseCoordinatesOrGoogleMapsUrl(input: String): GeoPointX? {
        val trimmed = input.trim()
        val direct = Regex("""(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)""").find(trimmed)
        if (direct != null) {
            val lat = direct.groupValues[1].toDoubleOrNull()
            val lng = direct.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                return GeoPointX(lat, lng)
            }
        }
        val gmaps = Regex("""[@/](-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""").find(trimmed)
        if (gmaps != null) {
            val lat = gmaps.groupValues[1].toDoubleOrNull()
            val lng = gmaps.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                return GeoPointX(lat, lng)
            }
        }
        return null
    }

    fun downsamplePoints(points: List<GeoPointX>, maxSamples: Int = 36): List<GeoPointX> {
        if (points.size <= maxSamples) return points
        val step = (points.size - 1).toDouble() / (maxSamples - 1)
        return List(maxSamples) { i ->
            val idx = floor(i * step).toInt().coerceIn(0, points.lastIndex)
            points[idx]
        }
    }

    fun buildElevationAndEnergy(
        sampledPoints: List<GeoPointX>,
        elevations: List<Double>,
        totalDistanceKm: Double,
        durationMin: Double,
        bikeKg: Double,
        riderKg: Double,
        loadKg: Double,
        calFactor: Double,
        totalPackWh: Double,
        currentSocPct: Double?
    ): ElevationPhysicsResult {
        val n = min(sampledPoints.size, elevations.size)
        if (n < 2) {
            val fallbackWh = totalDistanceKm * 28.0 * calFactor
            val socUsed = if (totalPackWh > 0) (fallbackWh / totalPackWh) * 100.0 else 0.0
            return ElevationPhysicsResult(
                samples = emptyList(),
                estimate = RouteEnergyEstimate(
                    totalWh = fallbackWh,
                    regenWh = 0.0,
                    netWh = fallbackWh,
                    whPerKm = if (totalDistanceKm > 0.1) fallbackWh / totalDistanceKm else 28.0,
                    socUsedPct = socUsed,
                    socAtArrivalPct = currentSocPct?.let { (it - socUsed).coerceIn(0.0, 100.0) },
                    maxClimbPct = 0.0,
                    totalAscentM = 0.0,
                    totalDescentM = 0.0
                )
            )
        }

        val totalMassKg = bikeKg + riderKg + loadKg
        val avgSpeedMs = if (durationMin > 0.1) {
            ((totalDistanceKm * 1000.0) / (durationMin * 60.0)).coerceIn(5.0, 22.0)
        } else 10.0

        val samples = mutableListOf<ElevationSample>()
        var cumDistKm = 0.0
        var driveWh = 0.0
        var regenWh = 0.0
        var ascent = 0.0
        var descent = 0.0
        var maxClimb = 0.0

        samples.add(
            ElevationSample(
                distanceKm = 0.0,
                elevationM = elevations[0],
                gradePct = 0.0,
                gradeType = HillGrade.FLAT,
                point = sampledPoints[0]
            )
        )

        for (i in 1 until n) {
            val segKm = max(0.02, haversineKm(sampledPoints[i - 1], sampledPoints[i]))
            cumDistKm += segKm
            val segM = segKm * 1000.0
            val dh = elevations[i] - elevations[i - 1]
            if (dh > 0) ascent += dh else descent += -dh

            val gradePct = ((dh / segM) * 100.0).coerceIn(-35.0, 35.0)
            if (gradePct > maxClimb) maxClimb = gradePct

            val theta = atan2(dh, segM)
            val force = totalMassKg * GRAVITY * sin(theta) +
                CRR * totalMassKg * GRAVITY * cos(theta) +
                0.5 * RHO * CDA * avgSpeedMs.pow(2.0)

            val mechEnergyJ = force * segM
            val segTimeHr = (segM / avgSpeedMs) / 3600.0
            val auxSegWh = AUX_W * segTimeHr

            if (mechEnergyJ >= 0) {
                driveWh += (mechEnergyJ / 3600.0) / ETA_DRIVE + auxSegWh
            } else {
                regenWh += ((-mechEnergyJ) / 3600.0) * ETA_REGEN
                driveWh += auxSegWh
            }

            val gradeType = when {
                gradePct >= 6.0 -> HillGrade.UPHILL_STEEP
                gradePct >= 2.5 -> HillGrade.UPHILL_MILD
                gradePct <= -2.5 -> HillGrade.DOWNHILL
                else -> HillGrade.FLAT
            }

            samples.add(
                ElevationSample(
                    distanceKm = cumDistKm,
                    elevationM = elevations[i],
                    gradePct = gradePct,
                    gradeType = gradeType,
                    point = sampledPoints[i]
                )
            )
        }

        val calDriveWh = driveWh * calFactor
        val netWh = max(totalDistanceKm * 10.0, calDriveWh - regenWh)
        val socUsed = if (totalPackWh > 0.0) (netWh / totalPackWh) * 100.0 else 0.0

        return ElevationPhysicsResult(
            samples = samples,
            estimate = RouteEnergyEstimate(
                totalWh = calDriveWh,
                regenWh = regenWh,
                netWh = netWh,
                whPerKm = if (totalDistanceKm > 0.1) netWh / totalDistanceKm else 28.0,
                socUsedPct = socUsed,
                socAtArrivalPct = currentSocPct?.let { (it - socUsed).coerceIn(0.0, 100.0) },
                maxClimbPct = maxClimb,
                totalAscentM = ascent,
                totalDescentM = descent
            )
        )
    }

    fun translateOsrmManeuver(type: String?, modifier: String?, roadName: String?): String {
        val road = if (!roadName.isNullOrBlank()) " ke $roadName" else ""
        return when (type) {
            "depart" -> "Mulai perjalanan$road"
            "arrive" -> "Tiba di tujuan"
            "roundabout", "rotary" -> "Masuk bundaran$road"
            "merge" -> "Gabung jalur$road"
            "fork" -> when (modifier) {
                "left", "slight left" -> "Ambil cabang kiri$road"
                "right", "slight right" -> "Ambil cabang kanan$road"
                else -> "Tetap di jalur$road"
            }
            else -> when (modifier) {
                "left" -> "Belok kiri$road"
                "sharp left" -> "Belok kiri tajam$road"
                "slight left" -> "Serong kiri$road"
                "right" -> "Belok kanan$road"
                "sharp right" -> "Belok kanan tajam$road"
                "slight right" -> "Serong kanan$road"
                "uturn" -> "Putar balik$road"
                else -> "Lurus terus$road"
            }
        }
    }

    fun decodeWeatherCode(code: Int?): DecodedWeather = when (code) {
        0 -> DecodedWeather("Cerah", "CERAH")
        1, 2 -> DecodedWeather("Cerah Berawan", "BERAWAN")
        3 -> DecodedWeather("Mendung", "MENDUNG")
        45, 48 -> DecodedWeather("Berkabut", "KABUT")
        51, 53, 55 -> DecodedWeather("Gerimis", "GERIMIS")
        61, 63, 65, 80, 81, 82 -> DecodedWeather("Hujan", "HUJAN")
        95, 96, 99 -> DecodedWeather("Badai Petir", "BADAI")
        else -> DecodedWeather("Berawan", "BERAWAN")
    }

    fun exportTripsCsv(trips: List<TripRecord>): String {
        val sb = StringBuilder(
            "id,start_ms,end_ms,distance_km,energy_wh,regen_wh,wh_per_km,max_speed_kmh,avg_speed_kmh,max_power_w,score,start_area,end_area\n"
        )
        for (t in trips) {
            sb.appendLine(
                String.format(
                    Locale.US,
                    "%s,%d,%d,%.2f,%.1f,%.1f,%.1f,%.1f,%.1f,%.0f,%s,\"%s\",\"%s\"",
                    t.id,
                    t.startTimeMs,
                    t.endTimeMs,
                    t.distanceKm,
                    t.energyWh,
                    t.regenWh,
                    t.whPerKm,
                    t.maxSpeedKmh,
                    t.avgSpeedKmh,
                    t.maxPowerW,
                    t.ridingScore?.toString() ?: "",
                    t.startArea.replace("\"", "'"),
                    t.endArea.replace("\"", "'")
                )
            )
        }
        return sb.toString()
    }

    fun exportTripGpx(trip: TripRecord): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="Dashboard App EV">""")
        sb.appendLine("""<trk><name>Trip ${trip.id}</name><trkseg>""")
        for (p in trip.pathPoints) {
            val ele = p.altM?.let { String.format(Locale.US, "<ele>%.1f</ele>", it) } ?: ""
            sb.appendLine(
                String.format(
                    Locale.US,
                    """  <trkpt lat="%.6f" lon="%.6f">%s</trkpt>""",
                    p.lat,
                    p.lng,
                    ele
                )
            )
        }
        sb.appendLine("</trkseg></trk></gpx>")
        return sb.toString()
    }

    fun exportFieldNotesGpx(notes: List<FieldNotePin>): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="Dashboard App EV Field Notes">""")
        for (n in notes) {
            sb.appendLine(
                String.format(
                    Locale.US,
                    """<wpt lat="%.6f" lon="%.6f"><name>%s</name><type>%s</type><desc>%s</desc></wpt>""",
                    n.lat,
                    n.lng,
                    xmlEscape(n.name),
                    xmlEscape(n.category),
                    xmlEscape(n.note)
                )
            )
        }
        sb.appendLine("</gpx>")
        return sb.toString()
    }

    fun windDirText(deg: Double): String {
        val dirs = listOf(
            "Utara", "Timur Laut", "Timur", "Tenggara",
            "Selatan", "Barat Daya", "Barat", "Barat Laut"
        )
        val idx = ((((deg % 360.0) + 360.0) % 360.0) / 45.0).roundToInt() % 8
        return dirs[idx]
    }

    fun gradeLabel(gradePct: Double): String {
        val g = abs(gradePct)
        return when {
            g >= 7.0 -> "curam"
            g >= 4.0 -> "sedang"
            else -> "landai"
        }
    }

    fun analyzeHillSegments(
        samples: List<ElevationSample>,
        bikeKg: Double,
        riderKg: Double,
        loadKg: Double,
        calFactor: Double,
        totalPackWh: Double
    ): List<HillSegment> {
        if (samples.size < 3) return emptyList()
        data class RawRun(val startIdx: Int, var endIdx: Int, val isUp: Boolean, var maxAbsG: Double)
        val runs = mutableListOf<RawRun>()
        var cur: RawRun? = null
        for (i in 1 until samples.size) {
            val g = samples[i].gradePct
            if (abs(g) < 1.6) {
                if (cur != null) {
                    runs.add(cur)
                    cur = null
                }
                continue
            }
            val up = g > 0.0
            if (cur == null) {
                cur = RawRun(i - 1, i, up, abs(g))
            } else if (cur.isUp == up) {
                cur.endIdx = i
                cur.maxAbsG = max(cur.maxAbsG, abs(g))
            } else {
                runs.add(cur)
                cur = RawRun(i - 1, i, up, abs(g))
            }
        }
        if (cur != null) runs.add(cur)

        // Merge same-direction runs separated by < 120m
        val merged = mutableListOf<RawRun>()
        for (r in runs) {
            val prev = merged.lastOrNull()
            val gapM = if (prev != null) {
                (samples[r.startIdx].distanceKm - samples[prev.endIdx].distanceKm) * 1000.0
            } else Double.MAX_VALUE
            if (prev != null && prev.isUp == r.isUp && gapM <= 120.0) {
                prev.endIdx = r.endIdx
                prev.maxAbsG = max(prev.maxAbsG, r.maxAbsG)
            } else {
                merged.add(r)
            }
        }

        val totalMassKg = bikeKg + riderKg + loadKg
        val out = mutableListOf<HillSegment>()
        merged.forEachIndexed { _, r ->
            val startM = samples[r.startIdx].distanceKm * 1000.0
            val endM = samples[r.endIdx].distanceKm * 1000.0
            val lenM = max(1.0, endM - startM)
            val gainM = samples[r.endIdx].elevationM - samples[r.startIdx].elevationM
            if (lenM < 100.0 || abs(gainM) < 4.0) return@forEachIndexed
            val avgG = (gainM / lenM) * 100.0
            val gravWh = if (gainM >= 0.0) {
                ((totalMassKg * GRAVITY * gainM) / 3600.0 / ETA_DRIVE) * calFactor
            } else {
                -((totalMassKg * GRAVITY * (-gainM)) / 3600.0 * ETA_REGEN)
            }
            val socPct = if (totalPackWh > 0.0) -(gravWh / totalPackWh) * 100.0 else null
            out.add(
                HillSegment(
                    id = out.size,
                    isUphill = r.isUp,
                    startDistM = startM,
                    endDistM = endM,
                    lengthM = lenM,
                    gainM = gainM,
                    avgGradePct = avgG,
                    maxGradePct = if (r.isUp) r.maxAbsG else -r.maxAbsG,
                    dWh = gravWh,
                    socPct = socPct,
                    notable = abs(avgG) >= 3.5 && abs(gainM) >= 12.0,
                    steep = abs(avgG) >= 6.0 || r.maxAbsG >= 8.0
                )
            )
        }
        return out
    }

    /**
     * Slices route elevation samples to a rolling lookahead window (default 2.0 km)
     * starting at the rider's current projected position along the route.
     * Returned sample distances are relative to the rider (0.0 at left edge).
     */
    fun sliceElevationWindow(
        samples: List<ElevationSample>,
        riderLocation: GeoPointX?,
        windowKm: Double = 2.0
    ): List<ElevationSample> {
        if (samples.size < 2) return samples
        val totalKm = samples.last().distanceKm
        if (totalKm <= 0.05) return samples

        var riderDistKm = samples.first().distanceKm
        if (riderLocation != null) {
            var bestSg = 0
            var bestT = 0.0
            var bestOff = Double.MAX_VALUE
            val cosLat = kotlin.math.cos(Math.toRadians(riderLocation.lat))
            for (i in 0 until samples.lastIndex) {
                val p0 = samples[i].point
                val p1 = samples[i + 1].point
                val bx = (p1.lng - p0.lng) * 111320.0 * cosLat
                val by = (p1.lat - p0.lat) * 110540.0
                val px = (riderLocation.lng - p0.lng) * 111320.0 * cosLat
                val py = (riderLocation.lat - p0.lat) * 110540.0
                val len2 = bx * bx + by * by
                val t = if (len2 > 1e-6) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
                val off = hypot(px - t * bx, py - t * by)
                if (off < bestOff) {
                    bestOff = off
                    bestSg = i
                    bestT = t
                }
            }
            val d0 = samples[bestSg].distanceKm
            val d1 = samples[bestSg + 1].distanceKm
            riderDistKm = (d0 + (d1 - d0) * bestT).coerceIn(0.0, totalKm)
        }

        val spanTarget = windowKm.coerceIn(0.5, 5.0)
        val startKm = if (totalKm - riderDistKm < 0.25 && totalKm > 0.5) {
            max(0.0, totalKm - 0.5)
        } else {
            riderDistKm
        }
        val endKm = (startKm + spanTarget).coerceAtMost(totalKm)
        if (endKm - startKm < 0.05) return samples

        fun sampleAtDist(targetKm: Double): ElevationSample {
            val idx = samples.indexOfFirst { it.distanceKm >= targetKm }
            if (idx <= 0) return samples.first().copy(distanceKm = targetKm - startKm)
            val a = samples[idx - 1]
            val b = samples[idx]
            val span = (b.distanceKm - a.distanceKm).coerceAtLeast(1e-6)
            val t = ((targetKm - a.distanceKm) / span).coerceIn(0.0, 1.0)
            return ElevationSample(
                distanceKm = (targetKm - startKm).coerceAtLeast(0.0),
                elevationM = a.elevationM + (b.elevationM - a.elevationM) * t,
                gradePct = b.gradePct,
                gradeType = b.gradeType,
                point = GeoPointX(
                    lat = a.point.lat + (b.point.lat - a.point.lat) * t,
                    lng = a.point.lng + (b.point.lng - a.point.lng) * t
                )
            )
        }

        val out = mutableListOf<ElevationSample>()
        out.add(sampleAtDist(startKm))
        for (s in samples) {
            if (s.distanceKm > startKm + 0.01 && s.distanceKm < endKm - 0.01) {
                out.add(s.copy(distanceKm = s.distanceKm - startKm))
            }
        }
        out.add(sampleAtDist(endKm))
        return out
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
