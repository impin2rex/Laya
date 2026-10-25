package com.music.laya.ios

import com.music.laya.data.settings.AppSettings
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue

/**
 * Feeds [AppSettings.meteredConnection] the way Android's ConnectivityManager callback does (null = offline,
 * true = cellular / Personal Hotspot), so [AppSettings.effectiveAudioQuality] picks the mobile-data ceiling there.
 */
internal fun watchIosConnection() {
    val monitor = nw_path_monitor_create()
    nw_path_monitor_set_update_handler(monitor) { path ->
        AppSettings.meteredConnection.value =
            if (path == null || nw_path_get_status(path) != nw_path_status_satisfied) null else nw_path_is_expensive(path)
    }
    nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
    nw_path_monitor_start(monitor)
}
