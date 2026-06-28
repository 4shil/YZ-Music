package com.music.yzmusic.data.discord

import android.content.Context
import com.music.yzmusic.R
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.artworkAt
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import java.util.Locale

/**
 * Publishes what's playing to Discord as a Rich Presence activity.
 *
 * This talks to Discord as a *user*, over the same gateway its own client
 * uses — there is no official API for a third-party app to set a user's
 * presence, so the token in [token] is the account's own bearer token and the
 * socket identifies itself as Discord Android (see [SuperProperties]). That is
 * the only way this feature can exist, and it is why the settings screen warns
 * about it before asking for a login.
 *
 * The presence Discord renders from one [updateSong] call:
 *
 * ```
 *   Listening to YZ Music          <- activityName, or the app's own name
 *   ┌────┐  Song title             <- details
 *   │art │  Artist                 <- state
 *   └────┘  ▁▁▁▁▁▁ 1:04 / 3:47     <- from the timestamps
 *   [ Listen on YouTube Music ]    <- button 1
 *   [ Visit YZ Music           ]   <- button 2
 * ```
 */
class DiscordRPC(
