package com.tempoverlay.mangohud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One in-process sample shared by the overlay and the settings activity.
 * The service owns polling when active; the app only samples while visible if the
 * overlay service is stopped. This avoids two competing thermal readers.
 */
object TemperatureRepository {
    private val mutableSnapshot = MutableStateFlow(TemperatureSnapshot())
    val snapshots = mutableSnapshot.asStateFlow()

    fun publish(snapshot: TemperatureSnapshot) {
        mutableSnapshot.value = snapshot
    }
}
