/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.GlassTopAppBar
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain

private const val BUY_ME_A_COFFEE_URL = "https://buymeacoffee.com/naklirajveer"
private const val UPI_ID = "naklirajveer@fam"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(navController: NavController) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val copiedMessage = stringResource(R.string.copied_to_clipboard)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding())
                .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.support_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        Spacer(Modifier.height(8.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.support_ways),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.favorite),
                        title = { Text(stringResource(R.string.buy_me_a_coffee)) },
                        description = { Text(stringResource(R.string.buy_me_a_coffee_desc)) },
                        trailingContent = {
                            Icon(
                                painter = painterResource(R.drawable.link),
                                contentDescription = null,
                            )
                        },
                        onClick = { uriHandler.openUri(BUY_ME_A_COFFEE_URL) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.content_copy),
                        title = { Text(stringResource(R.string.support_upi)) },
                        description = {
                            Text("${stringResource(R.string.support_upi_id)} · ${stringResource(R.string.support_upi_desc)}")
                        },
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("UPI ID", UPI_ID))
                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        },
                    ),
                ),
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.support_thanks),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))
    }

    GlassTopAppBar(
        title = { Text(stringResource(R.string.support)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
