package com.faka.invisible

import android.app.Application
import com.faka.invisible.oskit.Trace

/** Starts the trace log as soon as the process exists (a service or the listener may be first), so even an early crash is on disk. */
class InvisibleApp : Application() {
    override fun onCreate() { super.onCreate(); Trace.init(this); DeviceApps.warm(this) }
}
