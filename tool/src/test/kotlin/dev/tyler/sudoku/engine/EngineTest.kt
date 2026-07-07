package dev.tyler.sudoku.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-JVM acceptance tests for the engine. These are the bar the Kotlin port must clear; they
 * mirror what was verified in Node for the prototype. Run: ./gradlew test
 */
class EngineTest {

    private val dates = listOf("2026-06-16", "2026-06-17", "2026-01-01", "2025-12-25")

    // Pinned from the verified engine.js ground truth (Node, 2026-07-06). Hard can grade below its
    // nominal 5..7 band when the generator's 40 attempts find nothing in-band and it falls back to
    // the nearest — matching the JS behavior exactly IS the contract.
    private val expected = mapOf(
        "2026-06-16" to mapOf("easy" to (40 to 1), "medium" to (28 to 4), "hard" to (25 to 5)),
        "2026-06-17" to mapOf("easy" to (40 to 1), "medium" to (28 to 3), "hard" to (24 to 4)),
        "2026-01-01" to mapOf("easy" to (40 to 1), "medium" to (28 to 3), "hard" to (26 to 4)),
        "2025-12-25" to mapOf("easy" to (40 to 1), "medium" to (28 to 3), "hard" to (25 to 5)),
    )

    @Test fun puzzlesAreUniqueSolvableAndGraded() {
        for (date in dates) for (diff in listOf("easy", "medium", "hard")) {
            val p = SudokuEngine.generatePuzzle(date, diff)

            // unique solution
            assertEquals(1, SudokuEngine.countSolutions(p.puzzle.copyOf(), 2), "$date/$diff should have exactly one solution")
            // real solution agrees with engine.solve
            assertTrue(p.solution.all { it in 1..9 }, "$date/$diff solution must be complete")
            // solvable by human technique (no guessing required)
            assertTrue(p.solvableLogically, "$date/$diff must be logically solvable")
            // exact ground truth from the verified engine.js
            val (expClues, expTier) = expected[date]!![diff]!!
            assertEquals(expClues, p.clues, "$date/$diff clues")
            assertEquals(expTier, p.maxTier, "$date/$diff tier")
        }
    }

    @Test fun matchesVerifiedJsEngineExactly() {
        // First 20 cells pinned from engine.js output for two seeds.
        val easy = SudokuEngine.generatePuzzle("2026-06-16", "easy")
        assertEquals("1,0,2,0,0,7,3,9,0,0,0,3,0,2,9,8,0,0,0,7", easy.puzzle.take(20).joinToString(","))
        val hard = SudokuEngine.generatePuzzle("2026-06-17", "hard")
        assertEquals("0,0,9,0,5,3,0,0,4,0,5,0,0,0,0,8,0,0,7,4", hard.puzzle.take(20).joinToString(","))
    }

    @Test fun clueCountsSeparateDifficulty() {
        val date = "2026-06-16"
        val easy = SudokuEngine.generatePuzzle(date, "easy").clues
        val hard = SudokuEngine.generatePuzzle(date, "hard").clues
        assertEquals(40, easy, "easy targets 40 clues")
        assertTrue(hard < easy, "hard should have fewer clues than easy")
    }

    @Test fun generationIsDeterministic() {
        for (diff in listOf("easy", "medium", "hard")) {
            val a = SudokuEngine.generatePuzzle("2026-06-16", diff)
            val b = SudokuEngine.generatePuzzle("2026-06-16", diff)
            assertTrue(a.puzzle.contentEquals(b.puzzle), "$diff must be identical across calls")
        }
    }
}
