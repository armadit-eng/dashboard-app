package com.dashboard.app.dashboard_app.service

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

data class TileCoord(val z: Int, val x: Int, val y: Int)
data class TileCacheStats(val files: Int, val bytes: Long)

object TileCacheService {
    @Volatile
    var offlineOnly: Boolean = false

    const val MAX_TILES_PER_DOWNLOAD = 3500

    private val memCache = LruCache<String, ImageBitmap>(160)

    private fun rootDir(context: Context): File = File(context.filesDir, "tile_cache")

    private fun tileFile(context: Context, styleId: String, z: Int, x: Int, y: Int): File =
        File(rootDir(context), "$styleId/$z/$x/$y.tile")

    fun resolveTileUrl(urlTemplate: String, z: Int, x: Int, y: Int): String =
        urlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())

    suspend fun getTileBitmap(
        context: Context,
        styleId: String,
        urlTemplate: String,
        z: Int,
        x: Int,
        y: Int
    ): ImageBitmap? {
        val key = "$styleId/$z/$x/$y"
        memCache.get(key)?.let { return it }

        val bytes = withContext(Dispatchers.IO) {
            val f = tileFile(context, styleId, z, x, y)
            if (f.exists() && f.length() > 0L) {
                try {
                    return@withContext f.readBytes()
                } catch (_: Exception) {
                }
            }
            if (offlineOnly) return@withContext null
            val url = resolveTileUrl(urlTemplate, z, x, y)
            val fetched = fetchBytes(url) ?: return@withContext null
            saveBytes(f, fetched)
            fetched
        } ?: return null

        return withContext(Dispatchers.Default) {
            try {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                val img = bmp.asImageBitmap()
                memCache.put(key, img)
                img
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun fetchBytes(urlStr: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("User-Agent", "com.dashboard.app.dashboard_app")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val data = conn.inputStream.use { it.readBytes() }
                if (data.isNotEmpty()) data else null
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun saveBytes(file: File, bytes: ByteArray) {
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        } catch (_: Exception) {
        }
    }

    private fun tx(lng: Double, z: Int): Int {
        val n = 1 shl z
        return floor(((lng + 180.0) / 360.0) * n).toInt().coerceIn(0, n - 1)
    }

    private fun ty(lat: Double, z: Int): Int {
        val n = 1 shl z
        val rad = lat.coerceIn(-85.0, 85.0) * PI / 180.0
        val v = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
        return floor(v * n).toInt().coerceIn(0, n - 1)
    }

    fun tilesFor(lat: Double, lng: Double, radiusKm: Double, minZ: Int, maxZ: Int): List<TileCoord> {
        val dLat = radiusKm / 111.32
        val cosLat = abs(cos(lat * PI / 180.0)).coerceIn(0.2, 1.0)
        val dLng = radiusKm / (111.32 * cosLat)
        val out = mutableListOf<TileCoord>()
        for (z in minZ..maxZ) {
            val x0 = tx(lng - dLng, z)
            val x1 = tx(lng + dLng, z)
            val y0 = ty(lat + dLat, z)
            val y1 = ty(lat - dLat, z)
            for (x in min(x0, x1)..max(x0, x1)) {
                for (y in min(y0, y1)..max(y0, y1)) {
                    out.add(TileCoord(z, x, y))
                }
            }
        }
        return out
    }

    suspend fun downloadArea(
        context: Context,
        styleId: String,
        urlTemplate: String,
        lat: Double,
        lng: Double,
        radiusKm: Double,
        minZoom: Int = 10,
        maxZoom: Int = 16,
        onProgress: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean
    ): Int = withContext(Dispatchers.IO) {
        val tiles = tilesFor(lat, lng, radiusKm, minZoom, maxZoom)
        if (tiles.size > MAX_TILES_PER_DOWNLOAD) {
            throw IllegalStateException(
                "Area terlalu besar (${tiles.size} tile, batas $MAX_TILES_PER_DOWNLOAD)."
            )
        }
        var done = 0
        var ok = 0
        withContext(Dispatchers.Main) { onProgress(0, tiles.size) }

        val batches = tiles.chunked(4)
        for (slice in batches) {
            if (isCancelled()) break
            val results = coroutineScope {
                slice.map { t ->
                    async(Dispatchers.IO) {
                        val f = tileFile(context, styleId, t.z, t.x, t.y)
                        if (f.exists() && f.length() > 0L) return@async true
                        val url = resolveTileUrl(urlTemplate, t.z, t.x, t.y)
                        val bytes = fetchBytes(url) ?: return@async false
                        saveBytes(f, bytes)
                        true
                    }
                }.awaitAll()
            }
            ok += results.count { it }
            done += slice.size
            withContext(Dispatchers.Main) { onProgress(done, tiles.size) }
            delay(80)
        }
        ok
    }

    suspend fun stats(context: Context): TileCacheStats = withContext(Dispatchers.IO) {
        val root = rootDir(context)
        if (!root.exists()) return@withContext TileCacheStats(0, 0L)
        var count = 0
        var bytes = 0L
        root.walkTopDown().forEach { f ->
            if (f.isFile) {
                count++
                bytes += f.length()
            }
        }
        TileCacheStats(count, bytes)
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        memCache.evictAll()
        try {
            val root = rootDir(context)
            if (root.exists()) root.deleteRecursively()
        } catch (_: Exception) {
        }
    }
}
