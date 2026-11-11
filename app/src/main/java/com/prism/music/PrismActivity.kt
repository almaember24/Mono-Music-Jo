package com.prism.music

/**
 * The activity the manifest declares: [MainActivity] itself. Its launcher entries are activity-aliases, one per app
 * icon ([AppIcons]); the classic one keeps the name ".MainActivity". Open the app with this class, never with
 * `MainActivity::class` (that names the classic alias, which is off while another icon is picked).
 */
class PrismActivity : MainActivity()
