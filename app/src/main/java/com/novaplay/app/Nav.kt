package com.novaplay.app

/** Intent extras and tab names shared between notifications, shortcuts and MainActivity. */
object Nav {
    /** Which tab MainActivity should show when it is brought forward. */
    const val EXTRA_OPEN = "nova_open"

    /** A listing page (channel, category, search) MainActivity should open in the Home tab. */
    const val EXTRA_LISTING_URL = "nova_listing_url"
    const val EXTRA_LISTING_TITLE = "nova_listing_title"

    const val TAB_HOME = "home"
    const val TAB_SUBS = "subs"
    const val TAB_QUEUE = "queue"
    const val TAB_DOWNLOADS = "downloads"
    const val TAB_LIBRARY = "library"
}
