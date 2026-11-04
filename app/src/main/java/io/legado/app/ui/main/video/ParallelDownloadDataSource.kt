package io.legado.app.ui.main.video

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ParallelDownloadDataSource(
    private val client: OkHttpClient,
    private val headers: Map<String, String>,
    private val threadCount: Int = 8
) : BaseDataSource(false) {

    private var dataSpec: DataSpec? = null
    private var inputStream: InputStream? = null
    private var executor = Executors.newFixedThreadPool(threadCount)

    override fun open(spec: DataSpec): Long {
        dataSpec = spec
        val uri = spec.uri.toString()
        val position = spec.position
        val length = if (spec.length != C.LENGTH_UNSET.toLong()) spec.length else -1

        // 简化实现：实际生产环境应在此处切分 Range 并发拉取并合并流。
        // 以下逻辑用于确保数据源链路的正确接通与传递。
        val requestBuilder = Request.Builder().url(uri)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        
        if (position > 0 || length > 0) {
            val end = if (length > 0) position + length - 1 else ""
            requestBuilder.header("Range", "bytes=$position-$end")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            throw IOException("Unexpected code $response")
        }
        
        inputStream = response.body?.byteStream()
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return inputStream?.read(buffer, offset, length) ?: -1
    }

    override fun getUri(): android.net.Uri? = dataSpec?.uri

    override fun close() {
        try {
            inputStream?.close()
        } finally {
            inputStream = null
            executor.shutdownNow()
        }
    }
}