package com.netview.app.utils

/**
 * Maps EARFCN/UARFCN/ARFCN/NRARFCN to 3GPP band names.
 * Sources: 3GPP TS 36.101 (LTE), TS 38.104 (NR), TS 25.101 (WCDMA), TS 45.005 (GSM).
 */
object BandMapper {

    private data class Range(val band: String, val start: Int, val end: Int)

    // LTE downlink EARFCN ranges (TS 36.101 Table 5.7.3-1), FDD then TDD
    private val lteBands = listOf(
        // FDD
        Range("B1",  0,     599),
        Range("B2",  600,   1199),
        Range("B3",  1200,  1949),
        Range("B4",  1950,  2399),
        Range("B5",  2400,  2649),
        Range("B6",  2650,  2749),
        Range("B7",  2750,  3449),
        Range("B8",  3450,  3799),
        Range("B9",  3800,  4149),
        Range("B10", 4150,  4749),
        Range("B11", 4750,  4949),
        Range("B12", 5010,  5179),
        Range("B13", 5180,  5279),
        Range("B14", 5280,  5379),
        Range("B17", 5730,  5849),
        Range("B18", 5850,  5999),
        Range("B19", 6000,  6149),
        Range("B20", 6150,  6449),
        Range("B21", 6450,  6599),
        Range("B22", 6600,  7399),
        Range("B23", 7500,  7699),
        Range("B24", 7700,  8039),
        Range("B25", 8040,  8689),
        Range("B26", 8690,  9039),
        Range("B27", 9040,  9209),
        Range("B28", 9210,  9659),
        Range("B29", 9660,  9769),
        Range("B30", 9770,  9869),
        Range("B31", 9870,  9919),
        Range("B32", 9920,  10359),
        Range("B65", 65536, 66435),
        Range("B66", 66436, 67335),
        Range("B71", 68586, 68935),
        Range("B72", 69466, 70315),
        Range("B74", 73712, 74049),
        // TDD
        Range("B33", 36000, 36199),
        Range("B34", 36200, 36349),
        Range("B35", 36350, 36949),
        Range("B36", 36950, 37549),
        Range("B37", 37550, 37749),
        Range("B38", 37750, 38249),
        Range("B39", 38250, 38649),
        Range("B40", 38650, 39649),
        Range("B41", 39650, 41589),
        Range("B42", 41590, 43589),
        Range("B43", 43590, 45589),
        Range("B44", 45590, 46589),
        Range("B46", 46790, 54539),
        Range("B48", 55240, 56739),
        Range("B49", 56740, 58239),
    )

    fun lteBand(earfcn: Int?): String? {
        if (earfcn == null || earfcn < 0) return null
        return lteBands.firstOrNull { earfcn in it.start..it.end }?.band
    }

    // 5G NR downlink NRARFCN ranges (TS 38.104 Table 5.2-1).
    // More specific / common bands are listed before broader overlapping ones
    // so firstOrNull() picks the best match.
    private val nrBands = listOf(
        // Sub-6 FDD
        Range("n1",  422000, 434000),
        Range("n2",  386000, 398000),
        Range("n3",  361000, 376000),
        Range("n5",  173800, 178800),
        Range("n7",  524000, 538000),
        Range("n8",  185000, 192000),
        Range("n12", 145800, 149200),
        Range("n13", 149200, 151200),
        Range("n14", 151600, 153600),
        Range("n18", 172000, 175000),
        Range("n20", 158200, 164200),
        Range("n25", 386000, 399000),  // superset of n2
        Range("n26", 171800, 178800),
        Range("n28", 151600, 160600),
        Range("n70", 399000, 404000),
        Range("n71", 123400, 130400),
        Range("n74", 295000, 303600),
        Range("n66", 422000, 440000),  // AWS-3, superset of n1
        // Sub-6 TDD
        Range("n34", 402000, 405000),
        Range("n38", 514000, 524000),
        Range("n39", 376000, 384000),
        Range("n40", 460000, 480000),
        Range("n41", 499200, 537999),
        Range("n48", 636667, 646666),
        Range("n50", 286400, 303400),
        Range("n51", 285400, 286400),
        Range("n53", 496700, 499000),
        // n78 must come before n77 — n78 (3.3-3.8 GHz) is a subset of n77 (3.3-4.2 GHz)
        Range("n78", 620000, 653333),
        Range("n77", 653334, 680000),  // n77-only portion (3.8-4.2 GHz)
        Range("n79", 693334, 733333),
        // mmWave FR2
        Range("n257", 2054166, 2104165),
        Range("n258", 2016667, 2070832),
        Range("n260", 2229166, 2279165),
        Range("n261", 2070833, 2084999),
    )

    fun nrBand(nrarfcn: Int?): String? {
        if (nrarfcn == null || nrarfcn < 0) return null
        return nrBands.firstOrNull { nrarfcn in it.start..it.end }?.band
    }

    // UMTS downlink UARFCN ranges (TS 25.101 Table 5.1)
    private val wcdmaBands = listOf(
        Range("B1",  10562, 10838),  // 2100 MHz
        Range("B2",  9662,  9938),   // 1900 MHz PCS
        Range("B3",  1162,  1513),   // 1800 MHz DCS
        Range("B4",  1537,  1738),   // AWS
        Range("B5",  4357,  4458),   // 850 MHz
        Range("B6",  4387,  4413),   // 800 MHz Japan
        Range("B7",  2237,  2563),   // 2600 MHz
        Range("B8",  2937,  3088),   // 900 MHz
        Range("B9",  9237,  9387),   // 1700 MHz Japan
        Range("B10", 3112,  3388),   // AWS extended
        Range("B11", 3712,  3787),   // 1500 MHz Japan
        Range("B12", 3842,  3903),   // 700 A MHz
        Range("B13", 4017,  4043),   // 700 C MHz
        Range("B14", 4117,  4143),   // 700 PS MHz
        Range("B19", 712,   763),    // 800 MHz Japan
    )

    fun wcdmaBand(uarfcn: Int?): String? {
        if (uarfcn == null || uarfcn <= 0) return null
        return wcdmaBands.firstOrNull { uarfcn in it.start..it.end }?.band
    }

    /**
     * GSM band from ARFCN without country context.
     * ARFCN 512-810 is ambiguous (DCS 1800 or PCS 1900 depending on country);
     * returns "DCS 1800" as the more common default. Use gsmBandWithMcc for accuracy.
     */
    fun gsmBand(arfcn: Int?): String? = gsmBandWithMcc(arfcn, mcc = null)

    /**
     * GSM band from ARFCN with optional MCC for PCS 1900 / DCS 1800 disambiguation.
     * ARFCN 512-810 is PCS 1900 in North America and the Caribbean, DCS 1800 elsewhere.
     */
    fun gsmBandWithMcc(arfcn: Int?, mcc: String?): String? {
        if (arfcn == null || arfcn < 0) return null
        val mccInt = mcc?.toIntOrNull()
        val isPcs1900 = mccInt != null && (
            mccInt == 302 ||                   // Canada
            mccInt in 310..316 ||              // USA
            mccInt == 334 ||                   // Mexico
            mccInt in setOf(338, 342, 344, 346, 348, 350, 352, 354, 356, 358,
                            360, 362, 363, 364, 365, 366, 368, 370, 372, 374, 376)
        )
        return when (arfcn) {
            in 0..124, in 975..1023 -> "GSM 900"
            in 128..251             -> "GSM 850"
            in 512..810             -> if (isPcs1900) "PCS 1900" else "DCS 1800"
            in 811..885             -> "DCS 1800"
            else                    -> null
        }
    }

    /**
     * Bandwidth from LTE bandwidth indicator (PRB count).
     */
    fun lteBandwidthFromPrb(prb: Int): Double? = when (prb) {
        6   -> 1.4
        15  -> 3.0
        25  -> 5.0
        50  -> 10.0
        75  -> 15.0
        100 -> 20.0
        else -> null
    }
}
