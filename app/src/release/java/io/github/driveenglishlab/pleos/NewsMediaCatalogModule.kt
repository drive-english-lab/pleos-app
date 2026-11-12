package io.github.driveenglishlab.pleos

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import io.github.driveenglishlab.pleos.platform.media.NewsMediaCatalog

@Module
@InstallIn(SingletonComponent::class)
object NewsMediaCatalogModule {
    @Provides @Singleton
    fun catalog(): NewsMediaCatalog = NewsMediaCatalog.Empty
}
