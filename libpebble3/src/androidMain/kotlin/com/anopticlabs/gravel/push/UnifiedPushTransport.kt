package com.anopticlabs.gravel.push

import androidx.core.content.edit
import io.rebble.libpebblecommon.connection.AppContext
import org.unifiedpush.android.connector.UnifiedPush

class UnifiedPushTransport(private val appContext: AppContext) : PushTransport {
    private val context get() = appContext.context
    private val prefs get() = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    override fun distributors(): List<String> = UnifiedPush.getDistributors(context)

    override fun selectedDistributor(): String? = UnifiedPush.getSavedDistributor(context)

    override fun selectDistributor(packageName: String?) {
        prefs.edit { putBoolean(OPTED_OUT, packageName == null) }
        if (packageName == null) {
            UnifiedPush.removeDistributor(context)
        } else {
            UnifiedPush.saveDistributor(context, packageName)
        }
    }

    override fun optedOut(): Boolean = prefs.getBoolean(OPTED_OUT, false)

    override fun register(instance: String) = UnifiedPush.register(context, instance)

    override fun unregister(instance: String) = UnifiedPush.unregister(context, instance)

    private companion object {
        const val PREFS = "gravel_push"
        const val OPTED_OUT = "opted_out"
    }
}
