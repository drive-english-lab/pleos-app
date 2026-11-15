package io.github.driveenglishlab.pleos.ui

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class DeviceAppearance { SYSTEM, LIGHT, DARK }

@Singleton
class DeviceAppearanceStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("device_appearance", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(DeviceAppearance.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: DeviceAppearance.SYSTEM)
    val state = mutable.asStateFlow()
    suspend fun save(value: DeviceAppearance): Boolean = withContext(Dispatchers.IO) {
        if (!preferences.edit().putString("theme", value.name).commit()) false
        else { mutable.value = value; true }
    }
}
