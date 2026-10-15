package app.viberadio.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSettingsTest {

    private class FakeStore(var saved: DiagnosticsPrefs? = null) : DiagnosticsStore {
        override fun read(): DiagnosticsPrefs? = saved
        override fun write(prefs: DiagnosticsPrefs) {
            saved = prefs
        }
    }

    private fun settings(store: DiagnosticsStore) =
        DiagnosticsSettings(store, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    @Test
    fun `developer mode is off by default`() = runBlocking {
        val prefs = settings(FakeStore()).await()
        assertFalse(prefs.developerMode)
        assertFalse(prefs.nightly)
        assertEquals(null, prefs.folderUri)
    }

    @Test
    fun `changes are saved and survive a restart`() = runBlocking {
        val store = FakeStore()
        settings(store).apply {
            setDeveloperMode(true)
            setFolder("content://tree/1", "Diagnostics")
            setNightly(true)
            recordExport(ExportOutcome(atMs = 5, bundle = "2026-10-15_081203", savedToFolder = true))
        }
        val reloaded = settings(store).await()
        assertTrue(reloaded.developerMode)
        assertEquals("content://tree/1", reloaded.folderUri)
        assertEquals("Diagnostics", reloaded.folderName)
        assertTrue(reloaded.nightly)
        assertEquals("2026-10-15_081203", reloaded.lastExport?.bundle)
    }

    @Test
    fun `turning developer mode off stops the nightly export but keeps the folder`() = runBlocking {
        val s = settings(FakeStore())
        s.setDeveloperMode(true)
        s.setFolder("content://tree/1", "Diagnostics")
        s.setNightly(true)
        s.setDeveloperMode(false)
        assertFalse(s.prefs.value.nightly)
        assertEquals("content://tree/1", s.prefs.value.folderUri)
        s.setDeveloperMode(true)
        assertFalse("nightly stays off until turned on again", s.prefs.value.nightly)
    }

    @Test
    fun `nightly needs developer mode`() = runBlocking {
        val s = settings(FakeStore())
        s.setNightly(true)
        assertFalse(s.prefs.value.nightly)
    }

    @Test
    fun `a change made before the load lands on top of the saved settings`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val store = object : DiagnosticsStore {
            var saved: DiagnosticsPrefs? = DiagnosticsPrefs(developerMode = true, folderUri = "content://tree/1")
            override fun read(): DiagnosticsPrefs? {
                runBlocking { gate.await() }
                return saved
            }
            override fun write(prefs: DiagnosticsPrefs) {
                saved = prefs
            }
        }
        val s = DiagnosticsSettings(store, CoroutineScope(Dispatchers.Default), Dispatchers.Default)
        s.recordExport(ExportOutcome(atMs = 1, bundle = "b", savedToFolder = false, error = "x"))
        gate.complete(Unit)
        val prefs = s.await()
        assertTrue(prefs.developerMode)
        assertEquals("content://tree/1", prefs.folderUri)
        assertEquals("b", prefs.lastExport?.bundle)
    }

    @Test
    fun `unreadable store falls back to the defaults`() = runBlocking {
        val broken = object : DiagnosticsStore {
            override fun read(): DiagnosticsPrefs? = error("disk")
            override fun write(prefs: DiagnosticsPrefs) = error("disk")
        }
        val s = settings(broken)
        assertFalse(s.await().developerMode)
        s.setDeveloperMode(true)
        assertTrue(s.prefs.value.developerMode)
    }
}
