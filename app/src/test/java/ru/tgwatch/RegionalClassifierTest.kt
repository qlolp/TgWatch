package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class RegionalClassifierTest {
    @Test fun sharedAndroidAndMacFixtures() {
        val fixture = listOf(java.io.File("tools/fixtures/regional-classification.jsonl"),
            java.io.File("../tools/fixtures/regional-classification.jsonl")).first { it.isFile }
        fixture.forEachLine { line ->
            val values = mutableMapOf<String, String>()
            val reader = ru.tgwatch.probe.StrictJsonReader(java.io.StringReader(line))
            reader.objectFields { name -> values[name] = when (reader.nextType) {
                '"' -> reader.string()
                't', 'f' -> reader.booleanValue().toString()
                else -> reader.longValue().toString()
            } }
            reader.finish()
            assertEquals(values.getValue("id"), values.getValue("status"), classify(values.getValue("code").toInt(),
                values.getValue("body"), values.getValue("contentType"), values.getValue("locationHost"),
                values.getValue("complete").toBooleanStrict()))
        }
    }
    private fun classify(code: Int, body: String, contentType: String = "text/html",
        locationHost: String = "", complete: Boolean = true): String {
        val type = runCatching { Class.forName("ru.tgwatch.RegionalClassifier") }.getOrNull()
        assertNotNull("Regional classifier must distinguish evidence from HTTP status",type)
        return type!!.getMethod("classify",Int::class.javaPrimitiveType,String::class.java,
            String::class.java,String::class.java,Boolean::class.javaPrimitiveType)
            .invoke(type.getField("INSTANCE").get(null),code,contentType,body,locationHost,complete).toString()
    }
    @Test fun explicitVisibleRegionResponseIsEvidenceEvenWithHttp200() {
        assertEquals("REGION_RESTRICTED",classify(200,"<h1>This service is not available in your country.</h1>"))
        assertEquals("REGION_RESTRICTED",classify(403,"<p>Gemini недоступен в вашей стране</p>"))
    }
    @Test fun genericDenialAndLegalStatusNeverProveGeoblocking() {
        assertEquals("UNKNOWN",classify(403,"Access denied"))
        assertEquals("UNKNOWN",classify(451,"Unavailable for legal reasons"))
        assertEquals("RATE_LIMITED",classify(429,""))
        assertEquals("AUTH_REQUIRED",classify(401,""))
    }
    @Test fun staticBundlesCommentsAndExamplesAreNotVisibleRegionFailures() {
        val phrase = "This service is not available in your country"
        for (body in listOf("<script>const message='$phrase'</script><h1>Welcome</h1>",
            "<!-- $phrase --><h1>Welcome</h1>","<pre>$phrase</pre><h1>Welcome</h1>",
            "<script>$phrase")) assertEquals("WEB_REACHABLE",classify(200,body))
        assertEquals("UNKNOWN",classify(403,"<script>$phrase</script>"))
    }
    @Test fun challengeTakesPrecedenceOverRegionalWording() {
        assertEquals("CHALLENGE",classify(403,"<h1>Verify you are human</h1><p>Not available in your country</p>"))
        assertEquals("CHALLENGE",classify(200,"<p>Unusual traffic</p>"))
    }
    @Test fun onlyRecognizedLoginHostsClassifyRedirectsAsAuthentication() {
        assertEquals("AUTH_REQUIRED",classify(302,"",locationHost="accounts.google.com"))
        assertEquals("UNKNOWN",classify(302,"",locationHost="accounts.google.com.attacker.example"))
        assertEquals("UNKNOWN",classify(302,"",locationHost="example.org"))
    }
    @Test fun strictJsonAcceptsOnlyExplicitErrorCodes() {
        val json = """{"error":{"code":"unsupported_country_region_territory"}}"""
        assertEquals("REGION_RESTRICTED",classify(403,json,"application/json"))
        assertEquals("UNKNOWN",classify(403,json.dropLast(1),"application/json"))
        assertEquals("UNKNOWN",classify(403,json,"application/json",complete=false))
        assertEquals("UNKNOWN",classify(403,"""{"example":{"code":"unsupported_country"}}""","application/json"))
        assertEquals("UNKNOWN",classify(403,"""{"error":{"code":"unsupported_country","code":"other"}}""","application/json"))
        assertEquals("UNKNOWN",classify(200,"""{"error":{"code":"permission_denied"}}""","application/json"))
    }
    @Test fun normalPublicPagesDoNotCertifyAccountFunctionality() {
        assertEquals("WEB_REACHABLE",classify(200,"<h1>Welcome</h1><button>Sign in</button>"))
        assertEquals("WEB_REACHABLE",classify(200,"",complete=false))
        assertEquals("UNKNOWN",classify(500,"Internal error"))
    }
    @Test fun aFaqMentioningARegionalErrorIsNotAnActiveRestriction() {
        assertEquals("WEB_REACHABLE",classify(200,
            "<h1>Frequently asked questions</h1><p>Why is this service not available in your country?</p>"))
    }
}
