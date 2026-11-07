package com.revitvisualizer.app.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.revitvisualizer.app.imaging.ImageProcessor
import com.revitvisualizer.app.imaging.LocalImage
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Working images are private, excluded from backups, and discarded when the session ends. */
class ImageStore(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "working-images").apply {
        deleteRecursively()
        check(mkdirs()) { "Private image storage is unavailable." }
    }

    fun newFile(prefix: String, extension: String) = File(directory, "$prefix-${UUID.randomUUID()}.$extension")

    fun captureFile(): File = newFile("original", "jpg")

    suspend fun import(uri: Uri): LocalImage {
        val file = newFile("original", "image")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("The selected image could not be opened.")
            input.use { source -> file.outputStream().use { copy(source, it, 100L * 1024 * 1024) } }
            return ImageProcessor.describe(file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /** Copy the returned PNG bytes; do not decode or recompress the saved result. */
    suspend fun export(image: LocalImage): Uri {
        ImageProcessor.requirePng(image.file)
        val resolver = context.contentResolver
        val name = "Vizruna-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}-${UUID.randomUUID().toString().take(8)}.png"
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Vizruna")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }) ?: error("The gallery could not create a file.")
        try {
            val output = resolver.openOutputStream(uri) ?: error("The gallery file could not be opened.")
            image.file.inputStream().use { input -> output.use { copy(input, it) } }
            currentCoroutineContext().ensureActive()
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null) == 1) { "The gallery could not finish saving the image." }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private suspend fun copy(input: InputStream, output: OutputStream, limit: Long = Long.MAX_VALUE) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            require(total <= limit) { "The source image exceeds 100 MB. Import a smaller image." }
            output.write(buffer, 0, count)
        }
    }

    fun clear() { directory.deleteRecursively() }
}
