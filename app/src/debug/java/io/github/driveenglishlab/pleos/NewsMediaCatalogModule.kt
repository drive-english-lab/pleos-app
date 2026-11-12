package io.github.driveenglishlab.pleos

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import io.github.driveenglishlab.pleos.platform.media.NewsMediaCatalog
import io.github.driveenglishlab.pleos.platform.media.NewsArticle
import org.json.JSONArray

@Module
@InstallIn(SingletonComponent::class)
object NewsMediaCatalogModule {
    @Provides @Singleton
    fun catalog(@ApplicationContext context: Context): NewsMediaCatalog = object : NewsMediaCatalog {
        override val articles = JSONArray(context.assets.open("preview-stories.json").bufferedReader().use { it.readText() }).let { data ->
            List(data.length()) { i -> val item = data.getJSONObject(i); NewsArticle(item.getString("id"), item.getString("title"), item.getString("summary"), item.getString("createdAt"), "asset:///" + item.getString("audio"), (item.getDouble("seconds") * 1000).toLong()) }
        }
    }
}
