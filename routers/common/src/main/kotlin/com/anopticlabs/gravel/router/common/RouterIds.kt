package com.anopticlabs.gravel.router.common

import java.security.MessageDigest
import java.util.UUID

/**
 * The ids of listed routers. Every first-party app is signed with one key, so all their ids share
 * one namespace in Gravel's routing: each app gives its listed routers its own [prefix], distinct
 * from every app's declared id.
 */
object RouterIds {
    private val GRAMMAR = Regex("[a-z0-9-]{1,32}")

    /** The router id grammar of `ROUTER_API.md`. */
    fun isRouterId(id: String): Boolean = GRAMMAR.matches(id)

    /**
     * The id of the router for [key] (a project, channel or account id on the service), the same
     * on every refresh, so the user's routing keeps naming it: [prefix], a dash and 16 hex digits
     * of the key's SHA-256, which is of the grammar whatever characters the service uses.
     */
    fun of(prefix: String, key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        return "$prefix-${digest.take(16)}".also { require(isRouterId(it)) { "the prefix $prefix is too long" } }
    }

    /** A new id for a router the user declares in the app, which has no key of its own on a service. */
    fun fresh(prefix: String): String = of(prefix, UUID.randomUUID().toString())
}
