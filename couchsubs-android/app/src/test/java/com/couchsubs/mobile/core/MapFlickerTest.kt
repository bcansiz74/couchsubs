// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Titreyen harita düzeni (#1365 beta.105, #1391): harita kayıp kararınca düzen düşmez, hazır alanlar okunmaz, liste
 * alanı panelde biter; satırın çizim kipi bir kez seçilir, haritada ikiz yok, boş kutu çizilmez. Ortak deneme dosyası
 * fixtures/map-flicker.json (core/Tests/CouchSubsCoreTests/Fixtures kopyası; Mac MapFlickerTests.swift ve Windows
 * MapFlickerTests.cs aynı dosyayı okur). Sahne çizimi MapLayoutTest ile ortak.
 */
class MapFlickerTest {
    private class Phase(val scene: MapLayoutTest.Scene, val everySample: Boolean)

    private fun fixture() = JSONObject(Fixtures.text("map-flicker.json"))

    private fun phases(): List<Phase> {
        val array = fixture().getJSONArray("phases")
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val panel = o.optJSONObject("panel")?.let { MapLayoutTest.Panel(it.getString("side"), it.getInt("columns"), it.getInt("fill")) }
            val boxes = o.getJSONArray("boxes").let { a ->
                (0 until a.length()).map { j ->
                    val b = a.getJSONObject(j)
                    val cells = b.getJSONArray("cells")
                    MapLayoutTest.Box((0 until cells.length()).map { cells.getInt(it) }, b.getInt("fill"))
                }
            }
            val texts = o.getJSONArray("texts").let { a ->
                (0 until a.length()).map { j ->
                    val t = a.getJSONObject(j)
                    MapLayoutTest.Text(t.getString("role"), t.getInt("column"), t.getInt("row"), t.getInt("width"), t.getString("kind"))
                }
            }
            Phase(MapLayoutTest.Scene(o.getString("name"), o.getInt("map"), o.getBoolean("texture"), o.getBoolean("moving"),
                false, panel, boxes, texts, o.getInt("untilMs"), o.getJSONObject("expect")), o.getBoolean("everySample"))
        }
    }

    private fun strings(array: JSONArray?): List<String> = array?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    @Test
    fun mapLayoutHoldsThroughFlicker() {
        val detector = ScreenTextDetector { 0L }
        val notes = mutableListOf<String>()
        detector.onLog = { notes.add(it) }
        var time = 0
        for (phase in phases()) {
            val scene = phase.scene
            val expect = scene.expect
            val end = time + scene.untilMs
            while (time <= end) {
                val frame = MapLayoutTest.pixels(scene, time / 100)
                detector.update(detector.sample(MapLayoutTest.WIDTH, MapLayoutTest.HEIGHT) { x, y -> frame[y * MapLayoutTest.WIDTH + x] },
                    listOf(CropRegion()), time.toLong())
                if (phase.everySample) assertEquals("${scene.name} @$time ms", expect.getBoolean("mapLayout"), detector.inMapLayout)
                time += ScreenTextDetector.SAMPLE_GAP_MS.toInt()
            }
            assertEquals(scene.name, expect.getBoolean("menu"), detector.menu)
            assertEquals(scene.name, expect.getBoolean("mapLayout"), detector.inMapLayout)
            val areas = detector.areas
            for (role in strings(expect.optJSONArray("together"))) {
                val held = scene.texts.filter { it.role == role }.map { MapLayoutTest.holder(areas, it)?.id }
                assertFalse("${scene.name}: $role alansız", held.contains(null))
                assertEquals("${scene.name}: $role bölündü", 1, held.toSet().size)
            }
            for (role in strings(expect.optJSONArray("clipped"))) {
                val panel = scene.panel
                val area = scene.texts.firstOrNull { it.role == role }?.let { MapLayoutTest.holder(areas, it) }
                assertNotNull("${scene.name}: $role alansız", panel)
                assertNotNull("${scene.name}: $role alansız", area)
                assertTrue("${scene.name}: $role harita düzeni alanı değil", area!!.map)
                val edge = panel!!.columns.toDouble() / ScreenTextDetector.COLUMNS
                if (panel.side == "left") {
                    assertTrue("${scene.name}: $role haritaya taşıyor", area.region.right <= edge + 1e-9)
                } else {
                    assertTrue("${scene.name}: $role haritaya taşıyor", area.region.left >= 1 - edge - 1e-9)
                }
            }
            if (expect.optBoolean("presetsYield", false)) {
                val kept = RegionWatcher.yieldingPresets(GameAreaPresets.areas("GTA 6") + areas)
                assertFalse("${scene.name}: hazır alan okunuyor", kept.any { GameAreaPresets.isPreset(it.id) })
            }
        }
        val expected = fixture().getJSONObject("notes")
        assertEquals("$notes", expected.getInt("found"), notes.count { it.startsWith("ekran menüsü: harita düzeni bulundu") })
        assertEquals("$notes", expected.getInt("ended"), notes.count { it == "ekran menüsü: harita düzeni bitti" })
    }

    private fun box(b: JSONArray) = TextBox(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))

    /** Ölçücünün verdiği kipte sentetik ölçü (yanına yazmada sağdaki boş alan, ikizde altta panel). */
    private fun paint(mode: String, box: TextBox): TextPaint {
        val paint = TextPaint(background = "#202020", pitch = box.bottom - box.top, cover = box)
        val width = box.right - box.left
        val height = box.bottom - box.top
        return when (mode) {
            "beside" -> {
                val spot = TextBox(box.right + 0.01, box.top, box.right + 0.01 + width, box.bottom)
                paint.copy(mode = TextPaint.MODE_BESIDE, spot = spot, spotRoom = spot)
            }
            "twin" -> {
                val twin = TextBox(box.left, box.bottom + 0.01, box.right, box.bottom + 0.01 + height)
                paint.copy(mode = TextPaint.MODE_TWIN, twin = twin, spot = twin)
            }
            else -> paint.copy(mode = TextPaint.MODE_COVER)
        }
    }

    @Test
    fun rowPaintStaysWhileTextUnchanged() {
        val sequences = fixture().getJSONArray("paints")
        for (s in 0 until sequences.length()) {
            val sequence = sequences.getJSONObject(s)
            val name = sequence.getString("name")
            val map = sequence.getBoolean("map")
            val expected = sequence.getJSONObject("expected")
            val reads = sequence.getJSONArray("reads")
            val drawn = sequence.getJSONArray("drawn")
            val memory = HashMap<String, RegionWatcher.RowPaint>()
            for (index in 0 until reads.length()) {
                val read = reads.getJSONArray(index).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                val blocks = read.mapIndexed { i, row ->
                    val b = box(row.getJSONArray("box"))
                    RegionWatcher.Block(i, 1, b, 0.04, paint = paint(row.getString("mode"), b))
                }
                val out = RegionWatcher.steadyPaints(blocks, read.map { it.getString("text") }, memory, map)
                for ((row, block) in read.zip(out)) {
                    val text = row.getString("text")
                    assertEquals("$name okuma $index: $text", expected.getString(text), block.paint?.mode)
                    if (map) assertNotEquals("$name: haritada ikiz", TextPaint.MODE_TWIN, block.paint?.mode)
                    // Taşınan ölçü satırın yeni yerindedir (örtü satırın üstünde).
                    block.paint?.cover?.let { assertEquals("$name: $text", box(row.getJSONArray("box")).top, it.top, 1e-9) }
                }
                val items = read.map {
                    ScreenTranslation.Item(it.getString("text"), it.getString("translation"), box(it.getJSONArray("box")), 0.04)
                }
                assertEquals("$name okuma $index", strings(drawn.getJSONArray(index)), RegionWatcher.drawable(items).map { it.source })
            }
        }
    }

    /**
     * Düzenin sürmesi: bulunan düzen panel yerinde durdukça kalır; panel art arda [ScreenTextDetector.MAP_MISS_SAMPLES]
     * örnek yoksa ya da ekran bütünüyle değişirse biter; aynı yanda bir karo oynayan genişlik düzeni değiştirmez.
     */
    @Test
    fun mapHold() {
        val hold = ScreenTextDetector.MapHold()
        assertNull(hold.next(null, holds = false, sceneChanged = false))
        assertEquals(9, hold.next(ScreenTextDetector.MapPanel(true, 9), holds = false, sceneChanged = false)?.width)
        assertEquals("genişlik oynaması", 9, hold.next(ScreenTextDetector.MapPanel(true, 10), holds = false, sceneChanged = false)?.width)
        assertEquals("panel yerinde: harita kararsa da sürer", 9, hold.next(null, holds = true, sceneChanged = true)?.width)
        for (i in 1 until ScreenTextDetector.MAP_MISS_SAMPLES) {
            assertNotNull("tek karelik ıska", hold.next(null, holds = false, sceneChanged = false))
        }
        assertNull("art arda ıska", hold.next(null, holds = false, sceneChanged = false))
        assertNotNull(hold.next(ScreenTextDetector.MapPanel(false, 8), holds = false, sceneChanged = false))
        assertNull("ekran değişti", hold.next(null, holds = false, sceneChanged = true))
    }

    /** Simge ve bozuk okuma süzgeçleri menü dışında da (beta.105: "KM Collect…", "Kh Search…", "ocdich…"). */
    @Test
    fun filtersOutsideMenu() {
        fun read(text: String, confidence: Float? = null): List<String> {
            val line = TextLine(text, TextBox(0.1, 0.3, 0.5, 0.34), confidence = confidence)
            return RegionWatcher.candidate(Reading(listOf(line)), CropRegion(top = 0.2, bottom = 0.5, left = 0.05, right = 0.6),
                auto = true, menu = false).paragraphs
        }
        assertEquals(listOf("Collect Chemicals for Kyle"), read("KM Collect Chemicals for Kyle"))
        assertEquals(listOf("Search for the Skunk Ape"), read("Kh Search for the Skunk Ape"))
        assertEquals(listOf("Go Find The Boat"), read("Go Find The Boat"))
        assertEquals(emptyList<String>(), read("ocdich for the onurn Ape", confidence = 0.5f))
    }
}
