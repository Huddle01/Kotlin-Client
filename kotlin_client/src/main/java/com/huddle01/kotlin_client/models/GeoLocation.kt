package com.huddle01.kotlin_client.models

import org.json.JSONObject

data class GeoLocation(
    val country: String,
    val latitude: String,
    val longitude: String,
    val region: String,
    val globalRegion: String,
    val ip: String,
) {
    companion object {
        fun fromMap(data: JSONObject): GeoLocation {
            val country = data.get("country") as String
            val latitude = data.get("latitude") as String
            val longitude = data.get("longitude") as String
            val region = data.get("region") as String
            val globalRegion = data.get("globalRegion") as String
            val ip = data.get("ip") as String
            return GeoLocation(country, latitude, longitude, region, globalRegion, ip)
        }
    }
}