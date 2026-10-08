package com.claudeforwatch.core.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists [Credentials]. Platform implementations encrypt at rest (Wear OS: Keystore AES-GCM
 * over DataStore, alias `cfw_auth`; docs/PROTOCOL.md §1.1). A corrupt/undecryptable record must
 * be wiped and reported as `null` (signed out).
 */
interface TokenStore {
    suspend fun load(): Credentials?
    suspend fun save(credentials: Credentials)
    suspend fun clear()
}

class InMemoryTokenStore(initial: Credentials? = null) : TokenStore {
    private val lock = Mutex()
    private var value: Credentials? = initial
    var saveCount: Int = 0
        private set

    override suspend fun load(): Credentials? = lock.withLock { value }
    override suspend fun save(credentials: Credentials) { lock.withLock { value = credentials; saveCount++ } }
    override suspend fun clear() { lock.withLock { value = null } }
}
