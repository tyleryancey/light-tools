package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightFileShare
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.shared.LightServiceMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HomeScreenViewModel(
    private val fileShare: LightFileShare
) : LightViewModel() {

    val ringtones = MutableStateFlow<List<String>>(emptyList())
    val status = MutableStateFlow<String?>(null)

    override fun onScreenShow(screen: SimpleLightScreen) {
        super.onScreenShow(screen)
        ringtones.value = fileShare.list("ringtones")
    }

    fun selectRingtone(filename: String) {
        val uri = fileShare.getUri("ringtones/$filename").toString()
        viewModelScope.launch {
            status.value = "Setting ringtone..."
            callRemoteServiceMethod(
                LightServiceMethod.SetRingtone,
                LightServiceMethod.SetRingtone.Request(type = 1, uri = uri)
            )
            status.value = "Ringtone set: $filename"
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel {
        return HomeScreenViewModel(fileShare)
    }

    @Composable
    override fun Content() {
        val ringtones by viewModel.ringtones.collectAsState()
        val status by viewModel.status.collectAsState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(32.dp)
        ) {
            Text(
                text = "Ringtones",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            status?.let {
                Text(
                    text = it,
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            if (ringtones.isEmpty()) {
                Text(
                    text = "No ringtones found",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn {
                    items(ringtones) { filename ->
                        Text(
                            text = filename,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectRingtone(filename) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
