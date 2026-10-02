package com.anlite.backup.utils.extensions

import androidx.compose.ui.graphics.painter.Painter
import com.anlite.backup.data.preferences.traceDebug

object IconCache {

    private var painterCache = mutableMapOf<Any, Painter>()

    fun getIcon(key: Any): Painter? {
        return synchronized(painterCache) {
            painterCache[key]
        }
    }

    fun putIcon(key: Any, painter: Painter) {
        synchronized(painterCache) {
            painterCache.put(key, painter)
        }
    }

    fun removeIcon(key: Any) {
        traceDebug { "icon remove $key" }
        synchronized(painterCache) {
            painterCache.remove(key)
        }
    }

    fun clear() {
        synchronized(painterCache) {
            painterCache.clear()
        }
    }

    val size: Int
        get() {
            return synchronized(painterCache) {
                painterCache.size
            }
        }
}