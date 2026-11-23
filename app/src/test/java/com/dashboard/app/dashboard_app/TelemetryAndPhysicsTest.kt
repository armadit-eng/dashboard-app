package com.dashboard.app.dashboard_app

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
}
