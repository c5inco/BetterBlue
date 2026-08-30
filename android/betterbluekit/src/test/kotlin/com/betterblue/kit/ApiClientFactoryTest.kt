package com.betterblue.kit

import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Region
import com.betterblue.kit.regions.hyundaicanada.HyundaiCanadaClient
import com.betterblue.kit.regions.hyundaieurope.HyundaiEuropeClient
import com.betterblue.kit.regions.hyundaiusa.HyundaiUsaClient
import com.betterblue.kit.regions.kiaeurope.KiaEuropeClient
import com.betterblue.kit.regions.kiausa.KiaUsaClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ApiClientFactoryTest {
    private fun config(brand: Brand, region: Region) =
        ApiClientConfig(
            region = region,
            brand = brand,
            username = "test@example.com",
            password = "password123",
            pin = "0000",
            accountId = "00000000-0000-0000-0000-000000000001",
        )

    @Test
    fun `supported brand-region pairs build their client`() {
        assertTrue(createBetterBlueKitApiClient(config(Brand.HYUNDAI, Region.USA)) is HyundaiUsaClient)
        assertTrue(createBetterBlueKitApiClient(config(Brand.HYUNDAI, Region.CANADA)) is HyundaiCanadaClient)
        assertTrue(createBetterBlueKitApiClient(config(Brand.HYUNDAI, Region.EUROPE)) is HyundaiEuropeClient)
        assertTrue(createBetterBlueKitApiClient(config(Brand.KIA, Region.USA)) is KiaUsaClient)
        assertTrue(createBetterBlueKitApiClient(config(Brand.KIA, Region.EUROPE)) is KiaEuropeClient)
    }

    @Test
    fun `unsupported regions throw regionNotSupported`() {
        val unsupported =
            listOf(
                Brand.KIA to Region.CANADA,
                Brand.HYUNDAI to Region.AUSTRALIA,
                Brand.KIA to Region.AUSTRALIA,
                Brand.HYUNDAI to Region.CHINA,
                Brand.KIA to Region.INDIA,
            )
        for ((brand, region) in unsupported) {
            val e =
                assertThrows<ApiException>("$brand in $region") {
                    createBetterBlueKitApiClient(config(brand, region))
                }
            assertEquals(ApiErrorType.REGION_NOT_SUPPORTED, e.errorType, "$brand in $region")
        }
    }

    @Test
    fun `the fake brand is the app's responsibility, not the kit's`() {
        val e = assertThrows<ApiException> { createBetterBlueKitApiClient(config(Brand.FAKE, Region.USA)) }
        assertEquals(ApiErrorType.REGION_NOT_SUPPORTED, e.errorType)
    }

    @Test
    fun `supported and beta region lists`() {
        assertEquals(listOf(Region.USA, Region.CANADA, Region.EUROPE), supportedRegions(Brand.HYUNDAI))
        assertEquals(listOf(Region.USA, Region.EUROPE), supportedRegions(Brand.KIA))
        assertEquals(listOf(Region.CANADA, Region.EUROPE), betaRegions(Brand.HYUNDAI))
        assertEquals(listOf(Region.EUROPE), betaRegions(Brand.KIA))
    }

    @Test
    fun `requiresPin matrix`() {
        assertTrue(requiresPin(Brand.HYUNDAI, Region.USA))
        assertTrue(requiresPin(Brand.HYUNDAI, Region.CANADA))
        assertTrue(requiresPin(Brand.HYUNDAI, Region.EUROPE))
        assertTrue(requiresPin(Brand.KIA, Region.EUROPE))

        assertFalse(requiresPin(Brand.KIA, Region.USA))
        assertFalse(requiresPin(Brand.FAKE, Region.USA))
        // Unsupported combinations still answer — the helper is a UI gate,
        // not a regional-support check.
        assertFalse(requiresPin(Brand.KIA, Region.CANADA))
        assertFalse(requiresPin(Brand.KIA, Region.AUSTRALIA))
    }

    @Test
    fun `region base urls are https and brand-specific`() {
        for (region in listOf(Region.USA, Region.CANADA, Region.EUROPE)) {
            val hyundai = region.apiBaseUrl(Brand.HYUNDAI)
            val kia = region.apiBaseUrl(Brand.KIA)
            assertTrue(hyundai.startsWith("https://"), hyundai)
            assertTrue(kia.startsWith("https://"), kia)
            assertTrue(hyundai != kia)
        }
    }
}
