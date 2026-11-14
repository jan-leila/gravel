package com.anopticlabs.gravel.push

import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint as ConnectorEndpoint
import org.unifiedpush.android.connector.data.PushMessage as ConnectorMessage

/**
 * Where the connector delivers what distributors send. Not exported: the connector's own receiver
 * is the exported component, and it forwards only broadcasts that carry a registration's token.
 */
class WatchappPushService : PushService(), LibPebbleKoinComponent {
    private val events: PushTransportEvents get() = getKoin().get<WatchappPushManager>()

    override fun onNewEndpoint(endpoint: ConnectorEndpoint, instance: String) {
        events.onNewEndpoint(
            instance,
            PushEndpoint(endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth),
        )
    }

    override fun onMessage(message: ConnectorMessage, instance: String) {
        events.onMessage(instance, message.content, message.decrypted)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        events.onRegistrationFailed(instance)
    }

    override fun onUnregistered(instance: String) {
        events.onUnregistered(instance)
    }
}
