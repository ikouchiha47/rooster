package com.personalos.app.ui.article

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.MentionEntity
import com.personalos.app.data.TaggedEvent
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.TagLine
import com.personalos.app.ui.common.sourceDisplayName
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType

/**
 * Reads an article in-app.
 *
 * Javascript is enabled **here and only here**. The no-JS rule in the ADR is
 * about *ingestion* - never rendering a page to scrape it - but a reader that
 * cannot run the article's own scripts would fail on most news sites, which is
 * the whole point of opening it.
 *
 * The row's tap target is the **headline**; this screen is where it lands.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArticleScreen(
    eventId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getInstance(context) }

    var item by remember { mutableStateOf<TaggedEvent?>(null) }
    var mentions by remember { mutableStateOf<List<MentionEntity>>(emptyList()) }
    var resolved by remember { mutableStateOf(false) }
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(eventId) {
        item = database.eventDao().eventById(eventId)
        resolved = true
    }

    // One row, one read: mentions load beside the item, never on scroll.
    LaunchedEffect(item?.event?.ulid) {
        val ulid = item?.event?.ulid
        mentions = if (ulid == null) emptyList() else database.mentionDao().forItem(ulid)
    }

    val url = item?.event?.url?.takeIf { it.isNotBlank() }

    // Back walks the page history first, then leaves the screen - the same
    // contract as a browser tab.
    BackHandler {
        val web = webView
        if (web != null && web.canGoBack()) web.goBack() else onBack()
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RadarColors.paper2),
    ) {
        // Same header band the tiles use, so the reader matches the tile it was
        // opened from instead of introducing a shorter, cream-tinted variant.
        val tags = item?.tagList.orEmpty()
        RadarHeader(
            title =
                item
                    ?.event
                    ?.source
                    ?.let { sourceDisplayName(it).uppercase() }
                    .orEmpty(),
            accent = CategoryColors.Indigo,
            onBack = onBack,
            subtitle =
                if (tags.isEmpty() && mentions.isEmpty()) {
                    null
                } else {
                    {
                        TagLine(
                            tags = tags,
                            mentions = mentions,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        )
                    }
                },
        )

        when {
            !resolved -> Unit

            url == null ->
                Note("No link for this item.")

            pageError != null ->
                Note("Could not load this page.\n$pageError")

            else -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams =
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // Keep navigation inside the reader rather than
                            // kicking the user out to a browser on every tap.
                            webViewClient =
                                object : WebViewClient() {
                                    override fun onPageFinished(
                                        view: WebView?,
                                        url: String?,
                                    ) {
                                        pageLoading = false
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        // Sub-resource failures are noise; only a
                                        // failed main document is worth showing.
                                        if (request?.isForMainFrame == true) {
                                            pageError = error?.description?.toString()
                                            pageLoading = false
                                        }
                                    }
                                }
                            loadUrl(url)
                            webView = this
                        }
                    },
                )
                if (pageLoading) {
                    Text(
                        text = "LOADING",
                        style = RadarType.micro,
                        color = RadarColors.ink3,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = RadarType.body,
        color = RadarColors.ink2,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
    )
}
