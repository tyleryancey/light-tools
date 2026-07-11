package dev.tyler.lightledger.simplefin

import kotlinx.serialization.json.Json

/** Decodes SimpleFIN Account Set JSON, tolerating fields we don't model. */
object SimpleFinDecoder {
    val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): AccountSet = json.decodeFromString(text)
}
