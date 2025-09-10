package io.scer.pocketmine.utils

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import kotlin.collections.HashMap

class AsyncRequest {
    suspend fun execute(vararg channels: String): HashMap<String, JSONObject>? = withContext(Dispatchers.IO) {
        val map = HashMap<String, JSONObject>()
        channels.forEach { channel ->
            try {
                val body = URL("https://update.pmmp.io/api?channel=$channel").readText()
                map[channel] = JSONObject(body)
            } catch (e: Exception) {
                // Network error or pmmp.io is down
            }
        }
        map
    }
}