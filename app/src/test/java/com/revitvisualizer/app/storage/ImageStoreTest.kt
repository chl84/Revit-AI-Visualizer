package com.revitvisualizer.app.storage

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.revitvisualizer.app.VisualizerViewModel
import com.revitvisualizer.app.imaging.LocalImage
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageStoreTest {
    @get:Rule val files = TemporaryFolder()

    private fun png(): LocalImage {
        val file = files.newFile("fixture.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return LocalImage(file, 64, 64)
    }

    private fun provider() = GalleryProvider(files.newFile("gallery.png")).apply {
        attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, this)
    }

    @Test fun importCopiesBytesIntoPrivateStorage() = runBlocking {
        val source = png()
        val bytes = source.file.readBytes()
        val imported = ImageStore(RuntimeEnvironment.getApplication()).import(Uri.fromFile(source.file))
        assertArrayEquals(bytes, source.file.readBytes())
        assertArrayEquals(bytes, imported.file.readBytes())
        assertNotEquals(source.file, imported.file)
        assertTrue(imported.file.path.startsWith(RuntimeEnvironment.getApplication().noBackupFilesDir.path))
    }

    @Test fun exportCopiesExactPngAndPublishesOnlyAfterWriting() = runBlocking {
        val image = png()
        val provider = provider()
        ImageStore(RuntimeEnvironment.getApplication()).export(image)
        assertTrue(provider.pendingWhenOpened)
        assertEquals(0, provider.values.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals("image/png", provider.values.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertArrayEquals(image.file.readBytes(), provider.file.readBytes())
        assertFalse(provider.deleted)
    }

    @Test fun failedExportRemovesUnfinishedGalleryEntry() {
        val image = png()
        val provider = provider().apply { failOnOpen = true }
        assertThrows(FileNotFoundException::class.java) {
            runBlocking { ImageStore(RuntimeEnvironment.getApplication()).export(image) }
        }
        assertTrue(provider.deleted)
        assertTrue(image.file.exists())
    }

    @Test fun credentialCanBeClearedAndIsNotRestoredIntoANewSession() {
        val model = VisualizerViewModel(RuntimeEnvironment.getApplication())
        model.submitCredential("local-test-placeholder")
        assertTrue(model.hasCredential)
        model.clearCredential()
        assertFalse(model.hasCredential)
        assertFalse(VisualizerViewModel(RuntimeEnvironment.getApplication()).hasCredential)
    }
}

private class GalleryProvider(val file: File) : ContentProvider() {
    val values = ContentValues()
    var pendingWhenOpened = false
    var failOnOpen = false
    var deleted = false
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        this.values.putAll(values!!)
        return ContentUris.withAppendedId(uri, 1)
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        pendingWhenOpened = values.getAsInteger(MediaStore.Images.Media.IS_PENDING) == 1
        if (failOnOpen) throw FileNotFoundException("Synthetic gallery failure")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        this.values.putAll(values!!)
        return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deleted = true
        file.delete()
        return 1
    }
    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
}
