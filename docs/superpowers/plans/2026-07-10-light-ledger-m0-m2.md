# Light Ledger M0–M2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the `:ledger` module in the `light-sdk` fork and build the manual-entry slice of Light Ledger through milestone M2: module scaffold (M0), the pure-Kotlin domain layer + Room persistence (M1), and the manual-entry UI — Home, Add Entry, History, Categories (M2). SimpleFIN sync (M3), CSV/LAN import (M4), polish (M5), and submission prep (M6) are separate follow-on plans.

**Architecture:** Seven `LightScreen`/`SimpleLightScreen` classes are reduced to five for this slice (Home, AddEntry, History, Settings, Categories), each backed by a `LightViewModel<Unit>` where it has state. A `LedgerRepository` interface sits between ViewModels and Room, with `RoomLedgerRepository` as the real implementation and `FakeLedgerRepository` as an in-memory test double — this is what makes ViewModels JVM-testable without Robolectric, mirroring the one Room precedent in this repo (`examples/authenticator`) crossed with Sudoku's fake-store testing doctrine. Pure business logic (amount parsing, CSV parsing, column mapping, dedup hashing, rule matching, month math) lives in a separate `domain` package with zero Android/Room imports, fully unit-tested on the JVM.

**Tech Stack:** Kotlin 2.3.20, Jetpack Compose (via `sdk:ui`), Light SDK `:sdk:client`, Room 2.7.0 (KSP), kotlinx-serialization (plugin only, unused until M3), `kotlin.test` + `kotlinx-coroutines-test`.

## Global Constraints

- Working dir for all commands: `/Users/tyleryancey/Documents/light-phone-3-lightos-dev/light-sdk`.
- `JAVA_HOME` must be JDK 17 before any `./gradlew` call: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` (JBR alone fails — `plugin`/`lint-rules` pin `jvmToolchain(17)` exactly, no auto-download).
- New module `:ledger`, top-level directory `ledger/` (sibling of `tool/`, not under `examples/`). Package for all new code: `dev.tyler.lightledger`. Tool id: `dev.tyler.lightledger`, label `Ledger`.
- The plugin's source scanner walks all of `ledger/src/` (tests included) at Gradle configuration time and is regex-based over raw lines — don't put banned tokens in string literals or comments either. Banned import prefixes: `android.app.`, `android.content.Context|Intent|ComponentName|BroadcastReceiver|ContentProvider|ServiceConnection`, `androidx.compose.ui.platform.LocalContext|LocalView|LocalLifecycleOwner`, `androidx.activity.ComponentActivity`, `androidx.activity.compose.setContent`, `androidx.appcompat.`, `java.lang.reflect.`, `kotlin.reflect.`. Banned code patterns: `getSystemService(`, `startActivity(`, `startService(`, `bindService(`, `registerReceiver(`, `contentResolver`, `LocalContext.current`, `LocalView.current`, `as …Activity`/`as? …Activity`, `Class.forName(`, `.javaClass`, `.java.<word>` (bare `::class.java` with nothing chained after it is fine — that's how `viewModelClass` is implemented), `.getDeclaredMethod(`/`.getMethod(`/`.getDeclaredField(`/`.getField(`.
- Dependency allow-list (this module only needs a subset): `androidx.compose`, `androidx.activity:activity-compose`, `androidx.annotation`, `org.jetbrains.kotlinx:kotlinx-coroutines`, `androidx.lifecycle`, `androidx.room`, `org.jetbrains.kotlin:kotlin-test`. No `AndroidManifest.xml` — the plugin generates it from `lighttool.toml`; writing one is a build error. No `applicationId`/`versionCode`/`versionName`/`namespace` in the build script.
- Money is always a `Long` in minor units (`amountMinor`), spend negative. `BigDecimal` is used only at the string-parsing boundary in `AmountParser`; nowhere else.
- UI is `sdk:ui` components only, no Material components written directly, no color beyond the theme's black/white tokens. **`tool/` (Sudoku) is not a UI reference for this module** — it uses raw Material3 `Text`/custom theming, which violates this project's binding sdk:ui-only rule. Use `examples/authenticator` and `examples/weather` as the UI references instead; use Sudoku only for its package layout (`data/`, `domain`/`engine/`, `ui/<feature>/`) and JVM test conventions.
- **Corrections to `CLAUDE-light-ledger.md` verified against current SDK source** (the spec doc has the same kind of drift the SDK's own README has — trust this list over it):
  - `LightGrid` (`com.thelightphone.sdk.ui.LightGrid`) is a units/constants object (`WIDTH=27`, `HEIGHT=31`) used by `gridUnitsAsDp()`, **not** a button-grid widget. There is no ready-made category-grid component — Task 10 builds a custom `CategoryGrid` from `LazyVerticalGrid` + `LightText` + `lightClickable`.
  - `LightTextField` is a **read-only, tap-to-open** display field (label + value + underline), not a live text input. Real text entry is `LightTextInputEditor` (full-screen, embeds the LP3 keyboard), driven as one mode of a screen's own state machine — see `examples/weather/.../WeatherHomeScreen.kt`'s `WeatherScreenMode.LocationInput` for the reference pattern. Pair it with `rememberKeyboardOptions()` from `com.thelightphone.sdk` (not `sdk.ui`).
  - `LightFullscreenModal(message, onClose)` takes a fixed message string and a close button only — it cannot host an editable form. The transaction detail/edit view (Task 17) is a second internal screen mode, not a modal.
  - Icons are objects on `LightIcons` (`LightIcons.ADD`, `.SEARCH`, `.SETTINGS`, `.BACK`, `.CLOSE`, `.TRASH`, `.LIST`, …), not `ic_*` string names.
  - `LightViewModel<T>` is the real base class name; the SDK README's "LightScreenViewModel" does not exist (already known from the Sudoku port).
- Manual entries are `CONFIRMED`/`MANUAL` and skip the review inbox, so `DedupHash`, `RuleEngine`, and `ColumnMapper` (all built in M1 per the spec's own milestone definition) are **not wired into any M0–M2 screen** — their only verification in this plan is their unit tests. M3 (SimpleFIN) and M4 (CSV) wire them up for real. Don't read "tests pass" as "exercised end-to-end" for those three.
- `RuleEntity` and `CsvProfileEntity` are declared in the Room schema now (avoiding a version-2 migration later) but get no DAO until M3/M4 actually query them.
- Navigation shape follows the spec: Home's settings icon opens a `SettingsScreen` shell (top bar + one "Categories" row) rather than jumping straight to `CategoriesScreen`. This is more code than a direct shortcut, but it matches the spec's screen graph and means M3 only adds rows to `SettingsScreen` instead of rewiring Home's bottom bar.
- The LP3 panel is **~411×472dp** (1080×1240px @ density 420) — short and nearly square, not the taller phone shape the `@Preview(widthDp = 1080/3, heightDp = 1240/3)` annotations imply. A screen that stacks fixed-height chrome (top bar + a tall text variant + a multi-row control) with no scroll container can overflow on-device even though it looks fine in preview — this already happened on the Sudoku port (board vs. controls) and is why Task 13's amount step uses `Subtitle`/`Subheading` instead of `Title`/`Heading`. Prefer `gridUnitsAsDp()`/`weight()`-based sizing over hardcoded dp, and re-check any screen that stacks more than two fixed-height elements without a scroll container.
- Test files use `kotlin.test` imports (`kotlin.test.Test`, `assertEquals(expected, actual, message?)` — message LAST, unlike JUnit4). ViewModel tests use `StandardTestDispatcher()` + `Dispatchers.setMain/resetMain` + `runTest` + `advanceUntilIdle()`, matching `tool/src/test/kotlin/dev/tyler/sudoku/ui/game/GameViewModelTest.kt`.
- Build/scan command: `./gradlew :ledger:assembleDebug`. Unit test command: `./gradlew :ledger:testDebugUnitTest`. Both need `JAVA_HOME` set first (above).
- Dev loop uses the `Light_Phone` AVD (not `lp3`): `emulator -avd Light_Phone -writable-system`, then `./gradlew :ledger:installDebug`, launch with `adb shell am start -n dev.tyler.lightledger/com.thelightphone.sdk.LightActivity`. `serverPackage` in `lighttool.toml` must be `com.thelightphone.sdk.emulator` for this loop (flip to `com.lightos` only when testing on the physical LP3).
- Commit after every task; end commit messages with `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`.

---

### Task 1: Module scaffold + Home stub

**Files:**
- Create: `ledger/build.gradle.kts`
- Create: `ledger/lighttool.toml`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/ui/home/HomeScreen.kt`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: buildable `:ledger` module; `HomeScreen` class (`@InitialScreen`, package `dev.tyler.lightledger.ui.home`) — Task 18 replaces its body but keeps the same file/class/package so `@InitialScreen` never moves or duplicates.

- [ ] **Step 1: Create the module directory and build script**

```bash
mkdir -p ledger/src/main/kotlin/dev/tyler/lightledger/ui/home
mkdir -p ledger/src/test/kotlin/dev/tyler/lightledger
```

`ledger/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    compileSdk = rootProject.ext["compileSdk"] as Int

    signingConfigs {
        create("lightsdkDev") {
            storeFile = file("../sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int

        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        release {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    implementation(project(":sdk:client"))
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

`ledger/lighttool.toml`:

```toml
[tool]
id = "dev.tyler.lightledger"
label = "Ledger"
versionCode = 1
versionName = "0.1.0"
permissions = []
# change if you run this on an LP3!
# serverPackage = "com.lightos"
serverPackage = "com.thelightphone.sdk.emulator"
```

- [ ] **Step 2: Register the module in settings.gradle.kts**

In `settings.gradle.kts`, add immediately after `include(":tool")`:

```kotlin
include(":tool")
include(":ledger")
```

- [ ] **Step 3: Write the Home stub**

`ledger/src/main/kotlin/dev/tyler/lightledger/ui/home/HomeScreen.kt`:

```kotlin
package dev.tyler.lightledger.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
                contentAlignment = Alignment.Center,
            ) {
                LightText(text = "Ledger", variant = LightTextVariant.Title)
            }
        }
    }
}
```

- [ ] **Step 4: Build and verify**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:assembleDebug 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, no plugin scan violations.

- [ ] **Step 5: Install on the emulator and confirm the stub renders**

Run: `emulator -avd Light_Phone -writable-system &` (skip if already running), then once booted:
`./gradlew :ledger:installDebug && adb shell am start -n dev.tyler.lightledger/com.thelightphone.sdk.LightActivity`
Expected: app launches showing "Ledger" centered on a themed background.

- [ ] **Step 6: Commit**

```bash
git add ledger/build.gradle.kts ledger/lighttool.toml ledger/src/main/kotlin/dev/tyler/lightledger/ui/home/HomeScreen.kt settings.gradle.kts
git commit -m "$(cat <<'EOF'
feat(ledger): scaffold :ledger module with Home stub

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Amount parsing (`AmountParser`)

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/domain/AmountParser.kt`
- Test: `ledger/src/test/kotlin/dev/tyler/lightledger/domain/AmountParserTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object AmountParser { fun parseToMinorUnits(raw: String, exponent: Int = 2): Long }` — used by Task 4 (`ColumnMapper`), Task 9 (`RoomLedgerRepository`/`FakeLedgerRepository`), Task 12 (`AddEntryViewModel`).

- [ ] **Step 1: Write the failing tests**

`ledger/src/test/kotlin/dev/tyler/lightledger/domain/AmountParserTest.kt`:

```kotlin
package dev.tyler.lightledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AmountParserTest {
    @Test fun parsesPlainDecimal() {
        assertEquals(450L, AmountParser.parseToMinorUnits("4.50"))
    }

    @Test fun parsesNegativeSign() {
        assertEquals(-450L, AmountParser.parseToMinorUnits("-4.50"))
    }

    @Test fun parsesParenthesesAsNegative() {
        assertEquals(-450L, AmountParser.parseToMinorUnits("(4.50)"))
    }

    @Test fun parsesCommaAsDecimalSeparator() {
        assertEquals(450L, AmountParser.parseToMinorUnits("4,50"))
    }

    @Test fun parsesCommaAsThousandsSeparatorWithDot() {
        assertEquals(123456L, AmountParser.parseToMinorUnits("1,234.56"))
    }

    @Test fun parsesCommaAsThousandsSeparatorWithoutDot() {
        assertEquals(123400L, AmountParser.parseToMinorUnits("1,234"))
    }

    @Test fun stripsCurrencySymbolsAndSpaces() {
        assertEquals(450L, AmountParser.parseToMinorUnits("$ 4.50"))
        assertEquals(450L, AmountParser.parseToMinorUnits("€4.50"))
    }

    @Test fun parsesWholeNumberAsMajorUnits() {
        assertEquals(123400L, AmountParser.parseToMinorUnits("1234"))
    }

    @Test fun roundTripsParseFormatParse() {
        val minor = AmountParser.parseToMinorUnits("12.34")
        val text = "%.2f".format(minor / 100.0)
        assertEquals(minor, AmountParser.parseToMinorUnits(text))
    }

    @Test fun blankAmountThrows() {
        assertFailsWith<IllegalArgumentException> { AmountParser.parseToMinorUnits("") }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.domain.AmountParserTest' 2>&1 | tail -30`
Expected: compile failure — `AmountParser` is unresolved.

- [ ] **Step 3: Implement**

`ledger/src/main/kotlin/dev/tyler/lightledger/domain/AmountParser.kt`:

```kotlin
package dev.tyler.lightledger.domain

import java.math.BigDecimal
import java.math.RoundingMode

object AmountParser {
    private val CURRENCY_SYMBOLS = setOf('$', '€', '£', '¥')

    fun parseToMinorUnits(raw: String, exponent: Int = 2): Long {
        var text = raw.trim()
        require(text.isNotEmpty()) { "Amount is empty" }

        var negative = false
        if (text.startsWith("(") && text.endsWith(")")) {
            negative = true
            text = text.substring(1, text.length - 1)
        }

        text = text.filterNot { it.isWhitespace() || it in CURRENCY_SYMBOLS }

        when {
            text.startsWith("-") -> {
                negative = true
                text = text.substring(1)
            }
            text.startsWith("+") -> text = text.substring(1)
        }

        val commaCount = text.count { it == ',' }
        val dotCount = text.count { it == '.' }
        text = when {
            commaCount == 1 && dotCount == 0 -> {
                // A single comma with exactly 2 trailing digits reads as a decimal
                // separator ("4,50"); 3 trailing digits reads as a thousands
                // separator ("1,234"). Real bank exports use both conventions.
                val afterComma = text.substringAfter(',')
                if (afterComma.length == 3) text.replace(",", "") else text.replace(',', '.')
            }
            commaCount > 0 && dotCount > 0 -> text.replace(",", "")
            commaCount > 1 -> text.replace(",", "")
            else -> text
        }

        require(text.isNotEmpty() && text.any { it.isDigit() }) { "Amount has no digits: $raw" }
        val decimal = BigDecimal(text).setScale(exponent, RoundingMode.HALF_UP)
        val minor = decimal.movePointRight(exponent).longValueExact()
        return if (negative) -minor else minor
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.domain.AmountParserTest' 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, 10 tests pass.

- [ ] **Step 5: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/domain/AmountParser.kt ledger/src/test/kotlin/dev/tyler/lightledger/domain/AmountParserTest.kt
git commit -m "$(cat <<'EOF'
feat(ledger): amount string parsing to minor units

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: CSV parsing (`CsvParser`)

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/domain/CsvParser.kt`
- Test: `ledger/src/test/kotlin/dev/tyler/lightledger/domain/CsvParserTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class CsvTable(val headers: List<String>, val rows: List<List<String>>)`, `object CsvParser { fun parse(rawText: String): CsvTable }` — used by Task 4 (`ColumnMapperTest` fixtures).

- [ ] **Step 1: Write the failing tests**

`ledger/src/test/kotlin/dev/tyler/lightledger/domain/CsvParserTest.kt`:

```kotlin
package dev.tyler.lightledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvParserTest {
    @Test fun parsesSimpleCsv() {
        val table = CsvParser.parse("date,payee,amount\n2026-01-01,Coffee,-4.50\n")
        assertEquals(listOf("date", "payee", "amount"), table.headers)
        assertEquals(listOf(listOf("2026-01-01", "Coffee", "-4.50")), table.rows)
    }

    @Test fun handlesQuotedFieldsWithEmbeddedDelimiterAndEscape() {
        val table = CsvParser.parse("payee,memo\n\"Coffee, Inc\",\"Says \"\"hi\"\"\"\n")
        assertEquals(listOf("Coffee, Inc", "Says \"hi\""), table.rows.first())
    }

    @Test fun toleratesCrlfLineEndings() {
        val table = CsvParser.parse("a,b\r\n1,2\r\n3,4\r\n")
        assertEquals(2, table.rows.size)
    }

    @Test fun stripsUtf8Bom() {
        val table = CsvParser.parse("﻿a,b\n1,2\n")
        assertEquals(listOf("a", "b"), table.headers)
    }

    @Test fun skipsFullyEmptyLines() {
        val table = CsvParser.parse("a,b\n1,2\n\n3,4\n")
        assertEquals(2, table.rows.size)
    }

    @Test fun padsRaggedShortRows() {
        val table = CsvParser.parse("a,b,c\n1,2\n")
        assertEquals(listOf("1", "2", ""), table.rows.first())
    }

    @Test fun truncatesRaggedLongRows() {
        val table = CsvParser.parse("a,b\n1,2,3\n")
        assertEquals(listOf("1", "2"), table.rows.first())
    }

    @Test fun detectsSemicolonDelimiter() {
        val table = CsvParser.parse("a;b;c\n1;2;3\n")
        assertEquals(listOf("a", "b", "c"), table.headers)
        assertEquals(listOf("1", "2", "3"), table.rows.first())
    }

    @Test fun detectsTabDelimiter() {
        val table = CsvParser.parse("a\tb\n1\t2\n")
        assertEquals(listOf("a", "b"), table.headers)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.domain.CsvParserTest' 2>&1 | tail -30`
Expected: compile failure — `CsvParser`/`CsvTable` unresolved.

- [ ] **Step 3: Implement**

`ledger/src/main/kotlin/dev/tyler/lightledger/domain/CsvParser.kt`:

```kotlin
package dev.tyler.lightledger.domain

data class CsvTable(val headers: List<String>, val rows: List<List<String>>)

object CsvParser {
    fun parse(rawText: String): CsvTable {
        val text = stripBom(rawText)
        val delimiter = detectDelimiter(text)
        val records = splitLines(text)
            .filter { it.isNotEmpty() }
            .map { parseLine(it, delimiter) }
        require(records.isNotEmpty()) { "CSV has no rows" }

        val headers = records.first()
        val rows = records.drop(1).map { row ->
            when {
                row.size == headers.size -> row
                row.size < headers.size -> row + List(headers.size - row.size) { "" }
                else -> row.take(headers.size)
            }
        }
        return CsvTable(headers, rows)
    }

    private fun stripBom(text: String): String =
        if (text.isNotEmpty() && text[0] == '﻿') text.substring(1) else text

    private fun splitLines(text: String): List<String> =
        text.split("\r\n", "\n").map { it.removeSuffix("\r") }

    private fun detectDelimiter(text: String): Char {
        val headerLine = text.split("\r\n", "\n").firstOrNull { it.isNotBlank() } ?: return ','
        val counts = listOf(',', ';', '\t').associateWith { d -> headerLine.count { it == d } }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    private fun parseLine(line: String, delimiter: Char): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == delimiter && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.domain.CsvParserTest' 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/domain/CsvParser.kt ledger/src/test/kotlin/dev/tyler/lightledger/domain/CsvParserTest.kt
git commit -m "$(cat <<'EOF'
feat(ledger): RFC-4180-lite CSV parser

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Column mapping (`ColumnMapper`)

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/domain/ColumnMapper.kt`
- Test: `ledger/src/test/kotlin/dev/tyler/lightledger/domain/ColumnMapperTest.kt`

**Interfaces:**
- Consumes: `CsvTable` (Task 3), `AmountParser.parseToMinorUnits(String, Int)` (Task 2).
- Produces: `data class CsvColumnMapping(val dateCol: Int, val dateFormat: String, val payeeCol: Int, val amountCol: Int? = null, val debitCol: Int? = null, val creditCol: Int? = null, val memoCol: Int? = null, val negateAmounts: Boolean = false)`, `data class MappedCsvRow(val date: LocalDate, val amountMinor: Long, val payee: String, val memo: String)`, `object ColumnMapper { fun map(table: CsvTable, mapping: CsvColumnMapping, currencyExponent: Int = 2): List<MappedCsvRow> }`. Not consumed by any other M0–M2 task (M4 wires it to the CSV import screen).

- [ ] **Step 1: Write the failing tests**

`ledger/src/test/kotlin/dev/tyler/lightledger/domain/ColumnMapperTest.kt`:

```kotlin
package dev.tyler.lightledger.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ColumnMapperTest {
    private val table = CsvTable(
        headers = listOf("Date", "Description", "Amount"),
        rows = listOf(
            listOf("01/15/2026", "Coffee Shop", "-4.50"),
            listOf("01/16/2026", "Paycheck", "1200.00"),
        ),
    )

    @Test fun mapsSingleAmountColumn() {
        val mapping = CsvColumnMapping(dateCol = 0, dateFormat = "MM/dd/yyyy", amountCol = 2, payeeCol = 1)
        val rows = ColumnMapper.map(table, mapping)
        assertEquals(LocalDate.of(2026, 1, 15), rows[0].date)
        assertEquals(-450L, rows[0].amountMinor)
        assertEquals("Coffee Shop", rows[0].payee)
    }

    @Test fun negatesAmountsWhenConfigured() {
        val mapping = CsvColumnMapping(
            dateCol = 0, dateFormat = "MM/dd/yyyy", amountCol = 2, payeeCol = 1, negateAmounts = true,
        )
        val rows = ColumnMapper.map(table, mapping)
        assertEquals(450L, rows[0].amountMinor)
    }

    @Test fun mapsDebitColumnAsNegative() {
        val debitCreditTable = CsvTable(
            headers = listOf("Date", "Description", "Debit", "Credit"),
            rows = listOf(listOf("01/15/2026", "Coffee Shop", "4.50", "")),
        )
        val mapping = CsvColumnMapping(dateCol = 0, dateFormat = "MM/dd/yyyy", debitCol = 2, creditCol = 3, payeeCol = 1)
        val rows = ColumnMapper.map(debitCreditTable, mapping)
        assertEquals(-450L, rows[0].amountMinor)
    }

    @Test fun mapsCreditColumnAsPositive() {
        val debitCreditTable = CsvTable(
            headers = listOf("Date", "Description", "Debit", "Credit"),
            rows = listOf(listOf("01/16/2026", "Paycheck", "", "1200.00")),
        )
        val mapping = CsvColumnMapping(dateCol = 0, dateFormat = "MM/dd/yyyy", debitCol = 2, creditCol = 3, payeeCol = 1)
        val rows = ColumnMapper.map(debitCreditTable, mapping)
        assertEquals(120000L, rows[0].amountMinor)
    }

    @Test fun usesMemoColumnWhenPresent() {
        val memoTable = CsvTable(
            headers = listOf("Date", "Description", "Amount", "Memo"),
            rows = listOf(listOf("01/15/2026", "Coffee Shop", "-4.50", "extra hot")),
        )
        val mapping = CsvColumnMapping(dateCol = 0, dateFormat = "MM/dd/yyyy", amountCol = 2, payeeCol = 1, memoCol = 3)
        val rows = ColumnMapper.map(memoTable, mapping)
        assertEquals("extra hot", rows[0].memo)
    }

    @Test fun defaultsMemoToEmptyWhenNoMemoColumn() {
        val mapping = CsvColumnMapping(dateCol = 0, dateFormat = "MM/dd/yyyy", amountCol = 2, payeeCol = 1)
        val rows = ColumnMapper.map(table, mapping)
        assertEquals("", rows[0].memo)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.domain.ColumnMapperTest' 2>&1 | tail -30`
Expected: compile failure — `ColumnMapper`/`CsvColumnMapping`/`MappedCsvRow` unresolved.

- [ ] **Step 3: Implement**

`ledger/src/main/kotlin/dev/tyler/lightledger/domain/ColumnMapper.kt`:

```kotlin
package dev.tyler.lightledger.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class CsvColumnMapping(
    val dateCol: Int,
    val dateFormat: String,
    val payeeCol: Int,
    val amountCol: Int? = null,
    val debitCol: Int? = null,
    val creditCol: Int? = null,
    val memoCol: Int? = null,
    val negateAmounts: Boolean = false,
)

data class MappedCsvRow(
    val date: LocalDate,
    val amountMinor: Long,
    val payee: String,
    val memo: String,
)

object ColumnMapper {
    fun map(table: CsvTable, mapping: CsvColumnMapping, currencyExponent: Int = 2): List<MappedCsvRow> {
        val formatter = DateTimeFormatter.ofPattern(mapping.dateFormat)
        return table.rows.map { row ->
            MappedCsvRow(
                date = LocalDate.parse(row[mapping.dateCol].trim(), formatter),
                amountMinor = resolveAmountMinor(row, mapping, currencyExponent),
                payee = row[mapping.payeeCol].trim(),
                memo = mapping.memoCol?.let { row.getOrElse(it) { "" }.trim() } ?: "",
            )
        }
    }

    private fun resolveAmountMinor(row: List<String>, mapping: CsvColumnMapping, exponent: Int): Long {
        val amount = if (mapping.amountCol != null) {
            AmountParser.parseToMinorUnits(row[mapping.amountCol], exponent)
        } else {
            val debitText = mapping.debitCol?.let { row.getOrNull(it) }?.trim().orEmpty()
            val creditText = mapping.creditCol?.let { row.getOrNull(it) }?.trim().orEmpty()
            when {
                debitText.isNotEmpty() -> -abs(AmountParser.parseToMinorUnits(debitText, exponent))
                creditText.isNotEmpty() -> abs(AmountParser.parseToMinorUnits(creditText, exponent))
                else -> 0L
            }
        }
        return if (mapping.negateAmounts) -amount else amount
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.domain.ColumnMapperTest' 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/domain/ColumnMapper.kt ledger/src/test/kotlin/dev/tyler/lightledger/domain/ColumnMapperTest.kt
git commit -m "$(cat <<'EOF'
feat(ledger): CSV column mapping (single-amount and debit/credit)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Dedup hashing (`DedupHash`)

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/domain/DedupHash.kt`
- Test: `ledger/src/test/kotlin/dev/tyler/lightledger/domain/DedupHashTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object DedupHash { fun normalizePayee(payee: String): String; fun compute(accountId: Long, postedEpochDay: Long, amountMinor: Long, payee: String): String }` — used by Task 9 (`RoomLedgerRepository.addManualTransaction`, to populate the required `dedupHash` column; not used to reject inserts in M0–M2).

- [ ] **Step 1: Write the failing tests**

`ledger/src/test/kotlin/dev/tyler/lightledger/domain/DedupHashTest.kt`:

```kotlin
package dev.tyler.lightledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DedupHashTest {
    @Test fun normalizesCaseAndWhitespace() {
        assertEquals("coffee shop", DedupHash.normalizePayee("  Coffee   Shop  "))
    }

    @Test fun stripsTrailingDigitSuffix() {
        assertEquals("pos", DedupHash.normalizePayee("POS 1234"))
    }

    @Test fun keepsInternalDigits() {
        assertEquals("7 eleven", DedupHash.normalizePayee("7 Eleven"))
    }

    @Test fun hashIsCaseInsensitiveOnPayee() {
        val a = DedupHash.compute(1L, 20000L, -450L, "Coffee Shop")
        val b = DedupHash.compute(1L, 20000L, -450L, "coffee shop")
        assertEquals(a, b)
    }

    @Test fun hashDiffersWhenAmountDiffers() {
        val a = DedupHash.compute(1L, 20000L, -450L, "Coffee Shop")
        val b = DedupHash.compute(1L, 20000L, -451L, "Coffee Shop")
        assertNotEquals(a, b)
    }

    @Test fun hashDiffersWhenAccountDiffers() {
        val a = DedupHash.compute(1L, 20000L, -450L, "Coffee Shop")
        val b = DedupHash.compute(2L, 20000L, -450L, "Coffee Shop")
        assertNotEquals(a, b)
    }

    @Test fun hashDiffersWhenDayDiffers() {
        val a = DedupHash.compute(1L, 20000L, -450L, "Coffee Shop")
        val b = DedupHash.compute(1L, 20001L, -450L, "Coffee Shop")
        assertNotEquals(a, b)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.domain.DedupHashTest' 2>&1 | tail -30`
Expected: compile failure — `DedupHash` unresolved.

- [ ] **Step 3: Implement**

`ledger/src/main/kotlin/dev/tyler/lightledger/domain/DedupHash.kt`:

```kotlin
package dev.tyler.lightledger.domain

import java.security.MessageDigest

object DedupHash {
    private val TRAILING_DIGITS = Regex("\\s*\\d+$")
    private val WHITESPACE = Regex("\\s+")

    fun normalizePayee(payee: String): String {
        val collapsed = payee.lowercase().trim().replace(WHITESPACE, " ")
        return collapsed.replace(TRAILING_DIGITS, "")
    }

    fun compute(accountId: Long, postedEpochDay: Long, amountMinor: Long, payee: String): String {
        val input = "$accountId|$postedEpochDay|$amountMinor|${normalizePayee(payee)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.domain.DedupHashTest' 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/domain/DedupHash.kt ledger/src/test/kotlin/dev/tyler/lightledger/domain/DedupHashTest.kt
git commit -m "$(cat <<'EOF'
feat(ledger): payee normalization and dedup hashing

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Rule matching (`RuleEngine`)

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/domain/RuleEngine.kt`
- Test: `ledger/src/test/kotlin/dev/tyler/lightledger/domain/RuleEngineTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class CategoryRule(val id: Long, val payeeContains: String, val categoryId: Long, val enabled: Boolean)`, `data class PastConfirmation(val normalizedPayee: String, val categoryId: Long)`, `object RuleEngine { fun matchCategory(rules: List<CategoryRule>, payee: String): Long?; fun shouldCreateRule(pastConfirmations: List<PastConfirmation>, normalizedPayee: String, categoryId: Long, threshold: Int = 3): Boolean }`. Not consumed by any other M0–M2 task (M3/M4 wire this to the review inbox).

- [ ] **Step 1: Write the failing tests**

`ledger/src/test/kotlin/dev/tyler/lightledger/domain/RuleEngineTest.kt`:

```kotlin
package dev.tyler.lightledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleEngineTest {
    @Test fun matchesCaseInsensitiveContains() {
        val rules = listOf(CategoryRule(id = 1L, payeeContains = "starbucks", categoryId = 10L, enabled = true))
        assertEquals(10L, RuleEngine.matchCategory(rules, "STARBUCKS #123"))
    }

    @Test fun noMatchReturnsNull() {
        val rules = listOf(CategoryRule(id = 1L, payeeContains = "starbucks", categoryId = 10L, enabled = true))
        assertNull(RuleEngine.matchCategory(rules, "Peets Coffee"))
    }

    @Test fun disabledRuleIsIgnored() {
        val rules = listOf(CategoryRule(id = 1L, payeeContains = "starbucks", categoryId = 10L, enabled = false))
        assertNull(RuleEngine.matchCategory(rules, "Starbucks"))
    }

    @Test fun firstEnabledMatchWins() {
        val rules = listOf(
            CategoryRule(id = 1L, payeeContains = "coffee", categoryId = 10L, enabled = true),
            CategoryRule(id = 2L, payeeContains = "starbucks", categoryId = 20L, enabled = true),
        )
        assertEquals(10L, RuleEngine.matchCategory(rules, "Starbucks Coffee"))
    }

    @Test fun shouldCreateRuleAtThreshold() {
        val confirmations = List(3) { PastConfirmation("starbucks", 10L) }
        assertTrue(RuleEngine.shouldCreateRule(confirmations, "starbucks", 10L))
    }

    @Test fun shouldNotCreateRuleBelowThreshold() {
        val confirmations = List(2) { PastConfirmation("starbucks", 10L) }
        assertFalse(RuleEngine.shouldCreateRule(confirmations, "starbucks", 10L))
    }

    @Test fun differentCategoryDoesNotCountTowardThreshold() {
        val confirmations = listOf(
            PastConfirmation("starbucks", 10L),
            PastConfirmation("starbucks", 20L),
            PastConfirmation("starbucks", 10L),
        )
        assertFalse(RuleEngine.shouldCreateRule(confirmations, "starbucks", 10L))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.domain.RuleEngineTest' 2>&1 | tail -30`
Expected: compile failure — `RuleEngine`/`CategoryRule`/`PastConfirmation` unresolved.

- [ ] **Step 3: Implement**

`ledger/src/main/kotlin/dev/tyler/lightledger/domain/RuleEngine.kt`:

```kotlin
package dev.tyler.lightledger.domain

data class CategoryRule(
    val id: Long,
    val payeeContains: String,
    val categoryId: Long,
    val enabled: Boolean,
)

data class PastConfirmation(val normalizedPayee: String, val categoryId: Long)

object RuleEngine {
    fun matchCategory(rules: List<CategoryRule>, payee: String): Long? {
        val normalized = payee.lowercase()
        return rules.firstOrNull { it.enabled && normalized.contains(it.payeeContains) }?.categoryId
    }

    fun shouldCreateRule(
        pastConfirmations: List<PastConfirmation>,
        normalizedPayee: String,
        categoryId: Long,
        threshold: Int = 3,
    ): Boolean {
        val matches = pastConfirmations.count {
            it.normalizedPayee == normalizedPayee && it.categoryId == categoryId
        }
        return matches >= threshold
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.domain.RuleEngineTest' 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/domain/RuleEngine.kt ledger/src/test/kotlin/dev/tyler/lightledger/domain/RuleEngineTest.kt
git commit -m "$(cat <<'EOF'
feat(ledger): rule matching and 3-strike auto-rule threshold

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Month math (`LedgerMath`)

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/domain/LedgerMath.kt`
- Test: `ledger/src/test/kotlin/dev/tyler/lightledger/domain/LedgerMathTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class TransactionAmount(val categoryId: Long?, val currency: String, val amountMinor: Long)`, `data class CategoryTotal(val categoryId: Long?, val currency: String, val totalMinor: Long)`, `object LedgerMath { fun epochDayRange(yearMonth: YearMonth): LongRange; fun categoryTotals(transactions: List<TransactionAmount>): List<CategoryTotal> }` — used by Task 9 (`RoomLedgerRepository`/`FakeLedgerRepository`).

- [ ] **Step 1: Write the failing tests**

`ledger/src/test/kotlin/dev/tyler/lightledger/domain/LedgerMathTest.kt`:

```kotlin
package dev.tyler.lightledger.domain

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerMathTest {
    @Test fun epochDayRangeCoversFullMonth() {
        val range = LedgerMath.epochDayRange(YearMonth.of(2026, 2, 1))
        assertEquals(LocalDate.of(2026, 2, 1).toEpochDay(), range.first)
        assertEquals(LocalDate.of(2026, 2, 28).toEpochDay(), range.last)
    }

    @Test fun epochDayRangeHandlesYearWrap() {
        val range = LedgerMath.epochDayRange(YearMonth.of(2025, 12, 1))
        assertEquals(LocalDate.of(2025, 12, 1).toEpochDay(), range.first)
        assertEquals(LocalDate.of(2025, 12, 31).toEpochDay(), range.last)
    }

    @Test fun categoryTotalsSumsPerCategory() {
        val totals = LedgerMath.categoryTotals(
            listOf(
                TransactionAmount(categoryId = 1L, currency = "USD", amountMinor = -1000L),
                TransactionAmount(categoryId = 1L, currency = "USD", amountMinor = -500L),
                TransactionAmount(categoryId = 2L, currency = "USD", amountMinor = 5000L),
            ),
        )
        assertEquals(-1500L, totals.first { it.categoryId == 1L }.totalMinor)
        assertEquals(5000L, totals.first { it.categoryId == 2L }.totalMinor)
    }

    @Test fun keepsCurrenciesSeparate() {
        val totals = LedgerMath.categoryTotals(
            listOf(
                TransactionAmount(categoryId = 1L, currency = "USD", amountMinor = -1000L),
                TransactionAmount(categoryId = 1L, currency = "EUR", amountMinor = -900L),
            ),
        )
        assertEquals(2, totals.size)
        assertEquals(-1000L, totals.first { it.currency == "USD" }.totalMinor)
        assertEquals(-900L, totals.first { it.currency == "EUR" }.totalMinor)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.domain.LedgerMathTest' 2>&1 | tail -30`
Expected: compile failure — `LedgerMath`/`TransactionAmount`/`CategoryTotal` unresolved.

- [ ] **Step 3: Implement**

`ledger/src/main/kotlin/dev/tyler/lightledger/domain/LedgerMath.kt`:

```kotlin
package dev.tyler.lightledger.domain

import java.time.YearMonth

data class TransactionAmount(val categoryId: Long?, val currency: String, val amountMinor: Long)

data class CategoryTotal(val categoryId: Long?, val currency: String, val totalMinor: Long)

object LedgerMath {
    fun epochDayRange(yearMonth: YearMonth): LongRange {
        val start = yearMonth.atDay(1).toEpochDay()
        val end = yearMonth.atEndOfMonth().toEpochDay()
        return start..end
    }

    fun categoryTotals(transactions: List<TransactionAmount>): List<CategoryTotal> =
        transactions
            .groupBy { it.categoryId to it.currency }
            .map { (key, group) ->
                CategoryTotal(
                    categoryId = key.first,
                    currency = key.second,
                    totalMinor = group.sumOf { it.amountMinor },
                )
            }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.domain.LedgerMathTest' 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/domain/LedgerMath.kt ledger/src/test/kotlin/dev/tyler/lightledger/domain/LedgerMathTest.kt
git commit -m "$(cat <<'EOF'
feat(ledger): month epoch-day ranges and per-category totals

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Room entities, DAOs, database

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/data/AccountEntity.kt`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/data/TransactionEntity.kt`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/data/CategoryEntity.kt`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/data/RuleEntity.kt`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/data/CsvProfileEntity.kt`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/data/AccountDao.kt`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/data/TransactionDao.kt`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/data/CategoryDao.kt`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/data/LedgerDatabase.kt`

**Interfaces:**
- Consumes: nothing (entities/DAOs are the base of the data layer).
- Produces: `LedgerDatabase` (Room, `accountDao()`/`transactionDao()`/`categoryDao()`), `AccountEntity`/`TransactionEntity`/`CategoryEntity`/`RuleEntity`/`CsvProfileEntity` — used by Task 9 (`RoomLedgerRepository`). This task has no JVM-testable behavior of its own (Room needs an Android/Robolectric runtime to execute queries) — its "test" is that the Room KSP processor accepts the schema and the module compiles; ViewModel-level behavior is exercised against `FakeLedgerRepository` starting Task 9, and the real Room path is exercised on-device starting Task 1's install step and finished in Task 19.

- [ ] **Step 1: Write the entities**

`ledger/src/main/kotlin/dev/tyler/lightledger/data/AccountEntity.kt`:

```kotlin
package dev.tyler.lightledger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val currency: String,
    val externalId: String? = null,
    val csvProfileId: Long? = null,
    val archived: Boolean = false,
)
```

`ledger/src/main/kotlin/dev/tyler/lightledger/data/TransactionEntity.kt`:

```kotlin
package dev.tyler.lightledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["accountId"]),
        // SQLite treats every NULL as distinct for UNIQUE purposes, so manual
        // rows (externalId = null) never collide with each other here even
        // without a partial-index "WHERE externalId IS NOT NULL" clause.
        Index(value = ["accountId", "externalId"], unique = true),
        Index(value = ["dedupHash"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val postedEpochDay: Long,
    val amountMinor: Long,
    val payee: String,
    val memo: String = "",
    val categoryId: Long? = null,
    val status: String,
    val source: String,
    val externalId: String? = null,
    val pendingExternal: Boolean = false,
    val dedupHash: String,
    val attachmentRef: String? = null,
    val createdAtEpochMs: Long,
)
```

`ledger/src/main/kotlin/dev/tyler/lightledger/data/CategoryEntity.kt`:

```kotlin
package dev.tyler.lightledger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
    val archived: Boolean = false,
)
```

`ledger/src/main/kotlin/dev/tyler/lightledger/data/RuleEntity.kt`:

```kotlin
package dev.tyler.lightledger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payeeContains: String,
    val categoryId: Long,
    val enabled: Boolean = true,
)
```

`ledger/src/main/kotlin/dev/tyler/lightledger/data/CsvProfileEntity.kt`:

```kotlin
package dev.tyler.lightledger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "csv_profiles")
data class CsvProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val headerHash: String,
    val dateCol: Int,
    val dateFormat: String,
    val amountCol: Int? = null,
    val debitCol: Int? = null,
    val creditCol: Int? = null,
    val payeeCol: Int,
    val memoCol: Int? = null,
    val negateAmounts: Boolean = false,
)
```

- [ ] **Step 2: Write the DAOs (synchronous, matching `examples/authenticator`'s `TotpAccountDao` — callers wrap with `Dispatchers.IO` at the repository layer in Task 9)**

`ledger/src/main/kotlin/dev/tyler/lightledger/data/AccountDao.kt`:

```kotlin
package dev.tyler.lightledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface AccountDao {
    @Insert
    fun insert(account: AccountEntity): Long

    @Query("SELECT COUNT(*) FROM accounts")
    fun count(): Int

    @Query("SELECT * FROM accounts WHERE kind = 'MANUAL' AND archived = 0 LIMIT 1")
    fun findManualAccount(): AccountEntity?
}
```

`ledger/src/main/kotlin/dev/tyler/lightledger/data/TransactionDao.kt`:

```kotlin
package dev.tyler.lightledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface TransactionDao {
    @Insert
    fun insert(transaction: TransactionEntity): Long

    @Query(
        "SELECT * FROM transactions WHERE status = 'CONFIRMED' " +
            "AND postedEpochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY postedEpochDay DESC, id DESC",
    )
    fun listConfirmedInRange(startEpochDay: Long, endEpochDay: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun getById(id: Long): TransactionEntity?

    @Query("UPDATE transactions SET categoryId = :categoryId WHERE id = :id")
    fun updateCategory(id: Long, categoryId: Long): Int

    @Query("UPDATE transactions SET memo = :memo WHERE id = :id")
    fun updateMemo(id: Long, memo: String): Int

    @Query("DELETE FROM transactions WHERE id = :id")
    fun delete(id: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'NEEDS_REVIEW'")
    fun countNeedsReview(): Int
}
```

`ledger/src/main/kotlin/dev/tyler/lightledger/data/CategoryDao.kt`:

```kotlin
package dev.tyler.lightledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface CategoryDao {
    @Insert
    fun insert(category: CategoryEntity): Long

    @Query("SELECT * FROM categories WHERE archived = 0 ORDER BY sortOrder")
    fun listActive(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    fun count(): Int

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    fun rename(id: Long, name: String): Int

    @Query("UPDATE categories SET archived = 1 WHERE id = :id")
    fun archive(id: Long): Int
}
```

- [ ] **Step 3: Write the database**

`ledger/src/main/kotlin/dev/tyler/lightledger/data/LedgerDatabase.kt`:

```kotlin
package dev.tyler.lightledger.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        CategoryEntity::class,
        RuleEntity::class,
        CsvProfileEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LedgerDatabase : RoomDatabase() {
    internal abstract fun accountDao(): AccountDao
    internal abstract fun transactionDao(): TransactionDao
    internal abstract fun categoryDao(): CategoryDao
}
```

- [ ] **Step 4: Compile-verify (Room/KSP is the check here — there is no JVM runtime test for Room DAOs in this repo's testing doctrine)**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:compileDebugKotlin 2>&1 | tail -40`
Expected: `BUILD SUCCESSFUL`. If Room/KSP rejects the schema (e.g. an unsupported column type), the error names the offending entity/column directly — fix and rerun.

- [ ] **Step 5: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/data/AccountEntity.kt ledger/src/main/kotlin/dev/tyler/lightledger/data/TransactionEntity.kt ledger/src/main/kotlin/dev/tyler/lightledger/data/CategoryEntity.kt ledger/src/main/kotlin/dev/tyler/lightledger/data/RuleEntity.kt ledger/src/main/kotlin/dev/tyler/lightledger/data/CsvProfileEntity.kt ledger/src/main/kotlin/dev/tyler/lightledger/data/AccountDao.kt ledger/src/main/kotlin/dev/tyler/lightledger/data/TransactionDao.kt ledger/src/main/kotlin/dev/tyler/lightledger/data/CategoryDao.kt ledger/src/main/kotlin/dev/tyler/lightledger/data/LedgerDatabase.kt
git commit -m "$(cat <<'EOF'
feat(ledger): Room entities, DAOs, and LedgerDatabase

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: `LedgerRepository` (interface, Room impl, fake, contract test)

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/data/LedgerConstants.kt`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/data/LedgerModels.kt`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/data/LedgerRepository.kt`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/data/RoomLedgerRepository.kt`
- Test: `ledger/src/test/kotlin/dev/tyler/lightledger/data/FakeLedgerRepository.kt`
- Test: `ledger/src/test/kotlin/dev/tyler/lightledger/data/LedgerRepositoryContractTest.kt`

**Interfaces:**
- Consumes: `LedgerDatabase`/DAOs/entities (Task 8), `DedupHash.compute` (Task 5), `LedgerMath.epochDayRange`/`categoryTotals`/`TransactionAmount` (Task 7).
- Produces: `data class Category(val id: Long, val name: String, val sortOrder: Int)`, `data class Transaction(val id: Long, val accountId: Long, val postedEpochDay: Long, val amountMinor: Long, val payee: String, val memo: String, val categoryId: Long?)`, `data class CategoryMonthTotal(val categoryId: Long, val categoryName: String, val totalMinor: Long)`, `interface LedgerRepository` with the full suspend method set below, `RoomLedgerRepository` (companion `getInstance(databaseProvider: () -> LedgerDatabase)`, `const val DATABASE_NAME`), `FakeLedgerRepository` (test-only, also exposes `val seeded: Boolean`) — every remaining M2 task (11–18) depends on `LedgerRepository`/`Category`/`Transaction`/`CategoryMonthTotal`; every ViewModel test depends on `FakeLedgerRepository`.

- [ ] **Step 1: Write the failing contract test (drives the interface + fake into existence)**

`ledger/src/test/kotlin/dev/tyler/lightledger/data/LedgerRepositoryContractTest.kt`:

```kotlin
package dev.tyler.lightledger.data

import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LedgerRepositoryContractTest {
    @Test fun ensureSeededCreatesEightDefaultCategories() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        assertEquals(8, repo.listCategories().size)
        assertEquals("Groceries", repo.listCategories().first().name)
    }

    @Test fun ensureSeededIsIdempotent() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        repo.ensureSeeded()
        assertEquals(8, repo.listCategories().size)
    }

    @Test fun addManualTransactionIsRetrievable() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val category = repo.listCategories().first()
        val id = repo.addManualTransaction(amountMinor = -450L, payee = "Coffee Shop", categoryId = category.id)
        val stored = repo.getTransaction(id)
        assertNotNull(stored)
        assertEquals(-450L, stored.amountMinor)
        assertEquals("Coffee Shop", stored.payee)
    }

    @Test fun monthSummarySumsByCategory() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val groceries = repo.listCategories().first { it.name == "Groceries" }
        repo.addManualTransaction(amountMinor = -1000L, payee = "Store A", categoryId = groceries.id)
        repo.addManualTransaction(amountMinor = -500L, payee = "Store B", categoryId = groceries.id)
        val summary = repo.monthSummary(YearMonth.now())
        assertEquals(-1500L, summary.first { it.categoryId == groceries.id }.totalMinor)
    }

    @Test fun deleteTransactionRemovesIt() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val category = repo.listCategories().first()
        val id = repo.addManualTransaction(amountMinor = -200L, payee = "Test", categoryId = category.id)
        repo.deleteTransaction(id)
        assertNull(repo.getTransaction(id))
    }

    @Test fun updateTransactionCategoryPersists() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val categories = repo.listCategories()
        val id = repo.addManualTransaction(amountMinor = -200L, payee = "Test", categoryId = categories[0].id)
        repo.updateTransactionCategory(id, categories[1].id)
        assertEquals(categories[1].id, repo.getTransaction(id)?.categoryId)
    }

    @Test fun archiveCategoryRemovesFromActiveList() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val category = repo.listCategories().first()
        repo.archiveCategory(category.id)
        assertTrue(repo.listCategories().none { it.id == category.id })
    }

    @Test fun needsReviewCountIsZeroForManualOnlyData() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        assertEquals(0, repo.needsReviewCount())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.data.LedgerRepositoryContractTest' 2>&1 | tail -30`
Expected: compile failure — `FakeLedgerRepository`/`LedgerRepository`/`Category` unresolved.

- [ ] **Step 3: Implement the constants and models**

`ledger/src/main/kotlin/dev/tyler/lightledger/data/LedgerConstants.kt`:

```kotlin
package dev.tyler.lightledger.data

object AccountKind {
    const val MANUAL = "MANUAL"
    const val CSV = "CSV"
    const val SIMPLEFIN = "SIMPLEFIN"
}

object TransactionStatus {
    const val NEEDS_REVIEW = "NEEDS_REVIEW"
    const val CONFIRMED = "CONFIRMED"
}

object TransactionSource {
    const val MANUAL = "MANUAL"
    const val CSV = "CSV"
    const val SIMPLEFIN = "SIMPLEFIN"
}
```

`ledger/src/main/kotlin/dev/tyler/lightledger/data/LedgerModels.kt`:

```kotlin
package dev.tyler.lightledger.data

data class Category(val id: Long, val name: String, val sortOrder: Int)

data class Transaction(
    val id: Long,
    val accountId: Long,
    val postedEpochDay: Long,
    val amountMinor: Long,
    val payee: String,
    val memo: String,
    val categoryId: Long?,
)

data class CategoryMonthTotal(val categoryId: Long, val categoryName: String, val totalMinor: Long)
```

- [ ] **Step 4: Implement the interface**

`ledger/src/main/kotlin/dev/tyler/lightledger/data/LedgerRepository.kt`:

```kotlin
package dev.tyler.lightledger.data

import java.time.YearMonth

interface LedgerRepository {
    suspend fun ensureSeeded()
    suspend fun listCategories(): List<Category>
    suspend fun addCategory(name: String): Category
    suspend fun renameCategory(id: Long, name: String)
    suspend fun archiveCategory(id: Long)
    suspend fun addManualTransaction(amountMinor: Long, payee: String, categoryId: Long, memo: String = ""): Long
    suspend fun monthSummary(month: YearMonth): List<CategoryMonthTotal>
    suspend fun listTransactions(month: YearMonth): List<Transaction>
    suspend fun getTransaction(id: Long): Transaction?
    suspend fun updateTransactionCategory(id: Long, categoryId: Long)
    suspend fun updateTransactionMemo(id: Long, memo: String)
    suspend fun deleteTransaction(id: Long)
    suspend fun needsReviewCount(): Int
}
```

- [ ] **Step 5: Implement the Room-backed repository**

`ledger/src/main/kotlin/dev/tyler/lightledger/data/RoomLedgerRepository.kt`:

```kotlin
package dev.tyler.lightledger.data

import dev.tyler.lightledger.domain.DedupHash
import dev.tyler.lightledger.domain.LedgerMath
import dev.tyler.lightledger.domain.TransactionAmount
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomLedgerRepository private constructor(
    database: LedgerDatabase,
) : LedgerRepository {
    private val accountDao = database.accountDao()
    private val transactionDao = database.transactionDao()
    private val categoryDao = database.categoryDao()

    override suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        if (accountDao.count() == 0) {
            accountDao.insert(AccountEntity(name = "Cash", kind = AccountKind.MANUAL, currency = DEFAULT_CURRENCY))
        }
        if (categoryDao.count() == 0) {
            DEFAULT_CATEGORIES.forEachIndexed { index, name ->
                categoryDao.insert(CategoryEntity(name = name, sortOrder = index))
            }
        }
    }

    override suspend fun listCategories(): List<Category> = withContext(Dispatchers.IO) {
        categoryDao.listActive().map { it.toDomain() }
    }

    override suspend fun addCategory(name: String): Category = withContext(Dispatchers.IO) {
        val nextOrder = categoryDao.count()
        val id = categoryDao.insert(CategoryEntity(name = name, sortOrder = nextOrder))
        Category(id = id, name = name, sortOrder = nextOrder)
    }

    override suspend fun renameCategory(id: Long, name: String): Unit = withContext(Dispatchers.IO) {
        categoryDao.rename(id, name)
        Unit
    }

    override suspend fun archiveCategory(id: Long): Unit = withContext(Dispatchers.IO) {
        categoryDao.archive(id)
        Unit
    }

    override suspend fun addManualTransaction(
        amountMinor: Long,
        payee: String,
        categoryId: Long,
        memo: String,
    ): Long = withContext(Dispatchers.IO) {
        val account = accountDao.findManualAccount()
            ?: error("Manual account missing — call ensureSeeded() first")
        val postedEpochDay = LocalDate.now().toEpochDay()
        transactionDao.insert(
            TransactionEntity(
                accountId = account.id,
                postedEpochDay = postedEpochDay,
                amountMinor = amountMinor,
                payee = payee,
                memo = memo,
                categoryId = categoryId,
                status = TransactionStatus.CONFIRMED,
                source = TransactionSource.MANUAL,
                dedupHash = DedupHash.compute(account.id, postedEpochDay, amountMinor, payee),
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun monthSummary(month: YearMonth): List<CategoryMonthTotal> = withContext(Dispatchers.IO) {
        val range = LedgerMath.epochDayRange(month)
        val transactions = transactionDao.listConfirmedInRange(range.first, range.last)
        val categoriesById = categoryDao.listActive().associateBy { it.id }
        LedgerMath.categoryTotals(
            transactions.mapNotNull { txn ->
                val categoryId = txn.categoryId ?: return@mapNotNull null
                TransactionAmount(categoryId = categoryId, currency = DEFAULT_CURRENCY, amountMinor = txn.amountMinor)
            },
        ).mapNotNull { total ->
            val categoryId = total.categoryId ?: return@mapNotNull null
            val name = categoriesById[categoryId]?.name ?: return@mapNotNull null
            CategoryMonthTotal(categoryId = categoryId, categoryName = name, totalMinor = total.totalMinor)
        }
    }

    override suspend fun listTransactions(month: YearMonth): List<Transaction> = withContext(Dispatchers.IO) {
        val range = LedgerMath.epochDayRange(month)
        transactionDao.listConfirmedInRange(range.first, range.last).map { it.toDomain() }
    }

    override suspend fun getTransaction(id: Long): Transaction? = withContext(Dispatchers.IO) {
        transactionDao.getById(id)?.toDomain()
    }

    override suspend fun updateTransactionCategory(id: Long, categoryId: Long): Unit = withContext(Dispatchers.IO) {
        transactionDao.updateCategory(id, categoryId)
        Unit
    }

    override suspend fun updateTransactionMemo(id: Long, memo: String): Unit = withContext(Dispatchers.IO) {
        transactionDao.updateMemo(id, memo)
        Unit
    }

    override suspend fun deleteTransaction(id: Long): Unit = withContext(Dispatchers.IO) {
        transactionDao.delete(id)
        Unit
    }

    override suspend fun needsReviewCount(): Int = withContext(Dispatchers.IO) {
        transactionDao.countNeedsReview()
    }

    companion object {
        const val DATABASE_NAME = "ledger.db"
        private const val DEFAULT_CURRENCY = "USD"
        private val DEFAULT_CATEGORIES = listOf(
            "Groceries", "Dining", "Transport", "Home", "Health", "Fun", "Income", "Other",
        )

        @Volatile
        private var instance: RoomLedgerRepository? = null

        fun getInstance(databaseProvider: () -> LedgerDatabase): RoomLedgerRepository {
            return instance ?: synchronized(this) {
                instance ?: RoomLedgerRepository(databaseProvider()).also { instance = it }
            }
        }
    }
}

private fun CategoryEntity.toDomain() = Category(id = id, name = name, sortOrder = sortOrder)

private fun TransactionEntity.toDomain() = Transaction(
    id = id,
    accountId = accountId,
    postedEpochDay = postedEpochDay,
    amountMinor = amountMinor,
    payee = payee,
    memo = memo,
    categoryId = categoryId,
)
```

- [ ] **Step 6: Implement the fake repository (test-only)**

`ledger/src/test/kotlin/dev/tyler/lightledger/data/FakeLedgerRepository.kt`:

```kotlin
package dev.tyler.lightledger.data

import dev.tyler.lightledger.domain.LedgerMath
import dev.tyler.lightledger.domain.TransactionAmount
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicLong

class FakeLedgerRepository : LedgerRepository {
    private val categories = mutableListOf<Category>()
    private val transactions = mutableListOf<Transaction>()
    private val categoryIds = AtomicLong(1)
    private val transactionIds = AtomicLong(1)

    var seeded = false
        private set

    override suspend fun ensureSeeded() {
        if (seeded) return
        seeded = true
        listOf("Groceries", "Dining", "Transport", "Home", "Health", "Fun", "Income", "Other")
            .forEach { addCategory(it) }
    }

    override suspend fun listCategories(): List<Category> = categories.toList()

    override suspend fun addCategory(name: String): Category {
        val category = Category(id = categoryIds.getAndIncrement(), name = name, sortOrder = categories.size)
        categories.add(category)
        return category
    }

    override suspend fun renameCategory(id: Long, name: String) {
        val index = categories.indexOfFirst { it.id == id }
        if (index >= 0) categories[index] = categories[index].copy(name = name)
    }

    override suspend fun archiveCategory(id: Long) {
        categories.removeAll { it.id == id }
    }

    override suspend fun addManualTransaction(
        amountMinor: Long,
        payee: String,
        categoryId: Long,
        memo: String,
    ): Long {
        val id = transactionIds.getAndIncrement()
        transactions.add(
            Transaction(
                id = id,
                accountId = 1L,
                postedEpochDay = LocalDate.now().toEpochDay(),
                amountMinor = amountMinor,
                payee = payee,
                memo = memo,
                categoryId = categoryId,
            ),
        )
        return id
    }

    override suspend fun monthSummary(month: YearMonth): List<CategoryMonthTotal> {
        val range = LedgerMath.epochDayRange(month)
        val inRange = transactions.filter { it.postedEpochDay in range }
        return LedgerMath.categoryTotals(
            inRange.mapNotNull { txn ->
                val categoryId = txn.categoryId ?: return@mapNotNull null
                TransactionAmount(categoryId = categoryId, currency = "USD", amountMinor = txn.amountMinor)
            },
        ).mapNotNull { total ->
            val categoryId = total.categoryId ?: return@mapNotNull null
            val name = categories.firstOrNull { it.id == categoryId }?.name ?: return@mapNotNull null
            CategoryMonthTotal(categoryId = categoryId, categoryName = name, totalMinor = total.totalMinor)
        }
    }

    override suspend fun listTransactions(month: YearMonth): List<Transaction> {
        val range = LedgerMath.epochDayRange(month)
        return transactions.filter { it.postedEpochDay in range }.sortedByDescending { it.postedEpochDay }
    }

    override suspend fun getTransaction(id: Long): Transaction? = transactions.firstOrNull { it.id == id }

    override suspend fun updateTransactionCategory(id: Long, categoryId: Long) {
        val index = transactions.indexOfFirst { it.id == id }
        if (index >= 0) transactions[index] = transactions[index].copy(categoryId = categoryId)
    }

    override suspend fun updateTransactionMemo(id: Long, memo: String) {
        val index = transactions.indexOfFirst { it.id == id }
        if (index >= 0) transactions[index] = transactions[index].copy(memo = memo)
    }

    override suspend fun deleteTransaction(id: Long) {
        transactions.removeAll { it.id == id }
    }

    override suspend fun needsReviewCount(): Int = 0
}
```

- [ ] **Step 7: Run to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.data.LedgerRepositoryContractTest' 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, 8 tests pass.

- [ ] **Step 8: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/data/LedgerConstants.kt ledger/src/main/kotlin/dev/tyler/lightledger/data/LedgerModels.kt ledger/src/main/kotlin/dev/tyler/lightledger/data/LedgerRepository.kt ledger/src/main/kotlin/dev/tyler/lightledger/data/RoomLedgerRepository.kt ledger/src/test/kotlin/dev/tyler/lightledger/data/FakeLedgerRepository.kt ledger/src/test/kotlin/dev/tyler/lightledger/data/LedgerRepositoryContractTest.kt
git commit -m "$(cat <<'EOF'
feat(ledger): LedgerRepository with Room and fake implementations

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Shared UI — `CategoryGrid`, `AmountKeypad`

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/ui/shared/CategoryGrid.kt`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/ui/shared/AmountKeypad.kt`

**Interfaces:**
- Consumes: `Category` (Task 9); `sdk:ui`'s `LightText`/`LightTextVariant`/`gridUnitsAsDp`/`lightClickable`.
- Produces: `@Composable fun CategoryGrid(categories: List<Category>, onSelect: (Category) -> Unit, modifier: Modifier = Modifier)` — used by Task 13 (`AddEntryScreen`), Task 17 (`HistoryScreen`). `@Composable fun AmountKeypad(onDigit: (String) -> Unit, onDecimal: () -> Unit, onBackspace: () -> Unit, modifier: Modifier = Modifier)` — used by Task 13 (`AddEntryScreen`).

- [ ] **Step 1: Write `CategoryGrid`**

`ledger/src/main/kotlin/dev/tyler/lightledger/ui/shared/CategoryGrid.kt`:

```kotlin
package dev.tyler.lightledger.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import dev.tyler.lightledger.data.Category

@Composable
fun CategoryGrid(
    categories: List<Category>,
    onSelect: (Category) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp()),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(categories, key = { it.id }) { category ->
            LightText(
                text = category.name.uppercase(),
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { onSelect(category) }
                    .padding(vertical = 1f.gridUnitsAsDp()),
            )
        }
    }
}
```

- [ ] **Step 2: Write `AmountKeypad`**

`ledger/src/main/kotlin/dev/tyler/lightledger/ui/shared/AmountKeypad.kt`:

```kotlin
package dev.tyler.lightledger.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

private val KEYPAD_ROWS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf(".", "0", "⌫"),
)

@Composable
fun AmountKeypad(
    onDigit: (String) -> Unit,
    onDecimal: () -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        KEYPAD_ROWS.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { key ->
                    // Subheading, not Heading, and tight vertical padding — 4 rows
                    // of keys sit above a fixed (non-scrolling) bottom bar on the
                    // LP3's ~472dp-tall panel; see AmountStep's comment for why.
                    LightText(
                        text = key,
                        variant = LightTextVariant.Subheading,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .lightClickable {
                                when (key) {
                                    "." -> onDecimal()
                                    "⌫" -> onBackspace()
                                    else -> onDigit(key)
                                }
                            }
                            .padding(vertical = 0.5f.gridUnitsAsDp(), horizontal = 1.5f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: Build-verify**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:compileDebugKotlin 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/ui/shared/CategoryGrid.kt ledger/src/main/kotlin/dev/tyler/lightledger/ui/shared/AmountKeypad.kt
git commit -m "$(cat <<'EOF'
feat(ledger): shared CategoryGrid and AmountKeypad composables

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: `HomeViewModel`

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/ui/home/HomeViewModel.kt`
- Test: `ledger/src/test/kotlin/dev/tyler/lightledger/ui/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `LedgerRepository`/`CategoryMonthTotal` (Task 9), `LightViewModel<Unit>`/`SimpleLightScreen<Unit>` (SDK).
- Produces: `data class HomeUiState(val month: YearMonth = YearMonth.now(), val categoryTotals: List<CategoryMonthTotal> = emptyList(), val needsReviewCount: Int = 0, val loading: Boolean = true)`, `class HomeViewModel(repository: LedgerRepository) : LightViewModel<Unit>()` with `val uiState: StateFlow<HomeUiState>` and `fun reload()`, top-level `fun totalSpentMinor(totals: List<CategoryMonthTotal>): Long` — used by Task 18 (`HomeScreen`).

- [ ] **Step 1: Write the failing tests**

`ledger/src/test/kotlin/dev/tyler/lightledger/ui/home/HomeViewModelTest.kt`:

```kotlin
package dev.tyler.lightledger.ui.home

import dev.tyler.lightledger.data.CategoryMonthTotal
import dev.tyler.lightledger.data.FakeLedgerRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun initSeedsRepositoryAndClearsLoading() = runTest {
        val repository = FakeLedgerRepository()
        val vm = HomeViewModel(repository)
        advanceUntilIdle()

        assertEquals(true, repository.seeded)
        assertFalse(vm.uiState.value.loading)
    }

    @Test fun reloadReflectsNewTransactions() = runTest {
        val repository = FakeLedgerRepository()
        val vm = HomeViewModel(repository)
        advanceUntilIdle()

        val groceries = repository.listCategories().first { it.name == "Groceries" }
        repository.addManualTransaction(amountMinor = -1200L, payee = "Market", categoryId = groceries.id)
        vm.reload()
        advanceUntilIdle()

        val total = vm.uiState.value.categoryTotals.first { it.categoryId == groceries.id }
        assertEquals(-1200L, total.totalMinor)
    }

    @Test fun totalSpentMinorSumsOnlyNegativeAmounts() {
        val totals = listOf(
            CategoryMonthTotal(1L, "Groceries", -1200L),
            CategoryMonthTotal(2L, "Income", 5000L),
        )
        assertEquals(1200L, totalSpentMinor(totals))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.ui.home.HomeViewModelTest' 2>&1 | tail -30`
Expected: compile failure — `HomeViewModel`/`totalSpentMinor` unresolved.

- [ ] **Step 3: Implement**

`ledger/src/main/kotlin/dev/tyler/lightledger/ui/home/HomeViewModel.kt`:

```kotlin
package dev.tyler.lightledger.ui.home

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import dev.tyler.lightledger.data.CategoryMonthTotal
import dev.tyler.lightledger.data.LedgerRepository
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val month: YearMonth = YearMonth.now(),
    val categoryTotals: List<CategoryMonthTotal> = emptyList(),
    val needsReviewCount: Int = 0,
    val loading: Boolean = true,
)

class HomeViewModel(private val repository: LedgerRepository) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            repository.ensureSeeded()
            val month = _uiState.value.month
            _uiState.value = _uiState.value.copy(
                categoryTotals = repository.monthSummary(month),
                needsReviewCount = repository.needsReviewCount(),
                loading = false,
            )
        }
    }
}

fun totalSpentMinor(totals: List<CategoryMonthTotal>): Long =
    totals.filter { it.totalMinor < 0L }.sumOf { -it.totalMinor }
```

- [ ] **Step 4: Run to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.ui.home.HomeViewModelTest' 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/ui/home/HomeViewModel.kt ledger/src/test/kotlin/dev/tyler/lightledger/ui/home/HomeViewModelTest.kt
git commit -m "$(cat <<'EOF'
feat(ledger): HomeViewModel with month summary reload

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: `AddEntryViewModel`

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/ui/addentry/AddEntryViewModel.kt`
- Test: `ledger/src/test/kotlin/dev/tyler/lightledger/ui/addentry/AddEntryViewModelTest.kt`

**Interfaces:**
- Consumes: `LedgerRepository`/`Category` (Task 9), `AmountParser.parseToMinorUnits` (Task 2).
- Produces: `enum class AddEntryStep { AMOUNT, PAYEE, CATEGORY }`, `data class AddEntryUiState(val step: AddEntryStep = AddEntryStep.AMOUNT, val amountText: String = "", val payee: String = "", val categories: List<Category> = emptyList(), val saved: Boolean = false)`, `class AddEntryViewModel(repository: LedgerRepository) : LightViewModel<Unit>()` with `uiState`, `onDigit`, `onDecimal`, `onBackspace`, `canContinueFromAmount()`, `confirmAmount()`, `confirmPayee(payee: String)`, `selectCategory(category: Category)`, `onBackPressed()` (steps back through AMOUNT→PAYEE→CATEGORY, `false` at AMOUNT) — used by Task 13 (`AddEntryScreen`).

- [ ] **Step 1: Write the failing tests**

`ledger/src/test/kotlin/dev/tyler/lightledger/ui/addentry/AddEntryViewModelTest.kt`:

```kotlin
package dev.tyler.lightledger.ui.addentry

import dev.tyler.lightledger.data.FakeLedgerRepository
import java.time.YearMonth
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class AddEntryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeLedgerRepository

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeLedgerRepository()
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun digitsBuildAmountText() = runTest {
        repository.ensureSeeded()
        val vm = AddEntryViewModel(repository)
        vm.onDigit("4")
        vm.onDecimal()
        vm.onDigit("5")
        vm.onDigit("0")
        assertEquals("4.50", vm.uiState.value.amountText)
    }

    @Test fun cannotContinueWithZeroAmount() = runTest {
        repository.ensureSeeded()
        val vm = AddEntryViewModel(repository)
        vm.onDigit("0")
        assertFalse(vm.canContinueFromAmount())
    }

    @Test fun confirmAmountAdvancesToPayeeStep() = runTest {
        repository.ensureSeeded()
        val vm = AddEntryViewModel(repository)
        vm.onDigit("4")
        vm.onDecimal()
        vm.onDigit("5")
        vm.confirmAmount()
        assertEquals(AddEntryStep.PAYEE, vm.uiState.value.step)
    }

    @Test fun selectCategorySavesNegativeAmountAndMarksSaved() = runTest {
        repository.ensureSeeded()
        val vm = AddEntryViewModel(repository)
        advanceUntilIdle()
        vm.onDigit("4")
        vm.onDecimal()
        vm.onDigit("5")
        vm.confirmAmount()
        vm.confirmPayee("Coffee Shop")
        val category = repository.listCategories().first()
        vm.selectCategory(category)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saved)
        val stored = repository.listTransactions(YearMonth.now()).first()
        assertEquals(-450L, stored.amountMinor)
        assertEquals("Coffee Shop", stored.payee)
    }

    @Test fun backFromPayeeReturnsToAmountStep() = runTest {
        repository.ensureSeeded()
        val vm = AddEntryViewModel(repository)
        vm.onDigit("4")
        vm.confirmAmount()
        assertTrue(vm.onBackPressed())
        assertEquals(AddEntryStep.AMOUNT, vm.uiState.value.step)
    }

    @Test fun backFromAmountStepIsNotConsumed() = runTest {
        repository.ensureSeeded()
        val vm = AddEntryViewModel(repository)
        assertFalse(vm.onBackPressed())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.ui.addentry.AddEntryViewModelTest' 2>&1 | tail -30`
Expected: compile failure — `AddEntryViewModel`/`AddEntryStep` unresolved.

- [ ] **Step 3: Implement**

`ledger/src/main/kotlin/dev/tyler/lightledger/ui/addentry/AddEntryViewModel.kt`:

```kotlin
package dev.tyler.lightledger.ui.addentry

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import dev.tyler.lightledger.data.Category
import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.domain.AmountParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AddEntryStep { AMOUNT, PAYEE, CATEGORY }

data class AddEntryUiState(
    val step: AddEntryStep = AddEntryStep.AMOUNT,
    val amountText: String = "",
    val payee: String = "",
    val categories: List<Category> = emptyList(),
    val saved: Boolean = false,
)

class AddEntryViewModel(private val repository: LedgerRepository) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(AddEntryUiState())
    val uiState: StateFlow<AddEntryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(categories = repository.listCategories())
        }
    }

    fun onDigit(digit: String) {
        val current = _uiState.value.amountText
        if (current.substringAfter('.', "").length >= 2) return
        _uiState.value = _uiState.value.copy(amountText = current + digit)
    }

    fun onDecimal() {
        val current = _uiState.value.amountText
        if (current.isEmpty() || current.contains('.')) return
        _uiState.value = _uiState.value.copy(amountText = "$current.")
    }

    fun onBackspace() {
        val current = _uiState.value.amountText
        if (current.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(amountText = current.dropLast(1))
        }
    }

    fun canContinueFromAmount(): Boolean =
        _uiState.value.amountText.isNotEmpty() && parsedAmountMinorOrNull() != null

    fun confirmAmount() {
        if (canContinueFromAmount()) {
            _uiState.value = _uiState.value.copy(step = AddEntryStep.PAYEE)
        }
    }

    fun confirmPayee(payee: String) {
        _uiState.value = _uiState.value.copy(payee = payee, step = AddEntryStep.CATEGORY)
    }

    fun selectCategory(category: Category) {
        val amountMinor = parsedAmountMinorOrNull() ?: return
        viewModelScope.launch {
            repository.addManualTransaction(
                amountMinor = -amountMinor,
                payee = _uiState.value.payee,
                categoryId = category.id,
            )
            _uiState.value = _uiState.value.copy(saved = true)
        }
    }

    override fun onBackPressed(): Boolean {
        return when (_uiState.value.step) {
            AddEntryStep.PAYEE -> {
                _uiState.value = _uiState.value.copy(step = AddEntryStep.AMOUNT)
                true
            }
            AddEntryStep.CATEGORY -> {
                _uiState.value = _uiState.value.copy(step = AddEntryStep.PAYEE)
                true
            }
            AddEntryStep.AMOUNT -> false
        }
    }

    private fun parsedAmountMinorOrNull(): Long? =
        runCatching { AmountParser.parseToMinorUnits(_uiState.value.amountText) }
            .getOrNull()
            ?.takeIf { it > 0L }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.ui.addentry.AddEntryViewModelTest' 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/ui/addentry/AddEntryViewModel.kt ledger/src/test/kotlin/dev/tyler/lightledger/ui/addentry/AddEntryViewModelTest.kt
git commit -m "$(cat <<'EOF'
feat(ledger): AddEntryViewModel amount/payee/category step machine

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: `AddEntryScreen`

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/ui/addentry/AddEntryScreen.kt`

**Interfaces:**
- Consumes: `AddEntryViewModel`/`AddEntryStep` (Task 12), `LedgerRepository` (Task 9), `CategoryGrid`/`AmountKeypad` (Task 10), `LightTextInputEditor`/`rememberKeyboardOptions` (SDK).
- Produces: `class AddEntryScreen(sealedActivity: SealedLightActivity, repository: LedgerRepository) : LightScreen<Unit, AddEntryViewModel>` — used by Task 18 (`HomeScreen`, via `navigateTo`).

- [ ] **Step 1: Implement**

`ledger/src/main/kotlin/dev/tyler/lightledger/ui/addentry/AddEntryScreen.kt`:

```kotlin
package dev.tyler.lightledger.ui.addentry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.ui.shared.AmountKeypad
import dev.tyler.lightledger.ui.shared.CategoryGrid

class AddEntryScreen(
    sealedActivity: SealedLightActivity,
    private val repository: LedgerRepository,
) : LightScreen<Unit, AddEntryViewModel>(sealedActivity) {

    override val viewModelClass: Class<AddEntryViewModel>
        get() = AddEntryViewModel::class.java

    override fun createViewModel() = AddEntryViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val payeeFieldState = rememberTextFieldState(state.payee)
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LaunchedEffect(state.saved) {
            if (state.saved) goBack(Unit)
        }

        LightTheme(colors = themeColors) {
            when (state.step) {
                AddEntryStep.AMOUNT -> AmountStep(
                    amountText = state.amountText,
                    canContinue = viewModel.canContinueFromAmount(),
                    onDigit = viewModel::onDigit,
                    onDecimal = viewModel::onDecimal,
                    onBackspace = viewModel::onBackspace,
                    onContinue = viewModel::confirmAmount,
                    onBack = { goBack(Unit) },
                )

                AddEntryStep.PAYEE -> LightTextInputEditor(
                    title = "Payee",
                    state = payeeFieldState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    onSubmit = { viewModel.confirmPayee(it.toString()) },
                    onBack = { goBack(null) },
                    submitLabel = "NEXT",
                    modifier = Modifier.fillMaxSize(),
                )

                AddEntryStep.CATEGORY -> CategoryStep(
                    categories = state.categories,
                    onSelect = viewModel::selectCategory,
                    onBack = { goBack(Unit) },
                )
            }
        }
    }
}

@Composable
private fun AmountStep(
    amountText: String,
    canContinue: Boolean,
    onDigit: (String) -> Unit,
    onDecimal: () -> Unit,
    onBackspace: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
            center = LightTopBarCenter.Text("Add"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Subtitle, not Title (115 design units) — the LP3 panel is ~472dp tall
            // and near-square; Title here plus a fixed 4-row keypad below (no
            // scroll) overflows the same way the Sudoku board once did.
            LightText(
                text = amountText.ifEmpty { "0" },
                variant = LightTextVariant.Subtitle,
                align = TextAlign.Center,
            )
        }

        AmountKeypad(
            onDigit = onDigit,
            onDecimal = onDecimal,
            onBackspace = onBackspace,
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "NEXT", onClick = onContinue).takeIf { canContinue },
            ),
        )
    }
}

@Composable
private fun CategoryStep(
    categories: List<dev.tyler.lightledger.data.Category>,
    onSelect: (dev.tyler.lightledger.data.Category) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
            center = LightTopBarCenter.Text("Category"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        CategoryGrid(
            categories = categories,
            onSelect = onSelect,
            modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp()),
        )
    }
}
```

- [ ] **Step 2: Build-verify**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:compileDebugKotlin 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/ui/addentry/AddEntryScreen.kt
git commit -m "$(cat <<'EOF'
feat(ledger): AddEntryScreen — amount keypad, payee editor, category grid

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: `CategoriesViewModel`

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/ui/categories/CategoriesViewModel.kt`
- Test: `ledger/src/test/kotlin/dev/tyler/lightledger/ui/categories/CategoriesViewModelTest.kt`

**Interfaces:**
- Consumes: `LedgerRepository`/`Category` (Task 9).
- Produces: `class CategoriesViewModel(repository: LedgerRepository) : LightViewModel<Unit>()` with `val categories: StateFlow<List<Category>>`, `fun addCategory(name: String)`, `fun archiveCategory(id: Long)` — used by Task 15 (`CategoriesScreen`).

- [ ] **Step 1: Write the failing tests**

`ledger/src/test/kotlin/dev/tyler/lightledger/ui/categories/CategoriesViewModelTest.kt`:

```kotlin
package dev.tyler.lightledger.ui.categories

import dev.tyler.lightledger.data.FakeLedgerRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeLedgerRepository

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeLedgerRepository()
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun loadsSeededCategoriesOnInit() = runTest {
        repository.ensureSeeded()
        val vm = CategoriesViewModel(repository)
        advanceUntilIdle()
        assertEquals(8, vm.categories.value.size)
    }

    @Test fun addCategoryAppendsAndReloads() = runTest {
        repository.ensureSeeded()
        val vm = CategoriesViewModel(repository)
        advanceUntilIdle()
        vm.addCategory("Travel")
        advanceUntilIdle()
        assertTrue(vm.categories.value.any { it.name == "Travel" })
    }

    @Test fun blankNameIsIgnored() = runTest {
        repository.ensureSeeded()
        val vm = CategoriesViewModel(repository)
        advanceUntilIdle()
        vm.addCategory("   ")
        advanceUntilIdle()
        assertEquals(8, vm.categories.value.size)
    }

    @Test fun archiveCategoryRemovesFromList() = runTest {
        repository.ensureSeeded()
        val vm = CategoriesViewModel(repository)
        advanceUntilIdle()
        val target = vm.categories.value.first()
        vm.archiveCategory(target.id)
        advanceUntilIdle()
        assertTrue(vm.categories.value.none { it.id == target.id })
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.ui.categories.CategoriesViewModelTest' 2>&1 | tail -30`
Expected: compile failure — `CategoriesViewModel` unresolved.

- [ ] **Step 3: Implement**

`ledger/src/main/kotlin/dev/tyler/lightledger/ui/categories/CategoriesViewModel.kt`:

```kotlin
package dev.tyler.lightledger.ui.categories

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import dev.tyler.lightledger.data.Category
import dev.tyler.lightledger.data.LedgerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CategoriesViewModel(private val repository: LedgerRepository) : LightViewModel<Unit>() {
    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    init {
        reload()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            _categories.value = repository.listCategories()
        }
    }

    fun addCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.addCategory(trimmed)
            reload()
        }
    }

    fun archiveCategory(id: Long) {
        viewModelScope.launch {
            repository.archiveCategory(id)
            reload()
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.ui.categories.CategoriesViewModelTest' 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/ui/categories/CategoriesViewModel.kt ledger/src/test/kotlin/dev/tyler/lightledger/ui/categories/CategoriesViewModelTest.kt
git commit -m "$(cat <<'EOF'
feat(ledger): CategoriesViewModel add/archive/reload

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 15: `SettingsScreen` + `CategoriesScreen`

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/ui/categories/CategoriesScreen.kt`
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/ui/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `CategoriesViewModel` (Task 14), `LedgerRepository` (Task 9), `LightTextInputEditor`/`rememberKeyboardOptions` (SDK).
- Produces: `class CategoriesScreen(sealedActivity: SealedLightActivity, repository: LedgerRepository) : LightScreen<Unit, CategoriesViewModel>`, `class SettingsScreen(sealedActivity: SealedLightActivity, repository: LedgerRepository) : SimpleLightScreen<Unit>` — both used by Task 18 (`HomeScreen`, via `navigateTo`); `SettingsScreen` navigates to `CategoriesScreen` internally.

- [ ] **Step 1: Implement `CategoriesScreen`**

`ledger/src/main/kotlin/dev/tyler/lightledger/ui/categories/CategoriesScreen.kt`:

```kotlin
package dev.tyler.lightledger.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import dev.tyler.lightledger.data.LedgerRepository

class CategoriesScreen(
    sealedActivity: SealedLightActivity,
    private val repository: LedgerRepository,
) : LightScreen<Unit, CategoriesViewModel>(sealedActivity) {

    override val viewModelClass: Class<CategoriesViewModel>
        get() = CategoriesViewModel::class.java

    override fun createViewModel() = CategoriesViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val categories by viewModel.categories.collectAsState()
        var adding by remember { mutableStateOf(false) }
        val nameFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            if (adding) {
                LightTextInputEditor(
                    title = "New Category",
                    state = nameFieldState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    onSubmit = {
                        viewModel.addCategory(it.toString())
                        adding = false
                    },
                    onBack = { adding = false },
                    submitLabel = "ADD",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack(Unit) }),
                        center = LightTopBarCenter.Text("Categories"),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )

                    LightScrollView(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
                    ) {
                        categories.forEach { category ->
                            LightText(
                                text = category.name,
                                variant = LightTextVariant.Copy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable { viewModel.archiveCategory(category.id) }
                                    .padding(vertical = 0.75f.gridUnitsAsDp()),
                            )
                        }
                    }

                    LightBottomBar(
                        items = listOf(LightBarButton.LightIcon(icon = LightIcons.ADD, onClick = { adding = true })),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Implement `SettingsScreen`**

`ledger/src/main/kotlin/dev/tyler/lightledger/ui/settings/SettingsScreen.kt`:

```kotlin
package dev.tyler.lightledger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.ui.categories.CategoriesScreen

class SettingsScreen(
    sealedActivity: SealedLightActivity,
    private val repository: LedgerRepository,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Settings"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightText(
                    text = "Categories",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable {
                            navigateTo(screenFactory = { CategoriesScreen(it, repository) })
                        }
                        .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
                )
            }
        }
    }
}
```

- [ ] **Step 3: Build-verify**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:compileDebugKotlin 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/ui/categories/CategoriesScreen.kt ledger/src/main/kotlin/dev/tyler/lightledger/ui/settings/SettingsScreen.kt
git commit -m "$(cat <<'EOF'
feat(ledger): SettingsScreen shell and CategoriesScreen

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 16: `HistoryViewModel`

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/ui/history/HistoryViewModel.kt`
- Test: `ledger/src/test/kotlin/dev/tyler/lightledger/ui/history/HistoryViewModelTest.kt`

**Interfaces:**
- Consumes: `LedgerRepository`/`Category`/`Transaction` (Task 9).
- Produces: `data class HistoryUiState(val month: YearMonth = YearMonth.now(), val transactions: List<Transaction> = emptyList(), val categories: List<Category> = emptyList(), val selectedTransactionId: Long? = null)`, `class HistoryViewModel(repository: LedgerRepository) : LightViewModel<Unit>()` with `uiState`, `showPreviousMonth()`, `showNextMonth()`, `openDetail(id: Long)`, `closeDetail()`, `updateCategory(categoryId: Long)`, `deleteSelected()`, `onBackPressed()` (closes detail if open, else `false`) — used by Task 17 (`HistoryScreen`).

- [ ] **Step 1: Write the failing tests**

`ledger/src/test/kotlin/dev/tyler/lightledger/ui/history/HistoryViewModelTest.kt`:

```kotlin
package dev.tyler.lightledger.ui.history

import dev.tyler.lightledger.data.FakeLedgerRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeLedgerRepository

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeLedgerRepository()
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun loadsCurrentMonthTransactionsOnInit() = runTest {
        repository.ensureSeeded()
        val category = repository.listCategories().first()
        repository.addManualTransaction(-450L, "Coffee", category.id)
        val vm = HistoryViewModel(repository)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.transactions.size)
    }

    @Test fun previousMonthNavigatesBack() = runTest {
        repository.ensureSeeded()
        val vm = HistoryViewModel(repository)
        advanceUntilIdle()
        val currentMonth = vm.uiState.value.month
        vm.showPreviousMonth()
        advanceUntilIdle()
        assertEquals(currentMonth.minusMonths(1), vm.uiState.value.month)
    }

    @Test fun openDetailSetsSelection() = runTest {
        repository.ensureSeeded()
        val vm = HistoryViewModel(repository)
        advanceUntilIdle()
        vm.openDetail(42L)
        assertEquals(42L, vm.uiState.value.selectedTransactionId)
    }

    @Test fun backPressedClosesDetailWhenOpen() = runTest {
        repository.ensureSeeded()
        val vm = HistoryViewModel(repository)
        advanceUntilIdle()
        vm.openDetail(42L)
        assertTrue(vm.onBackPressed())
        assertNull(vm.uiState.value.selectedTransactionId)
    }

    @Test fun backPressedNotConsumedWhenNoDetailOpen() = runTest {
        repository.ensureSeeded()
        val vm = HistoryViewModel(repository)
        advanceUntilIdle()
        assertFalse(vm.onBackPressed())
    }

    @Test fun deleteSelectedRemovesTransactionAndClosesDetail() = runTest {
        repository.ensureSeeded()
        val category = repository.listCategories().first()
        val id = repository.addManualTransaction(-450L, "Coffee", category.id)
        val vm = HistoryViewModel(repository)
        advanceUntilIdle()
        vm.openDetail(id)
        vm.deleteSelected()
        advanceUntilIdle()
        assertNull(vm.uiState.value.selectedTransactionId)
        assertTrue(vm.uiState.value.transactions.none { it.id == id })
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.ui.history.HistoryViewModelTest' 2>&1 | tail -30`
Expected: compile failure — `HistoryViewModel` unresolved.

- [ ] **Step 3: Implement**

`ledger/src/main/kotlin/dev/tyler/lightledger/ui/history/HistoryViewModel.kt`:

```kotlin
package dev.tyler.lightledger.ui.history

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import dev.tyler.lightledger.data.Category
import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.data.Transaction
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val month: YearMonth = YearMonth.now(),
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val selectedTransactionId: Long? = null,
)

class HistoryViewModel(private val repository: LedgerRepository) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                transactions = repository.listTransactions(_uiState.value.month),
                categories = repository.listCategories(),
            )
        }
    }

    fun showPreviousMonth() {
        _uiState.value = _uiState.value.copy(month = _uiState.value.month.minusMonths(1))
        reload()
    }

    fun showNextMonth() {
        _uiState.value = _uiState.value.copy(month = _uiState.value.month.plusMonths(1))
        reload()
    }

    fun openDetail(id: Long) {
        _uiState.value = _uiState.value.copy(selectedTransactionId = id)
    }

    fun closeDetail() {
        _uiState.value = _uiState.value.copy(selectedTransactionId = null)
    }

    fun updateCategory(categoryId: Long) {
        val id = _uiState.value.selectedTransactionId ?: return
        viewModelScope.launch {
            repository.updateTransactionCategory(id, categoryId)
            reload()
        }
    }

    fun deleteSelected() {
        val id = _uiState.value.selectedTransactionId ?: return
        viewModelScope.launch {
            repository.deleteTransaction(id)
            _uiState.value = _uiState.value.copy(selectedTransactionId = null)
            reload()
        }
    }

    override fun onBackPressed(): Boolean {
        if (_uiState.value.selectedTransactionId != null) {
            closeDetail()
            return true
        }
        return false
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest --tests 'dev.tyler.lightledger.ui.history.HistoryViewModelTest' 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/ui/history/HistoryViewModel.kt ledger/src/test/kotlin/dev/tyler/lightledger/ui/history/HistoryViewModelTest.kt
git commit -m "$(cat <<'EOF'
feat(ledger): HistoryViewModel month pager and detail mode

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 17: `HistoryScreen`

**Files:**
- Create: `ledger/src/main/kotlin/dev/tyler/lightledger/ui/history/HistoryScreen.kt`

**Interfaces:**
- Consumes: `HistoryViewModel` (Task 16), `LedgerRepository`/`Transaction`/`Category` (Task 9), `CategoryGrid` (Task 10), `LightLazyScrollView` (SDK).
- Produces: `class HistoryScreen(sealedActivity: SealedLightActivity, repository: LedgerRepository) : LightScreen<Unit, HistoryViewModel>` — used by Task 18 (`HomeScreen`, via `navigateTo`).

- [ ] **Step 1: Implement**

`ledger/src/main/kotlin/dev/tyler/lightledger/ui/history/HistoryScreen.kt`:

```kotlin
package dev.tyler.lightledger.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import dev.tyler.lightledger.data.Category
import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.data.Transaction
import dev.tyler.lightledger.ui.shared.CategoryGrid
import java.time.YearMonth

class HistoryScreen(
    sealedActivity: SealedLightActivity,
    private val repository: LedgerRepository,
) : LightScreen<Unit, HistoryViewModel>(sealedActivity) {

    override val viewModelClass: Class<HistoryViewModel>
        get() = HistoryViewModel::class.java

    override fun createViewModel() = HistoryViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LightTheme(colors = themeColors) {
            val selected = state.transactions.firstOrNull { it.id == state.selectedTransactionId }
            if (selected != null) {
                DetailContent(
                    transaction = selected,
                    categories = state.categories,
                    onSelectCategory = viewModel::updateCategory,
                    onDelete = viewModel::deleteSelected,
                    onBack = { goBack(null) },
                )
            } else {
                ListContent(
                    month = state.month,
                    transactions = state.transactions,
                    onPreviousMonth = viewModel::showPreviousMonth,
                    onNextMonth = viewModel::showNextMonth,
                    onSelect = viewModel::openDetail,
                    onClose = { goBack(Unit) },
                )
            }
        }
    }
}

@Composable
private fun ListContent(
    month: YearMonth,
    transactions: List<Transaction>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelect: (Long) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onPreviousMonth),
            center = LightTopBarCenter.Text(monthTitle(month)),
            rightButton = LightBarButton.LightIcon(icon = LightIcons.ARROW_RIGHT, onClick = onNextMonth),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        if (transactions.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LightText(text = "No transactions this month.", variant = LightTextVariant.Copy)
            }
        } else {
            LightLazyScrollView(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
                uniformItemHeightGridUnits = 2.5f,
            ) {
                items(transactions, key = { it.id }) { transaction ->
                    LightText(
                        text = "${transaction.payee}  ${formatAmount(transaction.amountMinor)}",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { onSelect(transaction.id) }
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                }
            }
        }

        LightBottomBar(items = listOf(LightBarButton.LightIcon(icon = LightIcons.CLOSE, onClick = onClose)))
    }
}

@Composable
private fun DetailContent(
    transaction: Transaction,
    categories: List<Category>,
    onSelectCategory: (Long) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
            center = LightTopBarCenter.Text(transaction.payee),
            rightButton = LightBarButton.LightIcon(icon = LightIcons.TRASH, onClick = onDelete),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightText(
            text = formatAmount(transaction.amountMinor),
            variant = LightTextVariant.Heading,
            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        )

        CategoryGrid(
            categories = categories,
            onSelect = { onSelectCategory(it.id) },
            modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp()),
        )
    }
}

private fun monthTitle(month: YearMonth): String = month.month.name.take(3) + " " + month.year

private fun formatAmount(amountMinor: Long): String {
    val format = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.US)
    return format.format(amountMinor / 100.0)
}
```

- [ ] **Step 2: Build-verify**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:compileDebugKotlin 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/ui/history/HistoryScreen.kt
git commit -m "$(cat <<'EOF'
feat(ledger): HistoryScreen month list and transaction detail

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 18: `HomeScreen` (real) — replaces the M0 stub

**Files:**
- Modify: `ledger/src/main/kotlin/dev/tyler/lightledger/ui/home/HomeScreen.kt`

**Interfaces:**
- Consumes: `HomeViewModel`/`totalSpentMinor` (Task 11), `LedgerRepository`/`RoomLedgerRepository`/`LedgerDatabase` (Tasks 8–9), `AddEntryScreen` (Task 13), `SettingsScreen` (Task 15), `HistoryScreen` (Task 17), `com.thelightphone.sdk.buildDatabase` (SDK).
- Produces: real `HomeScreen` — this is the module's `@InitialScreen` and the terminal task before on-device verification (Task 19). Nothing later depends on its internals.

- [ ] **Step 1: Replace the stub with the real screen**

`ledger/src/main/kotlin/dev/tyler/lightledger/ui/home/HomeScreen.kt` (full replacement):

```kotlin
package dev.tyler.lightledger.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import dev.tyler.lightledger.data.LedgerDatabase
import dev.tyler.lightledger.data.RoomLedgerRepository
import dev.tyler.lightledger.ui.addentry.AddEntryScreen
import dev.tyler.lightledger.ui.history.HistoryScreen
import dev.tyler.lightledger.ui.settings.SettingsScreen
import java.time.YearMonth
import java.util.Locale

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeViewModel>(sealedActivity) {

    private val repository = RoomLedgerRepository.getInstance {
        lightContext.buildDatabase(LedgerDatabase::class.java, RoomLedgerRepository.DATABASE_NAME)
    }

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LightTheme(colors = themeColors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    center = LightTopBarCenter.Text(monthTitle(state.month)),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )

                LightText(
                    text = formatAmount(totalSpentMinor(state.categoryTotals)) + " spent",
                    variant = LightTextVariant.Title,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp()),
                )

                if (state.needsReviewCount > 0) {
                    LightText(
                        text = "${state.needsReviewCount} to review →",
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 0.5f.gridUnitsAsDp()),
                    )
                }

                when {
                    state.loading -> Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(text = "Loading…", variant = LightTextVariant.Copy)
                    }

                    state.categoryTotals.isEmpty() -> Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = "Nothing yet this month.",
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                        )
                    }

                    else -> LightScrollView(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
                    ) {
                        state.categoryTotals.forEach { total ->
                            LightText(
                                text = "${total.categoryName}  ${formatAmount(total.totalMinor)}",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.ADD,
                            onClick = {
                                navigateTo(screenFactory = { AddEntryScreen(it, repository) }) {
                                    viewModel.reload()
                                }
                            },
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.SEARCH,
                            onClick = { navigateTo(screenFactory = { HistoryScreen(it, repository) }) },
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            onClick = { navigateTo(screenFactory = { SettingsScreen(it, repository) }) },
                        ),
                    ),
                )
            }
        }
    }
}

private fun monthTitle(month: YearMonth): String = month.month.name.take(3) + " " + month.year

private fun formatAmount(amountMinor: Long): String {
    val format = java.text.NumberFormat.getCurrencyInstance(Locale.US)
    return format.format(amountMinor / 100.0)
}
```

- [ ] **Step 2: Build-verify**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:assembleDebug 2>&1 | tail -40`
Expected: `BUILD SUCCESSFUL`, no plugin scan violations, no duplicate `@InitialScreen`.

- [ ] **Step 3: Run the full unit test suite**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:testDebugUnitTest 2>&1 | tail -60`
Expected: `BUILD SUCCESSFUL`, all tests from Tasks 2–17 pass (roughly 60 tests total).

- [ ] **Step 4: Commit**

```bash
git add ledger/src/main/kotlin/dev/tyler/lightledger/ui/home/HomeScreen.kt
git commit -m "$(cat <<'EOF'
feat(ledger): real Home screen with month summary and navigation

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 19: On-device verification (M2 Definition of Done)

**Files:**
- None (verification only; fix-forward commits only if this step surfaces a bug).

**Interfaces:**
- Consumes: the fully wired module from Tasks 1–18.
- Produces: nothing new — this is the milestone's Definition of Done gate: tests green, `installDebug` runs, no plugin/lint violations, every screen reachable and exit-able with the hardware back gesture.

- [ ] **Step 1: Full clean build + test**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :ledger:clean :ledger:assembleDebug :ledger:testDebugUnitTest 2>&1 | tail -60`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install and launch on the `Light_Phone` AVD**

Run: `emulator -avd Light_Phone -writable-system &` (skip if already running), then:
`./gradlew :ledger:installDebug && adb shell am start -n dev.tyler.lightledger/com.thelightphone.sdk.LightActivity`
Expected: app launches to Home showing "JAN 0.00 spent" (or the current month) and "Nothing yet this month."

- [ ] **Step 3: Manual QA pass**

Walk through, using the hardware back gesture to exit each screen back to Home:
1. Tap `+` → enter `4.50` on the keypad → NEXT → type "Coffee Shop" → NEXT → tap "Groceries". Confirm it returns to Home and the month total now shows the entry.
2. Tap search icon → confirm the transaction appears in History for the current month. Tap it → change category → confirm it updates. Back to the list, tap it again → delete (trash icon) → confirm it's gone and Home's total drops back to zero.
3. Tap settings icon → tap "Categories" → tap `+` → add "Travel" → confirm it appears in the list. Tap "Travel" to archive it → confirm it disappears.
4. From every screen reached above, confirm the hardware back button/gesture returns one level at a time (AddEntry's amount→payee→category steps each pop one step; List/Category/Settings screens pop directly to their caller) and never force-closes the app.

Expected: all four flows work with no crashes, no visual overflow at the LP3's 1080×1240 resolution, no leftover "3 to review" text since the review inbox never gets a row from manual entries.

- [ ] **Step 4: If a bug surfaces, fix forward**

Fix the specific file, re-run Step 1, then re-run the relevant part of Step 3. Commit the fix separately (not folded into an earlier task's commit):

```bash
git add <fixed files>
git commit -m "$(cat <<'EOF'
fix(ledger): <specific bug found during on-device M2 verification>

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Confirm M2 Definition of Done**

No further commit — this step is a checklist, not a change:
- [ ] `./gradlew :ledger:testDebugUnitTest` green (Task 18 Step 3).
- [ ] `installDebug` succeeded on the `Light_Phone` AVD.
- [ ] No plugin/lint violations in any build output above.
- [ ] Every screen (Home, AddEntry×3 steps, History list/detail, Settings, Categories/Add) reachable and exit-able with the hardware back gesture.

M0–M2 is complete. The next plan picks up M3 (SimpleFIN sync): it adds `INTERNET`/`ACCESS_NETWORK_STATE`/`CAMERA` to `lighttool.toml`, wires `ktor-client-*`, builds the Keystore-encrypted Access URL storage (copy `TotpKeystore`/`TotpSecretCipher` from `examples/authenticator`), the `JOB_SYNC` `@LightJob` handler, `RuleDao`/`CsvProfileDao`, and finally puts `DedupHash`/`RuleEngine` to work against real imported data.
