package org.evsyukov.brook.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class TranslationReply(
    val id: String,
    val language: String,
    val sourceHash: String = "",
    val revision: String = "",
    val exists: Boolean = false,
    val complete: Boolean = false,
    val sameLanguage: Boolean = false,
    val title: String = "",
    val content: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
)
