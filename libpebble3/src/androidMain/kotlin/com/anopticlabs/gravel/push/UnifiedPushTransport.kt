package com.anopticlabs.gravel.push

import io.rebble.libpebblecommon.connection.AppContext
import org.unifiedpush.android.connector.UnifiedPush

class UnifiedPushTransport(private val appContext: AppContext) : PushTransport {
    private val context get() = appContext.context

    override fun distributors(): List<String> = UnifiedPush.getDistributors(context)

    override fun selectedDistributor(): String? = UnifiedPush.getSavedDistributor(context)

    override fun selectDistributor(packageName: String?) {
        if (packageName == null) {
            UnifiedPush.removeDistributor(context)
        } else {
            UnifiedPush.saveDistributor(context, packageName)
        }
    }

    override fun register(instance: String) = UnifiedPush.register(context, instance)

    override fun unregister(instance: String) = UnifiedPush.unregister(context, instance)
}
