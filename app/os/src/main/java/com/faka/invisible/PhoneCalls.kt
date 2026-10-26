package com.faka.invisible

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import com.faka.invisible.logickit.Calls
import com.faka.invisible.logickit.Contact
import java.util.Locale

/** The phone side of calls: contacts, placing, answering, ending, emergency. Pure decisions are in [Calls]. Every call here needs a runtime permission and fails to false without it. */
object PhoneCalls {
    private const val TAG = "Invisible"

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** The contact name for a number, null when unknown or READ_CONTACTS is off. */
    fun nameFor(ctx: Context, number: String): String? {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { Log.e(TAG, "CALL name lookup failed", e); null }
    }

    /** Starred and frequent contacts first (Android's own order), then by name; slow, call it off the main thread. */
    fun contacts(ctx: Context): List<Contact> {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        return try {
            val seen = ArrayList<Contact>()
            ctx.contentResolver.query(ContactsContract.Contacts.CONTENT_STREQUENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.STARRED), null, null, null)?.use { c ->
                var rank = 1000
                while (c.moveToNext() && seen.size < 12) {
                    val number = firstNumber(ctx, c.getLong(0)) ?: continue
                    seen += Contact(c.getString(1).orEmpty(), number, c.getInt(2) == 1, rank--)
                }
            }
            if (seen.isEmpty()) ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.STARRED),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)?.use { c ->
                while (c.moveToNext() && seen.size < 30) seen += Contact(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getInt(2) == 1)
            }
            Calls.rank(seen)
        } catch (e: Exception) { Log.e(TAG, "CALL contacts failed", e); emptyList() }
    }

    private fun firstNumber(ctx: Context, id: Long): String? =
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?", arrayOf(id.toString()), null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** Places a call (needs CALL_PHONE). TelecomManager works with the screen off; a plain activity start does not. */
    fun place(ctx: Context, number: String): Boolean {
        if (!has(ctx, Manifest.permission.CALL_PHONE)) return false
        val uri = Uri.fromParts("tel", number, null)
        return try {
            ctx.getSystemService(TelecomManager::class.java).placeCall(uri, null); true
        } catch (e: Exception) {
            Log.e(TAG, "CALL place failed, trying ACTION_CALL", e)
            try { ctx.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e2: Exception) { false }
        }
    }

    /** Answers the ringing call (ANSWER_PHONE_CALLS). */
    fun answer(ctx: Context): Boolean {
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return try { ctx.getSystemService(TelecomManager::class.java).acceptRingingCall(); true } catch (e: Exception) { Log.e(TAG, "CALL answer failed", e); false }
    }

    /** Ends the running call, or rejects the ringing one. */
    @Suppress("DEPRECATION")
    fun end(ctx: Context): Boolean {
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return try { ctx.getSystemService(TelecomManager::class.java).endCall() } catch (e: Exception) { Log.e(TAG, "CALL end failed", e); false }
    }

    /** The two-letter country the phone is in: the network, else the SIM, else the language setting. */
    fun country(ctx: Context): String {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        return listOf(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country).firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    /** Texts the emergency contact (if one is set and SEND_SMS is allowed), then calls the emergency number. True when the call was placed. */
    fun emergency(ctx: Context, contact: String): Boolean {
        if (contact.isNotBlank() && has(ctx, Manifest.permission.SEND_SMS)) {
            try { ctx.getSystemService(SmsManager::class.java).sendTextMessage(contact, null, Calls.emergencyText(), null, null) }
            catch (e: Exception) { Log.e(TAG, "EMERGENCY sms failed", e) }
        }
        return place(ctx, Calls.emergencyNumber(country(ctx)))
    }
}
