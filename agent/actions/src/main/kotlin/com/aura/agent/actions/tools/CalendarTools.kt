package com.aura.agent.actions.tools

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import com.aura.agent.actions.ToolHandler
import com.aura.core.domain.model.ToolType
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class CalendarReadHandler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolHandler {
    override val toolType = ToolType.CALENDAR_READ

    override suspend fun execute(params: Map<String, String>): String {
        val daysAhead = params["days_ahead"]?.toIntOrNull() ?: 7
        val now = System.currentTimeMillis()
        val end = now + daysAhead * 24 * 60 * 60 * 1000L

        val uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(now.toString(), end.toString())

        val events = mutableListOf<String>()
        try {
            val cursor: Cursor? = context.contentResolver.query(uri, projection, selection, selectionArgs, CalendarContract.Events.DTSTART)
            cursor?.use {
                val fmt = SimpleDateFormat("EEE dd/MM HH:mm", Locale.getDefault())
                while (it.moveToNext()) {
                    val title = it.getString(0) ?: "No title"
                    val start = it.getLong(1)
                    val loc = it.getString(4)?.takeIf { l -> l.isNotBlank() }?.let { " @ $it" } ?: ""
                    events.add("- ${fmt.format(Date(start))}: $title$loc")
                }
            }
        } catch (e: SecurityException) {
            return "Calendar permission not granted. Ask user to grant READ_CALENDAR permission."
        }
        return if (events.isEmpty()) "No events in the next $daysAhead days."
        else "Upcoming events:\n${events.joinToString("\n")}"
    }
}

class CalendarWriteHandler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolHandler {
    override val toolType = ToolType.CALENDAR_WRITE

    override suspend fun execute(params: Map<String, String>): String {
        val title = params["title"] ?: return "Missing 'title' parameter."
        val startMs = params["start_ms"]?.toLongOrNull() ?: return "Missing or invalid 'start_ms'."
        val endMs = params["end_ms"]?.toLongOrNull() ?: (startMs + 60 * 60 * 1000)
        val description = params["description"] ?: ""
        val location = params["location"] ?: ""

        // Get first available calendar ID
        val calId = getDefaultCalendarId() ?: return "No writable calendar found."

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) "Event '$title' created successfully (id=${uri.lastPathSegment})."
            else "Failed to create event."
        } catch (e: SecurityException) {
            "Calendar permission not granted. Ask user to grant WRITE_CALENDAR permission."
        }
    }

    private fun getDefaultCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection,
                "${CalendarContract.Calendars.VISIBLE} = 1", null, null
            )?.use { cursor ->
                var primaryId: Long? = null
                var firstId: Long? = null
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val isPrimary = cursor.getInt(1) == 1
                    if (firstId == null) firstId = id
                    if (isPrimary) { primaryId = id; break }
                }
                primaryId ?: firstId
            }
        } catch (_: SecurityException) { null }
    }
}
