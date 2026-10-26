package com.music.laya.data.listentogether

import android.content.Intent

private const val EXTRA_CONSUMED = "laya.jamInviteConsumed"

/** Reads a web invite from a cold launch or a new intent on the existing task. */
fun JamInviteLink.consume(intent: Intent?): Boolean {
    if (
        intent == null ||
        intent.action != Intent.ACTION_VIEW ||
        intent.getBooleanExtra(EXTRA_CONSUMED, false)
    ) return false
    if (!offer(intent.dataString)) return false
    intent.putExtra(EXTRA_CONSUMED, true)
    return true
}
