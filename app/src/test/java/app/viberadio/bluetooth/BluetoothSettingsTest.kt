package app.viberadio.bluetooth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothSettingsTest {

    private class FakeStore(var saved: Boolean? = null) : BluetoothSettingsStore {
        override fun readResumeOnConnect(): Boolean? = saved
        override fun writeResumeOnConnect(on: Boolean) {
            saved = on
        }
    }

    private fun settings(store: BluetoothSettingsStore) =
        BluetoothSettings(store, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    @Test
    fun `on by default`() = runBlocking {
        assertTrue(BluetoothSettings.DEFAULT_RESUME_ON_CONNECT)
        assertTrue(settings(FakeStore()).awaitResumeOnConnect())
    }

    @Test
    fun `saved choice is loaded`() = runBlocking {
        assertFalse(settings(FakeStore(saved = false)).awaitResumeOnConnect())
    }

    @Test
    fun `change is saved and survives a restart`() = runBlocking {
        val store = FakeStore()
        settings(store).setResumeOnConnect(false)
        assertEquals(false, store.saved)
        assertFalse(settings(store).awaitResumeOnConnect())
    }

    @Test
    fun `unreadable store falls back to the default`() = runBlocking {
        val broken = object : BluetoothSettingsStore {
            override fun readResumeOnConnect(): Boolean? = error("disk")
            override fun writeResumeOnConnect(on: Boolean) = error("disk")
        }
        val settings = settings(broken)
        assertTrue(settings.awaitResumeOnConnect())
        settings.setResumeOnConnect(false)
        assertFalse(settings.resumeOnConnect.value)
    }

    @Test
    fun `change made before the load wins`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val store = object : BluetoothSettingsStore {
            var saved: Boolean? = true
            override fun readResumeOnConnect(): Boolean? {
                runBlocking { gate.await() }
                return true
            }
            override fun writeResumeOnConnect(on: Boolean) {
                saved = on
            }
        }
        val settings = BluetoothSettings(store, CoroutineScope(Dispatchers.Default), Dispatchers.Default)
        settings.setResumeOnConnect(false)
        gate.complete(Unit)
        assertFalse(settings.awaitResumeOnConnect())
    }
}
