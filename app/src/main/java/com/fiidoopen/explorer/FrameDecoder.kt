package com.fiidoopen.explorer

/**
 * Décodeur best-effort. À ce stade on NE connaît PAS encore le format exact des
 * trames Fiido (construites dynamiquement, avec checksum). Ce décodeur affiche
 * donc des hypothèses lisibles (octets candidats batterie/vitesse) pour aider à
 * reconnaître la structure une fois qu'on voit des trames réelles.
 *
 * Il sera remplacé par le vrai parseur dès réception des logs du vélo.
 */
object FrameDecoder {
    fun tryDecode(b: ByteArray): String? {
        if (b.isEmpty()) return null
        val sb = StringBuilder()
        // En-tête éventuel
        sb.append("hdr=0x%02X".format(b[0]))
        // Hypothèses: beaucoup de vélos encodent batterie (0-100) et vitesse sur 1-2 octets.
        val batteryCandidates = b.indices.filter { b[it].toInt() and 0xFF in 1..100 }
        if (batteryCandidates.isNotEmpty()) {
            sb.append("  batt?=")
            sb.append(batteryCandidates.joinToString(",") {
                "@$it:${b[it].toInt() and 0xFF}%"
            })
        }
        // somme de contrôle simple (XOR / somme) pour tester l'hypothèse checksum
        val sum = b.dropLast(1).fold(0) { a, x -> a + (x.toInt() and 0xFF) } and 0xFF
        val xor = b.dropLast(1).fold(0) { a, x -> a xor (x.toInt() and 0xFF) } and 0xFF
        val last = b.last().toInt() and 0xFF
        if (last == sum) sb.append("  [checksum=SUM ok]")
        if (last == xor) sb.append("  [checksum=XOR ok]")
        return sb.toString()
    }
}
