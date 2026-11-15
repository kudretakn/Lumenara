package dev.lumenara.design

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor

/** Original, consistent 24 x 24 outlines, with 2-unit rounded strokes. */
enum class LumenIcon(val drawableRes: Int) {
    Tv(R.drawable.ic_lumen_tv),
    Film(R.drawable.ic_lumen_film),
    Series(R.drawable.ic_lumen_series),
    List(R.drawable.ic_lumen_list),
    Bookmark(R.drawable.ic_lumen_bookmark),
    Search(R.drawable.ic_lumen_search),
    Settings(R.drawable.ic_lumen_settings),
    Play(R.drawable.ic_lumen_play),
    Plus(R.drawable.ic_lumen_plus),
    Heart(R.drawable.ic_lumen_heart),
    ChevronLeft(R.drawable.ic_lumen_chevron_left),
    ChevronRight(R.drawable.ic_lumen_chevron_right),
    ChevronUp(R.drawable.ic_lumen_chevron_up),
    ChevronDown(R.drawable.ic_lumen_chevron_down),
    Star(R.drawable.ic_lumen_star),
    Check(R.drawable.ic_lumen_check),
    Filter(R.drawable.ic_lumen_filter),
    Back(R.drawable.ic_lumen_back),
    Clock(R.drawable.ic_lumen_clock),
    Subtitles(R.drawable.ic_lumen_subtitles),
    Audio(R.drawable.ic_lumen_audio),
    Timer(R.drawable.ic_lumen_timer),
    Info(R.drawable.ic_lumen_info),
}

object IconSizes {
    val row = 24.dp
    val hub = 32.dp
    val meta = 20.dp
}

/** Pass null for decorative icons; label icon-only actions for accessibility. */
@Composable
fun LumenIcon(
    icon: LumenIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = IconSizes.row,
) {
    Icon(
        painter = painterResource(icon.drawableRes),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}
