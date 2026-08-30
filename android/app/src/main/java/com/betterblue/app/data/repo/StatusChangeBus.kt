package com.betterblue.app.data.repo

import com.betterblue.kit.model.VehicleStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Broadcasts vehicle status updates so post-command status waiters can wake
 * up early when a fresh status arrives from anywhere (poll loop, manual
 * refresh, another waiter's fetch) — the Android analog of the iOS
 * `StatusWaitingManager` actor's continuation map.
 */
@Singleton
class StatusChangeBus
    @Inject
    constructor() {
        private val updates = MutableSharedFlow<VehicleStatus>(extraBufferCapacity = 16)

        val statusUpdates: SharedFlow<VehicleStatus> = updates.asSharedFlow()

        fun publish(status: VehicleStatus) {
            updates.tryEmit(status)
        }
    }
