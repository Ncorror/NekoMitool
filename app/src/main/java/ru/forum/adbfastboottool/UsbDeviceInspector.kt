package ru.forum.adbfastboottool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

object UsbDeviceInspector {

    enum class Mode(val label: String) {
        FASTBOOT("Fastboot"),
        ADB("ADB")
    }

    data class Candidate(
        val device: UsbDevice,
        val mode: Mode,
        val interfaceIndex: Int,
        val endpointInAddress: Int?,
        val endpointOutAddress: Int?
    ) {
        val stableKey: String
            get() = "${device.deviceName}:${device.vendorId}:${device.productId}:${mode.name}:$interfaceIndex"

        fun displayTitle(index: Int? = null): String {
            val prefix = index?.let { "$it. " } ?: ""
            val name = device.productName ?: device.deviceName ?: "Android USB device"
            return "$prefix${mode.label} — $name"
        }

        fun displaySubtitle(): String {
            val vid  = device.vendorId.toString(16).uppercase().padStart(4, '0')
            val pid  = device.productId.toString(16).uppercase().padStart(4, '0')
            val inEp  = endpointInAddress?.let  { "0x${it.toString(16).uppercase().padStart(2, '0')}" } ?: "нет"
            val outEp = endpointOutAddress?.let { "0x${it.toString(16).uppercase().padStart(2, '0')}" } ?: "нет"
            return "VID:PID=$vid:$pid | interface=$interfaceIndex | IN=$inEp | OUT=$outEp"
        }
    }

    fun findCandidates(device: UsbDevice): List<Candidate> {
        val result = mutableListOf<Candidate>()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val mode = detectMode(iface) ?: continue
            val endpoints = bulkEndpoints(iface)
            result.add(
                Candidate(
                    device = device,
                    mode = mode,
                    interfaceIndex = i,
                    endpointInAddress  = endpoints.first,
                    endpointOutAddress = endpoints.second
                )
            )
        }
        return result
    }

    /**
     * FIX #14: дедупликация по deviceName + mode — устройство с двумя интерфейсами
     * (например Samsung Download Mode) не появится в списке дважды.
     */
    fun findAllCandidates(devices: Collection<UsbDevice>): List<Candidate> {
        val seen = mutableSetOf<String>()
        return devices
            .flatMap { findCandidates(it) }
            .filter { candidate ->
                val dedupeKey = "${candidate.device.deviceName}:${candidate.mode.name}"
                seen.add(dedupeKey)  // добавляет и возвращает true только при первом добавлении
            }
            .sortedWith(compareBy<Candidate> { it.mode.name }.thenBy { it.device.productName ?: it.device.deviceName })
    }

    fun summarizeDevice(device: UsbDevice): String {
        val vid  = device.vendorId.toString(16).uppercase().padStart(4, '0')
        val pid  = device.productId.toString(16).uppercase().padStart(4, '0')
        val name = device.productName ?: device.deviceName ?: "неизвестно"
        val lines = mutableListOf<String>()
        lines.add("Device: $name")
        lines.add("VID:PID: $vid:$pid")
        lines.add("DeviceName: ${device.deviceName}")
        lines.add("Interfaces: ${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val mode = detectMode(iface)?.label ?: "unknown"
            lines.add("  Interface $i: class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} protocol=${iface.interfaceProtocol} mode=$mode endpoints=${iface.endpointCount}")
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                val direction = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) "bulk" else "type=${ep.type}"
                lines.add("    Endpoint $e: address=0x${ep.address.toString(16).uppercase().padStart(2, '0')} direction=$direction $type maxPacket=${ep.maxPacketSize}")
            }
        }
        return lines.joinToString("\n")
    }

    fun summarizeCandidates(candidates: List<Candidate>): String {
        if (candidates.isEmpty()) return "Совместимые ADB/Fastboot USB-устройства не найдены."
        return candidates.mapIndexed { index, candidate ->
            candidate.displayTitle(index + 1) + "\n" + candidate.displaySubtitle()
        }.joinToString("\n\n")
    }

    private fun detectMode(iface: UsbInterface): Mode? {
        if (iface.interfaceClass != 255 || iface.interfaceSubclass != 66) return null
        return when (iface.interfaceProtocol) {
            3 -> Mode.FASTBOOT
            1 -> Mode.ADB
            else -> null
        }
    }

    private fun bulkEndpoints(iface: UsbInterface): Pair<Int?, Int?> {
        var inAddress: Int? = null
        var outAddress: Int? = null
        for (e in 0 until iface.endpointCount) {
            val ep: UsbEndpoint = iface.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN)  inAddress  = ep.address
                if (ep.direction == UsbConstants.USB_DIR_OUT) outAddress = ep.address
            }
        }
        return inAddress to outAddress
    }
}
