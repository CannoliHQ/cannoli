package dev.cannoli.scorza.server

import org.apache.commons.fileupload2.core.AbstractFileUpload
import org.apache.commons.fileupload2.core.FileItemFactory
import org.apache.commons.fileupload2.core.FileItemInputIterator
import org.apache.commons.fileupload2.core.RequestContext
import java.io.File
import java.io.InputStream

object KitchenUpload {

    private class StreamRequestContext(
        private val stream: InputStream,
        private val contentType: String,
        private val length: Long,
    ) : RequestContext {
        override fun getCharacterEncoding(): String = "UTF-8"
        override fun getContentType(): String = contentType
        override fun getContentLength(): Long = length
        override fun getInputStream(): InputStream = stream
        override fun isMultipartRelated(): Boolean = false
    }

    private val uploader = object : AbstractFileUpload<RequestContext, Nothing, FileItemFactory<Nothing>>() {
        override fun getItemIterator(request: RequestContext): FileItemInputIterator =
            super.getItemIterator(request)

        // Required abstract overrides; unused because only the streaming getItemIterator path is used.
        override fun parseRequest(request: RequestContext): List<Nothing> = emptyList()

        override fun parseParameterMap(request: RequestContext): Map<String, List<Nothing>> = emptyMap()
    }

    fun streamTo(
        input: InputStream,
        contentType: String,
        contentLength: Long,
        destFor: (filename: String) -> File,
    ): List<String> {
        require(contentType.startsWith("multipart/", ignoreCase = true)) {
            "not a multipart request"
        }
        val written = mutableListOf<String>()
        val ctx = StreamRequestContext(input, contentType, contentLength)
        val iterator = uploader.getItemIterator(ctx)
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.isFormField) continue
            val rawName = item.name ?: continue
            val filename = sanitize(rawName)
            if (filename.isEmpty() || filename == "." || filename == "..") continue
            val dest = destFor(filename)
            dest.parentFile?.mkdirs()
            item.inputStream.use { src ->
                dest.outputStream().use { out -> src.copyTo(out, 262144) }
            }
            written.add(filename)
        }
        return written
    }

    private fun sanitize(name: String): String =
        java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
}
