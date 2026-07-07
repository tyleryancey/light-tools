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

    fun fmtTime(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val ss = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
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
