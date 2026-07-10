package dev.tyler.sudoku.data

import dev.tyler.sudoku.engine.SudokuEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecsTest {

    @Test fun settingsRoundTripAndVersionGuard() {
        val s = Settings(rowcol = true, timer = true, sound = true)
        val decoded = Codecs.decodeSettings(Codecs.encodeSettings(s))
        assertEquals(s, decoded)
        // wrong __v -> all-off defaults
        assertEquals(Settings(), Codecs.decodeSettings("""{"__v":1,"rowcol":true}"""))
        // corrupt / null -> defaults
        assertEquals(Settings(), Codecs.decodeSettings("not json"))
        assertEquals(Settings(), Codecs.decodeSettings(null))
        // field name parity with the prototype: __v present in output
        assertTrue(Codecs.encodeSettings(s).contains("\"__v\":2"))
    }

    @Test fun progressRoundTrip() {
        val p = ProgressDto(
            v = List(81) { it % 10 }, c = List(81) { 0 }, l = List(81) { 0 },
            a = 1, t = 123, s = 0, r = 0,
        )
        assertEquals(p, Codecs.decodeProgress(Codecs.encodeProgress(p)))
        assertNull(Codecs.decodeProgress("garbage"))
        assertNull(Codecs.decodeProgress(null))
    }

    @Test fun progressRoundTripCarriesAutoCandidateLayers() {
        val p = ProgressDto(
            v = List(81) { 0 }, c = List(81) { it % 4 }, l = List(81) { 0 },
            a = 1, t = 5, s = 0, r = 0,
            ca = List(81) { it % 3 }, cr = List(81) { it % 2 },
        )
        assertEquals(p, Codecs.decodeProgress(Codecs.encodeProgress(p)))
    }

    @Test fun oldProgressWithoutAutoLayersDecodesWithEmptyLayers() {
        val arr = (0 until 81).joinToString(",") { "0" }
        val legacy = """{"v":[$arr],"c":[$arr],"l":[$arr],"a":0,"t":10,"s":0,"r":0}"""
        val decoded = Codecs.decodeProgress(legacy)!!
        assertEquals(10, decoded.t, "legacy fields still decode")
        assertTrue(decoded.ca.isEmpty() && decoded.cr.isEmpty(), "missing auto layers default to empty")
    }

    @Test fun puzzleRoundTrip() {
        val p = SudokuEngine.generatePuzzle("2026-06-16", "easy")
        val q = Codecs.decodePuzzle(Codecs.encodePuzzle(p))!!
        assertTrue(p.puzzle.contentEquals(q.puzzle))
        assertTrue(p.solution.contentEquals(q.solution))
        assertEquals(p.clues, q.clues)
        assertEquals(p.maxTier, q.maxTier)
        assertEquals(p.solvableLogically, q.solvableLogically)
    }

    @Test fun indexRoundTrip() {
        val ix = mapOf(
            "2026-06-16:easy" to IndexEntry("done", 340),
            "2026-06-16:hard" to IndexEntry("progress"),
        )
        assertEquals(ix, Codecs.decodeIndex(Codecs.encodeIndex(ix)))
        assertEquals(emptyMap(), Codecs.decodeIndex(null))
        assertEquals(emptyMap(), Codecs.decodeIndex("garbage"))
    }

    @Test fun storeKeys() {
        assertEquals("puz:2026-06-16:easy", StoreKeys.puzzle("2026-06-16", "easy"))
        assertEquals("prog:2026-06-16:hard", StoreKeys.progress("2026-06-16", "hard"))
    }

    @Test fun dateKeys() {
        // 2026-07-06T12:00:00Z, but format uses default TZ; just check shape + count + ordering
        val now = 1783425600000L
        val days = DateKeys.lastNDays(3, now)
        assertEquals(3, days.size)
        assertEquals(DateKeys.today(now), days[0])
        assertTrue(days[0] > days[1] && days[1] > days[2], "descending date keys")
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(days[0]))
    }
}
