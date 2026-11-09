package com.example.data

import java.io.Serializable

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Long, // в миллисекундах
    val path: String, // путь файла
    val isLocal: Boolean,
    val albumArtSeed: Int = 0,
    val albumArtPath: String? = null,
    val album: String = "",
    val sourceUrl: String? = null
) : Serializable
