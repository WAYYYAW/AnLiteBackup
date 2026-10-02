package com.anlite.backup.data.entity

import androidx.annotation.StringRes

open class Pref(
    open val key: String,
    open val defaultValue: Any? = null,
    @StringRes open val titleId: Int = -1,
    @StringRes open val summaryId: Int = -1,
    open var summary: String? = null,
)

class BooleanPref(
    override val key: String,
    override val defaultValue: Boolean = false,
    @StringRes override val titleId: Int = -1,
    @StringRes override val summaryId: Int = -1,
    override var summary: String? = null,
    val private: Boolean = false,
    val enableIf: (() -> Boolean)? = null,
    val onChanged: ((Boolean) -> Unit)? = null,
) : Pref(key, defaultValue, titleId, summaryId, summary) {
    var value: Boolean = defaultValue
}

class IntPref(
    override val key: String,
    override val defaultValue: Int = 0,
    @StringRes override val titleId: Int = -1,
    @StringRes override val summaryId: Int = -1,
    override var summary: String? = null,
    val entries: List<Int> = emptyList(),
    val private: Boolean = false,
    val enableIf: (() -> Boolean)? = null,
    val onChanged: ((Int) -> Unit)? = null,
) : Pref(key, defaultValue, titleId, summaryId, summary) {
    var value: Int = defaultValue
}