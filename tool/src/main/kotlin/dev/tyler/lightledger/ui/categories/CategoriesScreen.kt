package dev.tyler.lightledger.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
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
        val nameFieldState = remember(adding) { TextFieldState() }
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
