package com.dashboard.app.dashboard_app.engine

import com.dashboard.app.dashboard_app.model.AppSettings
import com.dashboard.app.dashboard_app.model.ElevationSample
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.NavRouteAltOption
import com.dashboard.app.dashboard_app.model.NavRouteX
import com.dashboard.app.dashboard_app.model.PlannedStop
import com.dashboard.app.dashboard_app.model.RouteStepX
import com.dashboard.app.dashboard_app.model.SavedPlace
import com.dashboard.app.dashboard_app.model.WaypointArrivalSummary
import com.dashboard.app.dashboard_app.service.ReverseGeocodeResult
import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class OfflineRoadClass(val speedKmh: Double, val penalty: Double) {
    TRUNK(52.0, 1.0),
    PRIMARY(44.0, 1.02),
    SECONDARY(38.0, 1.05),
    TERTIARY(31.0, 1.08),
    UNCLASSIFIED(25.0, 1.15),
    RESIDENTIAL(21.0, 1.22),
    SERVICE(15.0, 1.35);

    companion object {
        fun fromOsmHighway(highway: String?): OfflineRoadClass? = when (highway?.lowercase(Locale.US)) {
            "trunk", "trunk_link" -> TRUNK
            "primary", "primary_link" -> PRIMARY
            "secondary", "secondary_link" -> SECONDARY
            "tertiary", "tertiary_link" -> TERTIARY
            "unclassified", "road" -> UNCLASSIFIED
            "residential", "living_street" -> RESIDENTIAL
            "service" -> SERVICE
            else -> null
        }

        fun fromOrdinalSafe(ord: Int): OfflineRoadClass =
            entries.getOrElse(ord) { UNCLASSIFIED }
    }
}

data class OsmWayInput(
    val nodeIds: List<Long>,
    val roadName: String,
    val roadClass: OfflineRoadClass,
    val onewayForward: Boolean,
    val onewayBackward: Boolean
)

data class OfflinePlaceEntry(
    val name: String,
    val category: String,
    val area: String,
    val lat: Double,
    val lng: Double,
    val kind: String // "area", "poi", "street"
)

@Serializable
data class OfflineRegionMeta(
    val id: String,
    val label: String,
    val centerLat: Double,
    val centerLng: Double,
    val radiusKm: Double,
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    val nodeCount: Int,
    val edgeCount: Int,
    val placeCount: Int,
    val tileCount: Int = 0,
    val totalBytes: Long = 0L,
    val downloadedAtMs: Long = System.currentTimeMillis()
)

data class OfflineEdge(
    val fromIdx: Int,
    val toIdx: Int,
    val roadName: String,
    val roadClass: OfflineRoadClass,
    val lengthM: Double,
    val costSec: Double,
    val shape: List<GeoPointX>
)

data class OfflineEdgeSnap(
    val edgeIdx: Int,
    val segIdx: Int,
    val t: Double,
    val point: GeoPointX,
    val distM: Double,
    val distAlongEdgeM: Double
)

data class OfflineRouteLeg(
    val geometry: List<GeoPointX>,
    val steps: List<RouteStepX>,
    val distanceM: Double,
    val durationSec: Double
)

class OfflineRoutingGraph(
    val meta: OfflineRegionMeta,
    val nodesLat: DoubleArray,
    val nodesLng: DoubleArray,
    val edges: List<OfflineEdge>,
    val places: List<OfflinePlaceEntry>
) {
    val nodeCount: Int get() = nodesLat.size
    val adj: Array<IntArray>
    private val reverseTwinIdx: IntArray
    private val spatialGrid = HashMap<Long, MutableList<Int>>()
    private val areaPlaces: List<OfflinePlaceEntry> = places.filter { it.kind == "area" }

    init {
        val adjLists = Array(nodeCount) { mutableListOf<Int>() }
        edges.forEachIndexed { idx, e ->
            if (e.fromIdx in 0 until nodeCount) {
                adjLists[e.fromIdx].add(idx)
            }
            addEdgeToGrid(idx, e)
        }
        adj = Array(nodeCount) { i -> adjLists[i].toIntArray() }

        reverseTwinIdx = IntArray(edges.size) { -1 }
        edges.forEachIndexed { idx, e ->
            if (reverseTwinIdx[idx] >= 0) return@forEachIndexed
            if (e.toIdx in 0 until nodeCount) {
                for (cand in adj[e.toIdx]) {
                    val ce = edges[cand]
                    if (ce.toIdx == e.fromIdx && abs(ce.lengthM - e.lengthM) < 0.8 && ce.roadName == e.roadName) {
                        reverseTwinIdx[idx] = cand
                        reverseTwinIdx[cand] = idx
                        break
                    }
                }
            }
        }
    }

    private fun cellKey(lat: Double, lng: Double): Long {
        val cy = floor(lat / GRID_CELL_DEG).toLong()
        val cx = floor(lng / GRID_CELL_DEG).toLong()
        return (cy shl 32) xor (cx and 0xFFFFFFFFL)
    }

    private fun addEdgeToGrid(edgeIdx: Int, edge: OfflineEdge) {
        if (edge.shape.isEmpty()) return
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        for (p in edge.shape) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lng < minLng) minLng = p.lng
            if (p.lng > maxLng) maxLng = p.lng
        }
        val y0 = floor(minLat / GRID_CELL_DEG).toInt()
        val y1 = floor(maxLat / GRID_CELL_DEG).toInt()
        val x0 = floor(minLng / GRID_CELL_DEG).toInt()
        val x1 = floor(maxLng / GRID_CELL_DEG).toInt()
        for (cy in y0..y1) {
            for (cx in x0..x1) {
                val key = (cy.toLong() shl 32) xor (cx.toLong() and 0xFFFFFFFFL)
                spatialGrid.getOrPut(key) { mutableListOf() }.add(edgeIdx)
            }
        }
    }

    fun containsPoint(point: GeoPointX, marginKm: Double = 0.8): Boolean {
        val dLat = marginKm / 110.54
        val cosLat = abs(cos(point.lat * PI / 180.0)).coerceIn(0.2, 1.0)
        val dLng = marginKm / (111.32 * cosLat)
        return point.lat in (meta.minLat - dLat)..(meta.maxLat + dLat) &&
            point.lng in (meta.minLng - dLng)..(meta.maxLng + dLng)
    }

    fun findNearestEdge(
        point: GeoPointX,
        maxSearchKm: Double = 1.8,
        requireNamed: Boolean = false
    ): OfflineEdgeSnap? {
        if (edges.isEmpty()) return null
        val cosLat = cos(point.lat * PI / 180.0).coerceIn(0.2, 1.0)
        val dLat = maxSearchKm / 110.54
        val dLng = maxSearchKm / (111.32 * cosLat)
        val y0 = floor((point.lat - dLat) / GRID_CELL_DEG).toInt()
        val y1 = floor((point.lat + dLat) / GRID_CELL_DEG).toInt()
        val x0 = floor((point.lng - dLng) / GRID_CELL_DEG).toInt()
        val x1 = floor((point.lng + dLng) / GRID_CELL_DEG).toInt()

        val seen = HashSet<Int>(256)
        var best: OfflineEdgeSnap? = null
        var bestDistM = maxSearchKm * 1000.0

        for (cy in y0..y1) {
            for (cx in x0..x1) {
                val key = (cy.toLong() shl 32) xor (cx.toLong() and 0xFFFFFFFFL)
                val bucket = spatialGrid[key] ?: continue
                for (edgeIdx in bucket) {
                    if (!seen.add(edgeIdx)) continue
                    val e = edges[edgeIdx]
                    if (requireNamed && e.roadName.isBlank()) continue
                    if (e.shape.size < 2) continue
                    var cumM = 0.0
                    for (s in 0 until e.shape.lastIndex) {
                        val a = e.shape[s]
                        val b = e.shape[s + 1]
                        val bx = (b.lng - a.lng) * 111320.0 * cosLat
                        val by = (b.lat - a.lat) * 110540.0
                        val px = (point.lng - a.lng) * 111320.0 * cosLat
                        val py = (point.lat - a.lat) * 110540.0
                        val segLen = hypot(bx, by)
                        val len2 = bx * bx + by * by
                        val t = if (len2 > 1e-6) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
                        val distM = hypot(px - t * bx, py - t * by)
                        if (distM < bestDistM) {
                            bestDistM = distM
                            best = OfflineEdgeSnap(
                                edgeIdx = edgeIdx,
                                segIdx = s,
                                t = t,
                                point = GeoPointX(
                                    lat = a.lat + (b.lat - a.lat) * t,
                                    lng = a.lng + (b.lng - a.lng) * t
                                ),
                                distM = distM,
                                distAlongEdgeM = cumM + segLen * t
                            )
                        }
                        cumM += segLen
                    }
                }
            }
        }
        return best
    }

    fun reverseGeocode(point: GeoPointX): ReverseGeocodeResult? {
        val roadSnap = findNearestEdge(point, maxSearchKm = 0.35, requireNamed = true)
            ?: findNearestEdge(point, maxSearchKm = 0.35, requireNamed = false)
        val road = roadSnap?.let { edges[it.edgeIdx].roadName }.orEmpty()

        var bestArea = ""
        var bestAreaKm = 8.0
        for (a in areaPlaces) {
            val d = RoutePhysics.haversineKm(point, GeoPointX(a.lat, a.lng))
            if (d < bestAreaKm) {
                bestAreaKm = d
                bestArea = a.name
            }
        }
        if (bestArea.isEmpty() && road.isEmpty()) return null
        return ReverseGeocodeResult(road = road, area = bestArea)
    }

    fun searchPlaces(
        query: String,
        biasOrigin: GeoPointX? = null,
        limit: Int = 15
    ): List<SavedPlace> {
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        data class ScoredPlace(val score: Double, val place: OfflinePlaceEntry)

        val matches = ArrayList<ScoredPlace>(32)
        for (p in places) {
            val lowName = p.name.lowercase(Locale.US)
            val lowArea = p.area.lowercase(Locale.US)
            val combined = if (lowArea.isNotEmpty()) "$lowName $lowArea" else lowName
            if (!tokens.all { combined.contains(it) }) continue

            var score = 0.0
            if (lowName == q) score += 100.0
            else if (lowName.startsWith(q)) score += 65.0
            else if (lowName.contains(q)) score += 40.0
            else score += 20.0

            when (p.kind) {
                "poi" -> score += 8.0
                "area" -> score += 6.0
                "street" -> score += 3.0
            }
            if (biasOrigin != null) {
                val distKm = RoutePhysics.haversineKm(biasOrigin, GeoPointX(p.lat, p.lng))
                score -= min(18.0, distKm * 0.9)
            }
            matches.add(ScoredPlace(score, p))
        }
        val now = System.currentTimeMillis()
        return matches
            .sortedByDescending { it.score }
            .take(limit)
            .mapIndexed { idx, sp ->
                val p = sp.place
                val suffix = if (p.area.isNotBlank() && !p.name.contains(p.area, ignoreCase = true)) {
                    "${p.category} • ${p.area}"
                } else p.category
                SavedPlace(
                    id = "off_${p.kind}_${idx}_${p.name.hashCode()}",
                    name = p.name,
                    category = suffix,
                    lat = p.lat,
                    lng = p.lng,
                    createdAtMs = now
                )
            }
    }

    fun routeLeg(from: GeoPointX, to: GeoPointX): OfflineRouteLeg? {
        if (nodeCount == 0 || edges.isEmpty()) return null
        val startSnap = findNearestEdge(from, maxSearchKm = 2.0) ?: return null
        val endSnap = findNearestEdge(to, maxSearchKm = 2.0) ?: return null

        val startEdge = edges[startSnap.edgeIdx]
        val endEdge = edges[endSnap.edgeIdx]

        // Same directed edge direct case
        if (startSnap.edgeIdx == endSnap.edgeIdx && startSnap.distAlongEdgeM <= endSnap.distAlongEdgeM) {
            val sliced = sliceEdgeShape(
                shape = startEdge.shape,
                sSeg = startSnap.segIdx,
                sT = startSnap.t,
                eSeg = endSnap.segIdx,
                eT = endSnap.t
            )
            return buildLegFromFragments(
                origin = from,
                dest = to,
                fragments = listOf(RouteFragment(startEdge.roadName, startEdge.roadClass, sliced))
            )
        }
        // Reverse twin same road case
        val startRevIdx = reverseTwinIdx[startSnap.edgeIdx]
        if (startRevIdx == endSnap.edgeIdx) {
            val twinLen = endEdge.lengthM
            val revStartDist = (twinLen - startSnap.distAlongEdgeM).coerceIn(0.0, twinLen)
            if (revStartDist <= endSnap.distAlongEdgeM) {
                val slicedRev = sliceEdgeShapeByDist(endEdge.shape, revStartDist, endSnap.distAlongEdgeM)
                return buildLegFromFragments(
                    origin = from,
                    dest = to,
                    fragments = listOf(RouteFragment(endEdge.roadName, endEdge.roadClass, slicedRev))
                )
            }
        }

        // Virtual start branches
        data class StartBranch(val nodeIdx: Int, val costSec: Double, val fragment: RouteFragment)
        val startBranches = mutableListOf<StartBranch>()
        run {
            val remDist = (startEdge.lengthM - startSnap.distAlongEdgeM).coerceAtLeast(0.5)
            val frac = (remDist / startEdge.lengthM.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            val tailShape = sliceEdgeShapeToEnd(startEdge.shape, startSnap.segIdx, startSnap.t)
            startBranches.add(
                StartBranch(
                    nodeIdx = startEdge.toIdx,
                    costSec = startEdge.costSec * frac,
                    fragment = RouteFragment(startEdge.roadName, startEdge.roadClass, tailShape)
                )
            )
            if (startRevIdx >= 0) {
                val revEdge = edges[startRevIdx]
                val distBack = startSnap.distAlongEdgeM.coerceAtLeast(0.5)
                val fracBack = (distBack / revEdge.lengthM.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                val revStartDist = (revEdge.lengthM - startSnap.distAlongEdgeM).coerceIn(0.0, revEdge.lengthM)
                val backShape = sliceEdgeShapeByDist(revEdge.shape, revStartDist, revEdge.lengthM)
                startBranches.add(
                    StartBranch(
                        nodeIdx = revEdge.toIdx,
                        costSec = revEdge.costSec * fracBack,
                        fragment = RouteFragment(revEdge.roadName, revEdge.roadClass, backShape)
                    )
                )
            }
        }

        // Virtual end entry candidates (incoming nodeIdx -> goal cost & trailing fragment)
        data class EndTarget(val nodeIdx: Int, val costSec: Double, val fragment: RouteFragment)
        val endTargets = HashMap<Int, EndTarget>(4)
        run {
            val headDist = endSnap.distAlongEdgeM.coerceAtLeast(0.5)
            val frac = (headDist / endEdge.lengthM.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            val headShape = sliceEdgeShapeFromStart(endEdge.shape, endSnap.segIdx, endSnap.t)
            endTargets[endEdge.fromIdx] = EndTarget(
                nodeIdx = endEdge.fromIdx,
                costSec = endEdge.costSec * frac,
                fragment = RouteFragment(endEdge.roadName, endEdge.roadClass, headShape)
            )
            val endRevIdx = reverseTwinIdx[endSnap.edgeIdx]
            if (endRevIdx >= 0) {
                val revEdge = edges[endRevIdx]
                val revEndDist = (revEdge.lengthM - endSnap.distAlongEdgeM).coerceIn(0.0, revEdge.lengthM)
                val revFrac = (revEndDist / revEdge.lengthM.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                val revHeadShape = sliceEdgeShapeByDist(revEdge.shape, 0.0, revEndDist)
                val revCost = revEdge.costSec * revFrac
                val prev = endTargets[revEdge.fromIdx]
                if (prev == null || revCost < prev.costSec) {
                    endTargets[revEdge.fromIdx] = EndTarget(
                        nodeIdx = revEdge.fromIdx,
                        costSec = revCost,
                        fragment = RouteFragment(revEdge.roadName, revEdge.roadClass, revHeadShape)
                    )
                }
            }
        }

        // A* Search on intersection graph
        val gScore = DoubleArray(nodeCount) { Double.POSITIVE_INFINITY }
        val parentNode = IntArray(nodeCount) { -1 }
        val parentEdge = IntArray(nodeCount) { -1 }
        val startBranchUsed = IntArray(nodeCount) { -1 }
        val closed = BooleanArray(nodeCount)
        val pq = MinHeap()

        val cosLat = cos(to.lat * PI / 180.0).coerceIn(0.2, 1.0)
        fun heuristicSec(nodeIdx: Int): Double {
            val dx = (to.lng - nodesLng[nodeIdx]) * 111320.0 * cosLat
            val dy = (to.lat - nodesLat[nodeIdx]) * 110540.0
            return hypot(dx, dy) / HEURISTIC_SPEED_MS
        }

        startBranches.forEachIndexed { bIdx, sb ->
            if (sb.costSec < gScore[sb.nodeIdx]) {
                gScore[sb.nodeIdx] = sb.costSec
                parentNode[sb.nodeIdx] = -2 - bIdx
                startBranchUsed[sb.nodeIdx] = bIdx
                pq.push(sb.nodeIdx, sb.costSec + heuristicSec(sb.nodeIdx))
            }
        }

        var bestGoalNode = -1
        var bestTotalCost = Double.POSITIVE_INFINITY
        var iterations = 0

        while (!pq.isEmpty && iterations < MAX_ASTAR_ITERATIONS) {
            iterations++
            val u = pq.popNode()
            val curF = pq.lastPoppedPriority
            if (curF > bestTotalCost) break
            if (closed[u]) continue
            closed[u] = true

            val directEnd = endTargets[u]
            if (directEnd != null) {
                val candTotal = gScore[u] + directEnd.costSec
                if (candTotal < bestTotalCost) {
                    bestTotalCost = candTotal
                    bestGoalNode = u
                }
            }

            val outEdges = adj[u]
            for (i in outEdges.indices) {
                val eIdx = outEdges[i]
                val e = edges[eIdx]
                val v = e.toIdx
                if (closed[v]) continue
                val tentativeG = gScore[u] + e.costSec
                if (tentativeG < gScore[v]) {
                    gScore[v] = tentativeG
                    parentNode[v] = u
                    parentEdge[v] = eIdx
                    pq.push(v, tentativeG + heuristicSec(v))
                }
            }
        }

        if (bestGoalNode < 0) return null

        val edgePath = mutableListOf<Int>()
        var cur = bestGoalNode
        while (parentNode[cur] >= 0) {
            edgePath.add(parentEdge[cur])
            cur = parentNode[cur]
        }
        edgePath.reverse()

        val sbIdx = startBranchUsed[cur].takeIf { it in startBranches.indices } ?: 0
        val fragments = ArrayList<RouteFragment>(edgePath.size + 2)
        fragments.add(startBranches[sbIdx].fragment)
        for (eIdx in edgePath) {
            val e = edges[eIdx]
            fragments.add(RouteFragment(e.roadName, e.roadClass, e.shape))
        }
        val et = endTargets[bestGoalNode]
        if (et != null) {
            fragments.add(et.fragment)
        }
        return buildLegFromFragments(from, to, fragments)
    }

    fun computeRoute(
        origin: GeoPointX,
        stops: List<PlannedStop>,
        settings: AppSettings,
        currentSocPct: Double?,
        fallbackElevM: Double = 25.0
    ): NavRouteX? {
        if (stops.isEmpty()) return null
        val waypoints = listOf(origin) + stops.map { GeoPointX(it.lat, it.lng) }
        val allGeometry = mutableListOf<GeoPointX>()
        val allSteps = mutableListOf<RouteStepX>()
        val legSummaries = mutableListOf<WaypointArrivalSummary>()

        var totalDistM = 0.0
        var totalDurSec = 0.0
        val totalPackWh = settings.b1NominalV * settings.b1CapacityAh +
            if (settings.savedEsp2Address != null) settings.b2NominalV * settings.b2CapacityAh else 0.0
        var runningSoc = currentSocPct ?: 100.0

        for (i in 0 until stops.size) {
            val leg = routeLeg(waypoints[i], waypoints[i + 1]) ?: return null
            if (allGeometry.isEmpty()) {
                allGeometry.addAll(leg.geometry)
            } else {
                allGeometry.addAll(leg.geometry.drop(1))
            }
            // Drop intermediate "arrive" if another stop follows
            if (i < stops.lastIndex && leg.steps.isNotEmpty() && leg.steps.last().maneuverType == "arrive") {
                allSteps.addAll(leg.steps.dropLast(1))
            } else if (i > 0 && leg.steps.isNotEmpty() && leg.steps.first().maneuverType == "depart") {
                allSteps.addAll(leg.steps.drop(1))
            } else {
                allSteps.addAll(leg.steps)
            }

            totalDistM += leg.distanceM
            totalDurSec += leg.durationSec

            val legKm = leg.distanceM / 1000.0
            val legMin = leg.durationSec / 60.0
            val estWh = legKm * settings.refWhPerKm * settings.routeCalFactor
            val socDrop = if (totalPackWh > 0.0) (estWh / totalPackWh) * 100.0 else 0.0
            runningSoc = (runningSoc - socDrop).coerceIn(0.0, 100.0)
            val stopMeta = stops[i]
            legSummaries.add(
                WaypointArrivalSummary(
                    label = stopMeta.label,
                    legDistKm = legKm,
                    legEtaMin = legMin,
                    arrivalSocPct = if (currentSocPct != null) runningSoc else null,
                    isChargingStop = stopMeta.isSpklu
                )
            )
            if (stopMeta.isSpklu && currentSocPct != null) {
                runningSoc = max(runningSoc, 85.0)
            }
        }

        val downsampled = RoutePhysics.downsamplePoints(allGeometry, maxSamples = 60)
        val elevs = List(downsampled.size) { fallbackElevM }
        val physics = RoutePhysics.buildElevationAndEnergy(
            sampledPoints = downsampled,
            elevations = elevs,
            totalDistanceKm = totalDistM / 1000.0,
            durationMin = totalDurSec / 60.0,
            bikeKg = settings.bikeWeightKg,
            riderKg = settings.riderWeightKg,
            loadKg = settings.loadWeightKg,
            calFactor = settings.routeCalFactor,
            totalPackWh = totalPackWh,
            currentSocPct = currentSocPct
        )
        val primaryOpt = NavRouteAltOption(
            geometry = allGeometry,
            steps = allSteps,
            distanceM = totalDistM,
            durationSec = totalDurSec,
            elevationSamples = physics.samples,
            energyEstimate = physics.estimate,
            hillSegments = emptyList(),
            legSummaries = legSummaries
        )
        return NavRouteX(
            geometry = allGeometry,
            steps = allSteps,
            distanceM = totalDistM,
            durationSec = totalDurSec,
            elevationSamples = physics.samples,
            energyEstimate = physics.estimate,
            legSummaries = legSummaries,
            hillSegments = emptyList(),
            destWeather = null,
            selectedAlternativeIndex = 0,
            alternativeOptions = listOf(primaryOpt)
        )
    }

    fun writeBinary(out: OutputStream) {
        val dos = DataOutputStream(BufferedOutputStream(out, 65536))
        val pool = ArrayList<String>(256)
        val poolIdx = HashMap<String, Int>(256)
        fun intern(s: String): Int = poolIdx.getOrPut(s) {
            val id = pool.size
            pool.add(s)
            id
        }

        // Pre-intern all strings
        for (e in edges) intern(e.roadName)
        for (p in places) {
            intern(p.name)
            intern(p.category)
            intern(p.area)
            intern(p.kind)
        }

        dos.writeInt(BINARY_MAGIC)
        dos.writeInt(BINARY_VERSION)
        dos.writeUTF(meta.id)
        dos.writeUTF(meta.label)
        dos.writeDouble(meta.centerLat)
        dos.writeDouble(meta.centerLng)
        dos.writeDouble(meta.radiusKm)
        dos.writeDouble(meta.minLat)
        dos.writeDouble(meta.maxLat)
        dos.writeDouble(meta.minLng)
        dos.writeDouble(meta.maxLng)
        dos.writeInt(meta.tileCount)
        dos.writeLong(meta.downloadedAtMs)

        dos.writeInt(pool.size)
        for (s in pool) dos.writeUTF(s)

        dos.writeInt(nodeCount)
        for (i in 0 until nodeCount) {
            dos.writeDouble(nodesLat[i])
            dos.writeDouble(nodesLng[i])
        }

        dos.writeInt(edges.size)
        for (e in edges) {
            dos.writeInt(e.fromIdx)
            dos.writeInt(e.toIdx)
            dos.writeInt(intern(e.roadName))
            dos.writeByte(e.roadClass.ordinal)
            dos.writeDouble(e.lengthM)
            dos.writeDouble(e.costSec)
            dos.writeInt(e.shape.size)
            for (pt in e.shape) {
                dos.writeDouble(pt.lat)
                dos.writeDouble(pt.lng)
            }
        }

        dos.writeInt(places.size)
        for (p in places) {
            dos.writeInt(intern(p.name))
            dos.writeInt(intern(p.category))
            dos.writeInt(intern(p.area))
            dos.writeInt(intern(p.kind))
            dos.writeDouble(p.lat)
            dos.writeDouble(p.lng)
        }
        dos.flush()
    }

    private data class RouteFragment(
        val roadName: String,
        val roadClass: OfflineRoadClass,
        val points: List<GeoPointX>
    )

    private fun buildLegFromFragments(
        origin: GeoPointX,
        dest: GeoPointX,
        fragments: List<RouteFragment>
    ): OfflineRouteLeg {
        val fullGeom = mutableListOf<GeoPointX>()
        fullGeom.add(origin)

        val validFrags = mutableListOf<RouteFragment>()
        for (frag in fragments) {
            if (frag.points.isEmpty()) continue
            val cleanPts = ArrayList<GeoPointX>(frag.points.size)
            for (p in frag.points) {
                val last = fullGeom.lastOrNull()
                if (last == null || RoutePhysics.haversineKm(last, p) > 0.0005) {
                    fullGeom.add(p)
                    cleanPts.add(p)
                }
            }
            if (frag.points.size >= 2) {
                validFrags.add(frag)
            } else if (cleanPts.isNotEmpty()) {
                validFrags.add(frag)
            }
        }
        if (RoutePhysics.haversineKm(fullGeom.last(), dest) > 0.0005) {
            fullGeom.add(dest)
        }

        // Compute distance & duration per fragment
        val fragDists = DoubleArray(validFrags.size)
        val fragDurs = DoubleArray(validFrags.size)
        var totalDistM = 0.0
        var totalDurSec = 0.0
        for (i in validFrags.indices) {
            val pts = validFrags[i].points
            var dM = 0.0
            for (j in 0 until pts.lastIndex) {
                dM += RoutePhysics.haversineKm(pts[j], pts[j + 1]) * 1000.0
            }
            val spdMs = (validFrags[i].roadClass.speedKmh / 3.6).coerceAtLeast(3.0)
            val tSec = dM / spdMs
            fragDists[i] = dM
            fragDurs[i] = tSec
            totalDistM += dM
            totalDurSec += tSec
        }

        val steps = mutableListOf<RouteStepX>()
        if (validFrags.isNotEmpty()) {
            var curRoad = validFrags[0].roadName
            var curType = "depart"
            var curMod: String? = null
            var curLoc = validFrags[0].points.first()
            var accDist = fragDists[0]
            var accDur = fragDurs[0]

            for (i in 1 until validFrags.size) {
                val prevPts = validFrags[i - 1].points
                val nextPts = validFrags[i].points
                val nextRoad = validFrags[i].roadName

                val inBear = exitBearing(prevPts)
                val outBear = entryBearing(nextPts)
                val delta = if (inBear != null && outBear != null) {
                    RoutePhysics.normalizeSignedDeg(outBear - inBear)
                } else 0.0

                val (mType, mMod) = classifyTurn(delta)
                val roadChanged = nextRoad.isNotBlank() &&
                    !nextRoad.equals(curRoad, ignoreCase = true)
                val isSignificantTurn = mMod != null
                if (isSignificantTurn || (roadChanged && abs(delta) >= 12.0 && accDist >= 25.0)) {
                    steps.add(
                        RouteStepX(
                            instruction = RoutePhysics.translateOsrmManeuver(curType, curMod, curRoad),
                            roadName = curRoad,
                            distanceM = accDist,
                            durationSec = accDur,
                            maneuverType = curType,
                            modifier = curMod,
                            location = curLoc
                        )
                    )
                    curRoad = nextRoad.ifBlank { curRoad }
                    curType = mType
                    curMod = mMod
                    curLoc = nextPts.first()
                    accDist = fragDists[i]
                    accDur = fragDurs[i]
                } else {
                    if (curRoad.isBlank() && nextRoad.isNotBlank()) {
                        curRoad = nextRoad
                    }
                    accDist += fragDists[i]
                    accDur += fragDurs[i]
                }
            }
            steps.add(
                RouteStepX(
                    instruction = RoutePhysics.translateOsrmManeuver(curType, curMod, curRoad),
                    roadName = curRoad,
                    distanceM = accDist,
                    durationSec = accDur,
                    maneuverType = curType,
                    modifier = curMod,
                    location = curLoc
                )
            )
        }
        steps.add(
            RouteStepX(
                instruction = "Tiba di tujuan",
                roadName = validFrags.lastOrNull()?.roadName ?: "",
                distanceM = 0.0,
                durationSec = 0.0,
                maneuverType = "arrive",
                modifier = null,
                location = dest
            )
        )

        return OfflineRouteLeg(
            geometry = fullGeom,
            steps = steps,
            distanceM = totalDistM,
            durationSec = totalDurSec
        )
    }

    private fun entryBearing(pts: List<GeoPointX>): Double? {
        if (pts.size < 2) return null
        val p0 = pts.first()
        for (i in 1 until pts.size) {
            if (RoutePhysics.haversineKm(p0, pts[i]) >= 0.005 || i == pts.lastIndex) {
                return RoutePhysics.bearingDeg(p0, pts[i])
            }
        }
        return null
    }

    private fun exitBearing(pts: List<GeoPointX>): Double? {
        if (pts.size < 2) return null
        val pEnd = pts.last()
        for (i in pts.lastIndex - 1 downTo 0) {
            if (RoutePhysics.haversineKm(pts[i], pEnd) >= 0.005 || i == 0) {
                return RoutePhysics.bearingDeg(pts[i], pEnd)
            }
        }
        return null
    }

    private fun classifyTurn(deltaDeg: Double): Pair<String, String?> {
        val a = abs(deltaDeg)
        if (a < 24.0) return "continue" to null
        if (a >= 162.0) return "turn" to "uturn"
        return if (deltaDeg > 0) {
            when {
                deltaDeg < 55.0 -> "turn" to "slight right"
                deltaDeg < 125.0 -> "turn" to "right"
                else -> "turn" to "sharp right"
            }
        } else {
            when {
                a < 55.0 -> "turn" to "slight left"
                a < 125.0 -> "turn" to "left"
                else -> "turn" to "sharp left"
            }
        }
    }

    private fun sliceEdgeShape(
        shape: List<GeoPointX>,
        sSeg: Int,
        sT: Double,
        eSeg: Int,
        eT: Double
    ): List<GeoPointX> {
        if (shape.size < 2) return shape
        val sa = shape[sSeg]
        val sb = shape[sSeg + 1]
        val startPt = GeoPointX(sa.lat + (sb.lat - sa.lat) * sT, sa.lng + (sb.lng - sa.lng) * sT)
        val ea = shape[eSeg]
        val eb = shape[eSeg + 1]
        val endPt = GeoPointX(ea.lat + (eb.lat - ea.lat) * eT, ea.lng + (eb.lng - ea.lng) * eT)
        val out = mutableListOf(startPt)
        for (i in (sSeg + 1)..eSeg) {
            out.add(shape[i])
        }
        out.add(endPt)
        return out
    }

    private fun sliceEdgeShapeToEnd(shape: List<GeoPointX>, sSeg: Int, sT: Double): List<GeoPointX> {
        if (shape.size < 2) return shape
        val sa = shape[sSeg]
        val sb = shape[sSeg + 1]
        val startPt = GeoPointX(sa.lat + (sb.lat - sa.lat) * sT, sa.lng + (sb.lng - sa.lng) * sT)
        val out = mutableListOf(startPt)
        for (i in (sSeg + 1) until shape.size) out.add(shape[i])
        return out
    }

    private fun sliceEdgeShapeFromStart(shape: List<GeoPointX>, eSeg: Int, eT: Double): List<GeoPointX> {
        if (shape.size < 2) return shape
        val ea = shape[eSeg]
        val eb = shape[eSeg + 1]
        val endPt = GeoPointX(ea.lat + (eb.lat - ea.lat) * eT, ea.lng + (eb.lng - ea.lng) * eT)
        val out = mutableListOf<GeoPointX>()
        for (i in 0..eSeg) out.add(shape[i])
        out.add(endPt)
        return out
    }

    private fun sliceEdgeShapeByDist(shape: List<GeoPointX>, dStart: Double, dEnd: Double): List<GeoPointX> {
        if (shape.size < 2 || dEnd <= dStart) return listOf(shape.first(), shape.last())
        val out = mutableListOf<GeoPointX>()
        var cum = 0.0
        var started = false
        for (i in 0 until shape.lastIndex) {
            val a = shape[i]
            val b = shape[i + 1]
            val segM = RoutePhysics.haversineKm(a, b) * 1000.0
            val segEnd = cum + segM
            if (!started && dStart <= segEnd) {
                val t = if (segM > 1e-3) ((dStart - cum) / segM).coerceIn(0.0, 1.0) else 0.0
                out.add(GeoPointX(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t))
                started = true
            }
            if (started) {
                if (dEnd <= segEnd) {
                    val t = if (segM > 1e-3) ((dEnd - cum) / segM).coerceIn(0.0, 1.0) else 1.0
                    out.add(GeoPointX(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t))
                    break
                } else {
                    out.add(b)
                }
            }
            cum = segEnd
        }
        return if (out.size >= 2) out else listOf(shape.first(), shape.last())
    }

    private class MinHeap {
        private var nodes = IntArray(512)
        private var priorities = DoubleArray(512)
        private var size = 0
        var lastPoppedPriority = 0.0
            private set
        val isEmpty: Boolean get() = size == 0

        fun push(node: Int, priority: Double) {
            if (size == nodes.size) {
                nodes = nodes.copyOf(nodes.size * 2)
                priorities = priorities.copyOf(priorities.size * 2)
            }
            var idx = size++
            while (idx > 0) {
                val p = (idx - 1) ushr 1
                if (priorities[p] <= priority) break
                nodes[idx] = nodes[p]
                priorities[idx] = priorities[p]
                idx = p
            }
            nodes[idx] = node
            priorities[idx] = priority
        }

        fun popNode(): Int {
            val res = nodes[0]
            lastPoppedPriority = priorities[0]
            val lastN = nodes[--size]
            val lastP = priorities[size]
            if (size > 0) {
                var idx = 0
                val half = size ushr 1
                while (idx < half) {
                    var child = (idx shl 1) + 1
                    val right = child + 1
                    if (right < size && priorities[right] < priorities[child]) {
                        child = right
                    }
                    if (lastP <= priorities[child]) break
                    nodes[idx] = nodes[child]
                    priorities[idx] = priorities[child]
                    idx = child
                }
                nodes[idx] = lastN
                priorities[idx] = lastP
            }
            return res
        }
    }

    companion object {
        private const val BINARY_MAGIC = 0x444F4646 // "DOFF"
        private const val BINARY_VERSION = 1
        private const val GRID_CELL_DEG = 0.01
        private const val HEURISTIC_SPEED_MS = 16.0 // ~57.6 km/h admissible upper bound for motor roads
        private const val MAX_ASTAR_ITERATIONS = 120_000

        fun buildFromOsm(
            id: String,
            label: String,
            centerLat: Double,
            centerLng: Double,
            radiusKm: Double,
            minLat: Double,
            maxLat: Double,
            minLng: Double,
            maxLng: Double,
            osmWays: List<OsmWayInput>,
            osmNodesLat: Map<Long, Double>,
            osmNodesLng: Map<Long, Double>,
            rawPlaces: List<OfflinePlaceEntry>,
            tileCount: Int = 0
        ): OfflineRoutingGraph {
            // 1. Identify junction/endpoint OSM node IDs
            val refCount = HashMap<Long, Int>(osmNodesLat.size / 2 + 16)
            for (w in osmWays) {
                if (w.nodeIds.size < 2) continue
                val last = w.nodeIds.lastIndex
                for (i in w.nodeIds.indices) {
                    val nid = w.nodeIds[i]
                    val add = if (i == 0 || i == last) 2 else 1
                    refCount[nid] = (refCount[nid] ?: 0) + add
                }
            }

            val junctionIdxMap = HashMap<Long, Int>(refCount.size / 3 + 16)
            val nodeLatList = ArrayList<Double>(refCount.size / 3 + 16)
            val nodeLngList = ArrayList<Double>(refCount.size / 3 + 16)

            fun getOrAddJunction(nid: Long): Int {
                junctionIdxMap[nid]?.let { return it }
                val lat = osmNodesLat[nid] ?: return -1
                val lng = osmNodesLng[nid] ?: return -1
                val idx = nodeLatList.size
                nodeLatList.add(lat)
                nodeLngList.add(lng)
                junctionIdxMap[nid] = idx
                return idx
            }

            val edges = ArrayList<OfflineEdge>(osmWays.size * 2)
            val streetCentroids = LinkedHashMap<String, GeoPointX>()

            for (w in osmWays) {
                if (w.nodeIds.size < 2) continue
                val cleanPts = ArrayList<Pair<Long, GeoPointX>>(w.nodeIds.size)
                for (nid in w.nodeIds) {
                    val lat = osmNodesLat[nid] ?: continue
                    val lng = osmNodesLng[nid] ?: continue
                    cleanPts.add(nid to GeoPointX(lat, lng))
                }
                if (cleanPts.size < 2) continue

                if (w.roadName.isNotBlank() && !streetCentroids.containsKey(w.roadName)) {
                    streetCentroids[w.roadName] = cleanPts[cleanPts.size / 2].second
                }

                var segStart = 0
                while (segStart < cleanPts.lastIndex) {
                    var segEnd = segStart + 1
                    while (segEnd < cleanPts.lastIndex && (refCount[cleanPts[segEnd].first] ?: 0) <= 1) {
                        segEnd++
                    }
                    val u = getOrAddJunction(cleanPts[segStart].first)
                    val v = getOrAddJunction(cleanPts[segEnd].first)
                    if (u >= 0 && v >= 0) {
                        val shape = ArrayList<GeoPointX>(segEnd - segStart + 1)
                        var lenM = 0.0
                        for (k in segStart..segEnd) {
                            val pt = cleanPts[k].second
                            if (shape.isNotEmpty()) {
                                lenM += RoutePhysics.haversineKm(shape.last(), pt) * 1000.0
                            }
                            shape.add(pt)
                        }
                        lenM = max(1.0, lenM)
                        val spdMs = (w.roadClass.speedKmh / 3.6).coerceAtLeast(2.0)
                        val costSec = (lenM / spdMs) * w.roadClass.penalty

                        if (w.onewayForward) {
                            edges.add(OfflineEdge(u, v, w.roadName, w.roadClass, lenM, costSec, shape))
                        }
                        if (w.onewayBackward) {
                            edges.add(OfflineEdge(v, u, w.roadName, w.roadClass, lenM, costSec, shape.reversed()))
                        }
                    }
                    segStart = segEnd
                }
            }

            // Deduplicate & merge places + street index
            val allPlaces = ArrayList<OfflinePlaceEntry>(rawPlaces.size + streetCentroids.size)
            val seenPlaceKeys = HashSet<String>(rawPlaces.size + streetCentroids.size)
            for (p in rawPlaces) {
                val key = "${p.kind}|${p.name.lowercase(Locale.US)}"
                if (p.name.isNotBlank() && seenPlaceKeys.add(key)) {
                    allPlaces.add(p)
                }
            }
            for ((streetName, pt) in streetCentroids) {
                val key = "street|${streetName.lowercase(Locale.US)}"
                if (seenPlaceKeys.add(key)) {
                    // Find nearest area name for context
                    var areaName = ""
                    var bestAreaKm = 5.0
                    for (p in allPlaces) {
                        if (p.kind != "area") continue
                        val d = RoutePhysics.haversineKm(pt, GeoPointX(p.lat, p.lng))
                        if (d < bestAreaKm) {
                            bestAreaKm = d
                            areaName = p.name
                        }
                    }
                    allPlaces.add(
                        OfflinePlaceEntry(
                            name = streetName,
                            category = "Jalan",
                            area = areaName,
                            lat = pt.lat,
                            lng = pt.lng,
                            kind = "street"
                        )
                    )
                }
            }

            val meta = OfflineRegionMeta(
                id = id,
                label = label,
                centerLat = centerLat,
                centerLng = centerLng,
                radiusKm = radiusKm,
                minLat = minLat,
                maxLat = maxLat,
                minLng = minLng,
                maxLng = maxLng,
                nodeCount = nodeLatList.size,
                edgeCount = edges.size,
                placeCount = allPlaces.size,
                tileCount = tileCount,
                downloadedAtMs = System.currentTimeMillis()
            )
            return OfflineRoutingGraph(
                meta = meta,
                nodesLat = nodeLatList.toDoubleArray(),
                nodesLng = nodeLngList.toDoubleArray(),
                edges = edges,
                places = allPlaces
            )
        }

        fun readBinary(input: InputStream, sizeOverrideBytes: Long = 0L): OfflineRoutingGraph? {
            return try {
                val dis = DataInputStream(BufferedInputStream(input, 65536))
                if (dis.readInt() != BINARY_MAGIC) return null
                val ver = dis.readInt()
                if (ver != BINARY_VERSION) return null

                val id = dis.readUTF()
                val label = dis.readUTF()
                val cLat = dis.readDouble()
                val cLng = dis.readDouble()
                val radKm = dis.readDouble()
                val minLat = dis.readDouble()
                val maxLat = dis.readDouble()
                val minLng = dis.readDouble()
                val maxLng = dis.readDouble()
                val tileCount = dis.readInt()
                val dlMs = dis.readLong()

                val poolSize = dis.readInt()
                val pool = Array(poolSize) { dis.readUTF() }
                fun str(i: Int): String = if (i in pool.indices) pool[i] else ""

                val nNodes = dis.readInt()
                val nodesLat = DoubleArray(nNodes)
                val nodesLng = DoubleArray(nNodes)
                for (i in 0 until nNodes) {
                    nodesLat[i] = dis.readDouble()
                    nodesLng[i] = dis.readDouble()
                }

                val nEdges = dis.readInt()
                val edges = ArrayList<OfflineEdge>(nEdges)
                for (i in 0 until nEdges) {
                    val u = dis.readInt()
                    val v = dis.readInt()
                    val roadName = str(dis.readInt())
                    val rClass = OfflineRoadClass.fromOrdinalSafe(dis.readByte().toInt())
                    val lenM = dis.readDouble()
                    val costSec = dis.readDouble()
                    val nPts = dis.readInt()
                    val shape = ArrayList<GeoPointX>(nPts)
                    for (k in 0 until nPts) {
                        shape.add(GeoPointX(dis.readDouble(), dis.readDouble()))
                    }
                    edges.add(OfflineEdge(u, v, roadName, rClass, lenM, costSec, shape))
                }

                val nPlaces = dis.readInt()
                val places = ArrayList<OfflinePlaceEntry>(nPlaces)
                for (i in 0 until nPlaces) {
                    val name = str(dis.readInt())
                    val cat = str(dis.readInt())
                    val area = str(dis.readInt())
                    val kind = str(dis.readInt())
                    val lat = dis.readDouble()
                    val lng = dis.readDouble()
                    places.add(OfflinePlaceEntry(name, cat, area, lat, lng, kind))
                }

                val meta = OfflineRegionMeta(
                    id = id,
                    label = label,
                    centerLat = cLat,
                    centerLng = cLng,
                    radiusKm = radKm,
                    minLat = minLat,
                    maxLat = maxLat,
                    minLng = minLng,
                    maxLng = maxLng,
                    nodeCount = nNodes,
                    edgeCount = nEdges,
                    placeCount = nPlaces,
                    tileCount = tileCount,
                    totalBytes = sizeOverrideBytes,
                    downloadedAtMs = dlMs
                )
                OfflineRoutingGraph(meta, nodesLat, nodesLng, edges, places)
            } catch (_: Exception) {
                null
            }
        }
    }
}
