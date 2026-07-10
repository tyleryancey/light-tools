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
