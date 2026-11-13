package com.aikeyboard.data

import com.google.gson.annotations.SerializedName

data class Note(
    @SerializedName("id") val id: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("content") val content: String = "",
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = ""
)

data class ChatMessage(val role: String, val content: String)   // "user" | "assistant"

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,      // epoch seconds
    val email: String
)
