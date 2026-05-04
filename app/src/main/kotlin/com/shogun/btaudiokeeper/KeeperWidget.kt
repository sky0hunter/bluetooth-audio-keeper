package com.shogun.btaudiokeeper

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

class KeeperWidget : AppWidgetProvider() {

    override fun onUpdate(
        ctx: Context,
        mgr: AppWidgetManager,
        ids: IntArray
    ) {
        ids.forEach { renderWidget(ctx, mgr, it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                val running = Prefs.isRunning(ctx)
                val svc = Intent(ctx, KeeperService::class.java)
                if (running) {
                    svc.action = KeeperService.ACTION_STOP
                    ctx.startService(svc)
                } else {
                    ContextCompat.startForegroundService(ctx, svc)
                }
                refreshAll(ctx)
            }
            Prefs.ACTION_STATE_CHANGED -> refreshAll(ctx)
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "com.shogun.btaudiokeeper.WIDGET_TOGGLE"

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, KeeperWidget::class.java))
            ids.forEach { renderWidget(ctx, mgr, it) }
        }

        private fun renderWidget(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val running = Prefs.isRunning(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_keeper).apply {
                setImageViewResource(
                    R.id.widget_icon,
                    if (running) R.drawable.ic_widget_on else R.drawable.ic_widget_off
                )
                setInt(
                    R.id.widget_root,
                    "setBackgroundResource",
                    if (running) R.drawable.widget_bg_on else R.drawable.widget_bg_off
                )
                setTextViewText(
                    R.id.widget_label,
                    ctx.getString(if (running) R.string.widget_label_on else R.string.widget_label_off)
                )

                val toggle = Intent(ctx, KeeperWidget::class.java).setAction(ACTION_TOGGLE)
                val pi = PendingIntent.getBroadcast(
                    ctx, 0, toggle,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                setOnClickPendingIntent(R.id.widget_root, pi)
            }
            mgr.updateAppWidget(id, views)
        }
    }
}
