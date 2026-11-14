package io.github.driveenglishlab.pleos

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.driveenglishlab.pleos.platform.NewsMediaRuntimeProvider
import io.github.driveenglishlab.pleos.domain.NewsPlaybackPort
import javax.inject.Inject
import androidx.media3.common.util.UnstableApi

@HiltAndroidApp
@UnstableApi
class PleosApplication : Application(), NewsMediaRuntimeProvider {
    @Inject lateinit var productHost: ProductSessionHost
    override val newsPlayback: NewsPlaybackPort get() = productHost.mediaPort
}
