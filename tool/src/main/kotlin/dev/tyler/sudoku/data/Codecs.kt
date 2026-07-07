package dev.tyler.sudoku.data

import dev.tyler.sudoku.engine.SudokuEngine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** All settings toggles. Defaults ALL OFF, matching the prototype. */
data class Settings(
    val rowcol: Boolean = false,
    val box: Boolean = false,
    val same: Boolean = false,
    val conflicts: Boolean = false,
    val checkOnEntry: Boolean = false,
    val autoStart: Boolean = false,
    val timer: Boolean = false,
    val sound: Boolean = false,
    val plain: Boolean = false,
)

@Serializable
internal data class SettingsDto(
    @SerialName("__v") val v: Int = 2,
    val rowcol: Boolean = false, val box: Boolean = false, val same: Boolean = false,
    val conflicts: Boolean = false, val checkOnEntry: Boolean = false,
    val autoStart: Boolean = false, val timer: Boolean = false,
    val sound: Boolean = false, val plain: Boolean = false,
)

/** Per-puzzle progress; field names v/c/l/a/t/s/r carry over from the prototype. */
@Serializable
data class ProgressDto(
    val v: List<Int>, val c: List<Int>, val l: List<Int>,
    val a: Int, val t: Int, val s: Int, val r: Int,
)

@Serializable
internal data class PuzzleDto(
    val p: List<Int>, val s: List<Int>,
    val clues: Int, val maxTier: Int, val solv: Boolean,
)

@Serializable
data class IndexEntry(val status: String, val time: Int? = null)

object Codecs {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeSettings(s: Settings): String = json.encodeToString(
        SettingsDto(
            rowcol = s.rowcol, box = s.box, same = s.same, conflicts = s.conflicts,
            checkOnEntry = s.checkOnEntry, autoStart = s.autoStart,
            timer = s.timer, sound = s.sound, plain = s.plain,
        )
    )

    fun decodeSettings(raw: String?): Settings {
        val dto = raw?.let { runCatching { json.decodeFromString<SettingsDto>(it) }.getOrNull() }
            ?: return Settings()
        if (dto.v != 2) return Settings()
        return Settings(
            rowcol = dto.rowcol, box = dto.box, same = dto.same, conflicts = dto.conflicts,
            checkOnEntry = dto.checkOnEntry, autoStart = dto.autoStart,
            timer = dto.timer, sound = dto.sound, plain = dto.plain,
        )
    }

    fun encodeProgress(p: ProgressDto): String = json.encodeToString(p)

    fun decodeProgress(raw: String?): ProgressDto? =
        raw?.let { runCatching { json.decodeFromString<ProgressDto>(it) }.getOrNull() }

    fun encodePuzzle(p: SudokuEngine.Puzzle): String = json.encodeToString(
        PuzzleDto(p.puzzle.toList(), p.solution.toList(), p.clues, p.maxTier, p.solvableLogically)
    )

    fun decodePuzzle(raw: String?): SudokuEngine.Puzzle? {
        val dto = raw?.let { runCatching { json.decodeFromString<PuzzleDto>(it) }.getOrNull() }
            ?: return null
        if (dto.p.size != 81 || dto.s.size != 81) return null
        return SudokuEngine.Puzzle(
            dto.p.toIntArray(), dto.s.toIntArray(), dto.clues, dto.maxTier, dto.solv
        )
    }

    fun encodeIndex(ix: Map<String, IndexEntry>): String = json.encodeToString(ix)

    fun decodeIndex(raw: String?): Map<String, IndexEntry> =
        raw?.let { runCatching { json.decodeFromString<Map<String, IndexEntry>>(it) }.getOrNull() }
            ?: emptyMap()
}
