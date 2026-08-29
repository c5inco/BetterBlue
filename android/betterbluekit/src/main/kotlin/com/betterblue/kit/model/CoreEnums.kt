package com.betterblue.kit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Brand {
    @SerialName("hyundai")
    HYUNDAI,

    @SerialName("kia")
    KIA,

    @SerialName("fake")
    FAKE,
    ;

    val displayName: String
        get() = when (this) {
            HYUNDAI -> "Hyundai"
            KIA -> "Kia"
            FAKE -> "Fake (Testing)"
        }

    companion object {
        fun availableBrands(username: String = "", password: String = ""): List<Brand> =
            if (isTestAccount(username, password)) entries else listOf(HYUNDAI, KIA)

        fun hyundaiBaseUrl(region: Region): String = when (region) {
            Region.USA -> "https://api.telematics.hyundaiusa.com"
            Region.CANADA -> "https://mybluelink.ca"
            Region.EUROPE -> "https://prd.eu-ccapi.hyundai.com:8080"
            Region.AUSTRALIA -> "https://au-apigw.ccs.hyundai.com.au:8080"
            Region.CHINA -> "https://prd.cn-ccapi.hyundai.com"
            Region.INDIA -> "https://prd.in-ccapi.hyundai.connected-car.io:8080"
        }

        fun kiaBaseUrl(region: Region): String = when (region) {
            Region.USA -> "https://api.owners.kia.com"
            Region.CANADA -> "https://kiaconnect.ca"
            Region.EUROPE -> "https://prd.eu-ccapi.kia.com:8080"
            Region.AUSTRALIA -> "https://au-apigw.ccs.kia.com.au:8082"
            Region.CHINA -> "https://prd.cn-ccapi.kia.com"
            Region.INDIA -> "https://prd.in-ccapi.kia.connected-car.io:8080"
        }
    }
}

fun isTestAccount(username: String, password: String): Boolean =
    username.lowercase() == "testaccount@betterblue.com" && password == "betterblue"

@Serializable
enum class FuelType {
    @SerialName("gas")
    GAS,

    @SerialName("electric")
    ELECTRIC,

    @SerialName("phev")
    PHEV,
    ;

    /** Whether this fuel type has electric/EV capability (true for both pure EV and PHEV). */
    val hasElectricCapability: Boolean get() = this != GAS

    companion object {
        fun fromNumber(number: Int): FuelType = when (number) {
            0 -> GAS
            1 -> PHEV
            2 -> ELECTRIC
            else -> GAS
        }
    }
}

@Serializable
enum class Region {
    @SerialName("US")
    USA,

    @SerialName("CA")
    CANADA,

    @SerialName("EU")
    EUROPE,

    @SerialName("AU")
    AUSTRALIA,

    @SerialName("CN")
    CHINA,

    @SerialName("IN")
    INDIA,
    ;

    fun apiBaseUrl(brand: Brand): String = when (brand) {
        Brand.HYUNDAI -> Brand.hyundaiBaseUrl(this)
        Brand.KIA -> Brand.kiaBaseUrl(this)
        Brand.FAKE -> "https://fake.api.testing.com"
    }

    val displayName: String
        get() = when (this) {
            USA -> "USA"
            CANADA -> "Canada"
            EUROPE -> "Europe"
            AUSTRALIA -> "Australia"
            CHINA -> "China"
            INDIA -> "India"
        }
}

@Serializable
sealed class VehicleMarketOptions {
    abstract val ccs2Supported: Boolean

    @Serializable
    @SerialName("hyundaiEurope")
    data class HyundaiEurope(override val ccs2Supported: Boolean) : VehicleMarketOptions()

    @Serializable
    @SerialName("kiaEurope")
    data class KiaEurope(override val ccs2Supported: Boolean) : VehicleMarketOptions()

    @Serializable
    @SerialName("generic")
    data object Generic : VehicleMarketOptions() {
        override val ccs2Supported: Boolean get() = false
    }
}
