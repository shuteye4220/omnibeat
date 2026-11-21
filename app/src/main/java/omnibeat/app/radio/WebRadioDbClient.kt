package omnibeat.app.radio

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.time.Duration.Companion.hours

/**
 * Client for webradiodb (https://github.com/jcorporation/webradiodb), a curated station
 * list published as static JSON. The whole index is small (well under 1 MB), so it is
 * downloaded once, cached on disk, and searched locally.
 */
class WebRadioDbClient(context: Context) {
    private val cacheDirectory = File(context.applicationContext.cacheDir, "webradiodb")
    private val mutex = Mutex()
    private var index: CachedIndex? = null

    private class CachedIndex(
        val savedAt: Long,
        val stations: List<Entry>,
        val brokenKeys: Set<String>,
    ) {
        val isFresh: Boolean
            get() = System.currentTimeMillis() - savedAt in 0 until INDEX_TTL_MILLIS
    }

    /** One webradiodb station, already mapped onto the shared search result type. */
    class Entry(
        val key: String,
        val station: RadioBrowserStation,
        val countryName: String,
        val genres: List<String>,
        val languages: List<String>,
        val added: Long,
    )

    suspend fun search(params: RadioBrowserSearchParams): List<RadioBrowserStation> = withContext(Dispatchers.IO) {
        val loaded = loadIndex()
        val nameQuery = params.name.trim()
        val tagQueries = params.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val matches = loaded.stations.filter { entry ->
            val station = entry.station
            (params.includeBroken || entry.key !in loaded.brokenKeys) &&
                (nameQuery.isEmpty() || station.title.contains(nameQuery, ignoreCase = true)) &&
                tagQueries.all { tag -> entry.genres.any { it.contains(tag, ignoreCase = true) } } &&
                params.country.matches(entry) &&
                (params.language == null || entry.languages.any { it.equals(params.language.name, ignoreCase = true) }) &&
                (params.bitrateMin == null || station.bitrate >= params.bitrateMin) &&
                (params.bitrateMax == null || station.bitrate in 1..params.bitrateMax)
        }
        sort(matches, params.sort, params.reverse).map { it.station }
    }

    private fun RadioBrowserFilterOption?.matches(entry: Entry): Boolean {
        if (this == null) return true
        if (code.isNotBlank() && code.equals(entry.station.countryCode, ignoreCase = true)) return true
        return name.equals(entry.countryName, ignoreCase = true)
    }

    private fun sort(entries: List<Entry>, sort: RadioBrowserSort, reverse: Boolean): List<Entry> {
        val byName = compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.station.title }
        return when (sort) {
            RadioBrowserSort.Random -> entries.shuffled()
            // webradiodb has no click or vote counts; newest additions are the closest equivalent.
            RadioBrowserSort.Clicks, RadioBrowserSort.Votes -> entries.sortedByDescending { it.added }
            RadioBrowserSort.Name -> entries.sortedWith(if (reverse) byName.reversed() else byName)
            RadioBrowserSort.Bitrate -> entries.sortedWith(
                compareBy<Entry> { it.station.bitrate }.let { if (reverse) it.reversed() else it }.then(byName),
            )
            RadioBrowserSort.Country -> {
                val byCountry = compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.countryName }
                entries.sortedWith((if (reverse) byCountry.reversed() else byCountry).then(byName))
            }
        }
    }

    private suspend fun loadIndex(): CachedIndex {
        mutex.withLock {
            index?.takeIf { it.isFresh }?.let { return it }
            val diskCopy = runCatching { readDiskCache() }.getOrNull()
            if (diskCopy != null && diskCopy.isFresh) {
                index = diskCopy
                return diskCopy
            }
            val downloaded = runCatching {
                val stationsJson = readText(URL("$BASE_URL/webradios.min.json"))
                // The status file is optional: if it fails we simply don't hide broken streams.
                val statusJson = runCatching { readText(URL("$BASE_URL/status.min.json")) }.getOrNull()
                runCatching { writeDiskCache(stationsJson, statusJson) } // cache is best-effort
                parse(System.currentTimeMillis(), stationsJson, statusJson)
            }
            // Prefer fresh data, fall back to a stale cache when offline, otherwise report the error.
            val result = downloaded.getOrNull() ?: diskCopy ?: throw downloaded.exceptionOrNull()!!
            index = result
            return result
        }
    }

    private fun readDiskCache(): CachedIndex? {
        val stationsFile = AtomicFile(File(cacheDirectory, "webradios.json"))
        if (!stationsFile.baseFile.exists()) return null
        val statusFile = AtomicFile(File(cacheDirectory, "status.json"))
        val stationsJson = stationsFile.readFully().toString(Charsets.UTF_8)
        val statusJson = if (statusFile.baseFile.exists()) statusFile.readFully().toString(Charsets.UTF_8) else null
        return parse(stationsFile.baseFile.lastModified(), stationsJson, statusJson)
    }

    private fun writeDiskCache(stationsJson: String, statusJson: String?) {
        cacheDirectory.mkdirs()
        writeAtomically(File(cacheDirectory, "webradios.json"), stationsJson)
        statusJson?.let { writeAtomically(File(cacheDirectory, "status.json"), it) }
    }

    private fun writeAtomically(target: File, text: String) {
        val file = AtomicFile(target)
        val output = file.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun parse(savedAt: Long, stationsJson: String, statusJson: String?): CachedIndex {
        val root = JSONObject(stationsJson)
        val stations = buildList {
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = root.optJSONObject(key) ?: continue
                parseEntry(key, item)?.let(::add)
            }
        }
        val brokenKeys = statusJson
            ?.let { runCatching { JSONObject(it).keys().asSequence().toSet() }.getOrNull() }
            .orEmpty()
        return CachedIndex(savedAt, stations, brokenKeys)
    }

    private fun parseEntry(key: String, item: JSONObject): Entry? {
        val title = item.optString("Name").trim()
        val streamUrl = item.optString("StreamUri").trim()
        if (title.isBlank() || !isHttpUrl(streamUrl)) return null
        val countryName = item.optString("Country").trim()
        val genres = item.optJSONArray("Genre").strings()
        val bitrate = item.optInt("highestBitrate", item.optInt("Bitrate", 0))
        val image = item.optString("Image").trim()
        return Entry(
            key = key,
            station = RadioBrowserStation(
                stationUuid = "webradiodb:$key",
                title = title,
                streamUrl = streamUrl,
                tags = genres.distinctBy { it.lowercase() },
                countryCode = countryCode(countryName),
                codec = item.optString("Codec").trim(),
                bitrate = bitrate,
                imageUrl = image.takeIf { it.isNotBlank() && !it.contains("/") && !it.contains("..") }
                    ?.let { "$PICTURES_URL/$it" },
                source = StationSource.WebRadioDb,
            ),
            countryName = countryName,
            genres = genres,
            languages = item.optJSONArray("Languages").strings(),
            added = item.optLong("Added", 0L),
        )
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { optString(it).trim() }.filter { it.isNotEmpty() }
    }

    private fun readText(requestUrl: URL): String {
        val connection = requestUrl.openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "OmniBeat Android")
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) error("webradiodb returned HTTP $responseCode")
            val bytes = connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_INDEX_BYTES) error("webradiodb index is unexpectedly large")
                }
                buffer.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BASE_URL = "https://jcorporation.github.io/webradiodb/db/index"
        private const val PICTURES_URL = "https://jcorporation.github.io/webradiodb/db/pics"
        private val INDEX_TTL_MILLIS = 24.hours.inWholeMilliseconds
        private const val MAX_INDEX_BYTES = 16 * 1024 * 1024

        private val countryCodesByName: Map<String, String> by lazy {
            Locale.getISOCountries().associateBy { code ->
                Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT)
            }
        }

        /** Maps an English country name ("Austria") to its ISO code ("AT"), or "" if unknown. */
        fun countryCode(countryName: String): String =
            countryCodesByName[countryName.trim().lowercase(Locale.ROOT)].orEmpty()

        private fun isHttpUrl(url: String): Boolean =
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
    }
}
