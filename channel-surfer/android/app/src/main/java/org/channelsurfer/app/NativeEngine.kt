package org.channelsurfer.app

object NativeEngine {
    init { System.loadLibrary("channel_surfer") }
    external fun load(m3u: String): Int
    external fun visible(query: String, group: String, favoritesOnly: Boolean): String
    external fun navigate(current: Int, direction: Int, query: String, group: String, favoritesOnly: Boolean): String
    external fun random(current: Int, query: String, group: String, favoritesOnly: Boolean): String
    external fun toggleFavorite(id: Int): Boolean
    external fun favorites(): String
    external fun restoreFavorites(json: String)
    external fun categories(): String
    external fun hide(id: Int)
    external fun hidden(): String
    external fun restoreHidden(json: String)
    external fun exportVisible(query: String, group: String, favoritesOnly: Boolean): String
}
