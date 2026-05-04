package com.shogun.btaudiokeeper

import android.content.Context
import android.content.Intent

object Prefs {
    private const val FILE = "bt-keeper"
    private const val KEY_RUNNING = "running"
    private const val KEY_AMPLITUDE = "amplitude"

    const val DEFAULT_AMPLITUDE = 4
    const val MIN_AMPLITUDE = 1
    const val MAX_AMPLITUDE = 128

    const val ACTION_STATE_CHANGED = "com.shogun.btaudiokeeper.STATE_CHANGED"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isRunning(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_RUNNING, false)

    fun setRunning(ctx: Context, running: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_RUNNING, running).apply()
        ctx.sendBroadcast(
            Intent(ACTION_STATE_CHANGED).setPackage(ctx.packageName)
        )
    }

    fun amplitude(ctx: Context): Int =
        prefs(ctx).getInt(KEY_AMPLITUDE, DEFAULT_AMPLITUDE)
            .coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)

    fun setAmplitude(ctx: Context, value: Int) {
        prefs(ctx).edit()
            .putInt(KEY_AMPLITUDE, value.coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE))
            .apply()
    }
}
