package omnibeat.app.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Searches Radio Browser, webradiodb, or both. In [StationSource.Both] mode the same station
 * found more than once (in either directory) is shown once, using a stream that actually
 * connects: each distinct stream URL in a duplicate group is probed and the best working one wins.
 */
class StationSearch(
    private val radioBrowser: RadioBrowserClient,
    private val webRadioDb: WebRadioDbClient,
    private val prober: StreamProber = StreamProber(),
) {
    /** Paging position across both directories, plus what has already been shown. */
    data class Cursor(
        val radioBrowserOffset: Int = 0,
        val webRadioDbOffset: Int = 0,
        val radioBrowserDone: Boolean = false,
        val webRadioDbDone: Boolean = false,
        val shownUrlKeys: Set<String> = emptySet(),
        val shownNameKeys: Set<String> = emptySet(),
    )

    data class Page(
        val stations: List<RadioBrowserStation>,
        val next: Cursor,
        val hasMore: Boolean,
    )

    suspend fun search(params: RadioBrowserSearchParams, cursor: Cursor = Cursor()): Page = coroutineScope {
        val limit = params.limit.coerceAtLeast(1)
        when (params.source) {
            StationSource.RadioBrowser -> {
                val results = radioBrowser.searchStations(params.copy(offset = cursor.radioBrowserOffset, limit = limit))
                val next = cursor.copy(radioBrowserOffset = cursor.radioBrowserOffset + results.size)
                Page(results, next, hasMore = results.size == limit)
            }

            StationSource.WebRadioDb -> {
                val all = webRadioDb.search(params)
                val page = all.drop(cursor.webRadioDbOffset).take(limit)
                val nextOffset = cursor.webRadioDbOffset + page.size
                Page(page, cursor.copy(webRadioDbOffset = nextOffset), hasMore = nextOffset < all.size)
            }

            StationSource.Both -> {
                // A page can come back empty when every result was already shown; keep going a little
                // so infinite scroll doesn't stall.
                var page = searchBoth(params, cursor, limit)
                var attempts = 1
                while (page.stations.isEmpty() && page.hasMore && attempts < MAX_EMPTY_PAGES) {
                    page = searchBoth(params, page.next, limit)
                    attempts++
                }
                page
            }
        }
    }

    private suspend fun searchBoth(params: RadioBrowserSearchParams, cursor: Cursor, limit: Int): Page =
        coroutineScope {
            run {
                val radioBrowserRequest = async {
                    if (cursor.radioBrowserDone) {
                        Result.success(emptyList<RadioBrowserStation>())
                    } else {
                        runCatching {
                            radioBrowser.searchStations(params.copy(offset = cursor.radioBrowserOffset, limit = limit))
                        }
                    }
                }
                val webRadioDbRequest = async {
                    if (cursor.webRadioDbDone) Result.success(emptyList<RadioBrowserStation>()) else runCatching { webRadioDb.search(params) }
                }
                val radioBrowserResult = radioBrowserRequest.await()
                val webRadioDbResult = webRadioDbRequest.await()
                // One directory being down shouldn't break search; both being down should.
                if (radioBrowserResult.isFailure && webRadioDbResult.isFailure) {
                    throw radioBrowserResult.exceptionOrNull()!!
                }
                val fromRadioBrowser = radioBrowserResult.getOrDefault(emptyList())
                val allWebRadioDb = webRadioDbResult.getOrDefault(emptyList())
                val fromWebRadioDb = allWebRadioDb.drop(cursor.webRadioDbOffset).take(limit)

                val radioBrowserDone = cursor.radioBrowserDone ||
                    radioBrowserResult.isFailure || fromRadioBrowser.size < limit
                val webRadioDbOffset = cursor.webRadioDbOffset + fromWebRadioDb.size
                val webRadioDbDone = cursor.webRadioDbDone ||
                    webRadioDbResult.isFailure || webRadioDbOffset >= allWebRadioDb.size

                val merged = mergeDuplicates(fromRadioBrowser + fromWebRadioDb, cursor)
                val next = cursor.copy(
                    radioBrowserOffset = cursor.radioBrowserOffset + fromRadioBrowser.size,
                    webRadioDbOffset = webRadioDbOffset,
                    radioBrowserDone = radioBrowserDone,
                    webRadioDbDone = webRadioDbDone,
                    shownUrlKeys = cursor.shownUrlKeys + merged.map { urlKey(it.streamUrl) },
                    shownNameKeys = cursor.shownNameKeys + merged.map { nameKey(it) },
                )
                Page(merged, next, hasMore = !(radioBrowserDone && webRadioDbDone))
            }
        }

    /**
     * Collapses duplicates within this page (and drops ones already shown on earlier pages),
     * keeping each group at the position of its first member.
     */
    private suspend fun mergeDuplicates(
        stations: List<RadioBrowserStation>,
        cursor: Cursor,
    ): List<RadioBrowserStation> {
        val fresh = stations.filter { station ->
            urlKey(station.streamUrl) !in cursor.shownUrlKeys && nameKey(station) !in cursor.shownNameKeys
        }
        val groups = groupDuplicates(fresh)
        val semaphore = Semaphore(MAX_PARALLEL_PROBES)
        return coroutineScope {
            groups.mapIndexed { index, group ->
                async {
                    if (group.size == 1 || index >= MAX_PROBED_GROUPS) {
                        group.first()
                    } else {
                        pickWorking(group, semaphore)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun pickWorking(group: List<RadioBrowserStation>, semaphore: Semaphore): RadioBrowserStation {
        // Prefer higher bitrate; on ties keep directory order (Radio Browser first).
        val candidates = group
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<RadioBrowserStation>> { it.value.bitrate }.thenBy { it.index })
            .map { it.value }
            .distinctBy { urlKey(it.streamUrl) }
        if (candidates.size == 1) return mergeMetadata(candidates.first(), group)
        val working = coroutineScope {
            candidates.map { candidate ->
                async { semaphore.withPermit { prober.isPlayable(candidate.streamUrl) } }
            }.awaitAll()
        }
        val winner = candidates.zip(working).firstOrNull { it.second }?.first ?: candidates.first()
        return mergeMetadata(winner, group)
    }

    /** Keeps the winner's stream but fills gaps (artwork, tags, country) from its duplicates. */
    private fun mergeMetadata(winner: RadioBrowserStation, group: List<RadioBrowserStation>): RadioBrowserStation {
        return winner.copy(
            imageUrl = winner.imageUrl ?: group.firstNotNullOfOrNull { it.imageUrl },
            tags = (winner.tags + group.flatMap { it.tags }).distinctBy { it.lowercase() },
            countryCode = winner.countryCode.ifBlank { group.firstOrNull { it.countryCode.isNotBlank() }?.countryCode.orEmpty() },
        )
    }

    companion object {
        private const val MAX_PARALLEL_PROBES = 8
        private const val MAX_PROBED_GROUPS = 30
        private const val MAX_EMPTY_PAGES = 3

        /** Groups stations that share a stream URL, or share a name in the same (or an unknown) country. */
        internal fun groupDuplicates(stations: List<RadioBrowserStation>): List<List<RadioBrowserStation>> {
            val parent = IntArray(stations.size) { it }
            fun find(i: Int): Int {
                var x = i
                while (parent[x] != x) {
                    parent[x] = parent[parent[x]]
                    x = parent[x]
                }
                return x
            }
            fun union(a: Int, b: Int) {
                val rootA = find(a)
                val rootB = find(b)
                if (rootA != rootB) parent[maxOf(rootA, rootB)] = minOf(rootA, rootB)
            }
            val urlKeys = stations.map { urlKey(it.streamUrl) }
            val names = stations.map { normalizedName(it.title) }
            for (i in stations.indices) {
                for (j in i + 1 until stations.size) {
                    val sameUrl = urlKeys[i] == urlKeys[j]
                    val sameName = names[i].isNotEmpty() && names[i] == names[j] &&
                        countriesCompatible(stations[i].countryCode, stations[j].countryCode)
                    if (sameUrl || sameName) union(i, j)
                }
            }
            return stations.indices.groupBy(::find).values.map { indexes -> indexes.map(stations::get) }
        }

        private fun countriesCompatible(a: String, b: String): Boolean =
            a.isBlank() || b.isBlank() || a.equals(b, ignoreCase = true)

        internal fun urlKey(url: String): String = url.trim()
            .lowercase(Locale.ROOT)
            .substringAfter("://")
            .removePrefix("www.")
            .trimEnd('/', ';', '?')

        private fun normalizedName(title: String): String =
            title.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        internal fun nameKey(station: RadioBrowserStation): String =
            normalizedName(station.title) + "|" + station.countryCode.uppercase(Locale.ROOT)
    }
}

/** Checks whether a stream URL answers with audio (or a playlist) within a few seconds. */
class StreamProber {
    suspend fun isPlayable(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { probe(url, redirects = 0) }.getOrDefault(false)
    }

    private fun probe(url: String, redirects: Int): Boolean {
        val requestUrl = URL(url)
        if (requestUrl.protocol != "http" && requestUrl.protocol != "https") return false
        val connection = requestUrl.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false // followed manually so http <-> https hops work
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("User-Agent", "OmniBeat Android")
        connection.setRequestProperty("Icy-MetaData", "0")
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: return false
                if (redirects >= MAX_REDIRECTS) return false
                return probe(URL(requestUrl, location).toString(), redirects + 1)
            }
            if (code !in 200..299) return false
            val contentType = connection.contentType.orEmpty().lowercase(Locale.ROOT)
            if (contentType.startsWith("text/html")) return false // error or landing page, not a stream
            val buffer = ByteArray(PROBE_BYTES)
            return connection.inputStream.use { it.read(buffer) } > 0
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 3_000
        const val MAX_REDIRECTS = 5
        const val PROBE_BYTES = 512
    }
}
