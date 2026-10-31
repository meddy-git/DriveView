package com.driveview.app

import android.content.Context

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("driveview", Context.MODE_PRIVATE)

    private fun f(key: String, def: Float) = sp.getFloat(key, def)
    private fun putF(key: String, v: Float) = sp.edit().putFloat(key, v).apply()
    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun putB(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    var host: String
        get() = sp.getString("host", "192.168.4.1") ?: "192.168.4.1"
        set(v) = sp.edit().putString("host", v.trim()).apply()

    var k1: Float
        get() = f("k1", 0.34f)
        set(v) = putF("k1", v)
    var k2: Float
        get() = f("k2", 0.15f)
        set(v) = putF("k2", v)
    var zoom: Float
        get() = f("zoom", 1.2f)
        set(v) = putF("zoom", v)
    var sep: Float
        get() = f("sep", 0.46f)
        set(v) = putF("sep", v)
    var size: Float
        get() = f("size", 0.92f)
        set(v) = putF("size", v)
    var radius: Float
        get() = f("radius", 0.22f)
        set(v) = putF("radius", v)
    var sharp: Float
        get() = f("sharp", 0.5f)
        set(v) = putF("sharp", v)
    var ledLevel: Int
        get() = sp.getInt("led", 117)
        set(v) = sp.edit().putInt("led", v.coerceIn(0, 255)).apply()
    var flipX: Boolean
        get() = b("flipx", true)
        set(v) = putB("flipx", v)
    var flipY: Boolean
        get() = b("flipy", true)
        set(v) = putB("flipy", v)

    fun reset() = sp.edit().clear().apply()

    fun config() = Config(k1, k2, zoom, sep, size, radius, sharp, flipX, flipY)
}
