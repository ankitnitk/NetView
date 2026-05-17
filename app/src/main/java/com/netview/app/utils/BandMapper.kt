package com.netview.app.utils

/**
 * Maps EARFCN (LTE) and NRARFCN (5G NR) to 3GPP band names.
 * Based on 3GPP TS 36.101 (LTE) and TS 38.101 (NR).
 */
object BandMapper {

    private data class Range(val band: String, val start: Int, val end: Int)

    // LTE downlink EARFCN ranges (TS 36.101 Table 5.7.3-1)
    private val lteBands = listOf(
        Range("B1", 0, 599),
        Range("B2", 600, 1199),
        Range("B3", 1200, 1949),
        Range("B4", 1950, 2399),
        Range("B5", 2400, 2649),
        Range("B7", 2750, 3449),
        Range("B8", 3450, 3799),
        Range("B11", 4750, 4949),
        Range("B12", 5010, 5179),
        Range("B13", 5180, 5279),
        Range("B14", 5280, 5379),
        Range("B17", 5730, 5849),
        Range("B18", 5850, 5999),
        Range("B19", 6000, 6149),
        Range("B20", 6150, 6449),
        Range("B21", 6450, 6599),
        Range("B25", 8040, 8689),
        Range("B26", 8690, 9039),
        Range("B28", 9210, 9659),
        Range("B29", 9660, 9769),
        Range("B30", 9770, 9869),
        Range("B32", 9920, 10359),
        Range("B38", 37750, 38249),
        Range("B39", 38250, 38649),
        Range("B40", 38650, 39649),
        Range("B41", 39650, 41589),
        Range("B42", 41590, 43589),
        Range("B43", 43590, 45589),
        Range("B46", 46790, 54539),
        Range("B48", 55240, 56739),
        Range("B66", 66436, 67335),
        Range("B71", 68586, 68935)
    )

    // 5G NR downlink NRARFCN ranges (approximate, TS 38.104)
    // Sub-6 only; mmWave bands omitted for brevity.
    private val nrBands = listOf(
        Range("n1", 422000, 434000),
        Range("n2", 386000, 398000),
        Range("n3", 361000, 376000),
        Range("n5", 173800, 178800),
        Range("n7", 524000, 538000),
        Range("n8", 185000, 192000),
        Range("n20", 158200, 164200),
        Range("n25", 386000, 399000),
        Range("n28", 151600, 160600),
        Range("n38", 514000, 524000),
        Range("n40", 460000, 480000),
        Range("n41", 499200, 537999),
        Range("n66", 422000, 440000),
        Range("n71", 123400, 130400),
        Range("n77", 620000, 680000),
        Range("n78", 620000, 653333),
        Range("n79", 693334, 733333)
    )

    fun lteBand(earfcn: Int?): String? {
        if (earfcn == null || earfcn < 0) return null
        return lteBands.firstOrNull { earfcn in it.start..it.end }?.band
    }

    fun nrBand(nrarfcn: Int?): String? {
        if (nrarfcn == null || nrarfcn < 0) return null
        return nrBands.firstOrNull { nrarfcn in it.start..it.end }?.band
    }

    // UMTS downlink UARFCN ranges (TS 25.101 Table 5.1)
    private val wcdmaBands = listOf(
        Range("B1", 10562, 10838),    // 2100 MHz
        Range("B2", 9662, 9938),      // 1900 MHz
        Range("B3", 1162, 1513),      // 1800 MHz
        Range("B4", 1537, 1738),      // AWS
        Range("B5", 4357, 4458),      // 850 MHz
        Range("B6", 4387, 4413),      // 800 Japan
        Range("B7", 2237, 2563),      // 2600 MHz
        Range("B8", 2937, 3088),      // 900 MHz
        Range("B9", 9237, 9387),      // 1700 Japan
        Range("B10", 3112, 3388),     // AWS extended
        Range("B19", 712, 763)        // 800 Japan
    )

    fun wcdmaBand(uarfcn: Int?): String? {
        if (uarfcn == null || uarfcn <= 0) return null
        return wcdmaBands.firstOrNull { uarfcn in it.start..it.end }?.band
    }

    /**
     * GSM band from ARFCN (TS 45.005). Returns short name like "GSM 900".
     */
    fun gsmBand(arfcn: Int?): String? {
        if (arfcn == null || arfcn < 0) return null
        return when (arfcn) {
            in 0..124, in 975..1023 -> "GSM 900"   // P-GSM 0-124, E-GSM 975-1023
            in 128..251 -> "GSM 850"
            in 512..885 -> "GSM 1800"              // DCS 1800 (also overlaps PCS 1900 numbering on some)
            in 886..1024 -> "GSM 1900"             // PCS 1900
            else -> null
        }
    }

    /**
     * Bandwidth from LTE bandwidth indicator (PRB count).
     */
    fun lteBandwidthFromPrb(prb: Int): Double? = when (prb) {
        6 -> 1.4
        15 -> 3.0
        25 -> 5.0
        50 -> 10.0
        75 -> 15.0
        100 -> 20.0
        else -> null
    }
}
