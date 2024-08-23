package com.huddle01.kotlin_client.common

import RtpCapabilities
import com.huddle01.kotlin_client.utils.JsonUtils
import org.json.JSONObject
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpCapabilities.CodecCapability

object ProtoParsing {
    private fun convertToProtobufMap(originalMap: Map<Any, Any>?): Map<String, AppDataOuterClass.Value> {
        val protobufMap = mutableMapOf<String, AppDataOuterClass.Value>()
        originalMap?.forEach { (key, value) ->
            if (key is String) {
                val protobufValue = when (value) {
                    is String -> AppDataOuterClass.Value.newBuilder().setStringValue(value).build()
                    is Int -> AppDataOuterClass.Value.newBuilder().setIntValue(value.toInt())
                        .build()

                    else -> throw IllegalArgumentException("Unsupported value type: $value")
                }
                protobufMap[key] = protobufValue
            }
        }
        return protobufMap
    }

    fun getRtpCodecsList(protoCodecList: List<RtpCapabilities.ProtoRtpCodecCapability>): List<CodecCapability> {
        return protoCodecList.map { proto ->
            CodecCapability().apply {
                numChannels = proto.channels
                clockRate = proto.clockRate
                kind = when (proto.kind) {
                    "audio" -> MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
                    "video" -> MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                    else -> throw IllegalArgumentException("Unknown media type: ${proto.kind}")
                }
                mimeType = proto.mimeType
                preferredPayloadType = proto.preferredPayloadType
            }
        }
    }

    fun getProtoRtpParameters(rtpParameters: String): RtpParameters.ProtoRtpParameters {
        val jsonRtpParameters = JsonUtils.toJsonObject(rtpParameters)
        val protoRtpParametersBuilder = RtpParameters.ProtoRtpParameters.newBuilder()

        jsonRtpParameters.getJSONArray("codecs").let { codecsArray ->
            for (i in 0..0) {
                val codecObject = codecsArray.getJSONObject(i)
                jsonRtpParameters.optString("mid").takeIf { it.isNotEmpty() }
                    ?.let { protoRtpParametersBuilder.setMid(it) }

                val codecBuilder = RtpParameters.ProtoCodecParameters.newBuilder().apply {
                    codecObject.optInt("channels").takeIf { it > 0 }?.let { setChannels(it) }
                    setClockRate(codecObject.getInt("clockRate"))
                    setMimeType(codecObject.getString("mimeType"))
                    setPayloadType(codecObject.getInt("payloadType"))

                    codecObject.getJSONArray("rtcpFeedback").let { rtcpFeedbackArray ->
                        for (j in 0 until rtcpFeedbackArray.length()) {
                            val rtcpFeedbackObject = rtcpFeedbackArray.getJSONObject(j)
                            addRtcpFeedback(RtpParameters.ProtoRtcpFeedback.newBuilder().apply {
                                setParameter(rtcpFeedbackObject.getString("parameter"))
                                setType(rtcpFeedbackObject.getString("type"))
                            }.build())
                        }
                    }
                }.build()
                protoRtpParametersBuilder.addCodecs(codecBuilder)
            }
        }

        jsonRtpParameters.getJSONArray("headerExtensions").let { headerExtensionsArray ->
            for (i in 0 until headerExtensionsArray.length()) {
                val headerExtensionObject = headerExtensionsArray.getJSONObject(i)
                protoRtpParametersBuilder.addHeaderExtensions(
                    RtpParameters.ProtoHeaderExtensionParameters.newBuilder().apply {
                        setId(headerExtensionObject.getInt("id"))
                        setUri(headerExtensionObject.getString("uri"))
                        setEncrypt(headerExtensionObject.getBoolean("encrypt"))
                    }.build()
                )
            }
        }

        jsonRtpParameters.getJSONArray("encodings").let { encodingsArray ->
            for (i in 0 until encodingsArray.length()) {
                val encodingObject = encodingsArray.getJSONObject(i)
                protoRtpParametersBuilder.addEncodings(
                    RtpParameters.ProtoEncodings.newBuilder().apply {
                        setDtx(encodingObject.optBoolean("dtx", false))
                        setSsrc(encodingObject.getLong("ssrc"))
                        encodingObject.optJSONObject("rtx")?.let { rtxObject ->
                            setRtx(RtpParameters.ProtoEncodings.ProtoRTX.newBuilder().apply {
                                setSsrc(rtxObject.getLong("ssrc"))
                            }.build())
                        }
                    }.build()
                )
            }
        }

        jsonRtpParameters.optJSONObject("rtcp")?.let { rtcpObject ->
            protoRtpParametersBuilder.setRtcp(RtpParameters.RtcpParameters.newBuilder().apply {
                rtcpObject.optString("cname").takeIf { it.isNotEmpty() }?.let { setCname(it) }
                setReducedSize(rtcpObject.optBoolean("reducedSize", false))
            }.build())
        }

        return protoRtpParametersBuilder.build()
    }


    fun getParsedIceParameters(iceParameters: SdpInfo.ProtoIceParameters): String {
        val usernameFragment = iceParameters.usernameFragment
        val password = iceParameters.password
        val iceLite = iceParameters.iceLite
        return """{"usernameFragment":"$usernameFragment","password":"$password","iceLite":$iceLite}""".trimIndent()
    }

    fun getParsedSctpParameters(sctpParams: SdpInfo.ProtoSctpParameters): String {
        return """
        {
            "port": ${sctpParams.port},
            "OS": ${sctpParams.os},
            "MIS": ${sctpParams.mis},
            "maxMessageSize": ${sctpParams.maxMessageSize}
        }
    """.trimIndent()
    }

    fun getParsedIceCandidatesList(protoIceCandidateList: List<SdpInfo.ProtoIceCandidates>): Any {
        return protoIceCandidateList.map { protoIceCandidate ->
            """{"foundation":"${protoIceCandidate.foundation}","priority":${protoIceCandidate.priority},"ip":"${protoIceCandidate.ip}","address":"${protoIceCandidate.address}","protocol":"${protoIceCandidate.protocol}","port":${protoIceCandidate.port},"type":"${protoIceCandidate.type}"}"""
        }
    }

    fun getParsedDtlsParameters(
        protoDtlsParametersList: List<SdpInfo.ProtoDtlsFingerPrints>, role: String
    ): String {
        val fingerprints = protoDtlsParametersList.joinToString(",") { protoDtlsParams ->
            """{"algorithm":"${protoDtlsParams.algorithm}","value":"${protoDtlsParams.value}"}"""
        }
        return """{"fingerprints":[$fingerprints],"role":"$role"}"""
    }

    fun getProtoSctpCapabilities(sctpCapabilities: String): SctpCapabilities.ProtoSctpCapabilities {
        val jsonSctpCapabilities = JsonUtils.toJsonObject(sctpCapabilities)
        val protoNumSctpStreams = SctpCapabilities.ProtoNumSctpStreams.newBuilder().apply {
            setOS(jsonSctpCapabilities.getJSONObject("numStreams").getInt("OS"))
            setMIS(jsonSctpCapabilities.getJSONObject("numStreams").getInt("MIS"))
        }.build()
        return SctpCapabilities.ProtoSctpCapabilities.newBuilder()
            .setNumStreams(protoNumSctpStreams).build()
    }

    fun getProtoDtlsParameters(dtlsParameters: String): SdpInfo.ProtoDtlsParameters {
        val jsonDtlsParameters = JsonUtils.toJsonObject(dtlsParameters)
        val protoDtlsParametersBuilder = SdpInfo.ProtoDtlsParameters.newBuilder()
        val fingerprintsArray = jsonDtlsParameters.getJSONArray("fingerprints")
        for (i in 0 until fingerprintsArray.length()) {
            val fingerprintObject = fingerprintsArray.getJSONObject(i)
            val fingerprint = SdpInfo.ProtoDtlsFingerPrints.newBuilder().apply {
                setAlgorithm(fingerprintObject.getString("algorithm"))
                setValue(fingerprintObject.getString("value"))
            }.build()
            protoDtlsParametersBuilder.addFingerprints(fingerprint)
        }
        protoDtlsParametersBuilder.setRole(jsonDtlsParameters.getString("role"))
        return protoDtlsParametersBuilder.build()
    }


    fun parseHeaderExtensions(headerExtensions: List<RtpCapabilities.ProtoRtpHeaderExtension>): String {
        return headerExtensions.joinToString(",", "{headerExtensions:[", "]}") { header ->
            """{
            kind:"${header.kind}",
            uri:"${header.uri}",
            preferredId:${header.preferredId},
            direction:"${header.direction}"
        }""".replace("\\s".toRegex(), "")
        }
    }

    fun parseCodecs(codecs: List<RtpCapabilities.ProtoRtpCodecCapability>): String {
        return codecs.joinToString(",", "{codecs:[", "]}") { codec ->
            val parametersJson = if (codec.parametersMap.isNotEmpty()) {
                codec.parametersMap.entries.joinToString(",", "{", "}") { entry ->
                    """"${entry.key}":${entry.value}"""
                }
            } else "{}"

            val rtcpFeedbackJson = if (codec.rtcpFeedbackList.isNotEmpty()) {
                codec.rtcpFeedbackList.joinToString(",", "[", "]") { feedback ->
                    val parameterPart = feedback.parameter?.let { """"parameter":"$it",""" } ?: ""
                    """{$parameterPart"type":"${feedback.type}"}"""
                }
            } else "[]"

            """{
            kind:"${codec.kind}",
            mimeType:"${codec.mimeType}",
            preferredPayloadType:${codec.preferredPayloadType},
            clockRate:${codec.clockRate},
            ${"channels:${codec.channels},"}
            parameters:$parametersJson,
            rtcpFeedback:$rtcpFeedbackJson
        }""".replace("\\s".toRegex(), "")
        }
    }

    fun parseProtoAppData(appData: String): AppDataOuterClass.AppData {
        val appDataMap = jsonToMap(appData)
        val appDataProtoMap = convertToProtobufMap(appDataMap)
        val protoAppData = AppDataOuterClass.AppData.newBuilder().putAllAppData(
            appDataProtoMap
        )
        return protoAppData.build()
    }

    private fun jsonToMap(jsonString: String): Map<Any, Any> {
        val jsonObject = JSONObject(jsonString)
        return jsonObject.toMap()
    }

    private fun JSONObject.toMap(): Map<Any, Any> {
        val map = mutableMapOf<Any, Any>()
        val keys = this.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = this.get(key)
            map[key] = when (value) {
                is JSONObject -> value.toMap()
                else -> value
            }
        }
        return map
    }

    fun parseRtpCapabilities(
        codecs: List<RtpCapabilities.ProtoRtpCodecCapability>,
        headerExtensions: List<RtpCapabilities.ProtoRtpHeaderExtension>
    ): String {
        fun parseCodec(codec: RtpCapabilities.ProtoRtpCodecCapability): String {

            val parametersJson = if (codec.parametersMap.isNotEmpty()) {
                codec.parametersMap.entries.joinToString(",", "{", "}") { entry ->
                    if (entry.key == "apt" && entry.value is String) {
                        try {
                            val intValue = (entry.value as String).toInt()
                            """"${entry.key}":$intValue"""
                        } catch (e: NumberFormatException) {
                            """"${entry.key}":"${entry.value}""""
                        }
                    } else {
                        if (entry.value is String) {
                            """"${entry.key}":"${entry.value}""""
                        } else {
                            """"${entry.key}":${entry.value}"""
                        }
                    }
                }
            } else null
            val rtcpFeedbackJson = if (codec.rtcpFeedbackList.isNotEmpty()) {
                codec.rtcpFeedbackList.joinToString(
                    separator = ",", prefix = "[", postfix = "]"
                ) { feedback ->
                    val parameterPart = feedback.parameter?.let { "\"parameter\":\"$it\"," } ?: ""
                    """{$parameterPart"type":"${feedback.type}"}"""
                }.replace("\\s".toRegex(), "")
            } else "[]"

            return """{
            "kind":"${codec.kind}",
            "mimeType":"${codec.mimeType}",
            "preferredPayloadType":${codec.preferredPayloadType},
            "clockRate":${codec.clockRate},
            "channels":${codec.channels},
            "parameters":$parametersJson,
            "rtcpFeedback":$rtcpFeedbackJson
        }""".replace("\\s".toRegex(), "")
        }

        fun parseHeaderExtension(header: RtpCapabilities.ProtoRtpHeaderExtension): String {
            return """{
            "kind":"${header.kind}",
            "uri":"${header.uri}",
            "preferredId":${header.preferredId},
            "direction":"${header.direction}"
        }""".replace("\\s".toRegex(), "")
        }

        val codecsJson = codecs.joinToString(
            separator = ",", prefix = "\"codecs\":[", postfix = "]"
        ) { parseCodec(it) }
        val headerExtensionsJson = headerExtensions.joinToString(
            separator = ",", prefix = "\"headerExtensions\":[", postfix = "]"
        ) { parseHeaderExtension(it) }

        return "{$codecsJson,$headerExtensionsJson}".replace(
            "\\s".toRegex(), ""
        )
    }

    fun parseRtpParameters(
        codecs: List<RtpParameters.ProtoCodecParameters>,
        headerExtensions: List<RtpParameters.ProtoHeaderExtensionParameters>,
        encodings: List<RtpParameters.ProtoEncodings>,
        rtcp: RtpParameters.RtcpParameters,
        mid: String
    ): String {
        val codecsJson = codecs.joinToString(",", "\"codecs\":[", "]", transform = ::parseCodec)
        val headerExtensionsJson = headerExtensions.joinToString(
            ",", "\"headerExtensions\":[", "]", transform = ::parseHeaderExtension
        )
        val encodingsJson =
            encodings.joinToString(",", "\"encodings\":[", "]", transform = ::parseEncoding)
        val rtcpJson = parseRtcp(rtcp)

        return """{$codecsJson,$headerExtensionsJson,$encodingsJson,"rtcp":$rtcpJson,"mid":"$mid"}""".trimEnd(
            '}'
        ).plus('}')
    }

    private fun parseCodec(codec: RtpParameters.ProtoCodecParameters): String {
        val parametersJson =
            codec.parametersMap.entries.joinToString(",", "{", "}") { (key, value) ->
                """"$key":${value.toInt()}"""
            }

        val rtcpFeedbackJson = codec.rtcpFeedbackList.takeIf(List<*>::isNotEmpty)
            ?.joinToString(",", "[", "]") { """{"type":"${it.type}"}""" }

        return """{"mimeType":"${codec.mimeType}","payloadType":${codec.payloadType},"clockRate":${codec.clockRate},"channels":${codec.channels}${if (parametersJson.isNotEmpty()) ",\"parameters\":$parametersJson" else ""}${if (rtcpFeedbackJson != null) ",\"rtcpFeedback\":$rtcpFeedbackJson" else ""}}"""
    }

    private fun parseHeaderExtension(header: RtpParameters.ProtoHeaderExtensionParameters): String {
        return """{"uri":"${header.uri}","id":${header.id},"encrypt":${header.encrypt},"parameters":${header.parametersMap}}"""
    }

    private fun parseEncoding(encoding: RtpParameters.ProtoEncodings): String {
        val ssrc = "\"ssrc\":${encoding.ssrc}"
        val rtxPart = encoding.rtx?.let { ",\"rtx\":{\"ssrc\":${it.ssrc}}" } ?: ""
        return "{$ssrc$rtxPart}"
    }

    private fun parseRtcp(rtcp: RtpParameters.RtcpParameters): String {
        return """{"cname":"${rtcp.cname}","reducedSize":${rtcp.reducedSize}}"""
    }
}