package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

class HomeScreenViewModel(
    @Suppress("unused") private val dataStore: DataStore<Preferences>,
) : LightViewModel() {
    sealed class WeatherState {
        data object Idle : WeatherState()
        data object Loading : WeatherState()
        data class Success(
            val temperatureC: Double,
            val windSpeedKmh: Double,
        ) : WeatherState()

        data class Error(val message: String) : WeatherState()
    }

    @Serializable
    data class GeocodingResponse(
        val results: List<GeocodingResult> = emptyList(),
    )

    @Serializable
    data class GeocodingResult(
        val latitude: Double,
        val longitude: Double,
    )

    @Serializable
    data class ForecastResponse(
        @SerialName("current_weather")
        val currentWeather: CurrentWeather? = null,
    )

    @Serializable
    data class CurrentWeather(
        val temperature: Double,
        val windspeed: Double,
    )

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val _weatherState = MutableStateFlow<WeatherState>(WeatherState.Idle)
    val weatherState: StateFlow<WeatherState> = _weatherState

    fun submitLocation(rawLocation: String) {
        val location = rawLocation.trim()
        if (location.isEmpty()) {
            _weatherState.value = WeatherState.Error("Please enter a location.")
            return
        }

        _weatherState.value = WeatherState.Loading

        viewModelScope.launch(Dispatchers.IO) {
            _weatherState.value = try {
                val encoded = URLEncoder.encode(location, UTF_8.name())
                val geo: GeocodingResponse = client
                    .get("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1")
                    .body()

                val first = geo.results.firstOrNull()
                    ?: return@launch run { _weatherState.value = WeatherState.Error("Location not found.") }

                val forecast: ForecastResponse = client
                    .get(
                        "https://api.open-meteo.com/v1/forecast" +
                            "?latitude=${first.latitude}&longitude=${first.longitude}&current_weather=true"
                    )
                    .body()

                val current = forecast.currentWeather
                    ?: return@launch run { _weatherState.value = WeatherState.Error("No current weather available.") }

                WeatherState.Success(
                    temperatureC = current.temperature,
                    windSpeedKmh = current.windspeed,
                )
            } catch (e: Exception) {
                WeatherState.Error(e.message ?: "Unknown error")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        client.close()
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel {
        return HomeScreenViewModel(dataStore)
    }

    @Composable
    override fun Content() {
        var location by remember { mutableStateOf("") }
        val weatherState by viewModel.weatherState.collectAsState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(32.dp)
        ) {
            Text(
                text = "Weather",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(24.dp))

            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()

            BasicTextField(
                value = location,
                onValueChange = { location = it },
                singleLine = true,
                interactionSource = interactionSource,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    // tighter inset between text and bounds
                    .padding(vertical = 6.dp)
                    // underline-only "border"
                    .drawBehind {
                        val strokeWidth = 1.dp.toPx()
                        val y = size.height - strokeWidth / 2f
                        drawLine(
                            color = if (isFocused) Color.White else Color(0xFF666666),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = strokeWidth,
                        )
                    },
                decorationBox = { innerTextField ->
                    if (location.isBlank()) {
                        Text(
                            text = "enter location",
                            color = Color(0xFFBBBBBB),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    innerTextField()
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "SUBMIT",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.submitLocation(location) }
                    .padding(vertical = 12.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (val s = weatherState) {
                is HomeScreenViewModel.WeatherState.Idle -> Unit
                is HomeScreenViewModel.WeatherState.Loading -> Text(
                    text = "Loading…",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )

                is HomeScreenViewModel.WeatherState.Success -> Text(
                    text = "Temp: ${s.temperatureC}°C\nWind: ${s.windSpeedKmh} km/h",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )

                is HomeScreenViewModel.WeatherState.Error -> Text(
                    text = "Error: ${s.message}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
