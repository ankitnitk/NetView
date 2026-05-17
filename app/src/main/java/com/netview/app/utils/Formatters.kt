package com.netview.app.utils

import android.telephony.CellInfo
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.TelephonyManager

object Formatters {

    fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN,
        TelephonyManager.NETWORK_TYPE_GSM -> "2G"

        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"

        TelephonyManager.NETWORK_TYPE_LTE,
        TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"

        TelephonyManager.NETWORK_TYPE_NR -> "5G"

        else -> "Unknown"
    }

    /**
     * Detect NSA vs SA vs LTE based on serviceState NR fields.
     */
    fun radioMode(dataType: Int, serviceState: ServiceState?): String {
        val baseType = networkTypeName(dataType)
        if (baseType != "5G" && serviceState == null) return baseType

        val nrState = nrStateSafe(serviceState)
        // ServiceState.NRSTATE_CONNECTED == 3 on most builds
        return when {
            baseType == "5G" -> "5G SA"
            dataType == TelephonyManager.NETWORK_TYPE_LTE && nrState == 3 -> "5G NSA"
            baseType == "4G" && nrState == 2 -> "4G (5G ready)" // NRSTATE_NOT_RESTRICTED
            else -> baseType
        }
    }

    private fun nrStateSafe(ss: ServiceState?): Int {
        if (ss == null) return -1
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return -1
        return try {
            val method = ss.javaClass.getMethod("getNrState")
            method.invoke(ss) as? Int ?: -1
        } catch (e: Throwable) { -1 }
    }

    /** Decode LTE eNB and sector from CID (CID = eNB*256 + sector). */
    fun lteEnbId(cid: Long?): Long? =
        if (cid == null || cid <= 0 || cid == CellInfo.UNAVAILABLE.toLong()) null
        else cid / 256

    fun lteSectorId(cid: Long?): Int? =
        if (cid == null || cid <= 0 || cid == CellInfo.UNAVAILABLE.toLong()) null
        else (cid % 256).toInt()

    /** Decode 5G gNB from NCI. NCI = (gNB << X) | sector. Default sector length = 14 bits. */
    fun nrGnbId(nci: Long?, sectorBits: Int = 14): Long? {
        if (nci == null || nci <= 0) return null
        return nci shr sectorBits
    }

    /** Format a signed integer with sign, or em-dash if null/unavailable. */
    fun signedOrDash(value: Int?, unit: String = ""): String {
        if (value == null || value == CellInfo.UNAVAILABLE) return "—"
        val u = if (unit.isNotEmpty()) " $unit" else ""
        return "$value$u"
    }

    fun longOrDash(value: Long?): String =
        if (value == null || value <= 0 || value == CellInfo.UNAVAILABLE.toLong()) "—"
        else value.toString()

    fun intOrDash(value: Int?): String =
        if (value == null || value == CellInfo.UNAVAILABLE) "—" else value.toString()

    fun stringOrDash(value: String?): String =
        if (value.isNullOrBlank()) "—" else value
}
