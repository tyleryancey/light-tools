# Sudoku Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the sudoku-light scaffold (`~/Documents/sudoku-light`) into this repo's `tool/` module as a Light SDK tool built on `LightScreen`/`LightViewModel`, passing the plugin scan.

**Architecture:** Three screens (Home `@InitialScreen` → Game, Archive) with per-screen ViewModels; a stateless `KeyValueStore` over the SDK's process-wide DataStore; the engine copied verbatim. Dual palette (dark/light) driven by `LightThemeController`. Spec: `docs/superpowers/specs/2026-07-06-sudoku-port-design.md`.

**Tech Stack:** Kotlin 2.3, Compose (BOM via sdk), Light SDK `:sdk:client`, kotlinx-serialization, DataStore Preferences, kotlin.test + kotlinx-coroutines-test.

## Global Constraints

- Working dir for all commands: `/Users/tyleryancey/Documents/light-phone-3-lightos-dev/light-sdk`. Scaffold source: `/Users/tyleryancey/Documents/sudoku-light` (read-only — never modify it).
- `JAVA_HOME` must be JDK 17 before any `./gradlew` call: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- The plugin scan walks ALL of `tool/src/` (tests included) at Gradle configuration time. Banned imports: `android.app.*`, `android.content.Context|Intent|ComponentName|BroadcastReceiver|ContentProvider|ServiceConnection`, `androidx.compose.ui.platform.LocalContext|LocalView|LocalLifecycleOwner`, `androidx.activity.ComponentActivity`, `androidx.activity.compose.setContent`, `androidx.appcompat.*`, `java.lang.reflect.*`, `kotlin.reflect.*`. Banned patterns: `getSystemService(`, `startActivity(`, `startService(`, `bindService(`, `registerReceiver(`, `contentResolver`, `LocalContext.current`, `LocalView.current`, `as …Activity`, `Class.forName(`, `.javaClass`, `.java.<word>` (note: bare `::class.java` is fine), `.getDeclaredMethod(` etc.
- Package for all new code: `dev.tyler.sudoku` (tool id `dev.tyler.sudoku`, label `Sudoku` — permanent once published).
- No `AndroidManifest.xml` in `tool/src/main/` — the plugin generates it from `lighttool.toml` and errors on a user manifest.
- Tests use `kotlin.test` imports (`kotlin.test.Test`, `assertEquals(expected, actual, message?)` — message LAST, unlike JUnit4's message-first).
- Test command: `./gradlew :tool:testDebugUnitTest`. Build+scan command: `./gradlew :tool:assembleDebug`.
- Do not modify `SudokuEngine.kt` beyond the package line. Do not touch `sdk/`, `plugin/`, or `examples/`.
- Commit after every task; end commit messages with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Engine port + acceptance tests (the green bar)

**Files:**
- Create: `tool/src/main/kotlin/dev/tyler/sudoku/engine/SudokuEngine.kt` (copy)
- Create: `tool/src/test/kotlin/dev/tyler/sudoku/engine/EngineTest.kt` (copy + convert)

**Interfaces:**
- Consumes: nothing.
- Produces: `object SudokuEngine` — `generatePuzzle(dateKey: String, difficulty: String): Puzzle` where `data class Puzzle(val puzzle: IntArray, val solution: IntArray, val clues: Int, val maxTier: Int, val solvableLogically: Boolean)`; `logicalSolve(grid: IntArray, maxTier: Int): LogicalResult` with `LogicalResult(val solved: Boolean, val usedMax: Int, val value: IntArray, val firstStep: Step?)`, `data class Step(val index: Int, val digit: Int)`; `boxOf(i: Int): Int`; `autoCandidates(grid: IntArray, i: Int): IntArray`; `countSolutions(grid: IntArray, limit: Int): Int`.

- [ ] **Step 1: Copy the engine verbatim, rename package**

```bash
mkdir -p tool/src/main/kotlin/dev/tyler/sudoku/engine
cp /Users/tyleryancey/Documents/sudoku-light/app/src/main/java/dev/tyler/sudokulight/engine/SudokuEngine.kt \
   tool/src/main/kotlin/dev/tyler/sudoku/engine/SudokuEngine.kt
```

Then edit line 1 only: `package dev.tyler.sudokulight.engine` → `package dev.tyler.sudoku.engine`. No other change.

- [ ] **Step 2: Copy EngineTest, convert JUnit4 → kotlin.test**

```bash
mkdir -p tool/src/test/kotlin/dev/tyler/sudoku/engine
cp /Users/tyleryancey/Documents/sudoku-light/app/src/test/java/dev/tyler/sudokulight/EngineTest.kt \
   tool/src/test/kotlin/dev/tyler/sudoku/engine/EngineTest.kt
```

Apply exactly these edits:
1. `package dev.tyler.sudokulight` → `package dev.tyler.sudoku.engine`
2. Delete `import dev.tyler.sudokulight.engine.SudokuEngine` (same package now).
3. Replace the three org.junit imports with:
```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
```
4. Reorder assertion args (message moves LAST):
```kotlin
// before (JUnit4)                                          // after (kotlin.test)
assertEquals("$date/$diff should have exactly one solution", 1, SudokuEngine.countSolutions(p.puzzle.copyOf(), 2))
assertEquals(1, SudokuEngine.countSolutions(p.puzzle.copyOf(), 2), "$date/$diff should have exactly one solution")

assertTrue("$date/$diff solution must be complete", p.solution.all { it in 1..9 })
assertTrue(p.solution.all { it in 1..9 }, "$date/$diff solution must be complete")

assertTrue("$date/$diff must be logically solvable", p.solvableLogically)
assertTrue(p.solvableLogically, "$date/$diff must be logically solvable")

assertTrue("$date/$diff tier ${p.maxTier} should fall in ${bands[diff]}", p.maxTier in bands[diff]!!)
assertTrue(p.maxTier in bands[diff]!!, "$date/$diff tier ${p.maxTier} should fall in ${bands[diff]}")

assertEquals("easy targets 40 clues", 40, easy)
assertEquals(40, easy, "easy targets 40 clues")

assertTrue("hard should have fewer clues than easy", hard < easy)
assertTrue(hard < easy, "hard should have fewer clues than easy")

assertTrue("$diff must be identical across calls", a.puzzle.contentEquals(b.puzzle))
assertTrue(a.puzzle.contentEquals(b.puzzle), "$diff must be identical across calls")
```

- [ ] **Step 3: Run the tests**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :tool:testDebugUnitTest --tests 'dev.tyler.sudoku.engine.EngineTest' 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, 3 tests pass. (The sample tool code still exists; that's fine — it compiles alongside.)

- [ ] **Step 4: Commit**

```bash
git add tool/src && git commit -m "feat(sudoku): port engine verbatim with kotlin.test acceptance tests"
```

---

### Task 2: Tool identity swap (toml, entry point, delete sample)

**Files:**
- Modify: `tool/lighttool.toml` (full replace)
- Delete: `tool/src/main/kotlin/com/thelightphone/sample/` (all three files)
- Create: `tool/src/main/kotlin/dev/tyler/sudoku/ToolEntryPoint.kt`
- Create: `tool/src/main/kotlin/dev/tyler/sudoku/ui/home/HomeScreen.kt` (placeholder — replaced in Task 11)

**Interfaces:**
- Produces: `@InitialScreen class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>` — the KSP processor requires exactly ONE `@InitialScreen` class app-wide with a single `(SealedLightActivity)` constructor, which is why deleting the sample and adding ours happens in one task.

- [ ] **Step 1: Replace `tool/lighttool.toml` entirely with:**

```toml
[tool]
id = "dev.tyler.sudoku"
label = "Sudoku"
versionCode = 1
versionName = "1.0"
permissions = []
# change if you run this on an LP3!
# serverPackage = "com.lightos"
serverPackage = "com.thelightphone.sdk.emulator"
```

- [ ] **Step 2: Delete the sample package**

```bash
rm -r tool/src/main/kotlin/com/thelightphone
```

- [ ] **Step 3: Create `tool/src/main/kotlin/dev/tyler/sudoku/ToolEntryPoint.kt`:**

```kotlin
package dev.tyler.sudoku

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    // Sudoku is fully offline: no server data, no push.
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {}
    override suspend fun onPushNotification(data: ByteArray) {}
}
```

- [ ] **Step 4: Create placeholder `tool/src/main/kotlin/dev/tyler/sudoku/ui/home/HomeScreen.kt`:**

```kotlin
package dev.tyler.sudoku.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    @Composable
    override fun Content() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Sudoku")
        }
    }
}
```

- [ ] **Step 5: Build (runs plugin scan + KSP registry generation)**

Run: `./gradlew :tool:assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL. If it fails with "@InitialScreen must be applied to exactly one class", the sample deletion in Step 2 didn't happen.

- [ ] **Step 6: Commit**

```bash
git add -A tool && git commit -m "feat(sudoku): claim tool identity dev.tyler.sudoku, replace sample entry point"
```

---
### Task 3: Dual palette + SudokuSurface wrapper

**Files:**
- Create: `tool/src/main/kotlin/dev/tyler/sudoku/ui/theme/SudokuPalette.kt`

**Interfaces:**
- Consumes: `LightThemeController.colors: StateFlow<LightColors>`, `LightColors.inferredSurfaceScheme()`, `LightTheme(colors) {}`, `LightSurfaceScheme` — all from `com.thelightphone.sdk.ui`.
- Produces: `data class SudokuPalette` (21 val fields below), `SudokuPalette.Dark`, `SudokuPalette.Light`, `val LocalSudokuPalette: ProvidableCompositionLocal<SudokuPalette>`, `@Composable fun SudokuSurface(content: @Composable () -> Unit)`. Every later Composable reads colors as `val pal = LocalSudokuPalette.current`.

- [ ] **Step 1: Create the file:**

```kotlin
package dev.tyler.sudoku.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.thelightphone.sdk.ui.LightSurfaceScheme
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.inferredSurfaceScheme

/**
 * Sudoku board tokens, one full ramp per OS theme. Dark is the scaffold's
 * SudokuColors verbatim; Light is the mirrored ramp from the design spec.
 * Both are device-calibration candidates — keep every color in this file.
 */
data class SudokuPalette(
    val bg: Color, val frame: Color, val line: Color, val box: Color,
    val givenTile: Color, val peer: Color, val sameNum: Color, val sel: Color,
    val givenInk: Color, val entryInk: Color, val pencilInk: Color,
    val ring: Color, val dot: Color, val txt: Color, val txtDim: Color,
    val txtFaint: Color, val hair: Color, val btn: Color, val btnLine: Color,
    val selInk: Color,
) {
    companion object {
        val Dark = SudokuPalette(
            bg = Color(0xFF000000), frame = Color(0xFFDCDCDC), line = Color(0xFF1B1B1B),
            box = Color(0xFF4A4A4A), givenTile = Color(0xFF141414), peer = Color(0xFF1F1F1F),
            sameNum = Color(0xFF2F2F2F), sel = Color(0xFFF1F1F1), givenInk = Color(0xFFF0F0F0),
            entryInk = Color(0xFFC9C9C9), pencilInk = Color(0xFF6E6E6E), ring = Color(0xFF8A8A8A),
            dot = Color(0xFFF1F1F1), txt = Color(0xFFEDEDED), txtDim = Color(0xFF7C7C7C),
            txtFaint = Color(0xFF555555), hair = Color(0xFF262626), btn = Color(0xFF161616),
            btnLine = Color(0xFF333333), selInk = Color(0xFF0A0A0A),
        )
        val Light = SudokuPalette(
            bg = Color(0xFFFFFFFF), frame = Color(0xFF232323), line = Color(0xFFE4E4E4),
            box = Color(0xFFB5B5B5), givenTile = Color(0xFFEBEBEB), peer = Color(0xFFE0E0E0),
            sameNum = Color(0xFFD0D0D0), sel = Color(0xFF0E0E0E), givenInk = Color(0xFF0F0F0F),
            entryInk = Color(0xFF363636), pencilInk = Color(0xFF919191), ring = Color(0xFF757575),
            dot = Color(0xFF0E0E0E), txt = Color(0xFF121212), txtDim = Color(0xFF838383),
            txtFaint = Color(0xFFAAAAAA), hair = Color(0xFFD9D9D9), btn = Color(0xFFE9E9E9),
            btnLine = Color(0xFFCCCCCC), selInk = Color(0xFFF5F5F5),
        )
    }
}

val LocalSudokuPalette = staticCompositionLocalOf { SudokuPalette.Dark }

/** Wrap every screen's Content() in this: OS theme -> LightTheme + palette + bg fill. */
@Composable
fun SudokuSurface(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    val palette = if (colors.inferredSurfaceScheme() == LightSurfaceScheme.Dark) {
        SudokuPalette.Dark
    } else {
        SudokuPalette.Light
    }
    LightTheme(colors = colors) {
        CompositionLocalProvider(LocalSudokuPalette provides palette) {
            Box(Modifier.fillMaxSize().background(palette.bg)) { content() }
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :tool:assembleDebug 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add tool/src && git commit -m "feat(sudoku): dual dark/light palette driven by LightThemeController"
```

---

### Task 4: Data layer — KeyValueStore, Codecs, DateKeys (+ coroutines-test dep)

**Files:**
- Modify: `gradle/libs.versions.toml` (add one line after line 30, next to the other kotlinx-coroutines entries)
- Modify: `tool/build.gradle.kts` (dependencies block)
- Create: `tool/src/main/kotlin/dev/tyler/sudoku/data/KeyValueStore.kt`
- Create: `tool/src/main/kotlin/dev/tyler/sudoku/data/Codecs.kt`
- Create: `tool/src/main/kotlin/dev/tyler/sudoku/data/DateKeys.kt`
- Create: `tool/src/test/kotlin/dev/tyler/sudoku/data/InMemoryKeyValueStore.kt`
- Test: `tool/src/test/kotlin/dev/tyler/sudoku/data/CodecsTest.kt`

**Interfaces:**
- Produces (used by Tasks 6–7, 11):
  - `interface KeyValueStore { suspend fun get(key: String): String?; suspend fun set(key: String, value: String); suspend fun delete(key: String) }`
  - `class DataStoreKeyValueStore(dataStore: DataStore<Preferences>) : KeyValueStore`
  - `class InMemoryKeyValueStore : KeyValueStore` (test sources; also exposes `val map: MutableMap<String, String>`)
  - `object StoreKeys { const val SETTINGS = "settings"; const val INDEX = "index"; fun puzzle(dateKey: String, difficulty: String): String; fun progress(dateKey: String, difficulty: String): String }`
  - `data class Settings(...)` (domain, all-off defaults) + `object Codecs` with: `encodeSettings(s: Settings): String`, `decodeSettings(raw: String?): Settings`, `encodeProgress(p: ProgressDto): String`, `decodeProgress(raw: String?): ProgressDto?`, `encodePuzzle(p: SudokuEngine.Puzzle): String`, `decodePuzzle(raw: String?): SudokuEngine.Puzzle?`, `encodeIndex(ix: Map<String, IndexEntry>): String`, `decodeIndex(raw: String?): Map<String, IndexEntry>`
  - `@Serializable data class ProgressDto(val v: List<Int>, val c: List<Int>, val l: List<Int>, val a: Int, val t: Int, val s: Int, val r: Int)`
  - `@Serializable data class IndexEntry(val status: String, val time: Int? = null)`
  - `object DateKeys { fun today(now: Long): String; fun lastNDays(n: Int, now: Long): List<String>; fun prettyShort(key: String): String }`

- [ ] **Step 1: Add to `gradle/libs.versions.toml` after the `kotlinx-coroutines-core` line:**

```toml
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version = "1.10.2" }
```

- [ ] **Step 2: In `tool/build.gradle.kts`, extend the dependencies block to:**

```kotlin
dependencies {
    implementation(project(":sdk:client"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    ksp(libs.androidx.room.compiler)
}
```

(`org.jetbrains.kotlinx:kotlinx-coroutines-test` passes the allowlist via the `org.jetbrains.kotlinx:kotlinx-coroutines` prefix.)

- [ ] **Step 3: Write the failing test `tool/src/test/kotlin/dev/tyler/sudoku/data/CodecsTest.kt`:**

```kotlin
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
```

- [ ] **Step 4: Run to verify failure**

Run: `./gradlew :tool:testDebugUnitTest --tests 'dev.tyler.sudoku.data.CodecsTest' 2>&1 | tail -10`
Expected: FAIL — unresolved references (`Settings`, `Codecs`, …).

- [ ] **Step 5: Create `tool/src/main/kotlin/dev/tyler/sudoku/data/KeyValueStore.kt`:**

```kotlin
package dev.tyler.sudoku.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * The prototype's `window.storage` seam. Backed by the SDK's process-wide
 * DataStore in production; in-memory in tests.
 */
interface KeyValueStore {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
    suspend fun delete(key: String)
}

class DataStoreKeyValueStore(private val dataStore: DataStore<Preferences>) : KeyValueStore {
    override suspend fun get(key: String): String? =
        dataStore.data.first()[stringPreferencesKey(key)]

    override suspend fun set(key: String, value: String) {
        dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun delete(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }
}

object StoreKeys {
    const val SETTINGS = "settings"
    const val INDEX = "index"
    fun puzzle(dateKey: String, difficulty: String) = "puz:$dateKey:$difficulty"
    fun progress(dateKey: String, difficulty: String) = "prog:$dateKey:$difficulty"
}
```

- [ ] **Step 6: Create `tool/src/main/kotlin/dev/tyler/sudoku/data/Codecs.kt`:**

```kotlin
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
```

Note: check `SudokuEngine.Puzzle`'s exact constructor parameter order in the copied file (`puzzle, solution, clues, maxTier, solvableLogically`) and match it.

- [ ] **Step 7: Create `tool/src/main/kotlin/dev/tyler/sudoku/data/DateKeys.kt`:**

```kotlin
package dev.tyler.sudoku.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateKeys {
    private fun keyFmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun today(now: Long): String = keyFmt().format(Date(now))

    /** Today first, then n-1 previous days. */
    fun lastNDays(n: Int, now: Long): List<String> {
        val fmt = keyFmt()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return List(n) { i ->
            if (i > 0) cal.add(Calendar.DAY_OF_YEAR, -1)
            fmt.format(cal.time)
        }
    }

    /** "2026-07-06" -> "Mon, Jul 6" (device locale). Falls back to the key on parse failure. */
    fun prettyShort(key: String): String = try {
        SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(keyFmt().parse(key)!!)
    } catch (_: Exception) {
        key
    }
}
```

- [ ] **Step 8: Create `tool/src/test/kotlin/dev/tyler/sudoku/data/InMemoryKeyValueStore.kt`:**

```kotlin
package dev.tyler.sudoku.data

class InMemoryKeyValueStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override suspend fun get(key: String): String? = map[key]
    override suspend fun set(key: String, value: String) { map[key] = value }
    override suspend fun delete(key: String) { map.remove(key) }
}
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `./gradlew :tool:testDebugUnitTest --tests 'dev.tyler.sudoku.data.CodecsTest' 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 6 tests pass.

- [ ] **Step 10: Commit**

```bash
git add gradle/libs.versions.toml tool && git commit -m "feat(sudoku): data layer — KeyValueStore, serialization codecs, date keys"
```

---

### Task 5: SolveFeedback — Context-free chime

**Files:**
- Create: `tool/src/main/kotlin/dev/tyler/sudoku/feedback/SolveFeedback.kt`
- Test: `tool/src/test/kotlin/dev/tyler/sudoku/feedback/SolveFeedbackTest.kt`

**Interfaces:**
- Produces: `object SolveFeedback { fun playChime(); internal fun buildArpeggioPcm(sampleRate: Int): ShortArray }`. `GameViewModel` (Task 6) takes `playChime: () -> Unit = SolveFeedback::playChime`.

The sandbox kills the scaffold's ringer/DND/vibrator branches (`getSystemService(` is banned). Only the AudioTrack synthesis survives — `android.media.*` imports are allowed and `AudioTrack` needs no Context. Haptics happen UI-side in Task 9 via `LocalHapticFeedback`.

- [ ] **Step 1: Write the failing test:**

```kotlin
package dev.tyler.sudoku.feedback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolveFeedbackTest {
    @Test fun pcmIsDeterministicBoundedAndNonSilent() {
        val a = SolveFeedback.buildArpeggioPcm(44100)
        val b = SolveFeedback.buildArpeggioPcm(44100)
        assertTrue(a.contentEquals(b), "synthesis must be deterministic")
        assertEquals((44100 * 0.77).toInt(), a.size, "0.32s offset + 0.45s tail")
        assertTrue(a.any { it != 0.toShort() }, "must not be silence")
        // normalized with 0.7 master gain: no clipping at the rails
        assertTrue(a.all { it > Short.MIN_VALUE }, "no negative clipping")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :tool:testDebugUnitTest --tests 'dev.tyler.sudoku.feedback.SolveFeedbackTest' 2>&1 | tail -5`
Expected: FAIL — unresolved reference `SolveFeedback`.

- [ ] **Step 3: Create the implementation.** Port from `/Users/tyleryancey/Documents/sudoku-light/app/src/main/java/dev/tyler/sudokulight/feedback/SolveFeedback.kt`: keep `playChime()` (private→public) and `buildArpeggioPcm()` (private→internal) EXACTLY as in the scaffold body; delete the class wrapper, constructor, `mode()`, `onSolve()`, `vibrate()`, and all `android.app`/`android.content`/`android.os` imports:

```kotlin
package dev.tyler.sudoku.feedback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Solve chime. The scaffold's ringer/DND-aware mode() is impossible in the
 * Tool sandbox (getSystemService is banned), so the single `sound` setting
 * gates this chime plus a UI-side haptic; the OS gates each channel itself
 * (media volume, system haptics). AudioTrack needs no Context.
 */
object SolveFeedback {

    /** Call on a legitimate solve when settings.sound is on. Fail-soft. */
    fun playChime() {
        try {
            val sampleRate = 44100
            val pcm = buildArpeggioPcm(sampleRate)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack(
                attrs, format, pcm.size * 2,
                AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) { t?.release() }
                override fun onPeriodicNotification(t: AudioTrack?) {}
            })
            track.play()
        } catch (_: Exception) { /* a missing chime must never crash a solve */ }
    }

    /** Soft bell arpeggio C5-E5-G5-C6; copied unchanged from the scaffold. */
    internal fun buildArpeggioPcm(sampleRate: Int): ShortArray {
        val notes = listOf(
            523.25 to 0.00,   // C5
            659.25 to 0.10,   // E5
            783.99 to 0.20,   // G5
            1046.50 to 0.32   // C6
        )
        val noteDur = 0.45
        val totalDur = 0.32 + noteDur
        val n = (sampleRate * totalDur).toInt()
        val buf = DoubleArray(n)
        val peak = 0.16

        for ((freq, start) in notes) {
            val startSample = (start * sampleRate).toInt()
            val len = (noteDur * sampleRate).toInt()
            for (k in 0 until len) {
                val idx = startSample + k
                if (idx >= n) break
                val t = k.toDouble() / sampleRate
                val attack = (t / 0.012).coerceAtMost(1.0)
                val env = attack * exp(-t * 6.0) * peak
                buf[idx] += sin(2.0 * PI * freq * t) * env
            }
        }

        var max = 1e-9
        for (v in buf) if (kotlin.math.abs(v) > max) max = kotlin.math.abs(v)
        val gain = (0.7 / max).coerceAtMost(1.0)
        val out = ShortArray(n)
        for (i in 0 until n) out[i] = (buf[i] * gain * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        return out
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :tool:testDebugUnitTest --tests 'dev.tyler.sudoku.feedback.SolveFeedbackTest' 2>&1 | tail -5`
Expected: PASS. (The PCM builder is pure Kotlin math; only `playChime` touches android.media, and tests never call it.)

- [ ] **Step 5: Commit**

```bash
git add tool/src && git commit -m "feat(sudoku): context-free solve chime, sandbox-safe"
```

---
### Task 6: GameViewModel part A — state model, open/load, persistence, settings, lifecycle

**Files:**
- Create: `tool/src/main/kotlin/dev/tyler/sudoku/ui/game/GameViewModel.kt`
- Test: `tool/src/test/kotlin/dev/tyler/sudoku/ui/game/GameViewModelTest.kt`

**Interfaces:**
- Consumes: Task 4's `KeyValueStore`/`Codecs`/`StoreKeys`/`DateKeys`/`Settings`/`ProgressDto`/`IndexEntry`, Task 1's `SudokuEngine`, Task 5's `SolveFeedback::playChime`.
- Produces (Tasks 7, 9–11 rely on these exact names):
  - `enum class InputMode { NORMAL, CANDIDATE }`
  - `sealed interface Overlay` — `Menu, HintPage, SettingsSheet, Help, Paused, ConfirmReset, ConfirmReveal` (data objects), `data class Win(val timeText: String, val subtitle: String)`
  - `sealed interface GameResult { data object Closed; data object OpenArchive }`
  - `data class UndoFrame(val i: Int, val value: Int, val cand: Int, val err: Boolean, val locked: Boolean)`
  - `data class GameUiState(...)` — scaffold's fields minus `screen`, plus `overlay: Overlay? = null`, `toast: String? = null`, `generating: Boolean = true`
  - `class GameViewModel(dateKey: String, difficulty: String, store: KeyValueStore, playChime: () -> Unit = SolveFeedback::playChime, now: () -> Long = System::currentTimeMillis) : LightViewModel<GameResult>()` — part A methods: `ui: StateFlow<GameUiState>`, `refreshElapsed()`, `persistProgress()`, `toggleSetting(name: String)`, `dismissOverlay()`, `pauseTapped()`, `fmtTime(sec: Int): String`, plus `onScreenShow/onScreenHide/onAppPause/onBackPressed` overrides.
- Design note: NO ticker coroutine in the VM. The UI (Task 9) runs `LaunchedEffect` polling `vm.refreshElapsed()` every 250 ms while `running`. This keeps the VM free of infinite loops so `runTest`+`advanceUntilIdle()` terminates.

- [ ] **Step 1: Write the failing test file:**

```kotlin
package dev.tyler.sudoku.ui.game

import dev.tyler.sudoku.data.Codecs
import dev.tyler.sudoku.data.InMemoryKeyValueStore
import dev.tyler.sudoku.data.ProgressDto
import dev.tyler.sudoku.data.StoreKeys
import dev.tyler.sudoku.engine.SudokuEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: InMemoryKeyValueStore
    private var clock = 0L
    private var chimes = 0

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = InMemoryKeyValueStore()
        clock = 0L
        chimes = 0
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm(date: String = "2026-06-16", diff: String = "easy") =
        GameViewModel(date, diff, store, playChime = { chimes++ }, now = { clock })

    @Test fun openGeneratesCachesAndStartsTimer() = runTest {
        val vm = vm(); advanceUntilIdle()
        val ui = vm.ui.value
        assertFalse(ui.generating)
        assertTrue(ui.running, "timer starts on open of unsolved puzzle")
        assertEquals(40, ui.givenMask.count { it }, "easy targets 40 clues")
        assertNotNull(store.map[StoreKeys.puzzle("2026-06-16", "easy")], "puzzle cached")
        // deterministic: cache round-trips to the same board
        val p = SudokuEngine.generatePuzzle("2026-06-16", "easy")
        assertTrue(p.puzzle.withIndex().all { (i, v) -> (v != 0) == ui.givenMask[i] })
    }

    @Test fun openRestoresProgress() = runTest {
        val p = SudokuEngine.generatePuzzle("2026-06-16", "easy")
        val editable = (0 until 81).first { p.puzzle[it] == 0 }
        val values = IntArray(81)
        values[editable] = 5
        store.map[StoreKeys.progress("2026-06-16", "easy")] = Codecs.encodeProgress(
            ProgressDto(
                v = values.toList(), c = List(81) { 0 }, l = List(81) { 0 },
                a = 0, t = 77, s = 0, r = 0,
            )
        )
        val vm = vm(); advanceUntilIdle()
        assertEquals(5, vm.ui.value.values[editable])
        assertEquals(77, vm.ui.value.elapsedSec)
        // givens must come from the puzzle, not the stored v-array
        assertTrue(vm.ui.value.givenMask.withIndex().all { (i, g) -> !g || vm.ui.value.values[i] == p.puzzle[i] })
    }

    @Test fun corruptProgressIsIgnored() = runTest {
        store.map[StoreKeys.progress("2026-06-16", "easy")] = "not json"
        val vm = vm(); advanceUntilIdle()
        assertFalse(vm.ui.value.generating)
        assertEquals(0, vm.ui.value.elapsedSec)
    }

    @Test fun timerAccountsWithVirtualClockAndPersistsOnPause() = runTest {
        val vm = vm(); advanceUntilIdle()
        clock = 12_000L
        vm.refreshElapsed()
        assertEquals(12, vm.ui.value.elapsedSec)
        vm.onAppPause(); advanceUntilIdle()
        assertFalse(vm.ui.value.running)
        val prog = Codecs.decodeProgress(store.map[StoreKeys.progress("2026-06-16", "easy")])
        assertEquals(12, prog!!.t)
        // resume continues from the base, not from zero
        clock = 20_000L
        vm.resumeFromShow()
        assertTrue(vm.ui.value.running)
        clock = 25_000L
        vm.refreshElapsed()
        assertEquals(17, vm.ui.value.elapsedSec, "12s banked + 5s since resume")
    }

    @Test fun toggleSettingPersistsAndRetroFlagsCheckOnEntry() = runTest {
        val p = SudokuEngine.generatePuzzle("2026-06-16", "easy")
        val editable = (0 until 81).first { p.puzzle[it] == 0 }
        val wrong = if (p.solution[editable] == 1) 2 else 1
        val values = IntArray(81); values[editable] = wrong
        store.map[StoreKeys.progress("2026-06-16", "easy")] = Codecs.encodeProgress(
            ProgressDto(values.toList(), List(81) { 0 }, List(81) { 0 }, 0, 0, 0, 0)
        )
        val vm = vm(); advanceUntilIdle()
        vm.toggleSetting("checkOnEntry"); advanceUntilIdle()
        assertTrue(vm.ui.value.settings.checkOnEntry)
        assertTrue(vm.ui.value.checkErr[editable], "existing wrong entry flagged retroactively")
        assertTrue(Codecs.decodeSettings(store.map[StoreKeys.SETTINGS]).checkOnEntry, "persisted")
    }

    @Test fun backPressWithoutOverlayPopsAfterPersist() = runTest {
        val vm = vm(); advanceUntilIdle()
        assertFalse(vm.onBackPressed()); advanceUntilIdle()
        assertNotNull(store.map[StoreKeys.progress("2026-06-16", "easy")])
    }

    @Test fun pauseOverlayStopsTimerAndDismissResumes() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.pauseTapped(); advanceUntilIdle()
        assertEquals(Overlay.Paused, vm.ui.value.overlay)
        assertFalse(vm.ui.value.running)
        vm.dismissOverlay(); advanceUntilIdle()
        assertEquals(null, vm.ui.value.overlay)
        assertTrue(vm.ui.value.running)
    }
}
```

Testability note: `SimpleLightScreen` cannot be instantiated on the JVM (it needs a real `SealedLightActivity`), so tests never call the screen-parameterized overrides `onScreenShow`/`onScreenHide`. Instead the VM exposes the no-arg forwarding targets — `resumeFromShow()` (called by the `onScreenShow` override) and the inherited no-arg `onAppPause()` — and tests drive those directly, as written above.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :tool:testDebugUnitTest --tests 'dev.tyler.sudoku.ui.game.GameViewModelTest' 2>&1 | tail -10`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Create `tool/src/main/kotlin/dev/tyler/sudoku/ui/game/GameViewModel.kt`:**

```kotlin
package dev.tyler.sudoku.ui.game

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import dev.tyler.sudoku.data.Codecs
import dev.tyler.sudoku.data.DateKeys
import dev.tyler.sudoku.data.IndexEntry
import dev.tyler.sudoku.data.KeyValueStore
import dev.tyler.sudoku.data.ProgressDto
import dev.tyler.sudoku.data.Settings
import dev.tyler.sudoku.data.StoreKeys
import dev.tyler.sudoku.engine.SudokuEngine
import dev.tyler.sudoku.feedback.SolveFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class InputMode { NORMAL, CANDIDATE }

/** Transient UI as synchronous state so onBackPressed() can answer "is something open?". */
sealed interface Overlay {
    data object Menu : Overlay
    data object HintPage : Overlay
    data object SettingsSheet : Overlay
    data object Help : Overlay
    data object Paused : Overlay
    data object ConfirmReset : Overlay
    data object ConfirmReveal : Overlay
    data class Win(val timeText: String, val subtitle: String) : Overlay
}

sealed interface GameResult {
    data object Closed : GameResult
    data object OpenArchive : GameResult
}

data class UndoFrame(val i: Int, val value: Int, val cand: Int, val err: Boolean, val locked: Boolean)

data class GameUiState(
    val dateKey: String = "",
    val difficulty: String = "medium",
    val solution: IntArray = IntArray(81),
    val values: IntArray = IntArray(81),
    val givenMask: BooleanArray = BooleanArray(81),
    val lockedMask: BooleanArray = BooleanArray(81),
    val candidates: IntArray = IntArray(81),   // pencil bitmasks (manual mode)
    val checkErr: BooleanArray = BooleanArray(81),
    val selected: Int = -1,
    val mode: InputMode = InputMode.NORMAL,
    val autoCandidate: Boolean = false,
    val undo: List<UndoFrame> = emptyList(),
    val solved: Boolean = false,
    val usedReveal: Boolean = false,
    val elapsedSec: Int = 0,
    val running: Boolean = false,
    val settings: Settings = Settings(),
    val generating: Boolean = true,
    val overlay: Overlay? = null,
    val toast: String? = null,
)

class GameViewModel(
    private val dateKey: String,
    private val difficulty: String,
    private val store: KeyValueStore,
    private val playChime: () -> Unit = SolveFeedback::playChime,
    private val now: () -> Long = System::currentTimeMillis,
) : LightViewModel<GameResult>() {

    private val _ui = MutableStateFlow(GameUiState(dateKey = dateKey, difficulty = difficulty))
    val ui = _ui.asStateFlow()
    private val s get() = _ui.value

    // Timer accounting in ms; UI shows floor(seconds).
    private var baseMs = 0L
    private var startTs = 0L
    private var clueLayout: IntArray = IntArray(81)   // original clue grid, captured at open

    init { viewModelScope.launch { open() } }

    // ---------- open / generate ----------
    private suspend fun open() {
        val settings = Codecs.decodeSettings(store.get(StoreKeys.SETTINGS))
        val cacheKey = StoreKeys.puzzle(dateKey, difficulty)
        val p = Codecs.decodePuzzle(store.get(cacheKey)) ?: withContext(Dispatchers.Default) {
            SudokuEngine.generatePuzzle(dateKey, difficulty)
        }.also { store.set(cacheKey, Codecs.encodePuzzle(it)) }

        val given = BooleanArray(81) { p.puzzle[it] != 0 }
        clueLayout = p.puzzle.copyOf()
        val values = p.puzzle.copyOf()
        var cand = IntArray(81)
        var locked = BooleanArray(81)
        var auto = settings.autoStart
        var solved = false
        var usedReveal = false
        var elapsed = 0

        Codecs.decodeProgress(store.get(StoreKeys.progress(dateKey, difficulty)))?.let { o ->
            for (i in 0 until 81) if (!given[i]) values[i] = o.v.getOrElse(i) { 0 }
            cand = IntArray(81) { o.c.getOrElse(it) { 0 } }
            locked = BooleanArray(81) { o.l.getOrElse(it) { 0 } == 1 }
            auto = o.a == 1; solved = o.s == 1; usedReveal = o.r == 1; elapsed = o.t
        }

        baseMs = elapsed * 1000L
        startTs = 0L
        _ui.value = s.copy(
            generating = false, solution = p.solution, values = values, givenMask = given,
            lockedMask = locked, candidates = cand, checkErr = BooleanArray(81),
            selected = -1, mode = InputMode.NORMAL, autoCandidate = auto,
            undo = emptyList(), solved = solved, usedReveal = usedReveal,
            elapsedSec = elapsed, running = false, settings = settings,
        )
        if (!solved) startTimer()
    }

    // ---------- timer ----------
    private fun startTimer() {
        if (s.running || s.solved) return
        startTs = now()
        _ui.value = s.copy(running = true)
    }

    private fun stopTimer() {
        if (!s.running) return
        baseMs += now() - startTs
        _ui.value = s.copy(running = false, elapsedSec = (baseMs / 1000).toInt())
    }

    private fun elapsedSec(): Int {
        var ms = baseMs
        if (s.running) ms += now() - startTs
        return (ms / 1000).toInt()
    }

    /** Called by the UI's 250ms LaunchedEffect while running — no coroutine loop in the VM. */
    fun refreshElapsed() {
        if (s.running) _ui.value = s.copy(elapsedSec = elapsedSec())
    }

    // ---------- lifecycle ----------
    override fun onScreenShow(screen: SimpleLightScreen<GameResult>) { resumeFromShow() }
    override fun onScreenHide(screen: SimpleLightScreen<GameResult>) { pauseForBackground() }
    override fun onAppPause() { pauseForBackground() }

    fun resumeFromShow() {
        if (!s.generating && !s.solved && s.overlay == null) startTimer()
    }

    private fun pauseForBackground() {
        if (!s.running) return
        stopTimer()
        persistProgress()
    }

    /** Back closes overlays (hint page steps back to the menu) before popping the screen. */
    override fun onBackPressed(): Boolean {
        when (s.overlay) {
            null -> {
                stopTimer()
                persistProgress()
                return false
            }
            Overlay.HintPage -> _ui.value = s.copy(overlay = Overlay.Menu)
            else -> dismissOverlay()
        }
        return true
    }

    // ---------- overlays (openers for part B UI; dismiss shared) ----------
    fun dismissOverlay() {
        val wasPaused = s.overlay == Overlay.Paused
        _ui.value = s.copy(overlay = null)
        if (wasPaused && !s.solved) startTimer()
    }

    fun pauseTapped() {
        if (s.solved) return
        stopTimer()
        persistProgress()
        _ui.value = s.copy(overlay = Overlay.Paused)
    }

    // ---------- settings ----------
    fun toggleSetting(name: String) {
        val st = s.settings
        val next = when (name) {
            "rowcol" -> st.copy(rowcol = !st.rowcol)
            "box" -> st.copy(box = !st.box)
            "same" -> st.copy(same = !st.same)
            "conflicts" -> st.copy(conflicts = !st.conflicts)
            "checkOnEntry" -> st.copy(checkOnEntry = !st.checkOnEntry)
            "autoStart" -> st.copy(autoStart = !st.autoStart)
            "timer" -> st.copy(timer = !st.timer)
            "sound" -> st.copy(sound = !st.sound)
            "plain" -> st.copy(plain = !st.plain)
            else -> st
        }
        _ui.value = s.copy(settings = next)
        // retroactively flag existing entries when enabling check-on-entry (matches prototype)
        if (name == "checkOnEntry" && next.checkOnEntry && !next.plain) checkPuzzleSilent()
        viewModelScope.launch { store.set(StoreKeys.SETTINGS, Codecs.encodeSettings(next)) }
    }

    private fun checkPuzzleSilent() {
        val err = s.checkErr.copyOf()
        for (i in 0 until 81) {
            if (s.givenMask[i] || s.lockedMask[i] || s.values[i] == 0) continue
            err[i] = s.values[i] != s.solution[i]
        }
        _ui.value = s.copy(checkErr = err)
    }

    // ---------- persistence ----------
    private var persistJob: Job? = null

    fun persistProgress() {
        if (s.generating) return
        val p = ProgressDto(
            v = s.values.toList(), c = s.candidates.toList(),
            l = s.lockedMask.map { if (it) 1 else 0 },
            a = if (s.autoCandidate) 1 else 0, t = elapsedSec(),
            s = if (s.solved) 1 else 0, r = if (s.usedReveal) 1 else 0,
        )
        viewModelScope.launch {
            store.set(StoreKeys.progress(dateKey, difficulty), Codecs.encodeProgress(p))
            val cur = Codecs.decodeIndex(store.get(StoreKeys.INDEX))["$dateKey:$difficulty"]
            if (cur?.status != "done") {
                writeIndex(
                    when { s.solved -> "done"; isStarted() -> "progress"; else -> "new" },
                    if (s.solved) elapsedSec() else null,
                )
            }
        }
    }

    internal fun persistProgressSoon() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch { delay(400); persistProgress() }
    }

    private suspend fun writeIndex(status: String, time: Int?) {
        val ix = Codecs.decodeIndex(store.get(StoreKeys.INDEX)).toMutableMap()
        ix["$dateKey:$difficulty"] = IndexEntry(status, time)
        store.set(StoreKeys.INDEX, Codecs.encodeIndex(ix))
    }

    private fun isStarted(): Boolean {
        for (i in 0 until 81) {
            if (s.givenMask[i]) continue
            if (s.values[i] != 0 || s.candidates[i] != 0 || s.lockedMask[i]) return true
        }
        return false
    }

    // ---------- toast ----------
    private var toastJob: Job? = null
    internal fun toast(t: String) {
        _ui.value = s.copy(toast = t)
        toastJob?.cancel()
        toastJob = viewModelScope.launch { delay(1700); _ui.value = s.copy(toast = null) }
    }

    // ---------- helpers ----------
    fun fmtTime(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val ss = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }
}
```

(`DateKeys` and `playChime` are referenced by part B — the imports and constructor param already land here so part B only adds methods.)

- [ ] **Step 4: Run the tests**

Run: `./gradlew :tool:testDebugUnitTest --tests 'dev.tyler.sudoku.ui.game.GameViewModelTest' 2>&1 | tail -8`
Expected: 7 tests PASS. If `Module with the Main dispatcher had failed to initialize` appears, the `Dispatchers.setMain` in `setUp` isn't running — check the `@BeforeTest` import.

- [ ] **Step 5: Commit**

```bash
git add tool/src && git commit -m "feat(sudoku): GameViewModel state model, open/persist/settings, lifecycle + back handling"
```

---

### Task 7: GameViewModel part B — input, undo, hints, checks, reveals, win

**Files:**
- Modify: `tool/src/main/kotlin/dev/tyler/sudoku/ui/game/GameViewModel.kt` (add methods to the class, before the `// ---------- helpers ----------` section)
- Modify: `tool/src/test/kotlin/dev/tyler/sudoku/ui/game/GameViewModelTest.kt` (append tests)

**Interfaces:**
- Produces (Task 9–10 UI calls these): `select(i: Int)`, `setMode(m: InputMode)`, `toggleAutoCandidate()`, `input(d: Int)`, `erase()`, `undo()`, `pointHint()`, `fillHint()`, `checkCell()`, `checkPuzzle()`, `revealCell()`, `requestRevealPuzzle()`, `confirmRevealPuzzle()`, `requestReset()`, `confirmReset()`, `showMenu()`, `showHintPage()`, `showSettings()`, `showHelp()`, `conflicts(): BooleanArray`.

- [ ] **Step 1: Append these tests to `GameViewModelTest`:**

```kotlin
    @Test fun solvingLegitimatelyWinsChimesAndMarksIndexDone() = runTest {
        val vm = vm(); advanceUntilIdle()
        val ui = vm.ui.value
        // enable sound so the chime fires
        vm.toggleSetting("sound"); advanceUntilIdle()
        clock = 90_000L
        for (i in 0 until 81) if (!ui.givenMask[i]) {
            vm.select(i); vm.input(ui.solution[i])
        }
        advanceUntilIdle()
        val end = vm.ui.value
        assertTrue(end.solved)
        assertTrue(end.overlay is Overlay.Win)
        assertEquals("1:30", (end.overlay as Overlay.Win).timeText)
        assertEquals(1, chimes)
        assertFalse(end.running)
        val ix = Codecs.decodeIndex(store.map[StoreKeys.INDEX])
        assertEquals("done", ix["2026-06-16:easy"]!!.status)
        assertEquals(90, ix["2026-06-16:easy"]!!.time)
    }

    @Test fun revealPuzzleBlocksWinCelebration() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.toggleSetting("sound"); advanceUntilIdle()
        vm.requestRevealPuzzle()
        assertEquals(Overlay.ConfirmReveal, vm.ui.value.overlay)
        vm.confirmRevealPuzzle()
        // assert the toast BEFORE advanceUntilIdle — the 1700ms auto-clear runs on virtual time
        assertEquals("Puzzle revealed", vm.ui.value.toast)
        advanceUntilIdle()
        val end = vm.ui.value
        assertTrue(end.solved)
        assertTrue(end.usedReveal)
        assertEquals(0, chimes, "reveal must not chime")
        assertTrue(end.overlay !is Overlay.Win)
        // index done with no time
        val ix = Codecs.decodeIndex(store.map[StoreKeys.INDEX])
        assertEquals("done", ix["2026-06-16:easy"]!!.status)
        assertEquals(null, ix["2026-06-16:easy"]!!.time)
    }

    @Test fun candidateModeTogglesPencilBit() = runTest {
        val vm = vm(); advanceUntilIdle()
        val i = (0 until 81).first { !vm.ui.value.givenMask[it] }
        vm.setMode(InputMode.CANDIDATE)
        vm.select(i)
        vm.input(3); advanceUntilIdle()
        assertEquals(1 shl 2, vm.ui.value.candidates[i])
        vm.input(3); advanceUntilIdle()
        assertEquals(0, vm.ui.value.candidates[i])
        assertEquals(0, vm.ui.value.values[i], "candidate mode never places values")
    }

    @Test fun undoRestoresPriorCellState() = runTest {
        val vm = vm(); advanceUntilIdle()
        val i = (0 until 81).first { !vm.ui.value.givenMask[it] }
        vm.select(i); vm.input(7); advanceUntilIdle()
        assertEquals(7, vm.ui.value.values[i])
        vm.undo(); advanceUntilIdle()
        assertEquals(0, vm.ui.value.values[i])
        assertEquals(i, vm.ui.value.selected, "undo re-selects the affected cell")
    }

    @Test fun inputClearsDigitFromPeerPencilMarks() = runTest {
        val vm = vm(); advanceUntilIdle()
        val ui = vm.ui.value
        // find two empty cells in the same row
        val row = (0 until 9).first { r -> (0 until 9).count { !ui.givenMask[r * 9 + it] } >= 2 }
        val cells = (0 until 9).map { row * 9 + it }.filter { !ui.givenMask[it] }
        val (a, b) = cells[0] to cells[1]
        vm.setMode(InputMode.CANDIDATE); vm.select(b); vm.input(4)
        vm.setMode(InputMode.NORMAL); vm.select(a); vm.input(4)
        advanceUntilIdle()
        assertEquals(0, vm.ui.value.candidates[b] and (1 shl 3), "peer pencil 4 cleared")
    }

    @Test fun fillHintLocksCellAndToasts() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.fillHint()
        // toast asserted before advanceUntilIdle (virtual-time auto-clear)
        assertEquals("Filled one square for you", vm.ui.value.toast)
        advanceUntilIdle()
        val ui = vm.ui.value
        val locked = (0 until 81).filter { ui.lockedMask[it] }
        assertEquals(1, locked.size)
        assertEquals(ui.solution[locked[0]], ui.values[locked[0]])
    }

    @Test fun checkPuzzleCountsWrongEntries() = runTest {
        val vm = vm(); advanceUntilIdle()
        val ui = vm.ui.value
        val i = (0 until 81).first { !ui.givenMask[it] }
        val wrong = if (ui.solution[i] == 1) 2 else 1
        vm.select(i); vm.input(wrong)
        vm.checkPuzzle()
        // toast asserted before advanceUntilIdle (virtual-time auto-clear)
        assertEquals("1 number is off", vm.ui.value.toast)
        advanceUntilIdle()
        assertTrue(vm.ui.value.checkErr[i])
    }

    @Test fun conflictsFlagsDuplicatesInRow() = runTest {
        val vm = vm(); advanceUntilIdle()
        val ui = vm.ui.value
        val row = (0 until 9).first { r -> (0 until 9).count { !ui.givenMask[r * 9 + it] } >= 2 }
        val cells = (0 until 9).map { row * 9 + it }.filter { !ui.givenMask[it] }
        vm.select(cells[0]); vm.input(9)
        vm.select(cells[1]); vm.input(9)
        val bad = vm.conflicts()
        assertTrue(bad[cells[0]] && bad[cells[1]])
    }

    @Test fun resetRestoresCluesAndZeroesTimer() = runTest {
        val vm = vm(); advanceUntilIdle()
        val i = (0 until 81).first { !vm.ui.value.givenMask[it] }
        vm.select(i); vm.input(5)
        clock = 30_000L
        vm.requestReset()
        assertEquals(Overlay.ConfirmReset, vm.ui.value.overlay)
        vm.confirmReset(); advanceUntilIdle()
        val end = vm.ui.value
        assertEquals(0, end.values[i])
        assertEquals(0, end.elapsedSec)
        assertTrue(end.running, "reset restarts the timer")
        assertFalse(end.solved)
    }

    @Test fun overlayBackNavigation() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.showMenu()
        vm.showHintPage()
        assertEquals(Overlay.HintPage, vm.ui.value.overlay)
        assertTrue(vm.onBackPressed(), "hint page consumes back")
        assertEquals(Overlay.Menu, vm.ui.value.overlay, "…stepping back to the menu")
        assertTrue(vm.onBackPressed(), "menu consumes back")
        assertEquals(null, vm.ui.value.overlay)
        assertFalse(vm.onBackPressed(), "no overlay: back pops the screen")
    }
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./gradlew :tool:testDebugUnitTest --tests 'dev.tyler.sudoku.ui.game.GameViewModelTest' 2>&1 | tail -8`
Expected: FAIL — unresolved `select`, `input`, etc.

- [ ] **Step 3: Add the part-B methods to `GameViewModel` (insert before `// ---------- toast ----------`):**

```kotlin
    // ---------- selection / input ----------
    fun select(i: Int) { _ui.value = s.copy(selected = i, elapsedSec = elapsedSec()) }

    fun setMode(m: InputMode) { if (!s.autoCandidate) _ui.value = s.copy(mode = m) }

    fun toggleAutoCandidate() {
        val on = !s.autoCandidate
        _ui.value = s.copy(autoCandidate = on, mode = if (on) InputMode.NORMAL else s.mode)
        persistProgressSoon()
    }

    private fun canEdit(i: Int) = i in 0..80 && !s.givenMask[i] && !s.lockedMask[i] && !s.solved

    private fun pushUndo(i: Int): List<UndoFrame> =
        (s.undo + UndoFrame(i, s.values[i], s.candidates[i], s.checkErr[i], s.lockedMask[i])).takeLast(400)

    fun input(d: Int) {
        val i = s.selected
        if (i < 0 || s.solved) return
        if (s.mode == InputMode.CANDIDATE && !s.autoCandidate) {
            if (!canEdit(i) || s.values[i] != 0) return
            val cand = s.candidates.copyOf()
            cand[i] = cand[i] xor (1 shl (d - 1))
            _ui.value = s.copy(candidates = cand, undo = pushUndo(i))
            persistProgressSoon()
            return
        }
        if (!canEdit(i)) return
        val undo = pushUndo(i)
        val values = s.values.copyOf(); val cand = s.candidates.copyOf(); val err = s.checkErr.copyOf()
        values[i] = d; cand[i] = 0; err[i] = false
        if (s.settings.checkOnEntry && !s.settings.plain) err[i] = d != s.solution[i]
        // courtesy: clear this digit from peers' manual pencil marks
        for (j in peersOf(i)) if (values[j] == 0 && (cand[j] and (1 shl (d - 1))) != 0) {
            cand[j] = cand[j] and (1 shl (d - 1)).inv()
        }
        _ui.value = s.copy(values = values, candidates = cand, checkErr = err, undo = undo)
        persistProgressSoon()
        checkWin()
    }

    fun erase() {
        val i = s.selected
        if (!canEdit(i)) return
        if (s.values[i] == 0 && s.candidates[i] == 0) return
        val undo = pushUndo(i)
        val values = s.values.copyOf(); val cand = s.candidates.copyOf(); val err = s.checkErr.copyOf()
        values[i] = 0; cand[i] = 0; err[i] = false
        _ui.value = s.copy(values = values, candidates = cand, checkErr = err, undo = undo)
        persistProgressSoon()
    }

    fun undo() {
        if (s.solved || s.undo.isEmpty()) return
        val u = s.undo.last()
        val values = s.values.copyOf(); val cand = s.candidates.copyOf()
        val err = s.checkErr.copyOf(); val locked = s.lockedMask.copyOf()
        values[u.i] = u.value; cand[u.i] = u.cand; err[u.i] = u.err; locked[u.i] = u.locked
        _ui.value = s.copy(
            values = values, candidates = cand, checkErr = err, lockedMask = locked,
            selected = u.i, undo = s.undo.dropLast(1),
        )
        persistProgressSoon()
    }

    // ---------- overlay openers ----------
    fun showMenu() { _ui.value = s.copy(overlay = Overlay.Menu) }
    fun showHintPage() { _ui.value = s.copy(overlay = Overlay.HintPage) }
    fun showSettings() { _ui.value = s.copy(overlay = Overlay.SettingsSheet) }
    fun showHelp() { _ui.value = s.copy(overlay = Overlay.Help) }
    fun requestRevealPuzzle() { _ui.value = s.copy(overlay = Overlay.ConfirmReveal) }
    fun requestReset() { _ui.value = s.copy(overlay = Overlay.ConfirmReset) }

    // ---------- hints ----------
    fun pointHint() {
        dismissOverlay()
        if (s.solved) { toast("Puzzle is finished"); return }
        if (s.values.all { it != 0 }) { toast("Board is already full"); return }
        val g = SudokuEngine.logicalSolve(s.values, 7)
        val target = g.firstStep?.index ?: s.values.indexOfFirst { it == 0 }
        if (target < 0) { toast("Nothing to point to"); return }
        _ui.value = s.copy(selected = target)
        toast(if (g.firstStep != null) "This square is solvable next" else "Try this square")
    }

    fun fillHint() {
        dismissOverlay()
        if (s.solved) { toast("Puzzle is finished"); return }
        val g = SudokuEngine.logicalSolve(s.values, 7)
        val step = g.firstStep
        val i = step?.index
            ?: (if (s.selected >= 0 && s.values[s.selected] == 0) s.selected else s.values.indexOfFirst { it == 0 })
        if (i < 0) { toast("Board is already full"); return }
        val digit = step?.digit ?: s.solution[i]
        val undo = pushUndo(i)
        val values = s.values.copyOf(); val cand = s.candidates.copyOf()
        val err = s.checkErr.copyOf(); val locked = s.lockedMask.copyOf()
        values[i] = digit; cand[i] = 0; err[i] = false; locked[i] = true
        _ui.value = s.copy(
            values = values, candidates = cand, checkErr = err, lockedMask = locked,
            selected = i, undo = undo,
        )
        persistProgressSoon()
        checkWin()
        if (!s.solved) toast("Filled one square for you")
    }

    // ---------- checks ----------
    fun checkCell() {
        dismissOverlay()
        val i = s.selected
        if (i < 0 || s.givenMask[i]) { toast("Pick a square to check"); return }
        if (s.values[i] == 0) { toast("That square is empty"); return }
        val ok = s.values[i] == s.solution[i]
        val err = s.checkErr.copyOf(); err[i] = !ok
        _ui.value = s.copy(checkErr = err)
        persistProgressSoon()
        toast(if (ok) "Looks right" else "That number is off")
    }

    fun checkPuzzle() {
        dismissOverlay()
        val err = s.checkErr.copyOf(); var wrong = 0; var filled = 0
        for (i in 0 until 81) {
            if (s.givenMask[i] || s.lockedMask[i]) continue
            if (s.values[i] == 0) { err[i] = false; continue }
            filled++
            val bad = s.values[i] != s.solution[i]
            err[i] = bad
            if (bad) wrong++
        }
        _ui.value = s.copy(checkErr = err)
        persistProgressSoon()
        toast(when {
            filled == 0 -> "Nothing to check yet"
            wrong == 0 -> "No mistakes so far"
            wrong == 1 -> "1 number is off"
            else -> "$wrong numbers are off"
        })
    }

    // ---------- reveals / reset ----------
    fun revealCell() {
        dismissOverlay()
        val i = s.selected
        if (i < 0 || s.givenMask[i]) { toast("Pick a square to reveal"); return }
        if (s.values[i] == s.solution[i] && s.values[i] != 0) { toast("Already correct"); return }
        val undo = pushUndo(i)
        val values = s.values.copyOf(); val cand = s.candidates.copyOf()
        val err = s.checkErr.copyOf(); val locked = s.lockedMask.copyOf()
        values[i] = s.solution[i]; cand[i] = 0; err[i] = false; locked[i] = true
        _ui.value = s.copy(
            values = values, candidates = cand, checkErr = err, lockedMask = locked, undo = undo,
        )
        persistProgressSoon()
        checkWin()
    }

    fun confirmRevealPuzzle() {
        val values = s.values.copyOf(); val locked = s.lockedMask.copyOf()
        for (i in 0 until 81) if (!s.givenMask[i]) { values[i] = s.solution[i]; locked[i] = true }
        stopTimer()
        _ui.value = s.copy(
            values = values, candidates = IntArray(81), lockedMask = locked,
            checkErr = BooleanArray(81), usedReveal = true, solved = true,
            undo = emptyList(), selected = -1, overlay = null,
        )
        viewModelScope.launch { writeIndex("done", null) }
        persistProgress()
        toast("Puzzle revealed")
    }

    fun confirmReset() {
        baseMs = 0; startTs = 0
        _ui.value = s.copy(
            values = clueLayout.copyOf(), candidates = IntArray(81),
            lockedMask = BooleanArray(81), checkErr = BooleanArray(81), undo = emptyList(),
            solved = false, usedReveal = false, selected = -1, elapsedSec = 0,
            running = false, overlay = null,
        )
        viewModelScope.launch { writeIndex("new", null) }
        persistProgress()
        startTimer()
    }

    // ---------- win ----------
    private fun checkWin() {
        if (s.solved || s.usedReveal) return
        if (s.values.any { it == 0 }) return
        if (!s.values.indices.all { s.values[it] == s.solution[it] }) return
        stopTimer()
        val sec = elapsedSec()
        val subtitle = "${difficulty.replaceFirstChar { it.uppercase() }} · ${DateKeys.prettyShort(dateKey)}"
        _ui.value = s.copy(
            solved = true, elapsedSec = sec,
            overlay = Overlay.Win(fmtTime(sec), subtitle),
        )
        if (s.settings.sound) runCatching { playChime() }
        viewModelScope.launch { writeIndex("done", sec) }
        persistProgress()
    }

    // ---------- conflicts (computed for rendering) ----------
    fun conflicts(): BooleanArray {
        val bad = BooleanArray(81)
        fun scan(idx: IntArray) {
            val seen = HashMap<Int, MutableList<Int>>()
            for (i in idx) {
                val v = s.values[i]; if (v == 0) continue
                seen.getOrPut(v) { mutableListOf() }.add(i)
            }
            for ((_, list) in seen) if (list.size > 1) for (i in list) bad[i] = true
        }
        for (r in 0 until 9) scan(IntArray(9) { r * 9 + it })
        for (c in 0 until 9) scan(IntArray(9) { it * 9 + c })
        for (b in 0 until 9) scan(IntArray(9) { (b / 3 * 3 + it / 3) * 9 + (b % 3 * 3 + it % 3) })
        return bad
    }

    private fun peersOf(i: Int): List<Int> {
        val r = i / 9; val c = i % 9; val b = SudokuEngine.boxOf(i)
        val set = LinkedHashSet<Int>()
        for (k in 0 until 9) { set.add(r * 9 + k); set.add(k * 9 + c) }
        for (k in 0 until 9) set.add((b / 3 * 3 + k / 3) * 9 + (b % 3 * 3 + k % 3))
        set.remove(i)
        return set.toList()
    }
```

- [ ] **Step 4: Run all VM + engine tests**

Run: `./gradlew :tool:testDebugUnitTest 2>&1 | tail -8`
Expected: BUILD SUCCESSFUL — 17 GameViewModel tests + 3 engine + 6 codec + 1 feedback all pass.

- [ ] **Step 5: Commit**

```bash
git add tool/src && git commit -m "feat(sudoku): full game logic — input, undo, hints, checks, reveals, win"
```

---
### Task 8: Board composable

**Files:**
- Create: `tool/src/main/kotlin/dev/tyler/sudoku/ui/game/Board.kt`

**Interfaces:**
- Consumes: `GameViewModel` (`select`, `conflicts()`), `GameUiState`, `LocalSudokuPalette`, `SudokuEngine.boxOf`/`autoCandidates`.
- Produces: `@Composable fun Board(vm: GameViewModel, ui: GameUiState, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Create the file.** This is the scaffold's `Board()`/`PencilMarks()` from `GameScreen.kt` lines 45–142 with exactly these changes: package/imports, `SudokuColors as C` → `val pal = LocalSudokuPalette.current` (every `C.X` becomes `pal.x` with lowercased first letter), pencil-mark dark ink `Color(0xFF3A3A3A)` → `pal.selInk` (theme-aware), and `PencilMarks` gains a `pal` parameter:

```kotlin
package dev.tyler.sudoku.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tyler.sudoku.engine.SudokuEngine
import dev.tyler.sudoku.ui.theme.LocalSudokuPalette
import dev.tyler.sudoku.ui.theme.SudokuPalette

@Composable
fun Board(vm: GameViewModel, ui: GameUiState, modifier: Modifier = Modifier) {
    val pal = LocalSudokuPalette.current
    val conflicts = vm.conflicts()
    val sel = ui.selected
    val selVal = if (sel >= 0) ui.values[sel] else 0
    val st = ui.settings

    Column(
        modifier
            .aspectRatio(1f)
            .border(2.dp, pal.frame)
    ) {
        for (r in 0 until 9) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0 until 9) {
                    val i = r * 9 + c
                    val v = ui.values[i]
                    val fixed = ui.givenMask[i] || ui.lockedMask[i]

                    // background value ramp (selection > samenum > peer > given-tile > bg)
                    val sameRowCol = sel >= 0 && (sel / 9 == r || sel % 9 == c)
                    val sameBox = sel >= 0 && SudokuEngine.boxOf(i) == SudokuEngine.boxOf(sel)
                    val bg = when {
                        i == sel -> pal.sel
                        !st.plain && st.same && selVal != 0 && v == selVal -> pal.sameNum
                        !st.plain && ((st.rowcol && sameRowCol) || (st.box && sameBox)) -> pal.peer
                        fixed -> pal.givenTile
                        else -> pal.bg
                    }
                    val conflict = !st.plain && st.conflicts && v != 0 && conflicts[i]

                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(bg)
                            // thin internal lines + thicker 3x3 box separators (right/bottom)
                            .drawBehind {
                                val thin = 1.dp.toPx(); val thick = 2.dp.toPx()
                                if (c < 8) drawLineRight(
                                    if ((c + 1) % 3 == 0) thick else thin,
                                    if ((c + 1) % 3 == 0) pal.box else pal.line,
                                )
                                if (r < 8) drawLineBottom(
                                    if ((r + 1) % 3 == 0) thick else thin,
                                    if ((r + 1) % 3 == 0) pal.box else pal.line,
                                )
                            }
                            .clickable { vm.select(i) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (conflict) Box(Modifier.matchParentSize().border(2.dp, pal.ring))
                        if (ui.checkErr[i]) Box(
                            Modifier.align(Alignment.TopEnd).padding(3.dp).size(6.dp)
                                .background(if (i == sel) pal.selInk else pal.dot, shape = CircleShape)
                        )

                        if (v != 0) {
                            Text(
                                text = v.toString(),
                                color = when {
                                    i == sel -> pal.selInk
                                    fixed -> pal.givenInk
                                    else -> pal.entryInk
                                },
                                fontSize = 24.sp,
                                fontWeight = if (ui.givenMask[i]) FontWeight.SemiBold else FontWeight.Medium
                            )
                        } else {
                            val mask = if (ui.autoCandidate) {
                                var m = 0
                                for (d in SudokuEngine.autoCandidates(ui.values, i)) m = m or (1 shl (d - 1))
                                m
                            } else ui.candidates[i]
                            if (mask != 0) PencilMarks(mask, dark = i == sel, pal = pal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PencilMarks(mask: Int, dark: Boolean, pal: SudokuPalette) {
    Column(Modifier.fillMaxSize()) {
        for (br in 0 until 3) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (bc in 0 until 3) {
                    val d = br * 3 + bc + 1
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (mask and (1 shl (d - 1)) != 0)
                            Text(d.toString(), color = if (dark) pal.selInk else pal.pencilInk, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawLineRight(w: Float, color: Color) {
    drawLine(color, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
}

private fun DrawScope.drawLineBottom(w: Float, color: Color) {
    drawLine(color, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :tool:assembleDebug 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add tool/src && git commit -m "feat(sudoku): board composable with theme-aware value ramp"
```

---

### Task 9: GameScreen — screen class + chrome (top bar, status, controls, transient overlays)

**Files:**
- Create: `tool/src/main/kotlin/dev/tyler/sudoku/ui/game/GameScreen.kt`

**Interfaces:**
- Consumes: everything from Tasks 3–8.
- Produces: `class GameScreen(sealedActivity: SealedLightActivity, dateKey: String, difficulty: String) : LightScreen<GameResult, GameViewModel>` — Home and Archive construct it via `navigateTo({ sa -> GameScreen(sa, dateKey, diff) }) { result -> … }`. Also `@Composable fun GameOverlays(vm, ui, onPastPuzzles: () -> Unit)` is declared in Task 10's file and called from here — this task stubs the call site with a TODO-free placeholder: an empty `when (ui.overlay) { else -> {} }` block replaced in Task 10.

**Design note (spec deviation, deliberate):** all overlays — including menu/settings sheets — render as plain scrim `Box`es inside `SudokuSurface`, NOT material3 `ModalBottomSheet`/`Dialog`. Window-level dialogs install their own back-press interception, which would bypass `LightActivity → goBack() → viewModel.onBackPressed()` and desync `ui.overlay`. Custom boxes keep ALL back handling on the one path the VM already owns (verified by `overlayBackNavigation` test). The spec flagged sheet theming as a risk; this resolves it.

- [ ] **Step 1: Create `GameScreen.kt`:**

```kotlin
package dev.tyler.sudoku.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import dev.tyler.sudoku.data.DataStoreKeyValueStore
import dev.tyler.sudoku.ui.theme.LocalSudokuPalette
import dev.tyler.sudoku.ui.theme.SudokuSurface
import kotlinx.coroutines.delay

class GameScreen(
    sealedActivity: SealedLightActivity,
    private val dateKey: String,
    private val difficulty: String,
) : LightScreen<GameResult, GameViewModel>(sealedActivity) {

    override val viewModelClass: Class<GameViewModel>
        get() = GameViewModel::class.java

    override fun createViewModel() = GameViewModel(
        dateKey, difficulty, DataStoreKeyValueStore(lightContext.dataStore)
    )

    @Composable
    override fun Content() {
        val ui by viewModel.ui.collectAsState()
        val haptic = LocalHapticFeedback.current

        // UI-side timer ticker: the VM stays free of infinite coroutines.
        LaunchedEffect(ui.running) {
            if (ui.running) while (true) { delay(250); viewModel.refreshElapsed() }
        }
        // Solve haptic: fires once when the win overlay appears (never on reveal).
        val won = ui.overlay is Overlay.Win
        LaunchedEffect(won) {
            if (won && ui.settings.sound) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }

        SudokuSurface {
            val pal = LocalSudokuPalette.current
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                TopBar(ui)
                Box(Modifier.fillMaxWidth().height(1.dp).background(pal.hair))
                StatusRow(ui)
                Board(viewModel, ui, Modifier.padding(top = 8.dp))
                Spacer(Modifier.weight(1f))
                Controls(ui)
                Spacer(Modifier.height(16.dp))
            }

            // transient chrome
            ui.toast?.let { ToastPill(it) }
            if (ui.generating) GeneratingOverlay()
            GameOverlays(viewModel, ui) {
                // "Past puzzles" from the win modal: close the (already dismissed) overlay
                // is handled by the VM; pop with a result Home reacts to.
                goBack(GameResult.OpenArchive)
            }
        }
    }

    @Composable
    private fun TopBar(ui: GameUiState) {
        val pal = LocalSudokuPalette.current
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconGlyph("‹", "Back") { goBack(GameResult.Closed) }
            Spacer(Modifier.weight(1f))
            IconGlyph("?", "How to play") { viewModel.showHelp() }
            Spacer(Modifier.width(16.dp))
            IconGlyph("⚙", "Settings") { viewModel.showSettings() }
            Spacer(Modifier.width(16.dp))
            IconGlyph("⋯", "More") { viewModel.showMenu() }
        }
    }

    @Composable
    private fun IconGlyph(glyph: String, label: String, onClick: () -> Unit) {
        val pal = LocalSudokuPalette.current
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = pal.txt, fontSize = 22.sp)
        }
    }

    @Composable
    private fun StatusRow(ui: GameUiState) {
        val pal = LocalSudokuPalette.current
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ui.difficulty.replaceFirstChar { it.uppercase() },
                color = pal.txtDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (ui.settings.timer) {
                Text(
                    viewModel.fmtTime(ui.elapsedSec),
                    color = pal.txt, fontSize = 16.sp, fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(10.dp))
                IconGlyph("❚❚", "Pause") { viewModel.pauseTapped() }
            }
        }
    }

    @Composable
    private fun Controls(ui: GameUiState) {
        val pal = LocalSudokuPalette.current
        // Normal | Candidate segmented control + Undo
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(pal.btn),
            ) {
                SegButton("Normal", ui.mode == InputMode.NORMAL && !ui.autoCandidate) {
                    viewModel.setMode(InputMode.NORMAL)
                }
                SegButton("Candidate", ui.mode == InputMode.CANDIDATE && !ui.autoCandidate) {
                    viewModel.setMode(InputMode.CANDIDATE)
                }
            }
            Spacer(Modifier.width(10.dp))
            val undoEnabled = ui.undo.isNotEmpty() && !ui.solved
            Text(
                "Undo",
                color = if (undoEnabled) pal.txt else pal.txtFaint,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = undoEnabled) { viewModel.undo() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        // numpad 1..9 + erase; dim digits placed 9 times
        val counts = IntArray(10)
        for (v in ui.values) if (v != 0) counts[v]++
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            for (d in 1..5) NumKey(d, counts[d] >= 9, Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            for (d in 6..9) NumKey(d, counts[d] >= 9, Modifier.weight(1f))
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(10.dp))
                    .background(pal.btn).clickable { viewModel.erase() },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = pal.txt, fontSize = 18.sp) }
        }

        // auto candidate row
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { viewModel.toggleAutoCandidate() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val pal2 = LocalSudokuPalette.current
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (ui.autoCandidate) pal2.txt else pal2.btn),
                contentAlignment = Alignment.Center,
            ) {
                if (ui.autoCandidate) Text("✓", color = pal2.bg, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text("Auto candidate mode", color = pal2.txtDim, fontSize = 14.sp)
        }
    }

    @Composable
    private fun SegButton(label: String, on: Boolean, onClick: () -> Unit) {
        val pal = LocalSudokuPalette.current
        Text(
            label,
            color = if (on) pal.bg else pal.txtDim,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (on) pal.txt else pal.btn)
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }

    @Composable
    private fun NumKey(d: Int, dim: Boolean, modifier: Modifier) {
        val pal = LocalSudokuPalette.current
        Box(
            modifier.height(52.dp).clip(RoundedCornerShape(10.dp))
                .background(pal.btn).clickable { viewModel.input(d) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                d.toString(),
                color = if (dim) pal.txtFaint else pal.txt,
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }

    @Composable
    private fun ToastPill(text: String) {
        val pal = LocalSudokuPalette.current
        Box(Modifier.fillMaxSize().padding(bottom = 90.dp), contentAlignment = Alignment.BottomCenter) {
            Text(
                text,
                color = pal.bg, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(pal.txt)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    @Composable
    private fun GeneratingOverlay() {
        val pal = LocalSudokuPalette.current
        Column(
            Modifier.fillMaxSize().background(pal.bg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("GENERATING", color = pal.txtDim, fontSize = 13.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            Text("Building today's puzzle…", color = pal.txt, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
```

- [ ] **Step 2: Add a temporary `GameOverlays` stub** so this task compiles before Task 10 replaces it. Create `tool/src/main/kotlin/dev/tyler/sudoku/ui/game/GameOverlays.kt`:

```kotlin
package dev.tyler.sudoku.ui.game

import androidx.compose.runtime.Composable

/** Replaced with the full overlay implementations in the next task. */
@Composable
fun GameOverlays(vm: GameViewModel, ui: GameUiState, onPastPuzzles: () -> Unit) {
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :tool:assembleDebug 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add tool/src && git commit -m "feat(sudoku): game screen chrome — top bar, status row, numpad, controls"
```

---

### Task 10: Game overlays — menu/hint sheet, settings, help, confirms, win, paused

**Files:**
- Modify: `tool/src/main/kotlin/dev/tyler/sudoku/ui/game/GameOverlays.kt` (full replace of the stub)

**Interfaces:**
- Consumes: every VM method from Tasks 6–7. `onPastPuzzles` pops the screen with `GameResult.OpenArchive` (wired in Task 9).
- Produces: the final `GameOverlays(vm, ui, onPastPuzzles)`.

Copy discipline — all strings verbatim from the prototype:
- Menu sheet: label "HELP" → tiles `Hint`, `Check cell`, `Check puzzle`; divider; label "REVEAL & RESET" → `Reveal cell`, `Reveal puzzle`, `Reset puzzle`.
- Hint page: back row "‹ Hint", then `Point to a square` / "Highlight the next solvable square — you place the number", `Fill in a square` / "Fill the next square in for you".
- Settings: groups "HIGHLIGHTING" (`Highlight row and column`→rowcol, `Highlight box`→box, `Highlight identical numbers`→same, `Highlight conflicts`→conflicts), "ASSISTANCE" (`Check guesses when entered`→checkOnEntry, `Start in auto candidate mode`→autoStart), "GAME" (`Show timer`→timer, `Play sound on solve`→sound), divider, `Plain mode` + caption "Hide every highlight and check for a bare grid"→plain. Button `Done`.
- Help: title "How to play", body (three paragraphs, verbatim from the prototype's `#help-ov`), button `Got it`.
- Confirm reset: "Reset puzzle" / "This clears everything you have entered. The clues stay." Confirm reveal: "Reveal puzzle" / "This fills in the whole solution and ends the puzzle." Buttons `Cancel` / `Confirm`.
- Win: "Solved" / "Nicely done." / big mono time / `Past puzzles` + `Close`.
- Paused: "PAUSED" / "Tap to resume" — tapping anywhere resumes (`vm.dismissOverlay()`).

- [ ] **Step 1: Replace the stub with the full implementation:**

```kotlin
package dev.tyler.sudoku.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tyler.sudoku.data.Settings
import dev.tyler.sudoku.ui.theme.LocalSudokuPalette
import dev.tyler.sudoku.ui.theme.SudokuPalette

/**
 * All overlays are plain scrim boxes (no window Dialogs) so system back always
 * routes through LightActivity -> goBack() -> GameViewModel.onBackPressed().
 * Scrim tap closes, matching the prototype's outside-tap-dismiss.
 */
@Composable
fun GameOverlays(vm: GameViewModel, ui: GameUiState, onPastPuzzles: () -> Unit) {
    when (val ov = ui.overlay) {
        null -> {}
        Overlay.Menu -> BottomSheet(vm) { MenuMain(vm) }
        Overlay.HintPage -> BottomSheet(vm) { HintPage(vm) }
        Overlay.SettingsSheet -> CenterSheet(vm, scrollable = true) { SettingsSheet(vm, ui.settings) }
        Overlay.Help -> CenterSheet(vm) { HelpSheet(vm) }
        Overlay.ConfirmReset -> CenterSheet(vm) {
            ConfirmSheet(
                vm, "Reset puzzle",
                "This clears everything you have entered. The clues stay.",
            ) { vm.confirmReset() }
        }
        Overlay.ConfirmReveal -> CenterSheet(vm) {
            ConfirmSheet(
                vm, "Reveal puzzle",
                "This fills in the whole solution and ends the puzzle.",
            ) { vm.confirmRevealPuzzle() }
        }
        is Overlay.Win -> CenterSheet(vm) { WinSheet(vm, ov, onPastPuzzles) }
        Overlay.Paused -> PausedOverlay(vm)
    }
}

private val Scrim = Color(0x99000000)

@Composable
private fun BottomSheet(vm: GameViewModel, content: @Composable () -> Unit) {
    val pal = LocalSudokuPalette.current
    Box(Modifier.fillMaxSize().background(Scrim).clickable { vm.dismissOverlay() }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(pal.btn)
                .clickable(enabled = false) {}   // swallow taps inside the sheet
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // grabber
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp)
                    .clip(CircleShape).background(pal.btnLine)
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun CenterSheet(vm: GameViewModel, scrollable: Boolean = false, content: @Composable () -> Unit) {
    val pal = LocalSudokuPalette.current
    Box(
        Modifier.fillMaxSize().background(Scrim).clickable { vm.dismissOverlay() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 340.dp).fillMaxWidth().padding(24.dp)
                .clip(RoundedCornerShape(16.dp)).background(pal.btn)
                .clickable(enabled = false) {}
                .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
                .padding(20.dp),
        ) { content() }
    }
}

@Composable
private fun SheetLabel(text: String) {
    val pal = LocalSudokuPalette.current
    Text(text.uppercase(), color = pal.txtDim, fontSize = 11.sp, letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Tile(label: String, caption: String? = null, onClick: () -> Unit) {
    val pal = LocalSudokuPalette.current
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp))
            .background(pal.bg).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, color = pal.txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (caption != null) {
            Text(caption, color = pal.txtDim, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun MenuMain(vm: GameViewModel) {
    val pal = LocalSudokuPalette.current
    SheetLabel("Help")
    Tile("Hint") { vm.showHintPage() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { Tile("Check cell") { vm.checkCell() } }
        Box(Modifier.weight(1f)) { Tile("Check puzzle") { vm.checkPuzzle() } }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.btnLine))
    Spacer(Modifier.height(12.dp))
    SheetLabel("Reveal & reset")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { Tile("Reveal cell") { vm.revealCell() } }
        Box(Modifier.weight(1f)) { Tile("Reveal puzzle") { vm.requestRevealPuzzle() } }
    }
    Tile("Reset puzzle") { vm.requestReset() }
}

@Composable
private fun HintPage(vm: GameViewModel) {
    val pal = LocalSudokuPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.showMenu() }.padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹", color = pal.txtDim, fontSize = 18.sp)
        Spacer(Modifier.width(6.dp))
        Text("Hint", color = pal.txtDim, fontSize = 14.sp)
    }
    Spacer(Modifier.height(10.dp))
    Tile("Point to a square", "Highlight the next solvable square — you place the number") { vm.pointHint() }
    Tile("Fill in a square", "Fill the next square in for you") { vm.fillHint() }
}

@Composable
private fun ToggleRow(label: String, caption: String? = null, on: Boolean, onToggle: () -> Unit) {
    val pal = LocalSudokuPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = pal.txt, fontSize = 15.sp)
            if (caption != null) {
                Text(caption, color = pal.txtDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        // pill toggle
        Box(
            Modifier.width(42.dp).height(24.dp).clip(RoundedCornerShape(12.dp))
                .background(if (on) pal.txt else pal.btnLine),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(3.dp).size(18.dp).clip(CircleShape).background(pal.bg))
        }
    }
}

@Composable
private fun SettingsSheet(vm: GameViewModel, st: Settings) {
    val pal = LocalSudokuPalette.current
    Text("Settings", color = pal.txt, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 10.dp))
    SheetLabel("Highlighting")
    ToggleRow("Highlight row and column", on = st.rowcol) { vm.toggleSetting("rowcol") }
    ToggleRow("Highlight box", on = st.box) { vm.toggleSetting("box") }
    ToggleRow("Highlight identical numbers", on = st.same) { vm.toggleSetting("same") }
    ToggleRow("Highlight conflicts", on = st.conflicts) { vm.toggleSetting("conflicts") }
    SheetLabel("Assistance")
    ToggleRow("Check guesses when entered", on = st.checkOnEntry) { vm.toggleSetting("checkOnEntry") }
    ToggleRow("Start in auto candidate mode", on = st.autoStart) { vm.toggleSetting("autoStart") }
    SheetLabel("Game")
    ToggleRow("Show timer", on = st.timer) { vm.toggleSetting("timer") }
    ToggleRow("Play sound on solve", on = st.sound) { vm.toggleSetting("sound") }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.btnLine))
    ToggleRow("Plain mode", "Hide every highlight and check for a bare grid", st.plain) {
        vm.toggleSetting("plain")
    }
    Spacer(Modifier.height(12.dp))
    SolidButton("Done") { vm.dismissOverlay() }
}

@Composable
private fun HelpSheet(vm: GameViewModel) {
    val pal = LocalSudokuPalette.current
    Text("How to play", color = pal.txt, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 12.dp))
    Text(
        "Fill the grid so every row, column, and 3×3 box contains 1 through 9, with no repeats.\n\n" +
            "Tap a cell, then a number. Normal places a number; Candidate jots small pencil marks. " +
            "Undo steps back. Repeated numbers are ringed so you can spot clashes.\n\n" +
            "The ⋯ menu can check or reveal a cell, or reset the puzzle. A new set of puzzles arrives each day.",
        color = pal.txtDim, fontSize = 15.sp, lineHeight = 22.sp,
    )
    Spacer(Modifier.height(16.dp))
    SolidButton("Got it") { vm.dismissOverlay() }
}

@Composable
private fun ConfirmSheet(vm: GameViewModel, title: String, message: String, onConfirm: () -> Unit) {
    val pal = LocalSudokuPalette.current
    Text(title, color = pal.txt, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text(message, color = pal.txtDim, fontSize = 15.sp, lineHeight = 21.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) { GhostButton("Cancel") { vm.dismissOverlay() } }
        Box(Modifier.weight(1f)) { SolidButton("Confirm", fill = true, onClick = onConfirm) }
    }
}

@Composable
private fun WinSheet(vm: GameViewModel, win: Overlay.Win, onPastPuzzles: () -> Unit) {
    val pal = LocalSudokuPalette.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Solved", color = pal.txt, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(win.subtitle, color = pal.txtDim, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        Text(
            win.timeText, color = pal.txt, fontSize = 34.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 14.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                GhostButton("Past puzzles") { vm.dismissOverlay(); onPastPuzzles() }
            }
            Box(Modifier.weight(1f)) { SolidButton("Close", fill = true) { vm.dismissOverlay() } }
        }
    }
}

@Composable
private fun PausedOverlay(vm: GameViewModel) {
    val pal = LocalSudokuPalette.current
    Column(
        Modifier.fillMaxSize().background(pal.bg).clickable { vm.dismissOverlay() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("PAUSED", color = pal.txtDim, fontSize = 14.sp, letterSpacing = 3.sp)
        Spacer(Modifier.height(10.dp))
        Text("Tap to resume", color = pal.txt, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SolidButton(label: String, fill: Boolean = false, onClick: () -> Unit) {
    val pal = LocalSudokuPalette.current
    Text(
        label, color = pal.bg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .let { if (fill) it.fillMaxWidth() else it }
            .clip(RoundedCornerShape(12.dp)).background(pal.txt)
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit) {
    val pal = LocalSudokuPalette.current
    Text(
        label, color = pal.txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)).background(pal.bg)
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
```

Note: the win modal's "Past puzzles" pops the Game screen with `GameResult.OpenArchive` — the overlay must be dismissed FIRST (as coded above) or `goBack()` would be consumed by `onBackPressed()`'s overlay branch.

- [ ] **Step 2: Compile**

Run: `./gradlew :tool:assembleDebug 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add tool/src && git commit -m "feat(sudoku): all game overlays — menu, hints, settings, confirms, win, paused"
```

---
### Task 11: Real HomeScreen + ArchiveScreen + navigation wiring

**Files:**
- Modify: `tool/src/main/kotlin/dev/tyler/sudoku/ui/home/HomeScreen.kt` (full replace of the Task 2 placeholder)
- Create: `tool/src/main/kotlin/dev/tyler/sudoku/ui/archive/ArchiveScreen.kt`

**Interfaces:**
- Consumes: `GameScreen(sa, dateKey, difficulty)`, `GameResult`, `DateKeys`, `Codecs`/`StoreKeys`/`IndexEntry`/`DataStoreKeyValueStore`, `SudokuSurface`/`LocalSudokuPalette`.
- Produces: final navigation graph. Home is the only `@InitialScreen`.

- [ ] **Step 1: Replace `HomeScreen.kt` entirely:**

```kotlin
package dev.tyler.sudoku.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import dev.tyler.sudoku.data.DateKeys
import dev.tyler.sudoku.ui.archive.ArchiveScreen
import dev.tyler.sudoku.ui.game.GameResult
import dev.tyler.sudoku.ui.game.GameScreen
import dev.tyler.sudoku.ui.theme.LocalSudokuPalette
import dev.tyler.sudoku.ui.theme.SudokuSurface

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    private fun openGame(difficulty: String) {
        val today = DateKeys.today(System.currentTimeMillis())
        navigateTo({ sa -> GameScreen(sa, today, difficulty) }) { result ->
            if (result == GameResult.OpenArchive) openArchive()
        }
    }

    private fun openArchive() {
        navigateTo({ sa -> ArchiveScreen(sa) })
    }

    @Composable
    override fun Content() {
        SudokuSurface {
            val pal = LocalSudokuPalette.current
            Column(
                Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Sudoku", color = pal.txt, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Text(
                    "A quiet numbers game. No clock pressure, no streaks.",
                    color = pal.txt, fontSize = 18.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
                Spacer(Modifier.height(40.dp))
                Text("CHOOSE YOUR PUZZLE", color = pal.txtDim, fontSize = 13.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(18.dp))

                listOf("easy" to "Easy", "medium" to "Medium", "hard" to "Hard").forEach { (key, label) ->
                    Box(
                        Modifier.fillMaxWidth().widthIn(max = 300.dp).padding(vertical = 7.dp)
                            .clip(RoundedCornerShape(34.dp))
                            .border(2.dp, pal.frame, RoundedCornerShape(34.dp))
                            .clickable { openGame(key) }
                            .height(52.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = pal.txt, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(38.dp))
                Text(
                    DateKeys.today(System.currentTimeMillis()),
                    color = pal.txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Past puzzles", color = pal.txtDim, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { openArchive() }.padding(8.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 2: Create `ArchiveScreen.kt` (screen + VM in one file — they change together):**

```kotlin
package dev.tyler.sudoku.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import dev.tyler.sudoku.data.Codecs
import dev.tyler.sudoku.data.DataStoreKeyValueStore
import dev.tyler.sudoku.data.DateKeys
import dev.tyler.sudoku.data.IndexEntry
import dev.tyler.sudoku.data.KeyValueStore
import dev.tyler.sudoku.data.StoreKeys
import dev.tyler.sudoku.ui.game.GameScreen
import dev.tyler.sudoku.ui.theme.LocalSudokuPalette
import dev.tyler.sudoku.ui.theme.SudokuSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ArchiveViewModel(
    private val store: KeyValueStore,
    now: Long = System.currentTimeMillis(),
) : LightViewModel<Unit>() {

    val dates: List<String> = DateKeys.lastNDays(60, now)

    private val _index = MutableStateFlow<Map<String, IndexEntry>>(emptyMap())
    val index = _index.asStateFlow()

    /** Re-read on every show so statuses refresh after finishing a game. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { _index.value = Codecs.decodeIndex(store.get(StoreKeys.INDEX)) }
    }
}

class ArchiveScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ArchiveViewModel>(sealedActivity) {

    override val viewModelClass: Class<ArchiveViewModel>
        get() = ArchiveViewModel::class.java

    override fun createViewModel() = ArchiveViewModel(DataStoreKeyValueStore(lightContext.dataStore))

    @Composable
    override fun Content() {
        val index by viewModel.index.collectAsState()

        SudokuSurface {
            val pal = LocalSudokuPalette.current
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { goBack() },
                        contentAlignment = Alignment.Center,
                    ) { Text("‹", color = pal.txt, fontSize = 22.sp) }
                    Spacer(Modifier.width(4.dp))
                    Text("Past puzzles", color = pal.txt, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(pal.hair))

                LazyColumn(Modifier.fillMaxSize()) {
                    items(viewModel.dates) { key ->
                        val isToday = key == viewModel.dates.first()
                        Column(Modifier.padding(vertical = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isToday) {
                                    Text(
                                        "TODAY", color = pal.bg, fontSize = 10.sp, letterSpacing = 1.sp,
                                        modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                            .background(pal.txtDim)
                                            .padding(horizontal = 5.dp, vertical = 2.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    DateKeys.prettyShort(key) + if (isToday) "" else ", ${key.take(4)}",
                                    color = pal.txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                listOf("easy" to "Easy", "medium" to "Medium", "hard" to "Hard")
                                    .forEach { (diff, label) ->
                                        val entry = index["$key:$diff"]
                                        val sub = when (entry?.status) {
                                            "done" -> entry.time?.let { viewModel.fmtTime(it) } ?: "Done"
                                            "progress" -> "In progress"
                                            else -> "Not started"
                                        }
                                        Column(
                                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    navigateTo({ sa -> GameScreen(sa, key, diff) })
                                                }
                                                .padding(8.dp),
                                        ) {
                                            Text(label, color = pal.txt, fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold)
                                            Text(sub, color = pal.txtDim, fontSize = 12.sp)
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

Add this small method to `ArchiveViewModel` (it's referenced above for done-times):

```kotlin
    fun fmtTime(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val ss = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }
```

- [ ] **Step 3: Full build + tests**

Run: `./gradlew :tool:testDebugUnitTest :tool:assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, all tests pass, plugin scan silent.

- [ ] **Step 4: Commit**

```bash
git add tool/src && git commit -m "feat(sudoku): home and archive screens, full navigation graph"
```

---

### Task 12: End-to-end verification on the AVD

**Files:** none created — this is the verification gate. Fixes discovered here are committed as `fix(sudoku): …`.

- [ ] **Step 1: Full test + scan pass**

Run: `./gradlew :tool:testDebugUnitTest :tool:assembleDebug :tool:lintDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL. Lint must not report `RestrictedApi` errors.

- [ ] **Step 2: Boot the AVD (if not running) and install**

```bash
emulator -avd Light_Phone -writable-system &   # AVD name is Light_Phone, not lp3
adb wait-for-device
./gradlew :tool:installDebug
adb shell am start -n dev.tyler.sudoku/com.thelightphone.sdk.LightActivity
```

Expected: app launches into the Home screen (splash first — `LightActivity` holds it ~1 s).

- [ ] **Step 3: Manual smoke checklist (watch `adb logcat | grep -iE 'sudoku|AndroidRuntime'` in a second terminal)**

1. Home shows title, three difficulty buttons, today's date.
2. Easy → board renders; generating gate visible only briefly on hard.
3. Tap cell → tap number → digit lands; numpad digit dims after 9 placements.
4. Candidate mode pencils; Undo steps back; auto-candidate row toggles.
5. `⋯` menu: Hint sub-page (back arrow returns to menu), Check cell/puzzle toast, Reveal cell.
6. Reveal puzzle → confirm → board fills, toast "Puzzle revealed", NO win modal, no chime.
7. Reset → confirm → clues only, timer at 0:00.
8. Settings: enable Show timer → timer + pause appear; pause → PAUSED overlay; tap resumes.
9. Enable sound, solve a puzzle (use Fill hint repeatedly — hints lock cells but `usedReveal` stays false, so the win modal MUST appear with chime + haptic). Win modal → Past puzzles → Archive shows "done" with time on today's row.
10. System back: closes overlays first (hint page → menu → closed), then pops Game → Home. Progress survives relaunch.
11. Theme: from the LightOS emulator's theme switch (or temporarily calling `LightThemeController.toggle()` from a debug tap), verify the light palette board is readable: selection inverts, pencil marks visible.
12. `serverPackage` reminder: leave as `com.thelightphone.sdk.emulator` — flip to `com.lightos` only for a real LP3 install.

- [ ] **Step 4: Close out**

```bash
git add -A && git commit -m "fix(sudoku): device calibration from AVD smoke pass"   # only if fixes were needed
git log --oneline main..HEAD 2>/dev/null || git log --oneline -12
```

Report any checklist item that could not be verified rather than marking it done.

---

## Self-Review Notes (already applied)

- **Spec coverage:** identity/entry (T2), engine+tests (T1), palette/theming (T3), persistence+codecs (T4), chime (T5), VM+lifecycle+back (T6–7), board (T8), chrome (T9), overlays (T10), home/archive/nav (T11), AVD verification (T12). Spec's `ModalBottomSheet`/`Dialog` choice replaced by scrim boxes — deviation documented in Task 9 with rationale (back-press unity through `onBackPressed`).
- **Type consistency:** `GameResult.OpenArchive` produced in T9/T10, consumed in T11. `KeyValueStore` signatures identical in T4/T6/T11. `refreshElapsed` declared T6, used T9. `SudokuPalette` fields lowercase (`pal.sel`, `pal.selInk`) everywhere.
- **Known intentional choices:** icon glyphs are text characters (no SVG assets in tools) — calibration item; `GameUiState` holds arrays in a data class (inherited scaffold trade-off, equality unused); Room/KSP wiring left in build file untouched.




