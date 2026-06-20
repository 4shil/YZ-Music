package com.music.yzmusic.ui.replay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.music.yzmusic.R
import com.music.yzmusic.ui.player.MeshGradientBackground
import com.music.yzmusic.ui.player.rememberArtworkColors
import java.util.Locale

/**
 * One headline number, as a card you could keep in a wallet.
 *
 * ## Why a card
 *
 * A Replay's four headline facts — minutes, artist, song, album — are four
 * unlike things, and a grid of tiles gives no clue that they are peers or that
 * any of them leads anywhere. A card is a thing people already know how to read:
 * it holds one fact, it has a *front* you look at rather than a list you scan,
 * and a row of them says "there are four of these" without a heading saying so.
 * Tapping one opens the story it summarises, which is the other half of why it
 * is a card — a card is an object, and objects can be picked up.
 *
 * ## Why it is built like a real one
 *
 * Every part of the anatomy is doing the job its real counterpart does, which is
 * what stops this reading as a rounded rectangle with a chip sticker on it:
 *
 *  - the **proportions** are the real ones, 85.6 × 54mm, so 1.586:1 — this alone
 *    accounts for most of the recognition;
 *  - the **cardholder line and member-since date** are the part that makes it
 *    *yours*. A card with nobody's name on it is a graphic; with a name on it,
 *    it is an artefact of an account, which is exactly what a year of listening
 *    is. There is no chip: a contact plate is the one piece of card anatomy
 *    that exists to be *inserted into something*, and on a card that is only
 *    ever looked at it reads as a sticker;
 *  - the **embossing** — a monospaced face, wide tracking, a dark offset shadow
 *    under a pale gradient fill — is how a real card's raised type catches the
 *    light, and it is why those lines read as pressed into the card rather than
 *    printed on it;
 *  - the **logo sits top-right**, opposite what the card is for, so the two
 *    corners of the head each carry one fact and neither is a caption for the
 *    other.
 *
 * ## Why the background is the player's own mesh
 *
 * Sampled from the artwork this card is about, so no two people's cards look
 * alike and each one is lit by the record it is describing. It is drawn once
 * and held still — [MeshGradientBackground]'s `animated = false` — rather
 * than crossfading or drifting, since a row of these redrawing a blurred
 * layer every time a card is opened or swiped past is the expensive case the
 * class note there warns about, multiplied by however many cards are on
 * screen.
 */
@Composable
fun ReplayCreditCard(
    label: String,
    value: String,
    detail: String?,
    artworkUrl: String?,
    /** Whose card it is. Empty falls back to [DEFAULT_HOLDER]. */
    holder: String,
    /** `MM/YY`, or null when there is nothing to date it from. */
    memberSince: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberArtworkColors(artworkUrl)
    Box(
        modifier = modifier
            .aspectRatio(CARD_RATIO)
            .shadow(16.dp, CardShape, clip = false)
            .clip(CardShape)
            .clickable(onClick = onClick),
    ) {
        MeshGradientBackground(
            palette = palette,
            trackKey = artworkUrl ?: label,
            blurRadius = 34.dp,
            animated = false,
        )
        // Deepened towards the foot, where the embossed lines are: the mesh is
        // built to be bright and those lines are pale, and without this the
        // cardholder name lands on whichever blob happened to drift under it.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.18f),
                        0.55f to Color.Black.copy(alpha = 0.30f),
                        1.0f to Color.Black.copy(alpha = 0.55f),
                    ),
                ),
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
            // The two things a card says about itself, at the two corners a card
            // says them from: what it is on the left, whose it is on the right.
            // The wordmark that used to sit here is gone — the logo says it, and
            // saying it twice on an object this small is the difference between
            // a card and an advert.
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "YOUR LISTENING\nEXPERIENCE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 1.4.sp,
                    lineHeight = 13.sp,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(width = 34.dp, height = 22.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 27.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.W800,
                    brush = PolishedInk,
                    shadow = EmbossShadow,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
