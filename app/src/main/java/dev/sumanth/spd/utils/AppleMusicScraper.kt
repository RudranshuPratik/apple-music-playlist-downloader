package dev.sumanth.spd.utils

import dev.sumanth.spd.model.DownloadStatus
import dev.sumanth.spd.model.Track
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.regex.Pattern

class AppleMusicScraper(private val client: OkHttpClient = OkHttpClient()) {

    companion object {
        private const val TAG = "AppleMusicScraper"
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private fun logD(tag: String, msg: String) {
            println("[$tag] $msg")
        }
        private fun logW(tag: String, msg: String, tr: Throwable? = null) {
            println("[$tag] WARN: $msg")
            tr?.printStackTrace()
        }
        private fun logE(tag: String, msg: String, tr: Throwable? = null) {
            println("[$tag] ERROR: $msg")
            tr?.printStackTrace()
        }
    }

    fun parsePlaylistUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.contains("/song/") || trimmed.contains("/album/") && !trimmed.contains("/playlist/")) {
            throw IllegalArgumentException("Single song or album links are not supported. Please provide a playlist link.")
        }
        if (trimmed.contains("/artist/")) {
            throw IllegalArgumentException("Artist pages are not supported. Please provide a playlist link.")
        }

        // Match standard Apple Music playlist URL
        val regex = Regex("https?://music\\.apple\\.com/[a-zA-Z]{2}/playlist/[^/]+/pl\\.[a-zA-Z0-9_-]+")
        val match = regex.find(trimmed)
        if (match != null) return match.value

        // Match localized or short playlist URL without country code
        val generalRegex = Regex("https?://music\\.apple\\.com/[^\\s\"'<>]+")
        val generalMatch = generalRegex.find(trimmed)
        if (generalMatch != null && (generalMatch.value.contains("/playlist/") || generalMatch.value.contains("pl."))) {
            return generalMatch.value.split("?")[0]
        }

        if (trimmed.startsWith("https://music.apple.com") && (trimmed.contains("pl.") || trimmed.contains("/playlist/"))) {
            return trimmed.split("?")[0]
        }

        throw IllegalArgumentException("Unable to extract a valid Apple Music playlist URL from: $input")
    }

    suspend fun scrapePlaylist(
        url: String,
        onProgress: suspend (List<Track>, Int) -> Unit
    ): ScrapeResult {
        val cleanUrl = parsePlaylistUrl(url)
        logD(TAG, "scrapePlaylist: Starting scrape for $cleanUrl")

        val request = Request.Builder()
            .url(cleanUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to load playlist page (HTTP ${response.code})")
            }
            response.body?.string() ?: throw IOException("Empty response from Apple Music")
        }

        // 1. Extract metadata from Schema.org JSON-LD
        var playlistName = "Apple Music Playlist"
        var playlistDesc: String? = null
        var curatorName: String? = null
        var totalTracksDeclared = 0

        val schemaPattern = Pattern.compile(
            "<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
            Pattern.DOTALL
        )
        val schemaMatcher = schemaPattern.matcher(html)
        while (schemaMatcher.find()) {
            try {
                val jsonStr = schemaMatcher.group(1)?.trim() ?: continue
                val schemaJson = JSONObject(jsonStr)
                if (schemaJson.optString("@type") == "MusicPlaylist") {
                    playlistName = schemaJson.optString("name", playlistName)
                    playlistDesc = schemaJson.optString("description")
                    totalTracksDeclared = schemaJson.optInt("numTracks", 0)
                    val author = schemaJson.optJSONObject("author")
                    if (author != null) {
                        curatorName = author.optString("name")
                    }
                    break
                }
            } catch (e: Exception) {
                logW(TAG, "Error parsing schema.org json-ld", e)
            }
        }

        // 2. Extract tracks from serialized-server-data (full title, artistName, and high-res cover art)
        val tracks = mutableListOf<Track>()
        val seen = HashSet<String>()

        val serverDataPattern = Pattern.compile(
            "<script[^>]*id=[\"']serialized-server-data[\"'][^>]*>(.*?)</script>",
            Pattern.DOTALL
        )
        val serverDataMatcher = serverDataPattern.matcher(html)

        if (serverDataMatcher.find()) {
            try {
                val serverDataStr = serverDataMatcher.group(1)?.trim() ?: ""
                val serverJson = JSONObject(serverDataStr)

                fun walk(obj: Any?) {
                    when (obj) {
                        is JSONObject -> {
                            if (obj.has("artistName") && obj.has("title")) {
                                val title = obj.optString("title").trim()
                                val artist = obj.optString("artistName").trim()
                                val key = "${title.lowercase()}||${artist.lowercase()}"

                                if (title.isNotEmpty() && artist.isNotEmpty() && !seen.contains(key)) {
                                    seen.add(key)
                                    var artUrl: String? = null
                                    val artwork = obj.optJSONObject("artwork")
                                    val dictionary = artwork?.optJSONObject("dictionary")
                                    val tmpl = dictionary?.optString("url")
                                    if (!tmpl.isNullOrBlank()) {
                                        artUrl = tmpl.replace("{w}x{h}bb.{f}", "1200x1200bb.jpg")
                                    }

                                    tracks.add(
                                        Track(
                                            title = title,
                                            artist = artist,
                                            status = DownloadStatus.IDLE,
                                            imageUrl = artUrl
                                        )
                                    )
                                }
                            }
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                walk(obj.opt(keys.next()))
                            }
                        }
                        is org.json.JSONArray -> {
                            for (i in 0 until obj.length()) {
                                walk(obj.opt(i))
                            }
                        }
                    }
                }

                walk(serverJson)
            } catch (e: Exception) {
                logE(TAG, "Error parsing serialized-server-data", e)
            }
        }

        // 3. Fallback to schema.org tracks if serialized-server-data didn't yield tracks
        if (tracks.isEmpty()) {
            val schemaMatcherFallback = schemaPattern.matcher(html)
            while (schemaMatcherFallback.find()) {
                try {
                    val jsonStr = schemaMatcherFallback.group(1)?.trim() ?: continue
                    val schemaJson = JSONObject(jsonStr)
                    if (schemaJson.optString("@type") == "MusicPlaylist") {
                        val trackArray = schemaJson.optJSONArray("track")
                        if (trackArray != null) {
                            for (i in 0 until trackArray.length()) {
                                val tObj = trackArray.optJSONObject(i) ?: continue
                                val title = tObj.optString("name").trim()
                                val audioObj = tObj.optJSONObject("audio")
                                val thumbUrl = audioObj?.optString("thumbnailUrl")
                                if (title.isNotEmpty()) {
                                    tracks.add(
                                        Track(
                                            title = title,
                                            artist = curatorName ?: "Apple Music",
                                            status = DownloadStatus.IDLE,
                                            imageUrl = thumbUrl
                                        )
                                    )
                                }
                            }
                        }
                        break
                    }
                } catch (e: Exception) {
                    logW(TAG, "Fallback schema parse error", e)
                }
            }
        }

        if (tracks.isEmpty()) {
            throw IOException("No tracks could be found in this playlist. Please ensure the playlist is public.")
        }

        val total = if (totalTracksDeclared > 0) totalTracksDeclared else tracks.size
        onProgress(tracks, total)

        val metadata = PlaylistMetadata(
            id = cleanUrl,
            name = playlistName,
            totalCount = tracks.size,
            ownerName = curatorName,
            description = playlistDesc
        )

        logD(TAG, "scrapePlaylist: Finished. Extracted ${tracks.size} tracks from $playlistName")
        return ScrapeResult(metadata, tracks.size, tracks)
    }

    data class ScrapeResult(
        val metadata: PlaylistMetadata,
        val totalTracks: Int,
        val tracks: List<Track>
    )

    data class PlaylistMetadata(
        val id: String,
        val name: String,
        val totalCount: Int,
        val ownerName: String? = null,
        val description: String? = null
    )
}
