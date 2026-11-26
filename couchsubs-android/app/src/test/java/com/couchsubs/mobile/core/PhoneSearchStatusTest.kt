// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import com.couchsubs.mobile.ocr.PhoneFrames
import com.couchsubs.mobile.remoteplay.VideoFrame
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * GTA 6 telefon aramasının 30 sn'lik durum satırı (X, beta-105: "telefon komple çalışmıyor"; #1385'in beta-104 günlüğünde
 * telefon hiç bulunamayınca tek satır yoktu). Ortak deneme dosyası fixtures/phone-search-status.json
 * (core/Tests/CouchSubsCoreTests/Fixtures ile aynı; Mac/iPhone PhoneSearchStatusTests.swift, Windows PhoneSearchStatusTests.cs).
 */
class PhoneSearchStatusTest {
    private val vectors = JSONObject(Fixtures.text("phone-search-status.json"))

    private fun step(name: String) = GamePhoneGeometry.Step.entries.first { it.name.replace("_", "").equals(name, ignoreCase = true) }

    @Test
    fun sharedCases() {
        val cases = vectors.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val status = PhoneSearchStatus()
            val samples = case.getJSONArray("samples")
            val due = case.getJSONArray("due")
            for (j in 0 until samples.length()) {
                val sample = samples.getJSONObject(j)
                val probe = if (sample.isNull("step")) null
                    else GamePhoneGeometry.Probe(step(sample.getString("step")), level = sample.getInt("level"))
                assertEquals("$name #$j", due.getBoolean(j), status.note(probe, sample.getBoolean("seen"), sample.getLong("at")))
            }
            val frame = if (case.isNull("frame")) null else case.getString("frame")
            assertEquals(name, case.getString("line"),
                status.line(case.getBoolean("open"), case.getInt("width"), case.getInt("height"), case.getInt("median"), frame))
        }
    }

    @Test
    fun sharedMedians() {
        val items = vectors.getJSONArray("median")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val tones = item.getJSONArray("tones").let { a: JSONArray -> IntArray(a.length()) { a.getInt(it) } }
            assertEquals(item.getInt("median"), PhoneSearchStatus.median(tones))
        }
    }

    /** Satırdan sonra pencere baştan başlar: sonraki satır yine 30 sn sonra. */
    @Test
    fun windowRestartsAfterLine() {
        val status = PhoneSearchStatus()
        status.note(null, false, 0)
        assertTrue(status.note(null, false, 30_000))
        status.line(false, 1280, 720, 0, null)
        assertEquals(false, status.note(null, false, 31_000))
        assertEquals(false, status.note(null, false, 60_000))
        assertTrue(status.note(null, false, 61_000))
    }

    /** İzleyici telefon hiç bulunamasa da 30 sn'de bir satır yazar (önceden sessizdi). */
    @Test
    fun trackerLogsStatusEvenWhenPhoneIsNeverFound() {
        val lines = mutableListOf<String>()
        val tracker = GamePhoneTracker()
        tracker.onLog = { lines += it }
        val blank = TestPhoneImage(800, 450) { _, _ -> TestPhoneImage.gray(40) }
        var now = 1_000L
        while (now <= 1_000L + 31_000L) {
            tracker.sample(blank, now) { "çözücü biçimi 0x15" }
            now += 400
        }
        val status = lines.filter { it.contains("telefon arama durumu") }
        assertEquals(lines.toString(), 1, status.size)
        assertTrue(status[0], status[0].contains("telefon kapalı"))
        assertTrue(status[0], status[0].contains("0 bulundu"))
        assertTrue(status[0], status[0].contains("ortanca parlaklık 40/255"))
        assertTrue(status[0], status[0].contains("kare 800x450, çözücü biçimi 0x15"))
    }

    /** Android kare biçimi: sınırlı aralıklı Y "sınırlı aralıkta", tam aralıklı (çevrilmemiş) Y uyarıyla. */
    @Test
    fun androidFrameDescriptionShowsRawLumaRange() {
        fun frame(luma: (Int) -> Int): VideoFrame {
            val buffer = ByteBuffer.allocateDirect(640 * 360)
            for (i in 0 until 640 * 360) buffer.put(i, luma(i % 640 * 256 / 640).toByte())
            return VideoFrame(buffer, 640, 360, 640, 0x15)
        }
        val limited = PhoneFrames.describe(frame { 16 + it * 219 / 255 })
        assertTrue(limited, limited.startsWith("çözücü biçimi 0x15, renk yok, ham Y %2 "))
        assertTrue(limited, limited.endsWith("(sınırlı aralıkta)"))
        val full = PhoneFrames.describe(frame { it })
        assertTrue(full, full.endsWith("(sınırlı aralık dışı değer var, kare tam aralıklı olabilir)"))
    }
}
