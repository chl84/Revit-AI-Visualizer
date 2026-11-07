package com.revitvisualizer.app

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.revitvisualizer.app.storage.CredentialStorageException
import com.revitvisualizer.app.storage.DeviceCredentialStore
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Keystore with isolated files/aliases and synthetic, nonfunctional credentials. */
@RunWith(AndroidJUnit4::class)
class CredentialPersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var alias: String
    private lateinit var vault: DeviceCredentialStore
    private val models = mutableListOf<ViewModelStore>()
    private val placeholder = "local-test-placeholder".toCharArray()

    @Before fun createIsolatedVault() {
        directory = File(application.noBackupFilesDir, "credential-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        file = File(directory, "credential.enc")
        alias = "revit-visualizer.test.${UUID.randomUUID()}"
        vault = newVault()
    }

    @After fun cleanUp() {
        instrumentation.runOnMainSync { models.forEach { it.clear() } }
        vault.clear()
        directory.deleteRecursively()
    }

    private fun newVault() = DeviceCredentialStore(AtomicFile(file), alias)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun model(store: DeviceCredentialStore = newVault()): VisualizerViewModel {
        lateinit var value: VisualizerViewModel
        instrumentation.runOnMainSync {
            value = VisualizerViewModel(application, store)
            models += ViewModelStore().apply { put("credential-test", value) }
        }
        awaitIdle(value)
        return value
    }

    private fun awaitIdle(value: VisualizerViewModel) = runBlocking {
        withTimeout(10_000) {
            while (value.busy != null) delay(20)
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun savedCredentialIsEncryptedAndReadableByAFreshStore() {
        vault.save(placeholder)
        assertTrue(vault.hasSavedCredential)
        assertTrue(file.canonicalPath.startsWith(application.noBackupFilesDir.canonicalPath + File.separator))
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(String(placeholder)))
        val loaded = newVault().load()!!
        try { assertArrayEquals(placeholder, loaded) } finally { loaded.fill('\u0000') }
        assertNull(keyStore().getKey(alias, null).encoded)
    }

    @Test fun rememberingRestoresIntoANewViewModelAndClearRemovesBothCopies() {
        val first = model()
        instrumentation.runOnMainSync { first.submitCredential(String(placeholder), rememberOnDevice = true) }
        awaitIdle(first)
        assertTrue(first.hasCredential)
        assertTrue(first.hasSavedCredential)
        instrumentation.runOnMainSync { models.first().clear() }
        val restored = model()
        assertTrue(restored.hasCredential)
        assertTrue(restored.hasSavedCredential)
        instrumentation.runOnMainSync { restored.clearCredential() }
        awaitIdle(restored)
        assertFalse(restored.hasCredential)
        assertFalse(restored.hasSavedCredential)
        assertFalse(file.exists())
        assertFalse(keyStore().containsAlias(alias))
        assertFalse(model().hasCredential)
    }

    @Test fun sessionOnlyCredentialDoesNotCreateAFileOrRestore() {
        val first = model()
        instrumentation.runOnMainSync { first.submitCredential(String(placeholder)) }
        assertTrue(first.hasCredential)
        assertFalse(vault.hasSavedCredential)
        assertFalse(keyStore().containsAlias(alias))
        assertFalse(model().hasCredential)
    }

    @Test fun optingOutRemovesAPreviouslyRememberedCredential() {
        vault.save(placeholder)
        val value = model()
        instrumentation.runOnMainSync { value.submitCredential("different-local-placeholder", rememberOnDevice = false) }
        awaitIdle(value)
        assertTrue(value.hasCredential)
        assertFalse(value.hasSavedCredential)
        assertFalse(file.exists())
        assertFalse(keyStore().containsAlias(alias))
        assertFalse(model().hasCredential)
    }

    @Test fun aFreshIvIsUsedForEverySaveAndTamperingIsRejected() {
        vault.save(placeholder)
        val first = file.readBytes()
        vault.save(placeholder)
        val second = file.readBytes()
        assertFalse(first.contentEquals(second))
        second[second.lastIndex] = (second.last().toInt() xor 1).toByte()
        file.writeBytes(second)
        val error = assertThrows(CredentialStorageException::class.java) { vault.load() }
        assertFalse(error.message!!.contains(String(placeholder)))
        assertFalse(error.message!!.contains(file.path))
    }

    @Test fun missingKeystoreKeyDoesNotCreateAReplacementDuringLoad() {
        vault.save(placeholder)
        keyStore().deleteEntry(alias)
        assertThrows(CredentialStorageException::class.java) { vault.load() }
        assertFalse(keyStore().containsAlias(alias))
        val value = model()
        assertFalse(value.hasCredential)
        assertTrue(value.hasSavedCredential)
        assertTrue(value.failure!!.contains("saved API key could not be unlocked"))
    }
}
