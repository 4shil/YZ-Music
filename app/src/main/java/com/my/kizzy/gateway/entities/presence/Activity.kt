package com.my.kizzy.gateway.entities.presence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Activity(
    @SerialName("name")
    val name: String?,
    @SerialName("state")
    val state: String? = null,
    @SerialName("state_url")
    val stateUrl: String? = null,
    @SerialName("details")
    val details: String? = null,
    @SerialName("details_url")
    val detailsUrl: String? = null,
    @SerialName("type")
    val type: Int? = 0,
    @SerialName("status_display_type")
