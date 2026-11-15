package dev.lumenara.tv

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.imageLoader
import coil.decode.SvgDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.lumenara.data.*
import javax.inject.Singleton

@HiltAndroidApp
class LumenaraApp : Application(), ImageLoaderFactory {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        Perf.mark("startup")
        super.onCreate()
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
    }

    @androidx.annotation.OptIn(coil.annotation.ExperimentalCoilApi::class)
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            Repository.get(this).trim()
            imageLoader.memoryCache?.clear()
        }
    }

    override fun newImageLoader(): ImageLoader {
        val disk = coil.disk.DiskCache.Builder().directory(java.io.File(cacheDir, "images"))
            .maxSizeBytes(256L * 1024 * 1024).build()
        val memory = coil.memory.MemoryCache.Builder(this).maxSizePercent(.15).build()
        return ImageLoader.Builder(this)
            .okHttpClient(Transport.imageClient())
            .diskCache(disk)
            .memoryCache(memory)
            .respectCacheHeaders(false)
            .allowRgb565(true)
            .crossfade(false)
            .components { add(ArtworkInterceptor(disk, memory)); add(SvgDecoder.Factory()) }
            .build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object Services {
    @Provides
    @Singleton
    fun repository(@ApplicationContext context: Context) = Repository.get(context)

    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context) = Preferences(context)
}
