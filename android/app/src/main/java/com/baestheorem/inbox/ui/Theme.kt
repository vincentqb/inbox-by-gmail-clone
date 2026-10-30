package com.baestheorem.inbox.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.baestheorem.inbox.R

// Inbox by Gmail reconstructed tokens; kept in lockstep with static/style.css
// and ios/Sources/Theme.swift.
object Theme {
    val blue = Color(0xFF4285F4)
    val blueDark = Color(0xFF3367D6)
    val fabRed = Color(0xFFD23F31)
    val doneGreen = Color(0xFF0F9D58)
    val snoozeYellow = Color(0xFFF4B400)
    val pinYellow = Color(0xFFF4B400)
    val pageBg = Color(0xFFF2F2F2)
    val cardBg = Color.White
    val divider = Color(0xFFDDDDDD)
    val textPrimary = Color(0xFF212121)
    val textSecondary = Color(0xFF5F6368)
    val textFaint = Color(0xFF9AA0A6)
    val unreadBg = Color(0xFFF4F8FE)
    val snackBg = Color(0xFF323232)
    val snackAction = Color(0xFF8AB4F8)
    val chipBg = Color(0xFFF1F3F4)
    val chipBorder = Color(0xFFE0E0E0)
    val chipText = Color(0xFF3C4043)
    val navActiveBg = Color(0xFFFCE8E6)
    val bannerBg = Color(0xFFFEF7E0)
    val bannerBorder = Color(0xFFFDE293)
    val bannerText = Color(0xFF5F4B00)
    val bannerIcon = Color(0xFFB06000)
    val sunYellow = Color(0xFFF4C20D)

    val roboto = FontFamily(
        Font(R.font.roboto_regular, FontWeight.Normal),
        Font(R.font.roboto_medium, FontWeight.Medium),
        Font(R.font.roboto_bold, FontWeight.Bold),
    )
    val materialIcons = FontFamily(Font(R.font.material_icons))
}

/** Roboto at a size and weight, matching the web client's type scale. */
@Composable
fun robotoStyle(size: Int, weight: FontWeight = FontWeight.Normal) =
    LocalTextStyle.current.copy(
        fontFamily = Theme.roboto,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size * 1.35).sp,
    )

/**
 * A Material Icons glyph by name, rendered from the bundled font by codepoint
 * (no ligature dependence). Same icon vocabulary as the web client's
 * `<i class="material-icons">` spans.
 */
@Composable
fun MIcon(
    name: String,
    size: Int = 20,
    color: Color = Theme.textSecondary,
    modifier: Modifier = Modifier,
) {
    val cp = MaterialCodepoints.map[name] ?: MaterialCodepoints.map["help_outline"] ?: 0xE8FD
    Text(
        text = String(Character.toChars(cp)),
        fontFamily = Theme.materialIcons,
        fontSize = size.sp,
        lineHeight = size.sp,
        color = color,
        modifier = modifier,
    )
}

/** One row of a dropdown menu: Material glyph on the left, plain Roboto label. */
@Composable
fun MenuRow(label: String, icon: String, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = {
            Text(label, fontFamily = Theme.roboto, fontSize = 14.sp, color = Theme.textPrimary)
        },
        leadingIcon = { MIcon(icon, size = 18) },
        onClick = onClick,
    )
}

/** Unicode PUA codepoints for the glyph set the UI uses. */
object MaterialCodepoints {
    val map: Map<String, Int> = mapOf(
        "add" to 0xE145,
        "all_inclusive" to 0xEB3D,
        "arrow_back" to 0xE5C4,
        "arrow_forward" to 0xE5C8,
        "attach_file" to 0xE226,
        "attach_money" to 0xE227,
        "block" to 0xE14B,
        "check_box" to 0xE834,
        "close" to 0xE5CD,
        "done" to 0xE876,
        "done_all" to 0xE877,
        "drafts" to 0xE151,
        "edit_calendar" to 0xE742,
        "event" to 0xE878,
        "flight" to 0xE539,
        "forum" to 0xE0BF,
        "help_outline" to 0xE8FD,
        "inbox" to 0xE156,
        "local_offer" to 0xE54E,
        "local_shipping" to 0xE558,
        "loyalty" to 0xE89A,
        "markunread" to 0xE159,
        "menu" to 0xE5D2,
        "more_vert" to 0xE5D4,
        "move_to_inbox" to 0xE168,
        "notifications" to 0xE7F4,
        "open_in_new" to 0xE89E,
        "outbox" to 0xEF5F,
        "payments" to 0xEF63,
        "people" to 0xE7FB,
        "playlist_add_check" to 0xE065,
        "push_pin" to 0xF10D,
        "receipt_long" to 0xEF6E,
        "report" to 0xE160,
        "schedule" to 0xE8B5,
        "search" to 0xE8B6,
        "send" to 0xE163,
        "settings" to 0xE8B8,
        "today" to 0xE8DF,
        "unfold_more" to 0xE5D7,
        "unsubscribe" to 0xE0EB,
        "wb_sunny" to 0xE430,
        "wb_twilight" to 0xE1C6,
        "weekend" to 0xE16B,
    )
}
