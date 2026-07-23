package dev.tyler.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The canonical rules gate. These counts are the community-verified ground
 * truth (chessprogramming.org) — if they don't match, the engine is wrong,
 * not the test.
 */
class PerftTest {
    // Startpos depths 1-5.
    private val startposCounts = longArrayOf(20, 400, 8_902, 197_281, 4_865_609)

    // Kiwipete: the standard castling/EP/pin torture position (4-field FEN).
    private val kiwipete = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -"
    private val kiwipeteCounts = longArrayOf(48, 2_039, 97_862)

    // CPW position 3: EP discovered-check pins.
    private val cpw3 = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - -"
    private val cpw3Counts = longArrayOf(14, 191, 2_812, 43_238)

    // CPW position 5: promotion-heavy middlegame.
    private val cpw5 = "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8"
    private val cpw5Counts = longArrayOf(44, 1_486, 62_379)

    @Test
    fun startposDepths1Through4() {
        val board = Board.initial()
        for (depth in 1..4) {
            assertEquals(startposCounts[depth - 1], Perft.perft(board, depth), "startpos perft($depth)")
        }
    }

    @Test
    fun kiwipeteDepths1Through3() {
        val board = Board.fromFen(kiwipete)
        for (depth in 1..3) {
            assertEquals(kiwipeteCounts[depth - 1], Perft.perft(board, depth), "kiwipete perft($depth)")
        }
    }

    @Test
    fun cpwPosition3Depths1Through4() {
        val board = Board.fromFen(cpw3)
        for (depth in 1..4) {
            assertEquals(cpw3Counts[depth - 1], Perft.perft(board, depth), "cpw3 perft($depth)")
        }
    }

    @Test
    fun cpwPosition5Depths1Through3() {
        val board = Board.fromFen(cpw5)
        for (depth in 1..3) {
            assertEquals(cpw5Counts[depth - 1], Perft.perft(board, depth), "cpw5 perft($depth)")
        }
    }

    @Test
    fun startposDepth5Slow() {
        // No test-tagging infra exists in this repo (JUnit4 via kotlin-test),
        // so the slow depth is gated on an env var, which Gradle test workers
        // inherit without build-script changes:
        //   RUN_SLOW_PERFT=1 ./gradlew :tool:testDebugUnitTest
        if (System.getenv("RUN_SLOW_PERFT") != "1") return
        assertEquals(startposCounts[4], Perft.perft(Board.initial(), 5), "startpos perft(5)")
    }
}
