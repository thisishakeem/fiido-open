package com.fiidoopen.explorer

/**
 * Décodeur du protocole Fiido (reconstitué depuis une capture réelle).
 * Trame : 46 64 <op> <reg> ... <xor>.  op 0x55 = lecture, 0xAA = réponse/écriture.
 * Le vélo répond sur ffe1 avec 46 64 AA <reg> ...
 *
 * La trame d'état principale est le registre 0x26 (~44 octets). Les offsets précis
 * (batterie, vitesse) sont en cours de calage — on affiche les octets candidats.
 */
object FrameDecoder {
    fun tryDecode(b: ByteArray): String? {
        if (b.size < 5) return null
        if (b[0].toInt() and 0xFF != 0x46 || b[1].toInt() and 0xFF != 0x64) return null
        val op = b[2].toInt() and 0xFF
        val reg = b[3].toInt() and 0xFF
        // vérifie le XOR
        var x = 0
        for (i in 0 until b.size - 1) x = x xor (b[i].toInt() and 0xFF)
        val crcOk = (x and 0xFF) == (b[b.size - 1].toInt() and 0xFF)
        val tag = if (crcOk) "" else "  [xor KO]"

        if (op == 0xAA && reg == 0x26) {
            // trame d'état : data commence après 46 64 AA 26 <arg>
            val d = b.copyOfRange(5, b.size - 1)
            fun u8(i: Int) = if (i < d.size) d[i].toInt() and 0xFF else -1
            fun u16(i: Int) = if (i + 1 < d.size) ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF) else -1
            val sb = StringBuilder("ÉTAT reg0x26$tag\n")
            // candidats plausibles (à confirmer contre l'appli officielle)
            sb.append("     batterie? ")
            for (i in d.indices) {
                val v = u8(i)
                if (v in 1..100) sb.append("@$i=$v% ")
            }
            sb.append("\n     u16 candidats: ")
            for (i in listOf(6, 8, 10, 18, 20)) {
                if (i + 1 < d.size) sb.append("@$i=${u16(i)} ")
            }
            return sb.toString()
        }
        val human = when (reg) {
            0x0D -> "infos/version"
            0x1C -> "table vitesses"
            0x0C -> "compteur/état"
            0x04 -> "état"
            else -> "reg 0x%02X".format(reg)
        }
        return "réponse $human$tag"
    }
}
