package com.dashboard.app.dashboard_app.service

import com.dashboard.app.dashboard_app.engine.RoutePhysics
import com.dashboard.app.dashboard_app.model.AppSettings
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.NavRouteX
import com.dashboard.app.dashboard_app.model.PlannedStop
import com.dashboard.app.dashboard_app.model.RouteStepX
import com.dashboard.app.dashboard_app.model.SavedPlace
import com.dashboard.app.dashboard_app.model.SpkluStation
import com.dashboard.app.dashboard_app.model.WaypointArrivalSummary
import com.dashboard.app.dashboard_app.model.WeatherData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

data class ReverseGeocodeResult(val road: String, val area: String)

object RemoteApisService {
    private const val USER_AGENT = "DashboardAppEV/1.0 (com.dashboard.app.dashboard_app)"

    private fun httpGet(
        urlStr: String,
        timeoutMs: Int = 10_000,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpPostForm(urlStr: String, formBody: String, timeoutMs: Int = 12_000): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun fetchWeather(lat: Double, lng: Double): WeatherData? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&hourly=precipitation_probability,uv_index&daily=sunrise,sunset&timezone=auto&forecast_days=1"
            val body = httpGet(url, 10_000) ?: return@withContext null
            val j = JSONObject(body)
            val cur = j.optJSONObject("current") ?: return@withContext null
            val hourly = j.optJSONObject("hourly")
            val probs = hourly?.optJSONArray("precipitation_probability")
            val uvs = hourly?.optJSONArray("uv_index")
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val probLen = probs?.length() ?: 0
            val hourIdx = hour.coerceIn(0, max(0, probLen - 1))
            val rainProb = if (probLen > 0) probs?.optInt(hourIdx, 0) ?: 0 else 0
            val uvIdx = if (uvs != null && hourIdx < uvs.length() && !uvs.isNull(hourIdx)) {
                uvs.optDouble(hourIdx)
            } else null

            val daily = j.optJSONObject("daily")
            var sunrise = "--:--"
            var sunset = "--:--"
            val srList = daily?.optJSONArray("sunrise")
            val ssList = daily?.optJSONArray("sunset")
            if (srList != null && srList.length() > 0) {
                val s = srList.optString(0)
                if (s.contains("T")) sunrise = s.substringAfter("T")
            }
            if (ssList != null && ssList.length() > 0) {
                val s = ssList.optString(0)
                if (s.contains("T")) sunset = s.substringAfter("T")
            }

            val wCode = cur.optInt("weather_code", 1)
            val decoded = RoutePhysics.decodeWeatherCode(wCode)
            WeatherData(
                tempC = cur.optDouble("temperature_2m", 28.0),
                feelsLikeC = cur.optDouble("apparent_temperature", 30.0),
                humidityPct = cur.optInt("relative_humidity_2m", 70),
                windKmh = cur.optDouble("wind_speed_10m", 8.0),
                rainProbPct = rainProb,
                uvIndex = uvIdx,
                weatherCode = wCode,
                description = decoded.desc,
                moodTag = decoded.moodTag,
                sunrise = sunrise,
                sunset = sunset,
                fetchedAtMs = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun reverseGeocode(lat: Double, lng: Double): ReverseGeocodeResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lng&zoom=18&addressdetails=1"
            val body = httpGet(url, 8_000) ?: return@withContext null
            val j = JSONObject(body)
            val addr = j.optJSONObject("address") ?: JSONObject()
            val road = addr.optString("road")
                .ifBlank { addr.optString("pedestrian") }
                .ifBlank { addr.optString("suburb") }
                .ifBlank { j.optString("name") }
            val area = addr.optString("city_district")
                .ifBlank { addr.optString("suburb") }
                .ifBlank { addr.optString("village") }
                .ifBlank { addr.optString("city") }
                .ifBlank { addr.optString("town") }
            ReverseGeocodeResult(road = road, area = area)
        } catch (_: Exception) {
            null
        }
    }

    fun isMapsShortUrl(input: String): Boolean {
        val low = input.lowercase(Locale.US)
        return low.contains("maps.app.goo.gl") ||
            low.contains("goo.gl/maps") ||
            low.contains("g.co/") ||
            low.contains("share.google")
    }

    suspend fun resolveGoogleMapsShortUrl(rawUrl: String): GeoPointX? = withContext(Dispatchers.IO) {
        val urlMatch = Regex("""https?://\S+""").find(rawUrl.trim())?.value ?: return@withContext null
        var current = urlMatch
        try {
            repeat(5) {
                RoutePhysics.parseCoordinatesOrGoogleMapsUrl(current)?.let { return@withContext it }
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(current).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        instanceFollowRedirects = false
                        connectTimeout = 7_000
                        readTimeout = 7_000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                    }
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location") ?: return@repeat
                        current = if (loc.startsWith("/")) {
                            val u = URL(current)
                            "${u.protocol}://${u.host}$loc"
                        } else loc
                    } else if (code == HttpURLConnection.HTTP_OK) {
                        val html = conn.inputStream.bufferedReader().use { it.readText() }.take(80_000)
                        // Check !3dLAT!4dLNG, @LAT,LNG, center=LAT,LNG, or og:image coordinates
                        val patterns = listOf(
                            Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)"""),
                            Regex("""center=(-?\d{1,2}\.\d+)(?:%2C|,)(-?\d{1,3}\.\d+)"""),
                            Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)"""),
                            Regex(""""lat"\s*:\s*(-?\d{1,2}\.\d+)\s*,\s*"(?:lng|lon)"\s*:\s*(-?\d{1,3}\.\d+)""")
                        )
                        for (re in patterns) {
                            val m = re.find(html)
                            if (m != null) {
                                val lat = m.groupValues[1].toDoubleOrNull()
                                val lng = m.groupValues[2].toDoubleOrNull()
                                if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                                    return@withContext GeoPointX(lat, lng)
                                }
                            }
                        }
                        return@withContext null
                    } else {
                        return@withContext null
                    }
                } finally {
                    conn?.disconnect()
                }
            }
            RoutePhysics.parseCoordinatesOrGoogleMapsUrl(current)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun searchPlaces(query: String): List<SavedPlace> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val direct = RoutePhysics.parseCoordinatesOrGoogleMapsUrl(trimmed)
        if (direct != null) {
            return@withContext listOf(
                SavedPlace(
                    id = "coord_${direct.lat}_${direct.lng}",
                    name = "Koordinat (${String.format(Locale.US, "%.5f", direct.lat)}, ${String.format(Locale.US, "%.5f", direct.lng)})",
                    category = "Pin Koordinat",
                    lat = direct.lat,
                    lng = direct.lng,
                    createdAtMs = System.currentTimeMillis()
                )
            )
        }
        if (isMapsShortUrl(trimmed)) {
            val resolved = resolveGoogleMapsShortUrl(trimmed)
            if (resolved != null) {
                return@withContext listOf(
                    SavedPlace(
                        id = "short_${resolved.lat}_${resolved.lng}",
                        name = "Lokasi Link Maps (${String.format(Locale.US, "%.5f", resolved.lat)}, ${String.format(Locale.US, "%.5f", resolved.lng)})",
                        category = "Pin Link",
                        lat = resolved.lat,
                        lng = resolved.lng,
                        createdAtMs = System.currentTimeMillis()
                    )
                )
            }
        }

        try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&q=$encoded&limit=8&addressdetails=1"
            val body = httpGet(url, 9_000) ?: return@withContext emptyList()
            val arr = JSONArray(body)
            val out = mutableListOf<SavedPlace>()
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val lat = item.optString("lat").toDoubleOrNull() ?: continue
                val lng = item.optString("lon").toDoubleOrNull() ?: continue
                val disp = item.optString("display_name", "Lokasi")
                val shortName = disp.split(",").take(2).joinToString(", ").trim()
                val id = item.opt("place_id")?.toString() ?: disp.hashCode().toString()
                out.add(
                    SavedPlace(
                        id = id,
                        name = shortName.ifEmpty { disp },
                        category = item.optString("type", "Hasil Cari"),
                        lat = lat,
                        lng = lng,
                        createdAtMs = now
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchElevations(points: List<GeoPointX>): List<Double>? = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext emptyList()
        try {
            val lats = points.joinToString(",") { String.format(Locale.US, "%.5f", it.lat) }
            val lngs = points.joinToString(",") { String.format(Locale.US, "%.5f", it.lng) }
            val url = "https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lngs"
            val body = httpGet(url, 9_000) ?: return@withContext null
            val j = JSONObject(body)
            val elev = j.optJSONArray("elevation") ?: return@withContext null
            List(elev.length()) { idx -> elev.optDouble(idx, 0.0) }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchOsrmRoute(
        origin: GeoPointX,
        stops: List<PlannedStop>,
        settings: AppSettings,
        currentSocPct: Double?
    ): NavRouteX? = withContext(Dispatchers.IO) {
        if (stops.isEmpty()) return@withContext null
        val allPts = listOf(origin) + stops.map { GeoPointX(it.lat, it.lng) }
        val coordStr = allPts.joinToString(";") {
            String.format(Locale.US, "%.6f,%.6f", it.lng, it.lat)
        }
        try {
            val url = "https://router.project-osrm.org/route/v1/driving/$coordStr?overview=full&geometries=geojson&steps=true"
            val body = httpGet(url, 12_000) ?: return@withContext null
            val j = JSONObject(body)
            val routes = j.optJSONArray("routes") ?: return@withContext null
            if (routes.length() == 0) return@withContext null
            val route0 = routes.getJSONObject(0)

            val distM = route0.optDouble("distance", 0.0)
            val durSec = route0.optDouble("duration", 0.0)

            val geomObj = route0.optJSONObject("geometry")
            val rawCoords = geomObj?.optJSONArray("coordinates") ?: JSONArray()
            val geometry = mutableListOf<GeoPointX>()
            for (i in 0 until rawCoords.length()) {
                val c = rawCoords.optJSONArray(i) ?: continue
                if (c.length() < 2) continue
                geometry.add(GeoPointX(lat = c.getDouble(1), lng = c.getDouble(0)))
            }

            val steps = mutableListOf<RouteStepX>()
            val legSummaries = mutableListOf<WaypointArrivalSummary>()
            val legs = route0.optJSONArray("legs") ?: JSONArray()

            val totalPackWh = settings.b1NominalV * settings.b1CapacityAh +
                if (settings.savedEsp2Address != null) settings.b2NominalV * settings.b2CapacityAh else 0.0

            var runningSoc = currentSocPct ?: 100.0
            for (i in 0 until legs.length()) {
                val leg = legs.getJSONObject(i)
                val legDistKm = leg.optDouble("distance", 0.0) / 1000.0
                val legDurMin = leg.optDouble("duration", 0.0) / 60.0
                val estWh = legDistKm * settings.refWhPerKm * settings.routeCalFactor
                val socDrop = if (totalPackWh > 0.0) (estWh / totalPackWh) * 100.0 else 0.0
                runningSoc = (runningSoc - socDrop).coerceIn(0.0, 100.0)

                val stopMeta = stops.getOrNull(i)
                legSummaries.add(
                    WaypointArrivalSummary(
                        label = stopMeta?.label ?: "Tujuan ${i + 1}",
                        legDistKm = legDistKm,
                        legEtaMin = legDurMin,
                        arrivalSocPct = if (currentSocPct != null) runningSoc else null,
                        isChargingStop = stopMeta?.isSpklu == true
                    )
                )
                if (stopMeta?.isSpklu == true && currentSocPct != null) {
                    runningSoc = max(runningSoc, 85.0)
                }

                val legSteps = leg.optJSONArray("steps") ?: JSONArray()
                for (sIdx in 0 until legSteps.length()) {
                    val st = legSteps.getJSONObject(sIdx)
                    val man = st.optJSONObject("maneuver") ?: JSONObject()
                    val locArr = man.optJSONArray("location") ?: continue
                    if (locArr.length() < 2) continue
                    val roadName = st.optString("name", "")
                    val manType = man.optString("type", "continue")
                    val modifier = if (man.has("modifier") && !man.isNull("modifier")) man.optString("modifier") else null
                    steps.add(
                        RouteStepX(
                            instruction = RoutePhysics.translateOsrmManeuver(manType, modifier, roadName),
                            roadName = roadName,
                            distanceM = st.optDouble("distance", 0.0),
                            durationSec = st.optDouble("duration", 0.0),
                            maneuverType = manType,
                            modifier = modifier,
                            location = GeoPointX(lat = locArr.getDouble(1), lng = locArr.getDouble(0))
                        )
                    )
                }
            }

            val downsampled = RoutePhysics.downsamplePoints(geometry, maxSamples = 36)
            val elevations = fetchElevations(downsampled) ?: emptyList()
            val physics = RoutePhysics.buildElevationAndEnergy(
                sampledPoints = downsampled,
                elevations = elevations,
                totalDistanceKm = distM / 1000.0,
                durationMin = durSec / 60.0,
                bikeKg = settings.bikeWeightKg,
                riderKg = settings.riderWeightKg,
                loadKg = settings.loadWeightKg,
                calFactor = settings.routeCalFactor,
                totalPackWh = totalPackWh,
                currentSocPct = currentSocPct
            )

            NavRouteX(
                geometry = geometry,
                steps = steps,
                distanceM = distM,
                durationSec = durSec,
                elevationSamples = physics.samples,
                energyEstimate = physics.estimate,
                legSummaries = legSummaries
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchNearbySpklu(
        lat: Double,
        lng: Double,
        radiusMeters: Int = 15000,
        ocmApiKey: String = ""
    ): List<SpkluStation> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<String, SpkluStation>()
        val origin = GeoPointX(lat, lng)

        // 1. Overpass OSM Query
        try {
            val query = "[out:json][timeout:12];(node[\"amenity\"=\"charging_station\"](around:$radiusMeters,$lat,$lng);way[\"amenity\"=\"charging_station\"](around:$radiusMeters,$lat,$lng););out center 30;"
            val body = httpPostForm(
                "https://overpass-api.de/api/interpreter",
                "data=${URLEncoder.encode(query, "UTF-8")}",
                12_000
            )
            if (body != null) {
                val j = JSONObject(body)
                val elements = j.optJSONArray("elements") ?: JSONArray()
                for (i in 0 until elements.length()) {
                    val el = elements.optJSONObject(i) ?: continue
                    val center = el.optJSONObject("center")
                    val sLat = when {
                        el.has("lat") && !el.isNull("lat") -> el.optDouble("lat")
                        center != null && center.has("lat") -> center.optDouble("lat")
                        else -> Double.NaN
                    }
                    val sLng = when {
                        el.has("lon") && !el.isNull("lon") -> el.optDouble("lon")
                        center != null && center.has("lon") -> center.optDouble("lon")
                        else -> Double.NaN
                    }
                    if (sLat.isNaN() || sLng.isNaN()) continue
                    val tags = el.optJSONObject("tags") ?: JSONObject()
                    val name = tags.optString("name").ifBlank { "SPKLU Umum" }
                    val op = tags.optString("operator")
                        .ifBlank { tags.optString("brand") }
                        .ifBlank { "PLN / OSM" }
                    val dist = RoutePhysics.haversineKm(origin, GeoPointX(sLat, sLng))
                    val id = "osm_${el.opt("id")}"
                    results[id] = SpkluStation(
                        id = id,
                        name = name,
                        operatorName = op,
                        lat = sLat,
                        lng = sLng,
                        socketType = if (tags.has("socket:type2")) "Type 2 / AC" else "SPKLU Universal",
                        distanceKm = dist
                    )
                }
            }
        } catch (_: Exception) {
        }

        // 2. OpenChargeMap
        try {
            val cleanKey = ocmApiKey.trim()
            val keyPart = if (cleanKey.isNotEmpty()) "&key=${URLEncoder.encode(cleanKey, "UTF-8")}" else ""
            val headers = if (cleanKey.isNotEmpty()) mapOf("X-API-Key" to cleanKey) else emptyMap()
            val distKm = (radiusMeters / 1000.0).roundToInt().coerceAtLeast(15)
            val url = "https://api.openchargemap.io/v3/poi/?output=json&countrycode=ID&latitude=$lat&longitude=$lng&distance=$distKm&distanceunit=KM&maxresults=40$keyPart"
            val body = httpGet(url, 10_000, headers)
            if (body != null) {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val addr = item.optJSONObject("AddressInfo") ?: continue
                    if (!addr.has("Latitude") || !addr.has("Longitude")) continue
                    val sLat = addr.optDouble("Latitude", Double.NaN)
                    val sLng = addr.optDouble("Longitude", Double.NaN)
                    if (sLat.isNaN() || sLng.isNaN()) continue
                    // Deduplicate against OSM stations within 80m
                    val duplicate = results.values.any { existing ->
                        RoutePhysics.haversineKm(GeoPointX(existing.lat, existing.lng), GeoPointX(sLat, sLng)) < 0.08
                    }
                    if (duplicate) continue

                    val opInfo = item.optJSONObject("OperatorInfo")
                    val conns = item.optJSONArray("Connections")
                    var kw: Double? = null
                    var socket = "AC/DC"
                    if (conns != null && conns.length() > 0) {
                        val c0 = conns.optJSONObject(0)
                        if (c0 != null) {
                            if (c0.has("PowerKW") && !c0.isNull("PowerKW")) {
                                kw = c0.optDouble("PowerKW")
                            }
                            val ct = c0.optJSONObject("ConnectionType")
                            if (ct != null && ct.has("Title") && !ct.isNull("Title")) {
                                socket = ct.optString("Title")
                            }
                        }
                    }
                    val dist = RoutePhysics.haversineKm(origin, GeoPointX(sLat, sLng))
                    val id = "ocm_${item.opt("ID")}"
                    results[id] = SpkluStation(
                        id = id,
                        name = addr.optString("Title").ifBlank { "Stasiun SPKLU" },
                        operatorName = opInfo?.optString("Title")?.ifBlank { "OpenChargeMap" } ?: "OpenChargeMap",
                        lat = sLat,
                        lng = sLng,
                        socketType = socket,
                        powerKw = kw,
                        distanceKm = dist
                    )
                }
            }
        } catch (_: Exception) {
        }

        results.values.sortedBy { it.distanceKm ?: 999.0 }
    }
}
