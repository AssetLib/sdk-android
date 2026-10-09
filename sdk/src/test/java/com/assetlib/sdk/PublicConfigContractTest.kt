package com.assetlib.sdk

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PublicConfigContractTest(private val name: String, private val case: JsonObject) {
    @Test fun publicConfigurationAndPinnedEnvelopeContract() {
        val input = bytes(name)
        assertEquals(name, case.number("utf8Bytes", 0, Int.MAX_VALUE.toLong()).toInt(), input.size)
        val result = runCatching { PublicConfig.parse(strictUtf8(input)) }
        assertEquals("$name: ${case.string("reason")}: ${result.exceptionOrNull()}", case.string("parsing") == "accept", result.isSuccess)
        if (result.isFailure) return

        val config = result.getOrThrow()
        val original = objectJson(strictUtf8(input), 4096)
        val single = original["pinnedPublicKey"]?.jsonPrimitive?.content ?: original["pinnedPublicKeys"]!!.jsonArray.first().jsonPrimitive.content
        assertEquals(name, single, config.pinnedPublicKey)
        assertEquals(name, sha256(single.toByteArray(Charsets.UTF_8)).take(16), config.keyId)
        assertEquals(name, config, PublicConfig.parse(config.toJson()))
        for (value in case["envelopes"]!!.jsonArray) {
            val envelopeCase = value.jsonObject
            val envelopeName = envelopeCase.string("file")
            val verification = runCatching {
                verifyEnvelope(objectJson(strictUtf8(bytes(envelopeName)), Limits.MANIFEST_BYTES), config)
            }
            assertEquals("$name / $envelopeName: ${verification.exceptionOrNull()}", envelopeCase.string("verification") == "accept", verification.isSuccess)
        }
    }

    companion object {
        private fun bytes(name: String) = PublicConfigContractTest::class.java.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val cases = objectJson(strictUtf8(bytes("cases.json")), 100000)["publicConfigs"]!!.jsonArray
            require(cases.size == 43) { "The complete public-config contract corpus must be present." }
            return cases.map { value -> value.jsonObject.let { arrayOf(it.string("file"), it) } }
        }
    }
}

class PublicConfigSerializationTest {
    @Test fun nearLimitConfigurationsRoundTripWithoutAddingRedundantPins() {
        val input = javaClass.getResourceAsStream("/fixtures/public-config/sixteen-keys.json")!!.use { strictUtf8(it.readBytes()) }
        val original = objectJson(input, 4096)
        for ((lineBreaks, explicitSingle) in listOf(45 to false, 57 to false, 48 to true)) {
            val longPins = original["pinnedPublicKeys"]!!.jsonArray.map {
                JsonPrimitive(it.jsonPrimitive.content.replace("-----END PUBLIC KEY-----", "\n".repeat(lineBreaks) + "-----END PUBLIC KEY-----"))
            }
            val fields = original.toMutableMap().apply {
                remove("keyIds")
                put("pinnedPublicKeys", JsonArray(longPins))
                if (explicitSingle) put("pinnedPublicKey", longPins[1])
                put("unknownPadding", JsonPrimitive(""))
            }
            val paddingBytes = 4096 - JsonObject(fields).toString().toByteArray(Charsets.UTF_8).size
            require(paddingBytes > 0)
            fields["unknownPadding"] = JsonPrimitive("x".repeat(paddingBytes))
            val nearLimit = JsonObject(fields).toString()
            assertEquals(4096, nearLimit.toByteArray(Charsets.UTF_8).size)
            val config = PublicConfig.parse(nearLimit)
            val serialized = config.toJson()
            assertTrue(serialized.toByteArray(Charsets.UTF_8).size <= 4096)
            assertEquals(config, PublicConfig.parse(serialized))
            assertEquals(longPins.map { it.content }, config.pinnedPublicKeys)
            assertEquals(longPins[if (explicitSingle) 1 else 0].content, config.pinnedPublicKey)
        }
    }
}
