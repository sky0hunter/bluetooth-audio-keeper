package com.shogun.btaudiokeeper

import android.content.Context
import android.content.Intent

object Prefs {
    enum class Mode { MANUAL, AUTO }
    enum class State { IDLE, WATCHING, STREAMING }

    private const val FILE = "bt-keeper"
    private const val KEY_STATE = "state"
    private const val KEY_AMPLITUDE = "amplitude"
    private const val KEY_MODE = "mode"

    const val DEFAULT_AMPLITUDE = 4
    const val MIN_AMPLITUDE = 1
    const val MAX_AMPLITUDE = 128

    const val ACTION_STATE_CHANGED = "com.shogun.btaudiokeeper.STATE_CHANGED"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun state(ctx: Context): State =
        runCatching { State.valueOf(prefs(ctx).getString(KEY_STATE, State.IDLE.name)!!) }
            .getOrDefault(State.IDLE)

    fun setState(ctx: Context, value: State) {
        prefs(ctx).edit().putString(KEY_STATE, value.name).apply()
        ctx.sendBroadcast(
            Intent(ACTION_STATE_CHANGED).setPackage(ctx.packageName)
        )
    }

    fun isRunning(ctx: Context): Boolean = state(ctx) != State.IDLE

    fun mode(ctx: Context): Mode =
        runCatching { Mode.valueOf(prefs(ctx).getString(KEY_MODE, Mode.MANUAL.name)!!) }
            .getOrDefault(Mode.MANUAL)

    fun setMode(ctx: Context, value: Mode) {
        prefs(ctx).edit().putString(KEY_MODE, value.name).apply()
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
