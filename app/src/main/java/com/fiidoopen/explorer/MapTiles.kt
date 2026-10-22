package com.fiidoopen.explorer

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * Fonds de carte sans clé API.
 * CyclOSM : rendu OpenStreetMap orienté vélo (pistes cyclables, dénivelé),
 * serveur communautaire — usage personnel raisonnable.
 */
object MapTiles {
    fun cyclosm(): OnlineTileSourceBase = object : OnlineTileSourceBase(
        "CyclOSM", 0, 20, 256, ".png",
        arrayOf(
            "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/",
            "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/",
            "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/"
        ),
        "© OpenStreetMap · CyclOSM"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                MapTileIndex.getX(pMapTileIndex) + "/" +
                MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
    }
}
