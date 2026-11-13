package com.ilya.dzshka

import com.ilya.dzshka.data.GhCfg
import com.ilya.dzshka.net.GhSync
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GhPhotoTest {
    @Test fun createsBranchThenUploads() {
        val srv = MockWebServer()
        var branch = false
        val seen = mutableListOf<String>()
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                seen += "${r.method} ${r.path}"
                return when {
                    r.method == "PUT" && !branch -> MockResponse().setResponseCode(404).setBody("""{"message":"Branch photos not found"}""")
                    r.method == "PUT" -> { assertTrue(r.body.readUtf8().contains("\"branch\":\"photos\"")); MockResponse().setResponseCode(201).setBody("{}") }
                    r.method == "GET" && r.path!!.contains("/git/ref/heads/main") -> MockResponse().setBody("""{"object":{"sha":"abc123"}}""")
                    r.method == "POST" && r.path!!.endsWith("/git/refs") -> { branch = true; MockResponse().setResponseCode(201).setBody("{}") }
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        srv.start()
        System.setProperty("github.api", srv.url("/").toString().trimEnd('/'))
        try {
            val f = File.createTempFile("dz_", ".jpeg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val url = GhSync.uploadPhoto(GhCfg(token = "t"), f)
            assertTrue(url, url.startsWith("https://raw.githubusercontent.com/Dimm5od/dzshka/photos/photos/") && url.endsWith(f.name))
            assertEquals(4, seen.size)
        } finally {
            System.clearProperty("github.api"); srv.shutdown()
        }
    }
}
