package com.betterblue.kit

import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Region
import com.betterblue.kit.regions.hyundaicanada.HyundaiCanadaClient
import com.betterblue.kit.regions.hyundaieurope.HyundaiEuropeClient
import com.betterblue.kit.regions.hyundaiusa.HyundaiUsaClient
import com.betterblue.kit.regions.kiaeurope.KiaEuropeClient
import com.betterblue.kit.regions.kiausa.KiaUsaClient

/**
 * Creates the appropriate API client for a brand and region. Handles the core
 * Hyundai/Kia clients but NOT the fake client or the caching wrapper — the
 * consuming application composes those (see the app's own factory).
 */
fun createBetterBlueKitApiClient(config: ApiClientConfig): ApiClient = when (config.brand) {
    Brand.HYUNDAI -> createHyundaiClient(config)
    Brand.KIA -> createKiaClient(config)
    Brand.FAKE -> throw ApiException.regionNotSupported(
        "Fake brand is not supported by the BetterBlueKit API client factory",
    )
}

private fun createHyundaiClient(config: ApiClientConfig): ApiClient = when (config.region) {
    Region.USA -> HyundaiUsaClient(config)
    Region.CANADA -> HyundaiCanadaClient(config)
    Region.EUROPE -> HyundaiEuropeClient(config)
    Region.AUSTRALIA, Region.CHINA, Region.INDIA -> throw ApiException.regionNotSupported(
        "${Brand.HYUNDAI.displayName} is not yet supported in ${config.region.displayName}",
    )
}

private fun createKiaClient(config: ApiClientConfig): ApiClient = when (config.region) {
    Region.USA -> KiaUsaClient(config)
    Region.EUROPE -> KiaEuropeClient(config)
    Region.CANADA, Region.AUSTRALIA, Region.CHINA, Region.INDIA -> throw ApiException.regionNotSupported(
        "${Brand.KIA.displayName} is not yet supported in ${config.region.displayName}",
    )
}

/** The regions a brand supports today. */
fun supportedRegions(brand: Brand): List<Region> = when (brand) {
    Brand.HYUNDAI -> listOf(Region.USA, Region.CANADA, Region.EUROPE)
    Brand.KIA -> listOf(Region.USA, Region.EUROPE)
    Brand.FAKE -> Region.entries
}

/** Supported regions still considered beta, so the UI can warn. */
fun betaRegions(brand: Brand): List<Region> = when (brand) {
    Brand.HYUNDAI -> listOf(Region.CANADA, Region.EUROPE)
    Brand.KIA -> listOf(Region.EUROPE)
    Brand.FAKE -> emptyList()
}

/**
 * Whether a brand/region pair requires a BlueLink / Kia Connect service PIN
 * at account setup. A UI gate, not a support check — unsupported combinations
 * still answer (false).
 */
fun requiresPin(brand: Brand, region: Region): Boolean = when (brand) {
    Brand.HYUNDAI -> region == Region.USA || region == Region.CANADA || region == Region.EUROPE
    Brand.KIA -> region == Region.EUROPE
    Brand.FAKE -> false
}
