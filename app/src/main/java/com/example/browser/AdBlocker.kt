package com.example.browser

import android.net.Uri
import android.util.Log

object AdBlocker {
    private const val TAG = "AdBlocker"

    // High performance list of common advertisement, popup, tracker and telemetry networks
    private val AD_DOMAINS = setOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "adnxs.com",
        "adsystem.com",
        "taboola.com",
        "outbrain.com",
        "popads.net",
        "exoclick.com",
        "adform.net",
        "adriver.ru",
        "an.yandex.ru",
        "clck.yandex.ru",
        "click.ru",
        "admob.com",
        "bannerbank.ru",
        "begun.ru",
        "mgid.com",
        "rtb.ro",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "exponential.com",
        "advertising.com",
        "media.net",
        "buyads.ru",
        "marketgid.com",
        "directadvert.ru"
    )

    fun isAdRequest(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false
            
            // Check if matches domain directly or as sub-domain
            for (adDomain in AD_DOMAINS) {
                if (host == adDomain || host.endsWith(".$adDomain")) {
                    Log.d(TAG, "Blocked Ad Request: $url (matched $adDomain)")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
