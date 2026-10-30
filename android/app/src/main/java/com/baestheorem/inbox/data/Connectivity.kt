package com.baestheorem.inbox.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.baestheorem.inbox.auth.AuthStore
import com.baestheorem.inbox.work.MailWork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Connectivity {
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun update() {
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val wasOnline = _online.value
            _online.value = connected
            if (connected && !wasOnline && AuthStore.isSignedIn) {
                MailWork.sync(context)
                MailWork.send(context)
            }
        }
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
        })
        update()
    }
}
