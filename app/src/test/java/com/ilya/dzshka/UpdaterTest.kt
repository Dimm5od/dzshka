package com.ilya.dzshka

import com.ilya.dzshka.net.Updater
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UpdaterTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parseAndPick() {
        val m = json.decodeFromString(Updater.Manifest.serializer(), """
            {"full":{"code":9,"name":"1.7.0","apk":"DZshka-1.7.0.apk","sha256":"ab","size":100,"notes":"x"},
             "helper":{"code":9,"name":"1.7.0-helper","apk":"DZshka-Pomoshnik-1.7.0.apk","sha256":"cd"},
             "extra":1}
        """)
        val r = Updater.pick(m)!!
        if (BuildConfig.HELPER) assertEquals("DZshka-Pomoshnik-1.7.0.apk", r.apk) else assertEquals("DZshka-1.7.0.apk", r.apk)
        assertTrue(Updater.isNewer(r, current = 8))
        assertFalse(Updater.isNewer(r, current = 9))
        assertFalse(Updater.isNewer(null))
    }
}
