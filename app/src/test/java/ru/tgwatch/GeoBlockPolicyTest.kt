package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoBlockPolicyTest {
    @Test fun legalAndForbiddenCodesAreGeoBlocks() {
        assertEquals(GeoBlockPolicy.Outcome.GEO_BLOCK, GeoBlockPolicy.classify(451, null, null))
        assertEquals(GeoBlockPolicy.Outcome.GEO_BLOCK, GeoBlockPolicy.classify(403, null, null))
    }

    @Test fun successNeedsBodyPhraseForGeoBlock() {
        assertEquals(GeoBlockPolicy.Outcome.OK, GeoBlockPolicy.classify(200, null, "<html>ok</html>"))
        assertEquals(GeoBlockPolicy.Outcome.GEO_BLOCK,
            GeoBlockPolicy.classify(200, null, "Sorry, ChatGPT is not available in your country."))
        assertEquals(GeoBlockPolicy.Outcome.GEO_BLOCK,
            GeoBlockPolicy.classify(200, null, "{\"error\":\"unsupported_country\"}"))
        assertTrue(GeoBlockPolicy.bodyLooksBlocked("This isn't available in your country right now"))
        assertFalse(GeoBlockPolicy.bodyLooksBlocked("Welcome back"))
    }

    @Test fun redirectUsesLocationHeuristics() {
        assertEquals(GeoBlockPolicy.Outcome.GEO_BLOCK,
            GeoBlockPolicy.classify(302, "https://www.google.com/unsupported_country", null))
        assertEquals(GeoBlockPolicy.Outcome.GEO_BLOCK,
            GeoBlockPolicy.classify(301, "https://support.google.com/accounts/answer/region", null))
        assertEquals(GeoBlockPolicy.Outcome.OK,
            GeoBlockPolicy.classify(302, "https://www.youtube.com/feed", null))
        assertEquals(GeoBlockPolicy.Outcome.OK, GeoBlockPolicy.classify(302, null, null))
        assertTrue(GeoBlockPolicy.locationLooksBlocked("https://example/geoblock"))
        assertFalse(GeoBlockPolicy.locationLooksBlocked("https://cdn.example/asset.js"))
    }

    @Test fun serverErrorsAreDownAndOtherClientErrorsStayUnknown() {
        assertEquals(GeoBlockPolicy.Outcome.DOWN, GeoBlockPolicy.classify(503, null, null))
        assertEquals(GeoBlockPolicy.Outcome.DOWN, GeoBlockPolicy.classify(0, null, null))
        assertEquals(GeoBlockPolicy.Outcome.UNKNOWN, GeoBlockPolicy.classify(404, null, null))
        assertEquals(GeoBlockPolicy.Outcome.UNKNOWN, GeoBlockPolicy.classify(418, null, null))
    }

    @Test fun catalogStaysInformationalAndSkipsUnknownProducts() {
        assertEquals(4, RegionalServices.all.size)
        assertEquals("https://www.youtube.com/", RegionalServices.service(RegionalServices.Id.YOUTUBE).url)
        assertEquals("https://gemini.google.com/", RegionalServices.service(RegionalServices.Id.GEMINI).url)
        assertEquals("https://notebooklm.google.com/",
            RegionalServices.service(RegionalServices.Id.NOTEBOOK_LM).url)
        assertEquals("https://chatgpt.com/", RegionalServices.service(RegionalServices.Id.CHATGPT).url)
        assertTrue(RegionalServices.all.none { it.url.contains("antigravity", ignoreCase = true) })
    }
}
