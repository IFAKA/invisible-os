package com.faka.invisible

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import com.faka.invisible.adapters.ChatAppsModule
import com.faka.invisible.logickit.*
import com.faka.invisible.oskit.NotificationHost
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The notification listener's glue with real Android Notification objects (Robolectric). The notifications are built exactly as the
 * phone's debug hook `fake call` / `fake chat` builds them (AiTestReceiver). Only Android itself delivering them is the phone's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlowListenerTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private fun pi(flags: Int) = PendingIntent.getBroadcast(ctx, 0, Intent("e2e"), flags)

    private fun sbn(id: Int, n: Notification): StatusBarNotification {
        val c = StatusBarNotification::class.java.constructors.first { k -> k.parameterTypes.any { it == Notification::class.java } && k.parameterTypes.any { it == UserHandle::class.java } }
        val args = c.parameterTypes.map { t -> when (t) {
            String::class.java -> if (c.parameterTypes.indexOf(t) == 0) ctx.packageName else if (c.parameterTypes.indexOf(t) == 1) ctx.packageName else "tag"
            Int::class.javaPrimitiveType -> if (c.parameterTypes.indexOfFirst { it == t } == 0) id else 0
            Long::class.javaPrimitiveType -> 1_000L
            Notification::class.java -> n
            UserHandle::class.java -> Process.myUserHandle()
            else -> null
        } }
        // parameter order is (pkg, opPkg, id, tag, uid, initialPid, [score], notification, user, [overrideGroupKey], postTime)
        return c.newInstance(*args.toTypedArray()) as StatusBarNotification
    }

    private fun fakeCall(): Notification =
        Notification.Builder(ctx, "e2e").setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("E2E Caller").setContentText("Incoming call").setCategory(Notification.CATEGORY_CALL)
            .addAction(Notification.Action.Builder(null, "Answer", pi(PendingIntent.FLAG_IMMUTABLE)).build())
            .addAction(Notification.Action.Builder(null, "Decline", pi(PendingIntent.FLAG_IMMUTABLE)).build()).build()

    private fun fakeChat(): Notification {
        val ri = RemoteInput.Builder("reply").setLabel("Reply").build()
        return Notification.Builder(ctx, "e2e").setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("E2E Friend").setContentText("Hello, are you coming to dinner tonight?").setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(Notification.Action.Builder(null, "Reply", pi(PendingIntent.FLAG_MUTABLE)).addRemoteInput(ri).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build()).build()
    }

    // ---- fake_incoming_call: what the listener's call() decides, then the screen the mode opens ----
    @Test fun incoming_call() {
        val n = fakeCall()
        val titles = n.actions.orEmpty().map { it.title?.toString().orEmpty() }
        assertEquals(CallKind.INCOMING, Calls.kind(n.category, n.extras.getInt("android.callType", 0), titles.size))
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val name = Calls.callerName(title, null)
        assertEquals("E2E Caller", name)
        assertEquals(0, Calls.answerIndex(titles)); assertEquals(1, Calls.declineIndex(titles))
        val f = Flow(); f.r.caller = name
        val i = f.mark(); f.m.callIncoming()   // Actions.callRinging does this on the main handler
        f.need("Call from E2E Caller", i); f.need("Answer", i); f.need("Reject", i)
        assertEquals(ControllerMode.Screen.CALL_IN, f.screen())
    }

    // ---- fake_chat_message: the real chat module takes the notification, keeps the message, hands the arrival to the OS ----
    @Test fun chat_message_is_read() {
        val n = fakeChat()
        assertTrue(ChatAppsModule.joinsByCategory(n.category, n.actions.orEmpty().any { !it.remoteInputs.isNullOrEmpty() }, false))   // the generic rule takes it
        val m = ChatAppsModule()
        val got = CountDownLatch(1); var arrived: List<String>? = null
        m.onConnected(ctx, object : NotificationHost {
            override fun arrival(pkg: String, sender: String, text: String, lang: String, visibility: Int) { arrived = listOf(pkg, sender, text, lang); got.countDown() }
            override fun detectLanguage(text: String) = "en"
        })
        m.onPosted(sbn(7002, n), true)
        assertTrue("the module never announced the message", got.await(10, TimeUnit.SECONDS))
        assertEquals("E2E Friend", arrived!![1]); assertTrue("dinner" in arrived!![2])
        val kept = m.store.all()
        assertEquals(1, kept.size); assertEquals("E2E Friend", kept[0].sender)
        // then the OS reads it: the app, who wrote, the text (FlowTest.chat_arrival_spoken), and a click-hold wake offers the reply options
        val lines = ArrivalPlan.lines("E2E app", arrived!![1], arrived!![2], arrived!![3], false).map { it.text }
        assertTrue(lines.any { "E2E Friend" in it }); assertTrue(lines.any { "dinner" in it })
    }
}
