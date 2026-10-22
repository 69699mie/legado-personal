package io.legado.app.help.coil

import android.util.Base64
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.legado.app.data.appDb
import io.legado.app.help.glide.progress.ProgressUrlTag
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.isWifiConnect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import splitties.init.appCtx
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CoverFetcher(
    private val url: String,
    private val options: Options,
    private val callFactory: Call.Factory,
    private val loadOnlyWifi: Boolean,
) : Fetcher {

    companion object {
        val COVER_REQUEST_TAG = Unit
        private const val FAIL_CACHE_TTL_MS = 5 * 60 * 1000L
        private val failCache = ConcurrentHashMap<String, Long>()

        fun isFailed(url: String): Boolean {
            val ts = failCache[url] ?: return false
            if (System.currentTimeMillis() - ts > FAIL_CACHE_TTL_MS) {
                failCache.remove(url)
                return false
            }
            return true
        }

        fun markFailed(url: String) {
            failCache[url] = System.currentTimeMillis()
        }

        fun clearFailure(url: String) {
            failCache.remove(url)
        }

        fun clearFailCache() {
            failCache.clear()
        }
    }

    override suspend fun fetch(): FetchResult {
        val source = options.extras[CoverExtras.Source]
        val isManga = options.extras[CoverExtras.Manga] == true
        val mangaBook = options.extras[CoverExtras.MangaBookUrl]
            ?.let { bookUrl -> withContext(Dispatchers.IO) { appDb.bookDao.getBook(bookUrl) } }

        if (url.startsWith("data:", true)) {
            val base64Data = url.substringAfter("base64,", "")
            if (base64Data.isEmpty()) {
                throw IOException("Invalid data URI")
            }
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            return SourceFetchResult(
                source = ImageSource(
                    source = Buffer().write(bytes),
                    fileSystem = options.fileSystem
                ),
                mimeType = null,
                dataSource = DataSource.MEMORY
            )
        }

        val originalUrl = options.extras[CoverExtras.OriginalUrl] ?: url
        val bookUrl = options.extras[CoverExtras.BookUrl]
        val requestHeaders = options.extras[CoverExtras.Headers]
        val progressUrl = options.extras[CoverExtras.OriginalUrl] ?: url
        val canStream = isManga && ImageUtils.skipDecode(source, !isManga)
        var streamedSource: ImageSource? = null

        var rawBytes: ByteArray? = null
        var fromCache = false
        try {
            withContext(Dispatchers.IO) {
                val cacheRequest = Request.Builder()
                    .url(url)
                    .tag(io.legado.app.data.entities.BaseSource::class.java, source)
                    .tag(ProgressUrlTag::class.java, ProgressUrlTag(progressUrl))
                    .apply { requestHeaders?.forEach { (key, value) -> addHeader(key, value) } }
                    .cacheControl(CacheControl.FORCE_CACHE)
                    .build()
                val cacheResponse = callFactory.newCall(cacheRequest).execute()
                if (cacheResponse.isSuccessful) {
                    fromCache = true
                    cacheResponse.body.use { rawBytes = it.bytes() }
                } else {
                    cacheResponse.close()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }

        if (rawBytes == null) {
            if (loadOnlyWifi && !appCtx.isWifiConnect) {
                throw IOException("WiFi not available, loadOnlyWifi enabled")
            }

            if (isFailed(url)) {
                throw IOException("URL previously failed, skipping: $url")
            }

            rawBytes = try {
                withContext(Dispatchers.IO) {
                    val networkRequest = Request.Builder()
                        .url(url)
                        .tag(io.legado.app.data.entities.BaseSource::class.java, source)
                        .tag(ProgressUrlTag::class.java, ProgressUrlTag(progressUrl))
                        .apply { requestHeaders?.forEach { (key, value) -> addHeader(key, value) } }
                        .tag(COVER_REQUEST_TAG)
                        .cacheControl(
                            CacheControl.Builder()
                                .maxAge(30, TimeUnit.DAYS)
                                .build()
                        )
                        .build()
                    
                    val networkResponse = callFactory.newCall(networkRequest).execute()
                    val body = networkResponse.body
                    if (!networkResponse.isSuccessful) {
                        body.close()
                        throw IOException("HTTP ${networkResponse.code}")
                    }
                    if (canStream) {
                        streamedSource = ImageSource(
                            source = body.source(),
                            fileSystem = options.fileSystem,
                        )
                        null
                    } else {
                        body.use { it.bytes() }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markFailed(url)
                if (!isManga && bookUrl != null) {
                    val stale = withContext(Dispatchers.IO) {
                        CoverFileCache.readByBookUrl(bookUrl)?.let { f ->
                            try {
                                if (f.length() in 1..20L * 1024 * 1024) f.readBytes() else null
                            } catch (ex: Exception) {
                                null
                            }
                        }
                    }
                    if (stale != null) {
                        return SourceFetchResult(
                            source = ImageSource(
                                source = Buffer().write(stale),
                                fileSystem = options.fileSystem
                            ),
                            mimeType = null,
                            dataSource = DataSource.DISK
                        )
                    }
                }
                throw e
            }
        }

        streamedSource?.let { stream ->
            clearFailure(url)
            return SourceFetchResult(
                source = stream,
                mimeType = null,
                dataSource = DataSource.NETWORK,
            )
        }

        val fetchedBytes = rawBytes ?: throw IOException("封面数据为空: $url")

        val decodedBytes = if (ImageUtils.skipDecode(source, !isManga)) {
            fetchedBytes
        } else {
            withContext(Dispatchers.IO) {
                if (isManga) {
                    ImageUtils.decode(url, fetchedBytes, false, source, mangaBook)
                } else {
                    ImageUtils.decode(url, fetchedBytes, true, source)
                }
            } ?: throw IOException("图片解密失败")
        }

        clearFailure(url)
        if (!isManga && bookUrl != null) {
            withContext(Dispatchers.IO) { CoverFileCache.write(originalUrl, decodedBytes, bookUrl) }
        }
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().write(decodedBytes),
                fileSystem = options.fileSystem
            ),
            mimeType = null,
            dataSource = if (fromCache) DataSource.DISK else DataSource.NETWORK
        )
    }

    class Factory(
        private val okHttpClient: OkHttpClient,
        private val okHttpClientManga: OkHttpClient,
    ) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme
            if (scheme != "http" && scheme != "https" && scheme != "data") return null

            val isManga = options.extras[CoverExtras.Manga] == true
            val loadOnlyWifi = options.extras[CoverExtras.LoadOnlyWifi] == true

            val client = if (isManga) okHttpClientManga else okHttpClient

            return CoverFetcher(data.toString(), options, client, loadOnlyWifi)
        }
    }
}