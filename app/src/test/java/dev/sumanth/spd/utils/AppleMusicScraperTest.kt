package dev.sumanth.spd.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicScraperTest {

    private val scraper = AppleMusicScraper()

    @Test
    fun testParsePlaylistUrl() {
        val validUrl = "https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb"
        val parsed = scraper.parsePlaylistUrl(validUrl)
        assertEquals(validUrl, parsed)

        val urlWithParams = "https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb?l=en&ls=1"
        val parsedClean = scraper.parsePlaylistUrl(urlWithParams)
        assertEquals(validUrl, parsedClean)
    }

    @Test
    fun testScrapeRealAppleMusicPlaylist() = runBlocking {
        val testUrl = "https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb"
        var progressReported = false

        val result = scraper.scrapePlaylist(testUrl) { tracks, total ->
            progressReported = true
            assertTrue("Progress tracks should not be empty", tracks.isNotEmpty())
            assertTrue("Total count should be positive", total > 0)
        }

        assertTrue("Progress callback should have been called", progressReported)
        assertNotNull("Metadata must not be null", result.metadata)
        assertEquals("Today’s Hits", result.metadata.name)
        assertTrue("Playlist must have tracks", result.totalTracks > 0)
        assertTrue("Track list must not be empty", result.tracks.isNotEmpty())

        val firstTrack = result.tracks.first()
        assertTrue("First track title should not be blank", firstTrack.title.isNotBlank())
        assertTrue("First track artist should not be blank", firstTrack.artist.isNotBlank())
        assertNotNull("Cover artwork URL should be populated", firstTrack.imageUrl)
        assertTrue("Cover artwork should be 1200x1200", firstTrack.imageUrl!!.contains("1200x1200"))
        
        println("Verified Apple Music Scraper successfully:")
        println("  Playlist Name: ${result.metadata.name}")
        println("  Curator: ${result.metadata.ownerName}")
        println("  Tracks found: ${result.tracks.size}")
        println("  First Track: '${firstTrack.title}' by '${firstTrack.artist}'")
        println("  Artwork URL: ${firstTrack.imageUrl}")
    }
}
