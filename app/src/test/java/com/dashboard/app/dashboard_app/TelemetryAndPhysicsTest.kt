package com.dashboard.app.dashboard_app

import com.dashboard.app.dashboard_app.engine.OfflinePlaceEntry
import com.dashboard.app.dashboard_app.engine.OfflineRoadClass
import com.dashboard.app.dashboard_app.engine.OfflineRoutingGraph
import com.dashboard.app.dashboard_app.engine.OsmWayInput
import com.dashboard.app.dashboard_app.engine.RoutePhysics
import com.dashboard.app.dashboard_app.engine.TelemetryEngine
import com.dashboard.app.dashboard_app.model.AppSettings
import com.dashboard.app.dashboard_app.model.Battery2Data
import com.dashboard.app.dashboard_app.model.ConnectionStateX
import com.dashboard.app.dashboard_app.model.Esp32Data
import com.dashboard.app.dashboard_app.model.GeoPointX
import com.dashboard.app.dashboard_app.model.JkBmsData
import com.dashboard.app.dashboard_app.model.LiveTripMetrics
import com.dashboard.app.dashboard_app.model.MapStyleOption
import com.dashboard.app.dashboard_app.model.MotorData
import com.dashboard.app.dashboard_app.model.PlannedStop
import com.dashboard.app.dashboard_app.model.RidingScoreState
import com.dashboard.app.dashboard_app.model.TripRecord
import com.dashboard.app.dashboard_app.service.RemoteApisService
import com.dashboard.app.dashboard_app.service.SpkluImportParser
import com.dashboard.app.dashboard_app.service.TileCacheService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TelemetryAndPhysicsTest {

    @Test
    fun parsesMotorAtLinesAndIgnoresCorruptOdo() {
        var m = MotorData()
        m = TelemetryEngine.parseMotorAtLine("+SPD=48.5", m)!!
        m = TelemetryEngine.parseMotorAtLine("+VBAT=73.4", m)!!
        m = TelemetryEngine.parseMotorAtLine("+IBAT=-12.0", m)!!
        m = TelemetryEngine.parseMotorAtLine("+ERR=0x09", m)!!
        m = TelemetryEngine.parseMotorAtLine("AT+TINV:38.5 °C", m)!!
        m = TelemetryEngine.parseMotorAtLine("+TMOT=46.2 C", m)!!
        m = TelemetryEngine.parseMotorAtLine("+TBAT=\"33.1\"", m)!!
        m = TelemetryEngine.parseMotorAtLine("AT+TIME=\"0915\"", m)!!
        m = TelemetryEngine.parseMotorAtLine("+RPM=1840", m)!!

        assertEquals(48.5, m.speedKmh!!, 0.001)
        assertEquals(73.4, m.vbat!!, 0.001)
        assertEquals(-12.0, m.ibat!!, 0.001)
        assertEquals(9, m.errMask)
        assertEquals(38.5, m.tempInverterC!!, 0.001)
        assertEquals(46.2, m.tempMotorC!!, 0.001)
        assertEquals(33.1, m.tempBatteryC!!, 0.001)
        assertEquals("0915", m.timeStr)
        assertEquals(1840, m.rpm)

        var backedUpOdo: Double? = null
        m = TelemetryEngine.parseMotorAtLine(
            "+ODO=1250",
            m,
            onValidOdo = { backedUpOdo = it }
        )!!
        assertEquals(1250.0, backedUpOdo!!, 0.001)

        var corruptMsg: String? = null
        m = TelemetryEngine.parseMotorAtLine(
            "+ODO=410000",
            m,
            onCorruptOdo = { corruptMsg = it }
        )!!
        assertEquals(1250.0, m.odoKm!!, 0.001)
        assertNotNull(corruptMsg)
    }

    @Test
    fun buildsAndParsesJkBms300ByteFrame() {
        val cmd = TelemetryEngine.buildJkCommandFrame(0x96)
        assertEquals(20, cmd.size)
        assertEquals(0xAA.toByte(), cmd[0])
        assertEquals(0x55.toByte(), cmd[1])
        assertEquals(0x90.toByte(), cmd[2])
        assertEquals(0xEB.toByte(), cmd[3])

        val frame = ByteArray(300)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        frame[2] = 0xEB.toByte()
        frame[3] = 0x90.toByte()
        frame[4] = 0x02.toByte()

        // 20 cells @ 3800 mV
        for (i in 0 until 20) {
            frame[6 + i * 2] = (3800 and 0xFF).toByte()
            frame[6 + i * 2 + 1] = ((3800 ushr 8) and 0xFF).toByte()
        }

        // 24S vpack = 76000 mV @ offset 118
        val vpackMv = 76000
        frame[118] = (vpackMv and 0xFF).toByte()
        frame[119] = ((vpackMv ushr 8) and 0xFF).toByte()
        frame[120] = ((vpackMv ushr 16) and 0xFF).toByte()
        frame[121] = ((vpackMv ushr 24) and 0xFF).toByte()

        // SOC = 82% @ 141
        frame[141] = 82.toByte()

        // CRC byte 299
        var crc = 0
        for (i in 0 until 299) {
            crc += frame[i].toInt() and 0xFF
        }
        frame[299] = (crc and 0xFF).toByte()

        val parsed = TelemetryEngine.parseJkBms300ByteFrame(frame)
        assertNotNull(parsed)
        assertEquals(20, parsed!!.cellCount)
        assertEquals(76.0, parsed.vpack!!, 0.01)
        assertEquals(82.0, parsed.socPct!!, 0.01)
    }

    @Test
    fun mergesDualBatteryWeightedSocAndIntegratesEnergy() {
        val now = 1_700_000_000_000L
        val settings = AppSettings(
            b1NominalV = 72.0,
            b1CapacityAh = 20.0,
            b2NominalV = 72.0,
            b2CapacityAh = 20.0
        )
        val eff = TelemetryEngine.computeEffective(
            motor = MotorData(
                speedKmh = 36.0,
                vbat = 72.0,
                ibat = -10.0,
                socPct = 80.0,
                lastUpdatedMs = now
            ),
            motorState = ConnectionStateX.CONNECTED,
            jk = JkBmsData(),
            jkState = ConnectionStateX.DISCONNECTED,
            esp1 = Esp32Data(),
            esp1State = ConnectionStateX.DISCONNECTED,
            b2 = Battery2Data(
                vbat = 72.0,
                ibat = -10.0,
                socPct = 60.0,
                lastUpdatedMs = now
            ),
            esp2State = ConnectionStateX.CONNECTED,
            gpsSpeedKmh = null,
            trip = LiveTripMetrics(),
            settings = settings,
            nowMs = now
        )

        assertEquals(70.0, eff.combinedSocPct!!, 0.1)
        assertEquals(1440.0, eff.combinedPowerW!!, 0.1)

        val res = TelemetryEngine.integrateSample(
            prevTrip = LiveTripMetrics(
                startTimeMs = now - 1000L,
                lastSampleMs = now - 1000L
            ),
            prevScore = RidingScoreState(),
            eff = eff,
            nowMs = now,
            wasMoving = false
        )
        assertEquals(0.01, res.trip.distanceKm, 0.001)
        assertEquals(0.4, res.trip.energyWh, 0.02)
        assertTrue(res.wasMoving)
    }

    @Test
    fun parsesGoogleMapsCoordsAndComputesElevationEnergy() {
        val gp = RoutePhysics.parseCoordinatesOrGoogleMapsUrl(
            "https://maps.google.com/?q=@-6.917464,107.619125,15z"
        )
        assertNotNull(gp)
        assertEquals(-6.917464, gp!!.lat, 0.0001)
        assertEquals(107.619125, gp.lng, 0.0001)

        val res = RoutePhysics.buildElevationAndEnergy(
            sampledPoints = listOf(
                GeoPointX(-6.917, 107.619),
                GeoPointX(-6.907, 107.619),
                GeoPointX(-6.897, 107.619)
            ),
            elevations = listOf(700.0, 760.0, 720.0),
            totalDistanceKm = 2.2,
            durationMin = 6.0,
            bikeKg = 95.0,
            riderKg = 70.0,
            loadKg = 5.0,
            calFactor = 1.0,
            totalPackWh = 1440.0,
            currentSocPct = 80.0
        )
        assertEquals(3, res.samples.size)
        assertEquals(60.0, res.estimate.totalAscentM, 0.1)
        assertEquals(40.0, res.estimate.totalDescentM, 0.1)
        assertTrue(res.estimate.socAtArrivalPct!! < 80.0)
    }

    @Test
    fun computesPersonalRecordsEfficiencyTrendAndIdleDrain() {
        val baseTs = 1_700_000_000_000L
        val trips = (0 until 6).map { i ->
            val eWh = if (i < 3) 220.0 else 300.0
            TripRecord(
                id = "t_$i",
                startTimeMs = baseTs - i * 3600_000L,
                endTimeMs = baseTs - i * 3600_000L + 600_000L,
                distanceKm = 10.0,
                energyWh = eWh,
                regenWh = (i + 1) * 5.0,
                maxSpeedKmh = 50.0 + i * 3.0,
                avgSpeedKmh = 35.0,
                maxPowerW = 2400.0,
                ridingScore = 80 + i
            )
        }
        val pr = TelemetryEngine.computePersonalRecords(trips)
        assertEquals(10.0, pr.longestTripKm, 0.01)
        assertEquals(65.0, pr.topSpeedKmh, 0.01)
        assertEquals(30.0, pr.highestRegenWh, 0.01)
        assertEquals(22.0, pr.bestWhPerKm!!, 0.01)
        assertEquals(85, pr.bestScore)

        val trend = TelemetryEngine.computeEfficiencyTrend(trips)
        assertNotNull(trend)
        assertTrue(trend!!.deltaPct < -15.0)

        val drain = TelemetryEngine.computeIdleDrainPct(80.0, baseTs - 3600_000L, 77.5, baseTs)
        assertEquals(2.5, drain!!, 0.01)
        assertNull(TelemetryEngine.computeIdleDrainPct(80.0, baseTs - 60_000L, 77.0, baseTs))
    }

    @Test
    fun resolvesCartoTileKeyAndDetectsShortUrls() {
        val noKey = TileCacheService.resolveTileUrl(MapStyleOption.DARK_CARTO.tileUrlTemplate, 15, 100, 200, "")
        assertFalse(noKey.contains("?key="))
        val withKey = TileCacheService.resolveTileUrl(MapStyleOption.CARTO_VOYAGER.tileUrlTemplate, 15, 100, 200, "test_carto_key")
        assertTrue(withKey.endsWith("?key=test_carto_key"))

        assertTrue(RemoteApisService.isMapsShortUrl("https://maps.app.goo.gl/AbCd123"))
        assertFalse(RemoteApisService.isMapsShortUrl("Jl. Sudirman Jakarta"))
    }

    @Test
    fun analyzesHillSegmentsAndWeatherHelpers() {
        assertEquals("Utara", RoutePhysics.windDirText(0.0))
        assertEquals("Barat", RoutePhysics.windDirText(270.0))
        assertEquals("curam", RoutePhysics.gradeLabel(8.2))
        assertEquals("sedang", RoutePhysics.gradeLabel(-5.5))

        val points = (0..12).map { i ->
            GeoPointX(-7.0 + i * 0.0022, 111.0)
        }
        val elevs = listOf(100.0, 102.0, 112.0, 126.0, 142.0, 158.0, 160.0, 158.0, 145.0, 130.0, 116.0, 115.0, 115.0)
        val res = RoutePhysics.buildElevationAndEnergy(
            sampledPoints = points,
            elevations = elevs,
            totalDistanceKm = 3.0,
            durationMin = 8.0,
            bikeKg = 95.0,
            riderKg = 70.0,
            loadKg = 5.0,
            calFactor = 1.0,
            totalPackWh = 1440.0,
            currentSocPct = 80.0
        )
        val hills = RoutePhysics.analyzeHillSegments(
            samples = res.samples,
            bikeKg = 95.0,
            riderKg = 70.0,
            loadKg = 5.0,
            calFactor = 1.0,
            totalPackWh = 1440.0
        )
        assertTrue(hills.any { it.isUphill && it.notable })
        assertTrue(hills.any { !it.isUphill && it.notable })
        val climb = hills.first { it.isUphill && it.notable }
        assertTrue(climb.dWh > 0.0)
        assertTrue(climb.gainM > 20.0)
        val descent = hills.first { !it.isUphill && it.notable }
        assertTrue(descent.dWh < 0.0)
    }

    @Test
    fun parsesSpkluImportsAcrossKmlGpxGeoJsonAndCsv() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml><Document><name>Charger Molis Jateng</name>
              <Placemark>
                <name>SPLU Balai Kota</name>
                <description><![CDATA[Colokan Schuko 16A<br/>Gratis parkir]]></description>
                <Point><coordinates>111.01234,-7.34567,0</coordinates></Point>
              </Placemark>
            </Document></kml>
        """.trimIndent()
        val kmlRes = SpkluImportParser.parseKml(kml)
        assertEquals(1, kmlRes.size)
        assertEquals("SPLU Balai Kota", kmlRes.first().name)
        assertEquals(-7.34567, kmlRes.first().lat, 0.0001)
        assertEquals(111.01234, kmlRes.first().lng, 0.0001)
        assertTrue(kmlRes.first().isMolisFriendly)

        val gpx = """
            <gpx><wpt lat="-6.917" lon="107.619"><name>Swap Oyika Bandung</name><desc>24 jam</desc></wpt></gpx>
        """.trimIndent()
        val gpxRes = SpkluImportParser.parseGpx(gpx)
        assertEquals(1, gpxRes.size)
        assertTrue(gpxRes.first().isMolisFriendly)
        assertEquals("Swap Baterai / SPBKLU", gpxRes.first().socketType)

        val geoJson = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[106.827,-6.175]},
               "properties":{"name":"SPKLU Monas","powerKw":22,"socket":"Type 2 AC"}}
            ]}
        """.trimIndent()
        val jsonRes = SpkluImportParser.parseJsonOrGeoJson(geoJson)
        assertEquals(1, jsonRes.size)
        assertEquals(22.0, jsonRes.first().powerKw!!, 0.01)
        assertFalse(jsonRes.first().isMolisFriendly)

        val csv = """
            Title,URL,Note
            Warung Molis Solo,"https://www.google.com/maps?q=-7.56,110.82",Colokan Schuko aman
            SPLU Stasiun,https://maps.google.com/?q=-7.57,110.83,24 jam
        """.trimIndent()
        val csvRes = SpkluImportParser.parseCsv(csv)
        assertEquals(2, csvRes.size)
        assertEquals("Warung Molis Solo", csvRes.first().name)
        assertEquals(-7.56, csvRes.first().lat, 0.001)
        assertTrue(csvRes.first().isMolisFriendly)
        assertEquals("SPLU Stasiun", csvRes[1].name)
        assertEquals(-7.57, csvRes[1].lat, 0.001)
    }

    @Test
    fun computesParentTileFallbackSliceAcrossZoomLevels() {
        val slice1 = TileCacheService.computeParentFallbackSlice(
            targetZ = 16,
            targetX = 52431,
            targetY = 33981,
            parentZ = 15,
            bmpWidth = 256,
            bmpHeight = 256
        )
        assertNotNull(slice1)
        assertEquals(15, slice1!!.z)
        assertEquals(26215, slice1.x)
        assertEquals(16990, slice1.y)
        assertEquals(128, slice1.srcX)
        assertEquals(128, slice1.srcY)
        assertEquals(128, slice1.srcW)
        assertEquals(128, slice1.srcH)

        val slice2 = TileCacheService.computeParentFallbackSlice(
            targetZ = 17,
            targetX = 104860,
            targetY = 67963,
            parentZ = 15,
            bmpWidth = 256,
            bmpHeight = 256
        )
        assertNotNull(slice2)
        assertEquals(15, slice2!!.z)
        assertEquals(64, slice2.srcW)
        assertEquals(64, slice2.srcH)
        assertTrue(slice2.srcX in 0 until 256)
        assertTrue(slice2.srcY in 0 until 256)
    }

    @Test
    fun buildsActiveTurnByTurnAtNavSlotsWithAsciiNormalization() {
        val slots = TelemetryEngine.buildAtNavSlots(
            eff = TelemetryEngine.computeEffective(
                motor = MotorData(),
                motorState = ConnectionStateX.DISCONNECTED,
                jk = JkBmsData(),
                jkState = ConnectionStateX.DISCONNECTED,
                esp1 = Esp32Data(),
                esp1State = ConnectionStateX.DISCONNECTED,
                b2 = Battery2Data(),
                esp2State = ConnectionStateX.DISCONNECTED,
                gpsSpeedKmh = 35.0,
                trip = LiveTripMetrics(distanceKm = 4.2, energyWh = 110.0),
                settings = AppSettings()
            ),
            trip = LiveTripMetrics(distanceKm = 4.2, energyWh = 110.0),
            roadName = "Jl. Sudirman",
            areaName = "Jakarta",
            weatherMood = "Cerah",
            slots = com.dashboard.app.dashboard_app.model.AtNavSlotConfig(),
            isNavigating = true,
            navStepInstruction = "Belok kanan ke Jl. Jenderal Sudirman",
            distToNextStepM = 240.0,
            remainingDistKm = 6.4,
            etaMin = 12.0
        )
        assertTrue(slots.isNotEmpty())
        assertTrue(slots.first().startsWith("240m Belok kanan"))
        assertTrue(slots.all { it.length <= 20 })
        assertTrue(slots.any { it.startsWith("ETA 12m 6.4km") })
    }

    @Test
    fun slicesDynamicElevationWindowAheadOfRider() {
        // 6.0 km linear elevation route heading north (~0.009 deg lat per km)
        val pts = (0..12).map { i -> GeoPointX(-7.0 + i * 0.0045, 110.0) }
        val elevs = (0..12).map { i -> 100.0 + i * 25.0 }
        val res = RoutePhysics.buildElevationAndEnergy(
            sampledPoints = pts,
            elevations = elevs,
            totalDistanceKm = 6.0,
            durationMin = 15.0,
            bikeKg = 95.0,
            riderKg = 70.0,
            loadKg = 5.0,
            calFactor = 1.0,
            totalPackWh = 1440.0,
            currentSocPct = 80.0
        )
        val totalKm = res.samples.last().distanceKm
        val riderLoc = pts[4] // ~2.0 km into route
        val sliced = RoutePhysics.sliceElevationWindow(
            samples = res.samples,
            riderLocation = riderLoc,
            windowKm = 2.0
        )
        assertTrue(sliced.size >= 2)
        assertEquals(0.0, sliced.first().distanceKm, 0.001)
        assertEquals(2.0, sliced.last().distanceKm, 0.05)
        assertEquals(res.samples[4].elevationM, sliced.first().elevationM, 2.0)
        assertTrue(sliced.last().elevationM > sliced.first().elevationM)

        // Near end of route still returns valid trailing slice
        val tail = RoutePhysics.sliceElevationWindow(
            samples = res.samples,
            riderLocation = pts.last(),
            windowKm = 2.0
        )
        assertTrue(tail.size >= 2)
        assertEquals(0.0, tail.first().distanceKm, 0.001)
        assertTrue(tail.last().distanceKm <= 0.55 && totalKm > 0.5)
    }

    @Test
    fun computesMirrorCaptureRectAcrossXYSizesAndBounds() {
        val fw = 1080
        val fh = 2400
        val outW = 350
        val outH = 220

        val topLeft = MirrorRegionOverlay.computeCaptureBounds(
            fw = fw,
            fh = fh,
            outW = outW,
            outH = outH,
            offsetX = 0f,
            offsetY = 0f,
            scalePct = 60
        )
        assertEquals(0, topLeft.left)
        assertEquals(0, topLeft.top)
        assertEquals(648, topLeft.width)
        assertTrue(topLeft.right <= fw)

        val bottomRight = MirrorRegionOverlay.computeCaptureBounds(
            fw = fw,
            fh = fh,
            outW = outW,
            outH = outH,
            offsetX = 1f,
            offsetY = 1f,
            scalePct = 60
        )
        assertEquals(fw, bottomRight.right)
        assertEquals(fh, bottomRight.bottom)
        assertEquals(topLeft.width, bottomRight.width)
        assertEquals(topLeft.height, bottomRight.height)

        val aspectExpected = outW.toFloat() / outH.toFloat()
        val aspectActual = bottomRight.width.toFloat() / bottomRight.height.toFloat()
        assertEquals(aspectExpected, aspectActual, 0.03f)
    }

    @Test
    fun measuresExactPolylineDistanceOnLongSegmentsForReroute() {
        val poly = listOf(
            GeoPointX(-6.2000, 106.8000),
            GeoPointX(-6.2000, 106.8200) // ~2.2 km straight East segment
        )
        // Dead center along the long segment should be ~0 m even though vertices are > 1.1 km away
        val onSegment = GeoPointX(-6.2000, 106.8100)
        assertTrue(RoutePhysics.distanceToPolylineM(onSegment, poly) < 2.0)

        // ~110 m north of the segment midpoint triggers off-route (> 65 m)
        val offRoute = GeoPointX(-6.1990, 106.8100)
        val dM = RoutePhysics.distanceToPolylineM(offRoute, poly)
        assertTrue(dM in 95.0..125.0)
    }

    @Test
    fun buildsOfflineRoadGraphRoutesWithTurnsAndSerializesCleanly() {
        // Construct a street grid:
        // Node 1 (-6.200, 106.800) -> 2 (-6.196, 106.800) North (Jl. Merdeka, degree-2 pass-through 10 added!)
        // Node 2 -> 3 (-6.196, 106.805) East (Jl. Sudirman)
        // Node 3 -> 4 (-6.200, 106.805) South (Jl. Diponegoro, oneway 4->3 only! so 3->4 blocked)
        // Node 3 -> 5 (-6.193, 106.805) North (Jl. Thamrin)
        val nLat = mapOf(
            1L to -6.2000,
            10L to -6.1980,
            2L to -6.1960,
            3L to -6.1960,
            4L to -6.2000,
            5L to -6.1930
        )
        val nLng = mapOf(
            1L to 106.8000,
            10L to 106.8000,
            2L to 106.8000,
            3L to 106.8050,
            4L to 106.8050,
            5L to 106.8050
        )
        val ways = listOf(
            OsmWayInput(listOf(1L, 10L, 2L), "Jl. Merdeka", OfflineRoadClass.PRIMARY, true, true),
            OsmWayInput(listOf(2L, 3L), "Jl. Sudirman", OfflineRoadClass.SECONDARY, true, true),
            OsmWayInput(listOf(4L, 3L), "Jl. Diponegoro", OfflineRoadClass.TERTIARY, true, false),
            OsmWayInput(listOf(3L, 5L), "Jl. Thamrin", OfflineRoadClass.PRIMARY, true, true)
        )
        val rawPlaces = listOf(
            OfflinePlaceEntry("Gambir", "Kecamatan / Kota", "", -6.1970, 106.8020, "area"),
            OfflinePlaceEntry("Alun-Alun Kota", "Park", "Gambir", -6.1962, 106.8035, "poi")
        )
        val graph = OfflineRoutingGraph.buildFromOsm(
            id = "test_jkt",
            label = "Jakarta Test",
            centerLat = -6.196,
            centerLng = 106.8025,
            radiusKm = 3.0,
            minLat = -6.21,
            maxLat = -6.18,
            minLng = 106.79,
            maxLng = 106.82,
            osmWays = ways,
            osmNodesLat = nLat,
            osmNodesLng = nLng,
            rawPlaces = rawPlaces
        )

        // Binary round-trip test
        val baos = ByteArrayOutputStream()
        graph.writeBinary(baos)
        val reloaded = OfflineRoutingGraph.readBinary(ByteArrayInputStream(baos.toByteArray()), baos.size().toLong())
        assertNotNull(reloaded)

        // Multi-turn route from Jl. Merdeka (-6.199, 106.800) to Jl. Thamrin (-6.194, 106.805)
        val route = reloaded!!.computeRoute(
            origin = GeoPointX(-6.1990, 106.8000),
            stops = listOf(PlannedStop("d1", "Thamrin", -6.1940, 106.8050)),
            settings = AppSettings(),
            currentSocPct = 82.0
        )
        assertNotNull(route)
        assertTrue(route!!.distanceM > 900.0)
        assertTrue(route.steps.any { it.maneuverType == "depart" && it.roadName == "Jl. Merdeka" })
        assertTrue(route.steps.any { it.modifier == "right" && it.roadName == "Jl. Sudirman" })
        assertTrue(route.steps.any { it.modifier == "left" && it.roadName == "Jl. Thamrin" })
        assertEquals("arrive", route.steps.last().maneuverType)

        // Offline reverse geocoding returns road & area
        val rev = reloaded.reverseGeocode(GeoPointX(-6.1980, 106.8001))
        assertNotNull(rev)
        assertEquals("Jl. Merdeka", rev!!.road)
        assertEquals("Gambir", rev.area)

        // Offline search finds streets, areas, and POIs
        val searchStreet = reloaded.searchPlaces("Sudirman")
        assertTrue(searchStreet.any { it.name == "Jl. Sudirman" })
        val searchPoi = reloaded.searchPlaces("Alun-Alun")
        assertTrue(searchPoi.any { it.name == "Alun-Alun Kota" })
    }
}
