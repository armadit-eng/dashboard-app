package com.dashboard.app.dashboard_app.service

import android.content.Context
import android.util.JsonReader
import com.dashboard.app.dashboard_app.engine.OfflineEdge
import com.dashboard.app.dashboard_app.engine.OfflinePlaceEntry
import com.dashboard.app.dashboard_app.engine.OfflineRegionMeta
import com.dashboard.app.dashboard_app.engine.OfflineRoadClass
import com.dashboard.app.dashboard_app.engine.OfflineRoutingGraph
import com.dashboard.app.dashboard_app.engine.OsmWayInput
import com.dashboard.app.dashboard_app.engine.RoutePhysics
import com.dashboard.app.dashboard_app.model.AppSettings
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.MapStyleOption
import com.dashboard.app.dashboard_app.model.NavRouteX
import com.dashboard.app.dashboard_app.model.PlannedStop
import com.dashboard.app.dashboard_app.model.SavedPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class OfflineDownloadProgress(
    val phaseLabel: String,
    val done: Int,
    val total: Int
)

object OfflineMapPackageService {
    private val OVERPASS_MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()
    private val loadedGraphs = LinkedHashMap<String, OfflineRoutingGraph>()
    @Volatile
    private var diskScanned = false

    private fun rootDir(context: Context): File = File(context.filesDir, "offline_regions")

    fun listRegions(context: Context): List<OfflineRegionMeta> {
        ensureLoadedFromDisk(context)
        synchronized(lock) {
            return loadedGraphs.values
                .map { it.meta }
                .sortedByDescending { it.downloadedAtMs }
        }
    }

    private fun ensureLoadedFromDisk(context: Context) {
        if (diskScanned) return
        synchronized(lock) {
            if (diskScanned) return
            val dir = rootDir(context)
            if (dir.exists()) {
                dir.listFiles()?.forEach { sub ->
                    if (!sub.isDirectory) return@forEach
                    val binFile = File(sub, "graph.bin")
                    if (binFile.exists() && binFile.length() > 0L) {
                        val g = runCatching {
                            binFile.inputStream().use { OfflineRoutingGraph.readBinary(it, binFile.length()) }
                        }.getOrNull()
                        if (g != null) {
                            loadedGraphs[g.meta.id] = g
                        }
                    }
                }
            }
            diskScanned = true
        }
    }

    suspend fun reloadRegions(context: Context): List<OfflineRegionMeta> = withContext(Dispatchers.IO) {
        diskScanned = false
        synchronized(lock) { loadedGraphs.clear() }
        listRegions(context)
    }

    suspend fun deleteRegion(context: Context, regionId: String): List<OfflineRegionMeta> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                loadedGraphs.remove(regionId)
            }
            runCatching {
                val sub = File(rootDir(context), regionId)
                if (sub.exists()) sub.deleteRecursively()
            }
            listRegions(context)
        }

    fun isPointCovered(context: Context, point: GeoPointX): OfflineRegionMeta? {
        ensureLoadedFromDisk(context)
        synchronized(lock) {
            return loadedGraphs.values.firstOrNull { it.containsPoint(point, marginKm = 0.25) }?.meta
        }
    }

    suspend fun reverseGeocodeOffline(context: Context, lat: Double, lng: Double): ReverseGeocodeResult? =
        withContext(Dispatchers.Default) {
            ensureLoadedFromDisk(context)
            val pt = GeoPointX(lat, lng)
            val candidates = synchronized(lock) {
                loadedGraphs.values.filter { it.containsPoint(pt, marginKm = 1.0) }
            }
            for (g in candidates) {
                val res = g.reverseGeocode(pt)
                if (res != null) return@withContext res
            }
            null
        }

    suspend fun searchPlacesOffline(
        context: Context,
        query: String,
        biasOrigin: GeoPointX? = null,
        limit: Int = 15
    ): List<SavedPlace> = withContext(Dispatchers.Default) {
        ensureLoadedFromDisk(context)
        val graphs = synchronized(lock) { loadedGraphs.values.toList() }
        if (graphs.isEmpty()) return@withContext emptyList()
        val combined = ArrayList<SavedPlace>()
        val seen = HashSet<String>()
        for (g in graphs) {
            val hits = g.searchPlaces(query, biasOrigin, limit)
            for (h in hits) {
                val key = "${h.name.lowercase(Locale.US)}|${(h.lat * 1000).roundToLong()}|${(h.lng * 1000).roundToLong()}"
                if (seen.add(key)) combined.add(h)
            }
        }
        if (biasOrigin != null) {
            combined.sortedBy { RoutePhysics.haversineKm(biasOrigin, GeoPointX(it.lat, it.lng)) }.take(limit)
        } else {
            combined.take(limit)
        }
    }

    suspend fun quickRouteSummaryOffline(
        context: Context,
        origin: GeoPointX,
        dest: GeoPointX
    ): Pair<Double, Double>? = withContext(Dispatchers.Default) {
        val g = selectOrMergeGraphForPoints(context, listOf(origin, dest)) ?: return@withContext null
        val leg = g.routeLeg(origin, dest) ?: return@withContext null
        (leg.distanceM / 1000.0) to max(1.0, leg.durationSec / 60.0)
    }

    suspend fun computeOfflineRoute(
        context: Context,
        origin: GeoPointX,
        stops: List<PlannedStop>,
        settings: AppSettings,
        currentSocPct: Double?
    ): NavRouteX? = withContext(Dispatchers.Default) {
        if (stops.isEmpty()) return@withContext null
        val pts = listOf(origin) + stops.map { GeoPointX(it.lat, it.lng) }
        val graph = selectOrMergeGraphForPoints(context, pts) ?: return@withContext null
        graph.computeRoute(
            origin = origin,
            stops = stops,
            settings = settings,
            currentSocPct = currentSocPct
        )
    }

    private fun selectOrMergeGraphForPoints(
        context: Context,
        points: List<GeoPointX>
    ): OfflineRoutingGraph? {
        ensureLoadedFromDisk(context)
        val allGraphs = synchronized(lock) { loadedGraphs.values.toList() }
        if (allGraphs.isEmpty()) return null

        // 1. Prefer a single downloaded region that covers every waypoint
        val singleMatch = allGraphs.firstOrNull { g ->
            points.all { p -> g.containsPoint(p, marginKm = 1.2) }
        }
        if (singleMatch != null) return singleMatch

        // 2. Otherwise check overlapping regions across waypoint bounding box and stitch nodes on shared coordinates
        val minLat = points.minOf { it.lat } - 0.02
        val maxLat = points.maxOf { it.lat } + 0.02
        val minLng = points.minOf { it.lng } - 0.02
        val maxLng = points.maxOf { it.lng } + 0.02

        val candidates = allGraphs.filter { g ->
            g.meta.maxLat >= minLat && g.meta.minLat <= maxLat &&
                g.meta.maxLng >= minLng && g.meta.minLng <= maxLng
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        val nodeMap = HashMap<Long, Int>(16384)
        val latList = ArrayList<Double>(16384)
        val lngList = ArrayList<Double>(16384)
        val edgeList = ArrayList<OfflineEdge>(32768)
        val edgeKeys = HashSet<String>(32768)
        val placeList = ArrayList<OfflinePlaceEntry>(2048)

        fun mapNode(lat: Double, lng: Double): Int {
            val qLat = (lat * 1_000_000.0).roundToLong()
            val qLng = (lng * 1_000_000.0).roundToLong()
            val key = (qLat shl 32) xor (qLng and 0xFFFFFFFFL)
            nodeMap[key]?.let { return it }
            val idx = latList.size
            latList.add(lat)
            lngList.add(lng)
            nodeMap[key] = idx
            return idx
        }

        for (g in candidates) {
            val remapped = IntArray(g.nodeCount) { i -> mapNode(g.nodesLat[i], g.nodesLng[i]) }
            for (e in g.edges) {
                val u = remapped[e.fromIdx]
                val v = remapped[e.toIdx]
                val k = "$u>$v|${e.roadName}"
                if (edgeKeys.add(k)) {
                    edgeList.add(e.copy(fromIdx = u, toIdx = v))
                }
            }
            placeList.addAll(g.places)
        }
        val mergedMeta = OfflineRegionMeta(
            id = "merged",
            label = "Gabungan Offline",
            centerLat = (minLat + maxLat) / 2.0,
            centerLng = (minLng + maxLng) / 2.0,
            radiusKm = 15.0,
            minLat = candidates.minOf { it.meta.minLat },
            maxLat = candidates.maxOf { it.meta.maxLat },
            minLng = candidates.minOf { it.meta.minLng },
            maxLng = candidates.maxOf { it.meta.maxLng },
            nodeCount = latList.size,
            edgeCount = edgeList.size,
            placeCount = placeList.size
        )
        return OfflineRoutingGraph(
            meta = mergedMeta,
            nodesLat = latList.toDoubleArray(),
            nodesLng = lngList.toDoubleArray(),
            edges = edgeList,
            places = placeList
        )
    }

    suspend fun downloadFullOfflinePackage(
        context: Context,
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double,
        styleOption: MapStyleOption,
        cartoApiKey: String,
        includeTiles: Boolean,
        onProgress: (OfflineDownloadProgress) -> Unit,
        isCancelled: () -> Boolean
    ): OfflineRegionMeta = withContext(Dispatchers.IO) {
        val safeRadius = radiusKm.coerceIn(2.0, 12.0)
        val dLat = safeRadius / 110.54
        val cosLat = abs(cos(centerLat * PI / 180.0)).coerceIn(0.2, 1.0)
        val dLng = safeRadius / (111.32 * cosLat)
        val south = centerLat - dLat
        val north = centerLat + dLat
        val west = centerLng - dLng
        val east = centerLng + dLng

        suspend fun report(phase: String, done: Int, total: Int) {
            withContext(Dispatchers.Main) {
                onProgress(OfflineDownloadProgress(phase, done, total))
            }
        }

        report("Mengenali nama wilayah...", 0, 0)
        val geo = runCatching { RemoteApisService.reverseGeocode(centerLat, centerLng) }.getOrNull()
        val areaTitle = geo?.area?.takeIf { it.isNotBlank() }
            ?: geo?.road?.takeIf { it.isNotBlank() }
            ?: String.format(Locale.US, "Area %.3f, %.3f", centerLat, centerLng)
        val label = "$areaTitle • ${safeRadius.toInt()} km"

        if (isCancelled()) throw IllegalStateException("Unduhan dibatalkan.")

        report("Mengunduh jaringan jalan & nama jalan...", 0, 0)
        val nodesLat = HashMap<Long, Double>(32768)
        val nodesLng = HashMap<Long, Double>(32768)
        val ways = ArrayList<OsmWayInput>(8192)
        val rawPlaces = ArrayList<OfflinePlaceEntry>(512)

        val roadQuery = String.format(
            Locale.US,
            "[out:json][timeout:35];(way[\"highway\"~\"^(trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|road|trunk_link|primary_link|secondary_link|tertiary_link)$\"](%.5f,%.5f,%.5f,%.5f););out body;>;out skel qt;",
            south, west, north, east
        )
        val roadOk = queryOverpassStreaming(roadQuery) { reader ->
            parseOverpassElements(
                reader = reader,
                nodesLat = nodesLat,
                nodesLng = nodesLng,
                waysOut = ways,
                placesOut = rawPlaces
            )
        }
        if (!roadOk || ways.isEmpty()) {
            throw IllegalStateException("Gagal mengunduh data jalan dari OpenStreetMap. Periksa koneksi internet.")
        }
        if (isCancelled()) throw IllegalStateException("Unduhan dibatalkan.")

        report("Mengunduh nama area & POI (${ways.size} ruas jalan)...", 0, 0)
        val poiQuery = String.format(
            Locale.US,
            "[out:json][timeout:25];(node[\"name\"][\"place\"](%.5f,%.5f,%.5f,%.5f);node[\"name\"][\"amenity\"](%.5f,%.5f,%.5f,%.5f);node[\"name\"][\"shop\"](%.5f,%.5f,%.5f,%.5f);node[\"name\"][\"tourism\"](%.5f,%.5f,%.5f,%.5f);way[\"name\"][\"amenity\"](%.5f,%.5f,%.5f,%.5f);way[\"name\"][\"place\"](%.5f,%.5f,%.5f,%.5f););out center 600;",
            south, west, north, east,
            south, west, north, east,
            south, west, north, east,
            south, west, north, east,
            south, west, north, east,
            south, west, north, east
        )
        queryOverpassStreaming(poiQuery) { reader ->
            parseOverpassElements(
                reader = reader,
                nodesLat = null,
                nodesLng = null,
                waysOut = null,
                placesOut = rawPlaces
            )
        }
        if (isCancelled()) throw IllegalStateException("Unduhan dibatalkan.")

        report("Menyusun grafik rute offline (${ways.size} jalan, ${rawPlaces.size} tempat)...", 0, 0)
        val regionId = String.format(
            Locale.US,
            "reg_%.3f_%.3f_%dkm_%d",
            centerLat,
            centerLng,
            safeRadius.toInt(),
            System.currentTimeMillis() % 100_000
        ).replace("-", "m").replace(".", "_")

        var tileCount = 0
        if (includeTiles) {
            val tileStyle = if (styleOption == MapStyleOption.OSM_STANDARD || styleOption == MapStyleOption.SATELLITE) {
                MapStyleOption.CARTO_VOYAGER
            } else {
                styleOption
            }
            tileCount = TileCacheService.downloadArea(
                context = context,
                styleId = tileStyle.id,
                urlTemplate = tileStyle.tileUrlTemplate,
                lat = centerLat,
                lng = centerLng,
                radiusKm = safeRadius,
                minZoom = 11,
                maxZoom = 16,
                cartoApiKey = cartoApiKey,
                onProgress = { d, t ->
                    onProgress(
                        OfflineDownloadProgress(
                            phaseLabel = "Mengunduh tile ${tileStyle.label} ($d/$t)...",
                            done = d,
                            total = t
                        )
                    )
                },
                isCancelled = isCancelled
            )
        }

        val graph = OfflineRoutingGraph.buildFromOsm(
            id = regionId,
            label = label,
            centerLat = centerLat,
            centerLng = centerLng,
            radiusKm = safeRadius,
            minLat = south,
            maxLat = north,
            minLng = west,
            maxLng = east,
            osmWays = ways,
            osmNodesLat = nodesLat,
            osmNodesLng = nodesLng,
            rawPlaces = rawPlaces,
            tileCount = tileCount
        )

        val dir = File(rootDir(context), regionId).apply { mkdirs() }
        val binFile = File(dir, "graph.bin")
        binFile.outputStream().use { graph.writeBinary(it) }
        val bytes = binFile.length()
        val finalMeta = graph.meta.copy(totalBytes = bytes)
        File(dir, "meta.json").writeText(json.encodeToString(OfflineRegionMeta.serializer(), finalMeta))

        val loadedGraph = OfflineRoutingGraph(
            meta = finalMeta,
            nodesLat = graph.nodesLat,
            nodesLng = graph.nodesLng,
            edges = graph.edges,
            places = graph.places
        )
        synchronized(lock) {
            loadedGraphs[finalMeta.id] = loadedGraph
            diskScanned = true
        }
        finalMeta
    }

    private fun queryOverpassStreaming(query: String, parser: (JsonReader) -> Unit): Boolean {
        val body = "data=${URLEncoder.encode(query, "UTF-8")}".toByteArray(Charsets.UTF_8)
        for (endpoint in OVERPASS_MIRRORS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 40_000
                    setRequestProperty("User-Agent", "DashboardAppEV/1.0")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                conn.outputStream.use { it.write(body) }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    JsonReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                        parser(reader)
                    }
                    return true
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }
        return false
    }

    private fun parseOverpassElements(
        reader: JsonReader,
        nodesLat: MutableMap<Long, Double>?,
        nodesLng: MutableMap<Long, Double>?,
        waysOut: MutableList<OsmWayInput>?,
        placesOut: MutableList<OfflinePlaceEntry>?
    ) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "elements" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        parseSingleElement(reader, nodesLat, nodesLng, waysOut, placesOut)
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun parseSingleElement(
        reader: JsonReader,
        nodesLat: MutableMap<Long, Double>?,
        nodesLng: MutableMap<Long, Double>?,
        waysOut: MutableList<OsmWayInput>?,
        placesOut: MutableList<OfflinePlaceEntry>?
    ) {
        var type = ""
        var id = 0L
        var lat = Double.NaN
        var lon = Double.NaN
        var centerLat = Double.NaN
        var centerLon = Double.NaN
        var wayNodes: MutableList<Long>? = null
        var tags: HashMap<String, String>? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type" -> type = reader.nextString()
                "id" -> id = reader.nextLong()
                "lat" -> lat = reader.nextDouble()
                "lon" -> lon = reader.nextDouble()
                "center" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "lat" -> centerLat = reader.nextDouble()
                            "lon" -> centerLon = reader.nextDouble()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                "nodes" -> {
                    if (waysOut != null) {
                        val list = ArrayList<Long>(16)
                        reader.beginArray()
                        while (reader.hasNext()) list.add(reader.nextLong())
                        reader.endArray()
                        wayNodes = list
                    } else {
                        reader.skipValue()
                    }
                }
                "tags" -> {
                    val map = HashMap<String, String>(8)
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val k = reader.nextName()
                        val v = if (reader.peek() == android.util.JsonToken.STRING) {
                            reader.nextString()
                        } else {
                            reader.skipValue()
                            ""
                        }
                        map[k] = v
                    }
                    reader.endObject()
                    tags = map
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (type == "node" && !lat.isNaN() && !lon.isNaN()) {
            if (nodesLat != null && nodesLng != null) {
                nodesLat[id] = lat
                nodesLng[id] = lon
            }
            if (placesOut != null && tags != null) {
                extractPlaceFromTags(tags, lat, lon, placesOut)
            }
        } else if (type == "way") {
            val wn = wayNodes
            if (waysOut != null && wn != null && wn.size >= 2 && tags != null) {
                val hw = tags["highway"]
                val roadClass = OfflineRoadClass.fromOsmHighway(hw)
                if (roadClass != null) {
                    val roadName = tags["name"]?.trim()
                        ?: tags["ref"]?.trim()
                        ?: ""
                    val onewayTag = tags["oneway"]?.lowercase(Locale.US)
                    val junctionTag = tags["junction"]?.lowercase(Locale.US)
                    val (fwd, bwd) = when {
                        onewayTag == "-1" || onewayTag == "reverse" -> false to true
                        onewayTag == "yes" || onewayTag == "true" || onewayTag == "1" || junctionTag == "roundabout" -> true to false
                        else -> true to true
                    }
                    waysOut.add(OsmWayInput(wn, roadName, roadClass, fwd, bwd))
                }
            }
            if (placesOut != null && tags != null && !centerLat.isNaN() && !centerLon.isNaN()) {
                extractPlaceFromTags(tags, centerLat, centerLon, placesOut)
            }
        }
    }

    private fun extractPlaceFromTags(
        tags: Map<String, String>,
        lat: Double,
        lng: Double,
        out: MutableList<OfflinePlaceEntry>
    ) {
        val name = tags["name"]?.trim() ?: return
        if (name.isEmpty()) return
        val placeTag = tags["place"]
        val area = tags["addr:suburb"]
            ?: tags["addr:village"]
            ?: tags["addr:city"]
            ?: ""
        if (!placeTag.isNullOrBlank()) {
            out.add(
                OfflinePlaceEntry(
                    name = name,
                    category = translatePlaceTag(placeTag),
                    area = area,
                    lat = lat,
                    lng = lng,
                    kind = "area"
                )
            )
            return
        }
        val poiTag = tags["amenity"] ?: tags["shop"] ?: tags["tourism"] ?: ""
        if (poiTag.isNotEmpty()) {
            out.add(
                OfflinePlaceEntry(
                    name = name,
                    category = poiTag.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    area = area,
                    lat = lat,
                    lng = lng,
                    kind = "poi"
                )
            )
        }
    }

    private fun translatePlaceTag(tag: String): String = when (tag.lowercase(Locale.US)) {
        "city" -> "Kota"
        "town" -> "Kecamatan / Kota"
        "suburb" -> "Kelurahan / Wilayah"
        "village" -> "Desa"
        "neighbourhood", "quarter" -> "Lingkungan"
        "hamlet" -> "Dusun"
        else -> "Area"
    }
}
