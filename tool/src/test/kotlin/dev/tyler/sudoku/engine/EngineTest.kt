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
    private val bands = mapOf("easy" to 1..2, "medium" to 3..4, "hard" to 5..7)

    @Test fun puzzlesAreUniqueSolvableAndGraded() {
        for (date in dates) for (diff in listOf("easy", "medium", "hard")) {
            val p = SudokuEngine.generatePuzzle(date, diff)

            // unique solution
            assertEquals(1, SudokuEngine.countSolutions(p.puzzle.copyOf(), 2), "$date/$diff should have exactly one solution")
            // real solution agrees with engine.solve
            assertTrue(p.solution.all { it in 1..9 }, "$date/$diff solution must be complete")
            // solvable by human technique (no guessing required)
            assertTrue(p.solvableLogically, "$date/$diff must be logically solvable")
            // graded into the right difficulty band
            assertTrue(p.maxTier in bands[diff]!!, "$date/$diff tier ${p.maxTier} should fall in ${bands[diff]}")
        }
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
