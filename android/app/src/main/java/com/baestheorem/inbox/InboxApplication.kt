package com.baestheorem.inbox

import android.app.Application
import com.baestheorem.inbox.auth.AuthStore
import com.baestheorem.inbox.data.Prefs
import com.baestheorem.inbox.data.SnoozeStore
import com.baestheorem.inbox.data.SwipeConfig
import com.baestheorem.inbox.data.UnsubScanCache
import com.baestheorem.inbox.data.OfflineMail
import com.baestheorem.inbox.data.Connectivity
import com.baestheorem.inbox.gmail.GmailClient
import com.baestheorem.inbox.work.NewMailWorker
import com.baestheorem.inbox.work.Notifications
import com.baestheorem.inbox.work.MailWork

// Every singleton the app and its background workers share gets its context
// here, so a worker waking with no Activity alive still has a live store.
class InboxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthStore.init(this)
        GmailClient.init(this)
        SnoozeStore.init(this)
        UnsubScanCache.init(this)
        SwipeConfig.init(this)
        Prefs.init(this)
        OfflineMail.init(this)
        Connectivity.init(this)
        Notifications.ensureChannels(this)
        if (AuthStore.isSignedIn) MailWork.schedule(this)
        if (AuthStore.isSignedIn && Prefs.notifyNewMail) NewMailWorker.schedule(this)
    }
}
