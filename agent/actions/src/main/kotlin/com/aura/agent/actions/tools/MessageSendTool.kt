package com.aura.agent.actions.tools

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.aura.agent.actions.ToolHandler
import com.aura.core.domain.model.ToolType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends an SMS to a contact (by name or phone number) using the system SmsManager.
 * Resolves a name to a phone number via the Contacts ContentProvider when possible.
 *
 * Params expected: "recipient" (name or number), "body" (message text).
 */
@Singleton
class MessageSendHandler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolHandler {

    override val toolType = ToolType.MESSAGE_SEND

    override suspend fun execute(params: Map<String, String>): String {
        val recipient = params["recipient"]?.trim()
            ?: return "Error: missing 'recipient' parameter"
        val body = params["body"]?.trim()
            ?: return "Error: missing 'body' parameter"

        val phoneNumber = resolvePhoneNumber(recipient)
            ?: return "Error: could not resolve phone number for '$recipient'"

        val smsManager = resolveSmsManager()
        smsManager.sendTextMessage(phoneNumber, null, body, null, null)
        return "SMS envoyé à $recipient"
    }

    private fun resolvePhoneNumber(nameOrNumber: String): String? {
        // Already a phone number — use directly
        if (nameOrNumber.matches(Regex("[+\\d][\\d\\s\\-().]+"))) return nameOrNumber

        // Look up by contact name
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(nameOrNumber),
        )
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.NUMBER),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun resolveSmsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
}
