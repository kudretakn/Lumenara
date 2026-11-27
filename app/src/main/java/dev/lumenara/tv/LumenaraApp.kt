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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltAndroidApp
class LumenaraApp : Application(), ImageLoaderFactory, androidx.work.Configuration.Provider {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchNextStarted = java.util.concurrent.atomic.AtomicBoolean()
    private val artworkStarted = java.util.concurrent.atomic.AtomicBoolean()
    private val artworkReady = MutableStateFlow(false)
    @Volatile private var createdImageLoader: ImageLoader? = null
    internal val hubArtworkReady = artworkReady.asStateFlow()

    internal fun prepareHubArtwork() {
        if (artworkStarted.compareAndSet(false, true)) startupScope.launch {
            Perf.mark("startup_image_loader")
            // Called after the populated draw. Visible artwork must not wait for idle
            // maintenance or its serial lane; initialize disk cache/OkHttp off Main.
            imageLoader
            Perf.since("startup_image_loader", "startup_image_loader_ready")
            artworkReady.value = true
        }
    }
    override val workManagerConfiguration: androidx.work.Configuration
        get() {
            // A worker-only process has no Activity frame. Preserve publishing after background
            // refreshes too; foreground scheduling first reaches this getter after its draw gate.
            startWatchNext()
            return androidx.work.Configuration.Builder().build()
        }

    internal fun startWatchNext() {
        if (watchNextStarted.compareAndSet(false, true)) startupScope.launch {
            delay(20_000)
            backgroundWork.startup.awaitIdleOrBackground()
            Perf.mark("startup_watch_next")
            WatchNext.get(this@LumenaraApp).start()
            Perf.since("startup_watch_next", "startup_watch_next_started")
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        Perf.mark("startup")
        Perf.mark("startup_first_frame")
        Perf.mark("startup_application")
        super.onCreate()
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        Perf.since("startup_application", "startup_application_ready")
        val cpuAtStart = android.os.Process.getElapsedCpuTime()
        val wallAtStart = android.os.SystemClock.elapsedRealtime()
        startupScope.launch {
            delay(15_000)
            Perf.duration("startup_cpu_first_15s", android.os.Process.getElapsedCpuTime() - cpuAtStart)
            Perf.duration("startup_cpu_sample_window", android.os.SystemClock.elapsedRealtime() - wallAtStart)
        }
    }

    @androidx.annotation.OptIn(coil.annotation.ExperimentalCoilApi::class)
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            Repository.get(this).trim()
            createdImageLoader?.memoryCache?.clear()
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
            .build().also { createdImageLoader = it }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object Services {
    @Provides
    @Singleton
    fun repository(@ApplicationContext context: Context): Repository {
        Perf.mark("startup_repository")
        return Repository.get(context).also {
            Perf.since("startup_repository", "startup_repository_ready")
        }
    }

    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context) = Preferences(context)
}
