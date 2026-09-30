package dev.wristline.watch.data

import android.content.Context
import java.time.Instant
import kotlinx.serialization.json.JsonObject

internal class DemoData(
    val sessions: List<Session>,
    val requests: List<PendingRequest>,
    val usage: List<Usage>,
    val items: Map<String, List<Item>>,
)

/**
 * Demo mode ("Try demo", store review): the protocol fixtures in assets/protocol/ stand in for a
 * bridge. The first snapshot event supplies sessions, requests and usage; the unfiltered item page
 * is shown in every session. Timestamps are shifted so the newest activity reads as a minute ago.
 */
internal object Demo {
    private const val DIR = "protocol"

    fun load(context: Context): DemoData {
        var snapshot: ServerEvent.Snapshot? = null
        var page: ItemPage? = null
        val assets = context.assets
        for (name in assets.list(DIR).orEmpty().sorted()) {
            if (!name.endsWith(".json")) continue
            val obj = assets.open("$DIR/$name").use { WireJson.parseToJsonElement(it.readBytes().decodeToString()) }
                as? JsonObject ?: continue
            try {
                when {
                    "type" in obj -> if (snapshot == null) snapshot = decodeServerEvent(obj) as? ServerEvent.Snapshot
                    // items.json is the unfiltered page; items-filtered.json, sorted first, is a subset.
                    "items" in obj -> if (page == null || name == "items.json") {
                        page = WireJson.decodeFromJsonElement(ItemPage.serializer(), obj)
                    }
                }
            } catch (_: IllegalArgumentException) {
                // A fixture this build cannot read; demo mode shows what it can.
            }
        }
        val snap = snapshot?.takeIf { it.apiVersion == API_VERSION } ?: ServerEvent.Snapshot(API_VERSION)
        val newest = snap.sessions.maxOfOrNull { isoToMillis(it.lastActivity) ?: 0L } ?: 0L
        val shift = if (newest > 0) System.currentTimeMillis() - 60_000 - newest else 0L
        fun String.shifted(): String = isoToMillis(this)?.let { Instant.ofEpochMilli(it + shift).toString() } ?: this

        val items = page?.items.orEmpty()
        return DemoData(
            sessions = snap.sessions.map { it.copy(lastActivity = it.lastActivity.shifted()) },
            requests = snap.requests.map { it.copy(createdAt = it.createdAt.shifted()) },
            usage = snap.usage.map { usage ->
                usage.copy(
                    updatedAt = usage.updatedAt.shifted(),
                    windows = usage.windows.map { it.copy(resetsAt = it.resetsAt?.shifted()) },
                )
            },
            items = snap.sessions.associate { it.id to items },
        )
    }
}
