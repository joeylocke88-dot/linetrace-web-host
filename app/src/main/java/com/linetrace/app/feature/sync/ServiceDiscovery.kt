package com.linetrace.app.feature.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors

class ServiceDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    interface OnServiceFoundListener {
        fun onServiceFound(ip: String, port: Int)
    }

    fun startDiscovery(listener: OnServiceFoundListener) {
        stopDiscovery()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("ServiceDiscovery", "Discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("ServiceDiscovery", "Service found: name=${service.serviceName}, type=${service.serviceType}")
                
                // Some devices return type with trailing dot, some without.
                val type = service.serviceType.trim('.')
                if (type.contains("_linetrace")) {
                    Log.i("ServiceDiscovery", "Matching LineTrace service found. Resolving...")
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        nsdManager.registerServiceInfoCallback(service, Executors.newSingleThreadExecutor(), object : NsdManager.ServiceInfoCallback {
                            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                Log.e("ServiceDiscovery", "Callback registration failed: $errorCode")
                            }

                            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                                handleResolvedService(serviceInfo, listener)
                                // One-shot behavior like resolveService
                                nsdManager.unregisterServiceInfoCallback(this)
                            }

                            override fun onServiceLost() {
                                Log.e("ServiceDiscovery", "Service lost during resolution")
                            }

                            override fun onServiceInfoCallbackUnregistered() {}
                        })
                    } else {
                        @Suppress("DEPRECATION")
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.e("ServiceDiscovery", "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                handleResolvedService(serviceInfo, listener)
                            }
                        })
                    }
                }
            }

            private fun handleResolvedService(serviceInfo: NsdServiceInfo, listener: OnServiceFoundListener) {
                val host = serviceInfo.host
                val ip = host?.hostAddress
                if (ip == null) {
                    Log.e("ServiceDiscovery", "Resolved host address is null")
                    return
                }
                val port = serviceInfo.port
                Log.i("ServiceDiscovery", "SUCCESS: Resolved to $ip:$port")
                
                val finalIp = if (ip.contains(":") && !ip.contains("[")) "[$ip]" else ip
                listener.onServiceFound(finalIp, port)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e("ServiceDiscovery", "service lost: $service")
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("ServiceDiscovery", "Start Discovery Failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("ServiceDiscovery", "Stop Discovery Failed: $errorCode")
                stopDiscovery()
            }
        }

        nsdManager.discoverServices("_linetrace._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            nsdManager.stopServiceDiscovery(it)
        }
        discoveryListener = null
    }
}
