package com.huddle01.kotlin_client.models

data class SendData(
    val to: ArrayList<String>,
    val payload: String,
    val label: String?,
)