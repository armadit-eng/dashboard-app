package com.dashboard.app.dashboard_app.service

import com.dashboard.app.dashboard_app.engine.RoutePhysics
import com.dashboard.app.dashboard_app.model.SpkluStation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

data class GoogleListParseResult(
    val listTitle: String,
    val author: String,
    val totalCount: Int,
    val stations: List<SpkluStation>
)

object SpkluImportParser {

    private val MOLIS_KEYWORDS = listOf(
        "molis", "schuko", "splu", "spbklu", "swap", "baterai", "battery",
        "motor", "colokan", "rumah", "warung", "16a", "gesits", "volta",
        "oyika", "polytron", "alva", "smoot", "yadea", "united"
    )

    fun inferMolisFriendly(
        name: String,
        socket: String = "",
        notes: String = "",
        powerKw: Double? = null
    ): Boolean {
        val hay = "$name $socket $notes".lowercase()
        if (MOLIS_KEYWORDS.any { hay.contains(it) }) return true
        if (powerKw != null && powerKw > 0.0 && powerKw <= 7.4) return true
        return false
    }

    fun parseBytes(bytes: ByteArray, fileName: String? = null): List<SpkluStation> {
        if (bytes.isEmpty()) return emptyList()
        val lower = fileName?.lowercase().orEmpty()
        // Detect KMZ (ZIP archive starting with 'PK' containing .kml)
        if (lower.endsWith(".kmz") || (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())) {
            val kmlTexts = extractKmlFromKmz(bytes)
            if (kmlTexts.isNotEmpty()) {
                return kmlTexts.flatMap { parseKml(it) }
            }
        }
        val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF').trim()
        if (text.isEmpty()) return emptyList()
        return parseText(text, lower)
    }

    fun parseText(rawText: String, fileHintLower: String = ""): List<SpkluStation> {
        val text = rawText.trimStart('\uFEFF').trim()
        if (text.isEmpty()) return emptyList()
        return when {
            text.startsWith("{") || text.startsWith("[") || fileHintLower.endsWith(".json") || fileHintLower.endsWith(".geojson") ->
                parseJsonOrGeoJson(text, sourceLabel = "Impor JSON")
            text.contains("<kml", ignoreCase = true) || text.contains("<Placemark", ignoreCase = true) || fileHintLower.endsWith(".kml") ->
                parseKml(text)
            text.contains("<gpx", ignoreCase = true) || text.contains("<wpt", ignoreCase = true) || fileHintLower.endsWith(".gpx") ->
                parseGpx(text)
            else -> parseCsv(text)
        }
    }

    private fun extractKmlFromKmz(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".kml")) {
                        out.add(zis.readBytes().toString(Charsets.UTF_8))
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return out
    }

    private fun newSecureXmlDoc(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder().parse(InputSource(StringReader(xml)))

    fun parseKml(xml: String): List<SpkluStation> {
        val out = mutableListOf<SpkluStation>()
        runCatching {
            val doc = newSecureXmlDoc(xml)
            val docName = doc.getElementsByTagName("Document").item(0)
                ?.let { it as? Element }
                ?.getElementsByTagName("name")?.item(0)?.textContent?.trim()
                .orEmpty()
            val placemarks = doc.getElementsByTagName("Placemark")
            for (i in 0 until placemarks.length) {
                val pm = placemarks.item(i) as? Element ?: continue
                val coordsRaw = pm.getElementsByTagName("coordinates").item(0)?.textContent.orEmpty()
                val firstCoord = coordsRaw.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                val parts = firstCoord.split(",")
                val lng = parts.getOrNull(0)?.toDoubleOrNull()
                val lat = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) continue

                val rawName = pm.getElementsByTagName("name").item(0)?.textContent?.trim().orEmpty()
                val cleanName = rawName.ifBlank { "Charger Molis #${out.size + 1}" }
                val rawDesc = pm.getElementsByTagName("description").item(0)?.textContent.orEmpty()
                val cleanNotes = stripHtml(rawDesc).take(160)
                val socket = inferSocketLabel(cleanName, cleanNotes, docName)
                out.add(
                    SpkluStation(
                        id = "imp_kml_${out.size}_${abs((lat * 1e5).toInt())}",
                        name = cleanName,
                        operatorName = docName.ifBlank { "Google My Maps" },
                        lat = lat,
                        lng = lng,
                        socketType = socket,
                        isUserAdded = true,
                        notes = cleanNotes,
                        sourceLabel = "Impor KML",
                        isMolisFriendly = inferMolisFriendly(cleanName, socket, "$cleanNotes $docName")
                    )
                )
            }
        }
        return out
    }

    fun parseGpx(xml: String): List<SpkluStation> {
        val out = mutableListOf<SpkluStation>()
        runCatching {
            val doc = newSecureXmlDoc(xml)
            val wpts = doc.getElementsByTagName("wpt")
            for (i in 0 until wpts.length) {
                val el = wpts.item(i) as? Element ?: continue
                val lat = el.getAttribute("lat").toDoubleOrNull()
                val lng = el.getAttribute("lon").toDoubleOrNull()
                if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) continue
                val cleanName = el.getElementsByTagName("name").item(0)?.textContent?.trim().orEmpty()
                    .ifBlank { "Titik GPX #${out.size + 1}" }
                val rawDesc = el.getElementsByTagName("desc").item(0)?.textContent
                    ?: el.getElementsByTagName("cmt").item(0)?.textContent
                    ?: ""
                val cleanNotes = stripHtml(rawDesc).take(160)
                val type = el.getElementsByTagName("type").item(0)?.textContent?.trim().orEmpty()
                val socket = inferSocketLabel(cleanName, cleanNotes, type)
                out.add(
                    SpkluStation(
                        id = "imp_gpx_${out.size}_${abs((lat * 1e5).toInt())}",
                        name = cleanName,
                        operatorName = type.ifBlank { "Impor GPX" },
                        lat = lat,
                        lng = lng,
                        socketType = socket,
                        isUserAdded = true,
                        notes = cleanNotes,
                        sourceLabel = "Impor GPX",
                        isMolisFriendly = inferMolisFriendly(cleanName, socket, "$cleanNotes $type")
                    )
                )
            }
        }
        return out
    }

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.contentOrNull?.trim()
            if (!v.isNullOrEmpty() && v != "null") return v
        }
        return ""
    }

    private fun JsonObject.dbl(vararg keys: String): Double? {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.doubleOrNull
            if (v != null && !v.isNaN()) return v
        }
        return null
    }

    private fun JsonObject.bool(vararg keys: String): Boolean =
        keys.any { this[it]?.jsonPrimitive?.booleanOrNull == true }

    fun parseJsonOrGeoJson(jsonText: String, sourceLabel: String = "Feed JSON"): List<SpkluStation> {
        val out = mutableListOf<SpkluStation>()
        runCatching {
            val element = jsonParser.parseToJsonElement(jsonText.trim())
            when (element) {
                is JsonArray -> {
                    element.forEachIndexed { i, item ->
                        (item as? JsonObject)?.let { parseFlatJsonObject(it, i, sourceLabel) }?.let { out.add(it) }
                    }
                }
                is JsonObject -> {
                    val features = element["features"] as? JsonArray
                    if (features != null) {
                        features.forEachIndexed { i, item ->
                            val feat = item as? JsonObject ?: return@forEachIndexed
                            val geom = feat["geometry"] as? JsonObject ?: return@forEachIndexed
                            if (!geom.str("type").equals("Point", ignoreCase = true)) return@forEachIndexed
                            val coords = geom["coordinates"] as? JsonArray ?: return@forEachIndexed
                            if (coords.size < 2) return@forEachIndexed
                            val lng = coords[0].jsonPrimitive.doubleOrNull ?: return@forEachIndexed
                            val lat = coords[1].jsonPrimitive.doubleOrNull ?: return@forEachIndexed
                            val props = feat["properties"] as? JsonObject ?: JsonObject(emptyMap())
                            val name = props.str("name", "title").ifBlank { "SPKLU #${i + 1}" }
                            val op = props.str("operator", "operatorName").ifBlank { sourceLabel }
                            val notes = stripHtml(props.str("notes", "note", "description")).take(160)
                            val kw = props.dbl("powerKw", "power_kw")?.takeIf { it > 0.0 }
                            val socket = props.str("socket", "socketType").ifBlank { inferSocketLabel(name, notes, op) }
                            val explicitMolis = props.bool("molis", "isMolisFriendly")
                            out.add(
                                SpkluStation(
                                    id = "json_${i}_${abs((lat * 1e5).toInt())}",
                                    name = name,
                                    operatorName = op,
                                    lat = lat,
                                    lng = lng,
                                    socketType = socket,
                                    powerKw = kw,
                                    isUserAdded = sourceLabel.startsWith("Impor"),
                                    notes = notes,
                                    sourceLabel = sourceLabel,
                                    isMolisFriendly = explicitMolis || inferMolisFriendly(name, socket, notes, kw)
                                )
                            )
                        }
                    } else {
                        val list = (element["stations"] ?: element["items"] ?: element["data"]) as? JsonArray
                        list?.forEachIndexed { i, item ->
                            (item as? JsonObject)?.let { parseFlatJsonObject(it, i, sourceLabel) }?.let { out.add(it) }
                        }
                    }
                }
                else -> {}
            }
        }
        return out
    }

    private fun parseFlatJsonObject(obj: JsonObject, idx: Int, sourceLabel: String): SpkluStation? {
        val lat = obj.dbl("lat", "latitude") ?: return null
        val lng = obj.dbl("lng", "lon", "longitude") ?: return null
        val name = obj.str("name", "title").ifBlank { "SPKLU #${idx + 1}" }
        val op = obj.str("operator", "operatorName").ifBlank { sourceLabel }
        val notes = stripHtml(obj.str("notes", "note", "description")).take(160)
        val kw = obj.dbl("powerKw", "power_kw")?.takeIf { it > 0.0 }
        val socket = obj.str("socket", "socketType").ifBlank { inferSocketLabel(name, notes, op) }
        val explicitMolis = obj.bool("molis", "isMolisFriendly")
        return SpkluStation(
            id = obj.str("id").ifBlank { "json_${idx}_${abs((lat * 1e5).toInt())}" },
            name = name,
            operatorName = op,
            lat = lat,
            lng = lng,
            socketType = socket,
            powerKw = kw,
            isUserAdded = sourceLabel.startsWith("Impor"),
            notes = notes,
            sourceLabel = sourceLabel,
            isMolisFriendly = explicitMolis || inferMolisFriendly(name, socket, notes, kw)
        )
    }

    fun parseCsv(csvText: String): List<SpkluStation> {
        val lines = csvText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return emptyList()
        val delim = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) ';' else ','
        val headers = splitCsvLine(lines.first(), delim).map { it.trim().lowercase() }

        val idxName = headers.indexOfFirst { it in listOf("name", "title", "nama", "lokasi", "label") }
        val idxLat = headers.indexOfFirst { it in listOf("lat", "latitude", "lintang") }
        val idxLng = headers.indexOfFirst { it in listOf("lng", "lon", "long", "longitude", "bujur") }
        val idxUrl = headers.indexOfFirst { it in listOf("url", "link", "maps_url", "google maps url") }
        val idxWkt = headers.indexOfFirst { it in listOf("wkt", "geometry", "geom") }
        val idxNotes = headers.indexOfFirst { it in listOf("note", "notes", "catatan", "comment", "description", "deskripsi") }
        val idxSocket = headers.indexOfFirst { it in listOf("socket", "sockettype", "soket", "tipe", "type") }
        val idxOp = headers.indexOfFirst { it in listOf("operator", "operatorname", "pengelola") }

        val out = mutableListOf<SpkluStation>()
        for (rowIdx in 1 until lines.size) {
            val cols = splitCsvLine(lines[rowIdx], delim)
            var lat = cols.getOrNull(idxLat)?.trim()?.toDoubleOrNull()
            var lng = cols.getOrNull(idxLng)?.trim()?.toDoubleOrNull()
            if ((lat == null || lng == null) && idxUrl >= 0) {
                val urlVal = cols.getOrNull(idxUrl).orEmpty()
                val parsed = RoutePhysics.parseCoordinatesOrGoogleMapsUrl(urlVal)
                if (parsed != null) {
                    lat = parsed.lat
                    lng = parsed.lng
                }
            }
            if ((lat == null || lng == null) && idxWkt >= 0) {
                val wkt = cols.getOrNull(idxWkt).orEmpty()
                val m = Regex("""POINT\s*\(\s*([-+]?\d+(?:\.\d+)?)\s+([-+]?\d+(?:\.\d+)?)\s*\)""", RegexOption.IGNORE_CASE)
                    .find(wkt)
                if (m != null) {
                    lng = m.groupValues[1].toDoubleOrNull()
                    lat = m.groupValues[2].toDoubleOrNull()
                }
            }
            if (lat == null || lng == null) {
                val rowFallback = RoutePhysics.parseCoordinatesOrGoogleMapsUrl(lines[rowIdx])
                if (rowFallback != null) {
                    lat = rowFallback.lat
                    lng = rowFallback.lng
                }
            }
            if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) continue

            val rawName = cols.getOrNull(idxName)?.trim().orEmpty().ifBlank { "Charger Impor #$rowIdx" }
            val rawNotes = cols.getOrNull(idxNotes)?.trim().orEmpty().take(160)
            val rawOp = cols.getOrNull(idxOp)?.trim().orEmpty().ifBlank { "Impor CSV" }
            val rawSocket = cols.getOrNull(idxSocket)?.trim().orEmpty()
                .ifBlank { inferSocketLabel(rawName, rawNotes, rawOp) }
            out.add(
                SpkluStation(
                    id = "imp_csv_${rowIdx}_${abs((lat * 1e5).toInt())}",
                    name = rawName,
                    operatorName = rawOp,
                    lat = lat,
                    lng = lng,
                    socketType = rawSocket,
                    isUserAdded = true,
                    notes = rawNotes,
                    sourceLabel = "Impor CSV",
                    isMolisFriendly = inferMolisFriendly(rawName, rawSocket, rawNotes)
                )
            )
        }
        return out
    }

    private fun splitCsvLine(line: String, delim: Char): List<String> {
        val res = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    cur.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (ch == delim && !inQuotes) {
                res.add(cur.toString())
                cur.clear()
            } else {
                cur.append(ch)
            }
            i++
        }
        res.add(cur.toString())
        return res
    }

    private fun stripHtml(s: String): String = s
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun inferSocketLabel(name: String, notes: String, extra: String): String {
        val hay = "$name $notes $extra".lowercase()
        return when {
            hay.contains("swap") || hay.contains("spbklu") || hay.contains("oyika") || hay.contains("smoot") ->
                "Swap Baterai / SPBKLU"
            hay.contains("m23") || hay.contains("polytron") || hay.contains("fast charging") ->
                "Fast / Socket Molis"
            hay.contains("schuko") || hay.contains("rumah") || hay.contains("16a") || hay.contains("splu") || hay.contains("molis") ->
                "SPLU / Schuko Molis"
            hay.contains("ccs") -> "CCS2 / Type 2"
            else -> "Charger Molis / SPKLU"
        }
    }

    private fun inferOperatorLabel(name: String, notes: String, fallback: String): String {
        val hay = "$name $notes".lowercase()
        return when {
            hay.contains("pln") || hay.contains("splu") -> "PLN / SPLU"
            hay.contains("polytron") -> "Polytron EV"
            hay.contains("gesits") -> "Komunitas Gesits"
            hay.contains("volta") || hay.contains("sgb") -> "Volta / SGB"
            hay.contains("oyika") || hay.contains("smoot") -> "Swap Komunitas"
            else -> fallback.ifBlank { "Komunitas Molis" }
        }
    }

    fun extractGoogleMapsListId(urlOrText: String): String? {
        val patterns = listOf(
            Regex("""/(?:placelists|userlists)/list/([A-Za-z0-9_-]{8,})"""),
            Regex("""!11m2!2s([A-Za-z0-9_-]{10,})"""),
            Regex("""!1m4!1s([A-Za-z0-9_-]{10,})""")
        )
        for (p in patterns) {
            val m = p.find(urlOrText)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    fun extractGoogleMyMapsId(urlOrText: String): String? {
        if (!urlOrText.contains("google.", ignoreCase = true) && !urlOrText.contains("mymaps", ignoreCase = true)) {
            return null
        }
        return Regex("""[?&]mid=([A-Za-z0-9_-]{10,})""").find(urlOrText)?.groupValues?.getOrNull(1)
    }

    fun extractEntityListGetListUrl(html: String): String? {
        val m = Regex("""(/maps/preview/entitylist/getlist\?[^"'\s<>]+)""").find(html) ?: return null
        val path = m.groupValues[1].replace("&amp;", "&").replace("\\u0026", "&")
        return "https://www.google.com$path"
    }

    fun isGoogleMapsCollectionUrl(url: String): Boolean {
        return extractGoogleMapsListId(url) != null || extractGoogleMyMapsId(url) != null
    }

    fun parseGoogleEntityListResponse(
        rawResponse: String,
        fallbackSourceLabel: String = "GMap Molis",
        markAsUserAdded: Boolean = false
    ): GoogleListParseResult {
        val cleaned = rawResponse.trimStart('\uFEFF').trim()
            .replace(Regex("""^\)\]\}'\s*"""), "")
        if (cleaned.isBlank()) return GoogleListParseResult("", "", 0, emptyList())
        return runCatching {
            val top = jsonParser.parseToJsonElement(cleaned) as? JsonArray
                ?: return@runCatching GoogleListParseResult("", "", 0, emptyList())
            val root = top.getOrNull(0) as? JsonArray
                ?: return@runCatching GoogleListParseResult("", "", 0, emptyList())
            val listTitle = (root.getOrNull(4) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val author = (root.getOrNull(3) as? JsonArray)?.getOrNull(0)
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim() }
                .orEmpty()
            val totalCount = (root.getOrNull(12) as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0
            val items = root.getOrNull(8) as? JsonArray ?: JsonArray(emptyList())
            val molisListHint = inferMolisFriendly(listTitle, "", author)
            val stations = mutableListOf<SpkluStation>()
            val seenIds = HashSet<String>()

            items.forEachIndexed { idx, el ->
                val itemArr = el as? JsonArray ?: return@forEachIndexed
                val loc = itemArr.getOrNull(1) as? JsonArray
                val coords = loc?.getOrNull(5) as? JsonArray
                val lat = (coords?.getOrNull(2) as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
                val lng = (coords?.getOrNull(3) as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
                if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) return@forEachIndexed

                val addr = (loc.getOrNull(4) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val rawName = (itemArr.getOrNull(2) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val name = rawName.ifBlank { addr.ifBlank { "Charger Molis #${idx + 1}" } }
                val rawNote = (itemArr.getOrNull(3) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val noteClean = stripHtml(rawNote)
                val addrClean = stripHtml(addr).take(85)
                val combinedNotes = listOf(noteClean, addrClean)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                    .take(180)
                val placeKey = (loc.getOrNull(7) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    ?.trim()?.trim('/')?.replace('/', '_')
                    .orEmpty()
                var sid = "gm_molis_${placeKey.ifBlank { idx.toString() }}_${abs((lat * 1e4).toInt())}"
                if (!seenIds.add(sid)) {
                    sid = "${sid}_$idx"
                    seenIds.add(sid)
                }
                val socket = inferSocketLabel(name, combinedNotes, listTitle)
                val op = inferOperatorLabel(name, combinedNotes, author.ifBlank { listTitle })
                stations.add(
                    SpkluStation(
                        id = sid,
                        name = name,
                        operatorName = op,
                        lat = lat,
                        lng = lng,
                        socketType = socket,
                        powerKw = null,
                        isUserAdded = markAsUserAdded,
                        distanceKm = null,
                        notes = combinedNotes,
                        sourceLabel = fallbackSourceLabel,
                        isMolisFriendly = molisListHint || inferMolisFriendly(name, socket, combinedNotes)
                    )
                )
            }
            GoogleListParseResult(
                listTitle = listTitle,
                author = author,
                totalCount = totalCount.coerceAtLeast(stations.size),
                stations = stations
            )
        }.getOrDefault(GoogleListParseResult("", "", 0, emptyList()))
    }
}
