package dev.tyler.sudoku.engine

/**
 * Deterministic daily Sudoku engine — a faithful port of the verified engine.js
 * (see /reference/sudoku-light.html). Pure logic, no Android dependencies, so it is
 * unit-testable on the JVM with no instrumentation.
 *
 * Design contract carried over from the prototype:
 *  - generatePuzzle(dateKey, difficulty) is deterministic: same inputs -> same puzzle on every device.
 *  - Every generated puzzle has a UNIQUE solution and is solvable by human technique (no guessing).
 *  - Difficulty is graded by the HARDEST technique required, not just clue count.
 *
 * IMPORTANT (32-bit arithmetic): the JS RNG relies on Math.imul and unsigned shifts.
 *  - Math.imul(a, b)  == Kotlin Int multiply `a * b` (low 32 bits, two's complement) — identical.
 *  - JS `>>>` (unsigned shift) == Kotlin `ushr`.
 *  - JS `x >>> 0` (coerce to unsigned 32-bit) == `(x.toLong() and 0xFFFFFFFFL)`.
 *  - Big constants exceeding Int range are written as Long literals with `.toInt()` (low 32 bits).
 * Keep these exact or the daily puzzles will silently diverge between devices.
 */
object SudokuEngine {

    // ---- Seeded RNG: xmur3 seed -> mulberry32 stream (faithful to engine.js) ----
    class Rng(seedStr: String) {
        private var a: Int

        init {
            var h = 1779033703 xor seedStr.length
            for (ch in seedStr) {
                h = (h xor ch.code) * 3432918353.toInt()   // Math.imul
                h = (h shl 13) or (h ushr 19)
            }
            // xmur3 finalizer, called once to seed mulberry32
            h = (h xor (h ushr 16)) * 2246822507.toInt()
            h = (h xor (h ushr 13)) * 3266489909.toInt()
            h = h xor (h ushr 16)
            a = h
        }

        /** Returns a Double in [0, 1). */
        fun next(): Double {
            a += 0x6D2B79F5
            var t = a
            t = (t xor (t ushr 15)) * (1 or t)
            t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
    }

    fun makeRng(seed: String) = Rng(seed)

    /** Fisher–Yates using the seeded RNG; returns a new list. */
    fun <T> shuffle(arr: List<T>, rng: Rng): MutableList<T> {
        val a = arr.toMutableList()
        for (i in a.size - 1 downTo 1) {
            val j = (rng.next() * (i + 1)).toInt()
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
        return a
    }

    // ---- Geometry (precomputed once) ----
    private val ROWS: Array<IntArray> = Array(9) { r -> IntArray(9) { c -> r * 9 + c } }
    private val COLS: Array<IntArray> = Array(9) { c -> IntArray(9) { r -> r * 9 + c } }
    private val BOXES: Array<IntArray> = Array(9) { b ->
        val br = b / 3; val bc = b % 3
        val out = IntArray(9); var k = 0
        for (r in 0 until 3) for (c in 0 until 3) out[k++] = (br * 3 + r) * 9 + (bc * 3 + c)
        out
    }
    private val UNITS: Array<IntArray> = ROWS + COLS + BOXES

    fun boxOf(i: Int): Int = (i / 9 / 3) * 3 + (i % 9 / 3)

    private val PEERS: Array<IntArray> = Array(81) { i ->
        val s = LinkedHashSet<Int>()
        val r = i / 9; val c = i % 9; val b = boxOf(i)
        for (j in ROWS[r]) if (j != i) s.add(j)
        for (j in COLS[c]) if (j != i) s.add(j)
        for (j in BOXES[b]) if (j != i) s.add(j)
        s.toIntArray()
    }

    private fun candidatesFor(grid: IntArray, i: Int): IntArray {
        val used = BooleanArray(10)
        for (j in PEERS[i]) { val v = grid[j]; if (v != 0) used[v] = true }
        val out = ArrayList<Int>(9)
        for (d in 1..9) if (!used[d]) out.add(d)
        return out.toIntArray()
    }

    // ---- Full solution via randomized MRV backtracking ----
    fun generateFullSolution(rng: Rng): IntArray {
        val grid = IntArray(81)
        fun fill(): Boolean {
            var bi = -1; var bc: IntArray? = null
            for (i in 0 until 81) if (grid[i] == 0) {
                val c = candidatesFor(grid, i)
                if (c.isEmpty()) return false
                if (bc == null || c.size < bc!!.size) { bi = i; bc = c; if (c.size == 1) break }
            }
            if (bi == -1) return true
            for (d in shuffle(bc!!.toList(), rng)) { grid[bi] = d; if (fill()) return true; grid[bi] = 0 }
            return false
        }
        fill()
        return grid
    }

    /** Counts up to [limit] solutions; mutates [grid] but restores it in place. */
    fun countSolutions(grid: IntArray, limit: Int): Int {
        var bi = -1; var bc: IntArray? = null
        for (i in 0 until 81) if (grid[i] == 0) {
            val c = candidatesFor(grid, i)
            if (c.isEmpty()) return 0
            if (bc == null || c.size < bc!!.size) { bi = i; bc = c; if (c.size == 1) break }
        }
        if (bi == -1) return 1
        var total = 0
        for (d in bc!!) {
            grid[bi] = d
            total += countSolutions(grid, limit - total)
            grid[bi] = 0
            if (total >= limit) return total
        }
        return total
    }

    fun solve(grid: IntArray): IntArray? {
        val g = grid.copyOf()
        fun f(): Boolean {
            var bi = -1; var bc: IntArray? = null
            for (i in 0 until 81) if (g[i] == 0) {
                val c = candidatesFor(g, i)
                if (c.isEmpty()) return false
                if (bc == null || c.size < bc!!.size) { bi = i; bc = c; if (c.size == 1) break }
            }
            if (bi == -1) return true
            for (d in bc!!) { g[bi] = d; if (f()) return true; g[bi] = 0 }
            return false
        }
        return if (f()) g else null
    }

    // ---- Bit helpers for the logical solver ----
    private fun bit(d: Int) = 1 shl (d - 1)
    private fun popcount(m: Int) = Integer.bitCount(m)
    private fun digitsOf(m: Int): List<Int> { val o = ArrayList<Int>(); for (d in 1..9) if (m and bit(d) != 0) o.add(d); return o }
    private fun combos(arr: List<Int>, k: Int): List<List<Int>> {
        val res = ArrayList<List<Int>>(); val n = arr.size; val idx = IntArray(k)
        fun rec(start: Int, depth: Int) {
            if (depth == k) { res.add(idx.map { arr[it] }); return }
            for (i in start until n) { idx[depth] = i; rec(i + 1, depth + 1) }
        }
        rec(0, 0); return res
    }

    data class Step(val index: Int, val digit: Int)
    data class LogicalResult(val solved: Boolean, val usedMax: Int, val value: IntArray, val firstStep: Step?)

    /**
     * Human-technique solver. Tiers (escalating): 1 naked single, 2 hidden single,
     * 3 locked candidates, 4 naked pair, 5 hidden pair, 6 naked triple, 7 X-wing.
     * usedMax = the hardest technique genuinely required. firstStep = first placement (for Hint).
     */
    fun logicalSolve(grid: IntArray, maxTier: Int): LogicalResult {
        val value = grid.copyOf()
        val cand = IntArray(81)
        for (i in 0 until 81) if (value[i] == 0) { var m = 0; for (d in candidatesFor(value, i)) m = m or bit(d); cand[i] = m }
        var usedMax = 0
        var firstStep: Step? = null

        fun place(i: Int, d: Int) {
            value[i] = d; cand[i] = 0
            for (j in PEERS[i]) cand[j] = cand[j] and bit(d).inv()
            if (firstStep == null) firstStep = Step(i, d)
        }
        fun elim(i: Int, d: Int): Boolean {
            if (value[i] == 0 && (cand[i] and bit(d)) != 0) { cand[i] = cand[i] and bit(d).inv(); return true }
            return false
        }
        fun nakedSingles(): Boolean { var ch = false; for (i in 0 until 81) if (value[i] == 0 && popcount(cand[i]) == 1) { place(i, digitsOf(cand[i])[0]); ch = true }; return ch }
        fun hiddenSingles(): Boolean {
            var ch = false
            for (u in UNITS) for (d in 1..9) {
                var pos = -1; var cnt = 0; var present = false
                for (i in u) { if (value[i] == d) { present = true; break }; if (value[i] == 0 && (cand[i] and bit(d)) != 0) { cnt++; pos = i } }
                if (!present && cnt == 1) { place(pos, d); ch = true }
            }
            return ch
        }
        fun lockedCandidates(): Boolean {
            var ch = false
            for (b in 0 until 9) for (d in 1..9) {
                val cells = BOXES[b].filter { value[it] == 0 && (cand[it] and bit(d)) != 0 }
                if (cells.size < 2 || cells.size > 3) continue
                if (cells.map { it / 9 }.toSet().size == 1) { val r = cells[0] / 9; for (i in ROWS[r]) if (boxOf(i) != b && elim(i, d)) ch = true }
                if (cells.map { it % 9 }.toSet().size == 1) { val c = cells[0] % 9; for (i in COLS[c]) if (boxOf(i) != b && elim(i, d)) ch = true }
            }
            for (r in 0 until 9) for (d in 1..9) {
                val cells = ROWS[r].filter { value[it] == 0 && (cand[it] and bit(d)) != 0 }
                if (cells.size < 2 || cells.size > 3) continue
                if (cells.map { boxOf(it) }.toSet().size == 1) { val b = boxOf(cells[0]); for (i in BOXES[b]) if (i / 9 != r && elim(i, d)) ch = true }
            }
            for (c in 0 until 9) for (d in 1..9) {
                val cells = COLS[c].filter { value[it] == 0 && (cand[it] and bit(d)) != 0 }
                if (cells.size < 2 || cells.size > 3) continue
                if (cells.map { boxOf(it) }.toSet().size == 1) { val b = boxOf(cells[0]); for (i in BOXES[b]) if (i % 9 != c && elim(i, d)) ch = true }
            }
            return ch
        }
        fun nakedSubset(k: Int): Boolean {
            var ch = false
            for (u in UNITS) {
                val cells = u.filter { value[it] == 0 && popcount(cand[it]) in 2..k }
                for (combo in combos(cells, k)) {
                    var union = 0; for (i in combo) union = union or cand[i]
                    if (popcount(union) == k) {
                        val set = combo.toHashSet()
                        for (i in u) if (value[i] == 0 && i !in set) for (d in digitsOf(union)) if (elim(i, d)) ch = true
                    }
                }
            }
            return ch
        }
        fun hiddenSubset(k: Int): Boolean {
            var ch = false
            for (u in UNITS) {
                val dpos = HashMap<Int, List<Int>>()
                for (d in 1..9) { val cells = u.filter { value[it] == 0 && (cand[it] and bit(d)) != 0 }; if (cells.isNotEmpty()) dpos[d] = cells }
                val digits = dpos.keys.filter { dpos[it]!!.size in 2..k }
                for (combo in combos(digits, k)) {
                    val cellSet = HashSet<Int>(); for (d in combo) cellSet.addAll(dpos[d]!!)
                    if (cellSet.size == k) {
                        var keep = 0; for (d in combo) keep = keep or bit(d)
                        for (i in cellSet) { val extra = cand[i] and keep.inv(); if (extra != 0) for (d in digitsOf(extra)) if (elim(i, d)) ch = true }
                    }
                }
            }
            return ch
        }
        fun xwing(): Boolean {
            var ch = false
            for (d in 1..9) {
                val rc = ArrayList<Pair<Int, List<Int>>>()
                for (r in 0 until 9) { val cs = ROWS[r].filter { value[it] == 0 && (cand[it] and bit(d)) != 0 }.map { it % 9 }; if (cs.size == 2) rc.add(r to cs) }
                for (a in rc.indices) for (b in a + 1 until rc.size) {
                    val A = rc[a]; val B = rc[b]
                    if (A.second[0] == B.second[0] && A.second[1] == B.second[1]) {
                        for (r in 0 until 9) { if (r == A.first || r == B.first) continue; if (elim(r * 9 + A.second[0], d)) ch = true; if (elim(r * 9 + A.second[1], d)) ch = true }
                    }
                }
                val cr = ArrayList<Pair<Int, List<Int>>>()
                for (c in 0 until 9) { val rs = COLS[c].filter { value[it] == 0 && (cand[it] and bit(d)) != 0 }.map { it / 9 }; if (rs.size == 2) cr.add(c to rs) }
                for (a in cr.indices) for (b in a + 1 until cr.size) {
                    val A = cr[a]; val B = cr[b]
                    if (A.second[0] == B.second[0] && A.second[1] == B.second[1]) {
                        for (c in 0 until 9) { if (c == A.first || c == B.first) continue; if (elim(A.second[0] * 9 + c, d)) ch = true; if (elim(A.second[1] * 9 + c, d)) ch = true }
                    }
                }
            }
            return ch
        }

        var guard = 0
        while (guard++ < 2000) {
            if (value.all { it != 0 }) break
            if (nakedSingles()) { usedMax = maxOf(usedMax, 1); continue }
            if (maxTier >= 2 && hiddenSingles()) { usedMax = maxOf(usedMax, 2); continue }
            if (maxTier >= 3 && lockedCandidates()) { usedMax = maxOf(usedMax, 3); continue }
            if (maxTier >= 4 && nakedSubset(2)) { usedMax = maxOf(usedMax, 4); continue }
            if (maxTier >= 5 && hiddenSubset(2)) { usedMax = maxOf(usedMax, 5); continue }
            if (maxTier >= 6 && nakedSubset(3)) { usedMax = maxOf(usedMax, 6); continue }
            if (maxTier >= 7 && xwing()) { usedMax = maxOf(usedMax, 7); continue }
            break
        }
        return LogicalResult(value.all { it != 0 }, usedMax, value, firstStep)
    }

    private fun digToClues(full: IntArray, rng: Rng, targetClues: Int, ceiling: Int): IntArray {
        val puzzle = full.copyOf()
        val order = shuffle((0 until 81).toList(), rng)
        var clues = 81
        for (i in order) {
            if (clues <= targetClues) break
            val saved = puzzle[i]; if (saved == 0) continue
            puzzle[i] = 0
            if (countSolutions(puzzle, 2) != 1) { puzzle[i] = saved; continue }
            if (ceiling != 0 && !logicalSolve(puzzle, ceiling).solved) { puzzle[i] = saved; continue }
            clues--
        }
        return puzzle
    }

    data class Puzzle(
        val puzzle: IntArray,      // 81 ints, 0 = blank
        val solution: IntArray,    // 81 ints, full solution
        val clues: Int,
        val maxTier: Int,
        val solvableLogically: Boolean
    )

    private val BANDS = mapOf("easy" to (1..2), "medium" to (3..4), "hard" to (5..7))
    private val TARGETS = mapOf("easy" to 40, "medium" to 28, "hard" to 24)

    /** Grade-and-select: dig uniqueness-only, grade by hardest technique, keep first in-band. */
    fun generatePuzzle(dateKey: String, difficulty: String): Puzzle {
        val band = BANDS[difficulty]!!
        val target = TARGETS[difficulty]!!

        if (difficulty == "easy") {
            val rng = makeRng("$dateKey|easy|v2")
            val puzzle = digToClues(generateFullSolution(rng), rng, target, 2)
            return finalize(puzzle, null)
        }

        var best: Pair<IntArray, LogicalResult>? = null
        var bestDist = Int.MAX_VALUE
        for (attempt in 0 until 40) {
            val rng = makeRng("$dateKey|$difficulty|v2|$attempt")
            val puzzle = digToClues(generateFullSolution(rng), rng, target, 0)
            val grade = logicalSolve(puzzle, 7)
            if (!grade.solved) continue
            val t = grade.usedMax
            if (t in band) return finalize(puzzle, grade)
            val dist = if (t < band.first) band.first - t else t - band.last
            if (dist < bestDist) { bestDist = dist; best = puzzle to grade }
        }
        best?.let { return finalize(it.first, it.second) }
        val rng = makeRng("$dateKey|$difficulty|fb")
        return finalize(digToClues(generateFullSolution(rng), rng, target, 7), null)
    }

    private fun finalize(puzzle: IntArray, grade: LogicalResult?): Puzzle {
        val solution = solve(puzzle)!!
        val g = grade ?: logicalSolve(puzzle, 7)
        val clues = puzzle.count { it != 0 }
        return Puzzle(puzzle, solution, clues, g.usedMax, g.solved)
    }

    /** Candidates that are legal for cell [i] given the current grid (for auto-candidate display). */
    fun autoCandidates(grid: IntArray, i: Int): IntArray = candidatesFor(grid, i)
}
