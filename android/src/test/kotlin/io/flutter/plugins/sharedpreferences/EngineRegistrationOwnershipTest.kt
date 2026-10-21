package io.flutter.plugins.sharedpreferences

import android.content.Context
import android.content.SharedPreferences
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

@RunWith(Parameterized::class)
class EngineRegistrationOwnershipTest(private val allApis: Boolean) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "allApis={0}")
    fun modes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }

  private data class Engine(val plugin: FlutterPlugin, val binding: FlutterPluginBinding)
  private val engines = mutableListOf<Engine>()

  private fun attach(value: String): Engine {
    val context = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java)
    Mockito.`when`(prefs.all).thenReturn(mapOf("flutter.lifecycle" to value))
    Mockito.`when`(context.packageName).thenReturn("synthetic.lifecycle")
    Mockito.`when`(context.getSharedPreferences(
        ArgumentMatchers.anyString(), ArgumentMatchers.anyInt())).thenReturn(prefs)
    val binding = Mockito.mock(FlutterPluginBinding::class.java)
    Mockito.`when`(binding.applicationContext).thenReturn(context)
    val plugin: FlutterPlugin = if (allApis) SharedPreferencesPlugin() else LegacySharedPreferencesPlugin()
    return Engine(plugin, binding).also {
      plugin.onAttachedToEngine(binding)
      engines.add(it)
    }
  }

  private fun detach(engine: Engine) {
    engine.plugin.onDetachedFromEngine(engine.binding)
    engines.remove(engine)
  }

  @After
  fun tearDown() {
    engines.asReversed().toList().forEach(::detach)
  }

  private suspend fun assertOwnerReadable(expected: String) {
    val legacy = SharedPreferencesApiInstances.values.singleOrNull()
    assertNotNull("An active engine must keep its preferences API registered", legacy)
    assertEquals(expected, legacy!!.getAll("flutter.", null)["flutter.lifecycle"])
    if (allApis) {
      val registry = SharedPreferencesAsyncApiRegistrar()
      assertNotNull("DataStore registration must survive", registry.getInstance("data_store"))
      val backend = registry.getInstance("shared_preferences")
      assertNotNull("SharedPreferences backend registration must survive", backend)
      assertEquals(expected, backend!!.getAll(null,
          SharedPreferencesPigeonOptions(useDataStore = false))["flutter.lifecycle"])
    }
  }

  @Test
  fun olderEngineDetachKeepsNewerEnginePreferencesReadable() = runBlocking {
    val older = attach("older")
    attach("newer")
    detach(older)
    assertOwnerReadable("newer")
  }

  @Test
  fun newerEngineDetachRestoresOlderEnginePreferencesReadable() = runBlocking {
    attach("older")
    val newer = attach("newer")
    detach(newer)
    assertOwnerReadable("older")
  }

  @Test
  fun finalEngineDetachRemovesTheGlobalRegistration() {
    val only = attach("only")
    detach(only)
    assertTrue(SharedPreferencesApiInstances.isEmpty())
    if (allApis) assertTrue(SharedPreferencesAsyncApiInstances.isEmpty())
  }
}
