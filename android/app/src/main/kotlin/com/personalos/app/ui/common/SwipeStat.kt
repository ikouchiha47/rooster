package com.personalos.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch

/** One page of a [SwipeStat]. */
data class SwipeStatPage(
    val value: String,
    val suffix: String? = null,
    val color: Color = RadarColors.ink,
)

/**
 * A compact stat cell that pages horizontally when it holds more than one value.
 *
 * Used where a small Home cell has to carry N items that will not fit at once.
 * The label and a placeholder value always render, so the cell keeps its shape
 * while data loads instead of popping in.
 */
@Composable
fun SwipeStat(
    label: String,
    pages: List<SwipeStatPage>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(text = label.uppercase(), style = RadarType.micro, color = RadarColors.ink3)
        Spacer(Modifier.height(4.dp))

        when {
            pages.isEmpty() ->
                StatValue(
                    value = "--",
                    suffix = "loading",
                    valueStyle = RadarType.monoH2.copy(fontSize = 18.sp),
                )

            pages.size == 1 -> Value(pages[0])

            else -> {
                val state = rememberPagerState(pageCount = { pages.size })
                val scope = rememberCoroutineScope()

                Box(modifier = Modifier.fillMaxWidth()) {
                    HorizontalPager(
                        state = state,
                        pageSize = PageSize.Fill,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(VALUE_HEIGHT),
                    ) { index ->
                        Value(pages[index])
                    }
                }
                Spacer(Modifier.height(3.dp))
                CarouselDots(
                    count = pages.size,
                    current = state.currentPage,
                    onDotClick = { index -> scope.launch { state.animateScrollToPage(index) } },
                )
            }
        }
    }
}

@Composable
private fun Value(page: SwipeStatPage) {
    StatValue(
        value = page.value,
        suffix = page.suffix,
        valueStyle = RadarType.monoH2.copy(fontSize = 18.sp),
        valueColor = page.color,
    )
}

private val VALUE_HEIGHT = 26.dp
