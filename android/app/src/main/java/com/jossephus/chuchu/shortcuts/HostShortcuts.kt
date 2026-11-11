package com.jossephus.chuchu.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.jossephus.chuchu.MainActivity
import com.jossephus.chuchu.R
import com.jossephus.chuchu.model.HostProfile

/** Launcher (long-press app icon) shortcuts that open a saved server directly. */
object HostShortcuts {
    private const val ACTION_CONNECT_HOST = "com.jossephus.chuchu.action.CONNECT_HOST"
    private const val EXTRA_HOST_ID = "com.jossephus.chuchu.extra.HOST_ID"
    private const val NO_HOST = -1L

    /**
     * Returns the host id carried by a shortcut intent, or null for any other intent.
     * A task restarted from Recents replays its first intent, which must not reconnect.
     */
    fun hostIdFrom(intent: Intent?): Long? {
        if (intent?.action != ACTION_CONNECT_HOST) return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val id = intent.getLongExtra(EXTRA_HOST_ID, NO_HOST)
        return id.takeIf { it != NO_HOST }
    }

    /**
     * Replaces the app's dynamic shortcuts with [hosts], in list order, and updates
     * shortcuts the user pinned. Launchers show only a few, and setDynamicShortcuts
     * throws above the limit, so the list is truncated to it.
     *
     * With [hideNames], no dynamic shortcuts are published and pinned ones are
     * relabelled with the app name, so the launcher does not show server names.
     */
    fun publish(context: Context, hosts: List<HostProfile>, hideNames: Boolean) {
        val limit = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        val dynamic =
            if (hideNames) emptyList() else hosts.take(limit).mapIndexed { rank, host -> build(context, host, rank, host.name) }
        ShortcutManagerCompat.setDynamicShortcuts(context, dynamic)

        // Pinned shortcuts outlive the dynamic list, so relabel or disable them here.
        val hostsById = hosts.associateBy { shortcutId(it.id) }
        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
        val (live, deleted) = pinned.partition { it.id in hostsById }
        if (live.isNotEmpty()) {
            val appName = context.getString(R.string.app_name)
            ShortcutManagerCompat.updateShortcuts(
                context,
                live.map { shortcut ->
                    val host = hostsById.getValue(shortcut.id)
                    build(context, host, shortcut.rank, if (hideNames) appName else host.name)
                },
            )
        }
        if (deleted.isNotEmpty()) {
            ShortcutManagerCompat.disableShortcuts(
                context,
                deleted.map { it.id },
                context.getString(R.string.shortcut_host_missing),
            )
        }
    }

    private fun build(context: Context, host: HostProfile, rank: Int, label: String): ShortcutInfoCompat {
        // Clear-top plus single-top hands the shortcut to the running activity through onNewIntent.
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_CONNECT_HOST)
            .putExtra(EXTRA_HOST_ID, host.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return ShortcutInfoCompat.Builder(context, shortcutId(host.id))
            .setShortLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    private fun shortcutId(hostId: Long): String = "host-$hostId"
}
