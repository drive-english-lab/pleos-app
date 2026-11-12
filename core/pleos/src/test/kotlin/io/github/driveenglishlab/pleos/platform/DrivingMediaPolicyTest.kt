package io.github.driveenglishlab.pleos.platform

import io.github.driveenglishlab.pleos.platform.vehicle.DrivingAccess
import io.github.driveenglishlab.pleos.platform.media.NewsArticle
import io.github.driveenglishlab.pleos.platform.media.NewsMediaCatalog
import org.junit.Assert.*
import org.junit.Test

class DrivingMediaPolicyTest {
    @Test fun detailsRequireEveryCurrentSafetySignal() {
        val parked = DrivingAccess(connected = true, permissionGranted = true, gear = 4, speed = 0f, uxKnown = true, requiresOptimization = false, restrictions = 0)
        assertTrue(parked.detailsAllowed)
        for (unsafe in listOf(parked.copy(gear = 8), parked.copy(gear = 2), parked.copy(gear = 1), parked.copy(speed = 1f), parked.copy(speed = null), parked.copy(speed = Float.NaN), parked.copy(uxKnown = false), parked.copy(requiresOptimization = true), parked.copy(restrictions = 80), parked.copy(permissionGranted = false), parked.copy(connected = false))) assertFalse(unsafe.toString(), unsafe.detailsAllowed)
        assertFalse(DrivingAccess().detailsAllowed)
    }
    @Test fun catalogResolvesApprovedIdentityAndTitleOnly() {
        val article = NewsArticle("fiction-1", "A City Library", "Fictional example", "2026.11.11", "asset:///sample.mp3", 38000)
        val last = article.copy(id = "fiction-2", title = "A Battery")
        val catalog = object : NewsMediaCatalog { override val articles = listOf(article, last) }
        assertEquals(article, catalog.find("fiction-1"))
        assertEquals(listOf(article), catalog.search(" CITY "))
        assertTrue(catalog.search("missing").isEmpty())
        assertTrue(catalog.search("").isEmpty())
        assertNull(catalog.find("https://untrusted.example/audio.mp3"))
        assertEquals(last, catalog.next(article.id))
        assertEquals(article, catalog.previous(last.id))
        assertNull(catalog.previous(article.id))
        assertNull(catalog.next(last.id))
        assertNull(catalog.previous("missing"))
        assertNull(catalog.next("missing"))
        assertTrue(NewsMediaCatalog.Empty.articles.isEmpty())
    }
    @Test fun invalidCatalogContentIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { NewsArticle("", "Title", "", "", "asset:///a.mp3", 1) }
        assertThrows(IllegalArgumentException::class.java) { NewsArticle("id", "Title", "", "", "", 1) }
        assertThrows(IllegalArgumentException::class.java) { NewsArticle("id", "Title", "", "", "asset:///a.mp3", 0) }
    }
}
