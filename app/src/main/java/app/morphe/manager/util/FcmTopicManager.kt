/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.util

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging

/** Whether Google Play services can deliver FCM here, which otherwise leaves update checks to WorkManager. */
fun Context.hasGooglePlayServices(): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS

/**
 * FCM topic strings used for update push notifications.
 *
 * The custom manager intentionally does not subscribe to the official Morphe manager-update
 * topics. Its own releases are checked against this fork's app-release.json by WorkManager.
 * Only the built-in Morphe patches bundle keeps the original stable/dev FCM topics.
 *
 * | Topic            | Audience                                      |
 * |------------------|-----------------------------------------------|
 * | [MANAGER_STABLE] | Retired official manager topic (always unsubscribed) |
 * | [MANAGER_DEV]    | Retired official manager topic (always unsubscribed) |
 * | [PATCHES_STABLE] | Built-in patch bundle stable releases         |
 * | [PATCHES_DEV]    | Built-in patch bundle prerelease releases     |
 *
 * Stable topics are always subscribed when notifications are enabled —
 * a stable release (e.g. `1.5.0`) is always a valid upgrade from a dev
 * build (e.g. `1.5.0-dev.1`). Dev topics require explicit opt-in.
 */
object FcmTopics {
    const val MANAGER_STABLE = "morphe_updates"
    const val MANAGER_DEV    = "morphe_updates_dev"
    const val PATCHES_STABLE = "morphe_patches_updates"
    const val PATCHES_DEV    = "morphe_patches_updates_dev"

    val all = listOf(MANAGER_STABLE, MANAGER_DEV, PATCHES_STABLE, PATCHES_DEV)
}

/**
 * Synchronizes FCM subscriptions for patch-bundle updates only. Official manager topics are
 * always unsubscribed because manager updates come exclusively from this fork's Release metadata.
 * When [notificationsEnabled] is false, all four legacy topics are unsubscribed.
 *
 * Custom third-party bundles do not have FCM topics — only the built-in Morphe bundle does.
 * Safe to call multiple times — FCM deduplicates subscribe/unsubscribe internally.
 *
 * Called from:
 * - [app.morphe.manager.ManagerApplication] on every cold start
 * - [app.morphe.manager.ui.screen.settings.advanced.UpdatesItems] on preference toggle
 */
fun syncFcmTopics(
    notificationsEnabled: Boolean,
    useManagerPrereleases: Boolean,
    usePatchesPrereleases: Boolean = false,
) {
    val messaging = FirebaseMessaging.getInstance()

    if (!notificationsEnabled) {
        FcmTopics.all.forEach { topic ->
            messaging.unsubscribeFromTopic(topic)
                .addOnCompleteListener { Log.d("FcmTopicSync", "Unsubscribed from $topic") }
        }
        return
    }

    // Never receive official manager releases on this custom fork. Unsubscribe explicitly so
    // installs upgraded from an older build stop receiving the official manager topic messages.
    messaging.syncTopic(FcmTopics.MANAGER_STABLE, subscribe = false)
    messaging.syncTopic(FcmTopics.MANAGER_DEV, subscribe = false)

    // Built-in Morphe patch-bundle notifications remain on their original channels.
    messaging.syncTopic(FcmTopics.PATCHES_STABLE, subscribe = true)
    messaging.syncTopic(FcmTopics.PATCHES_DEV, subscribe = usePatchesPrereleases)
}

/**
 * Subscribes to or unsubscribes from a single FCM topic and logs the result.
 */
private fun FirebaseMessaging.syncTopic(topic: String, subscribe: Boolean) {
    val tag = "Morphe FcmTopicSync"
    if (subscribe) {
        subscribeToTopic(topic).addOnCompleteListener { task ->
            Log.d(tag, if (task.isSuccessful) "Subscribed to $topic" else "Failed to subscribe to $topic")
        }
    } else {
        unsubscribeFromTopic(topic).addOnCompleteListener {
            Log.d(tag, "Unsubscribed from $topic")
        }
    }
}
