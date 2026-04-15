package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SimpleLightScreen

class DetailScreen : SimpleLightScreen() {
    @Composable
    override fun Content() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(32.dp)
        ) {
            Text(
                text = "Detail Screen",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Go Back",
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clickable { goBack() },
            )
        }
    }
}
