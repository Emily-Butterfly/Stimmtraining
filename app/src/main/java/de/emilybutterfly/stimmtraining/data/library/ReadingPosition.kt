package de.emilybutterfly.stimmtraining.data.library

/** Bis wohin ein Text gelesen ist. */
sealed interface ReadingPosition {
    /**
     * Bis [offset] gelesen (exklusiv, Zeichenposition im [plainText][de.emilybutterfly.stimmtraining.data.document.TextDocument.plainText]).
     * [anchor] ist der zuletzt gelesene Satz; an ihm wird die Stelle wiedergefunden, wenn sich der Text ändert.
     */
    data class InText(val offset: Int, val anchor: String) : ReadingPosition

    /**
     * Auf Seite [page] (ab 0) bis zur Höhe [y] (0 = oben, 1 = unten) gelesen. [pageName] ist bei Fotos
     * der Dateiname der Seite, damit die Stelle ein Umsortieren der Seiten übersteht.
     */
    data class OnPage(val page: Int, val y: Float, val pageName: String? = null) : ReadingPosition
}
