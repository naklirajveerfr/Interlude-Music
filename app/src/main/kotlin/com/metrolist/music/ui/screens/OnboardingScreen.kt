/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.music.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.YouTube.SearchFilter.Companion.FILTER_ARTIST
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.OnboardingStepKey
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import com.metrolist.music.viewmodels.AccountSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

const val ONBOARDING_STEP_FOLLOW = 1
const val ONBOARDING_STEP_DONE = 2

private const val STAGE_LOADING = 0
private const val STAGE_WELCOME = 1
private const val STAGE_FOLLOW = 2
private const val STAGE_FINISH = 3

private val BUTTON_SPACING = 12.dp
private val LATER_SLOT_HEIGHT = 40.dp

private const val ARTISTS_TO_FOLLOW = 5
private const val LOADING_MIN_MS = 1500L
private const val FINISH_LOADING_MS = 4000L

/** Onboarding runs on a fresh install that has no account yet, or while it is already in progress. */
suspend fun isOnboardingNeeded(context: Context): Boolean {
    val prefs = context.dataStore.data.first()
    return when (prefs[OnboardingStepKey]) {
        null -> !prefs.hasLoginCookie()
        ONBOARDING_STEP_DONE -> false
        else -> true
    }
}

/**
 * Login restarts the app, so a login made during the welcome step records that the
 * follow-artists step comes next.
 */
fun MutablePreferences.advanceOnboardingAfterLogin() {
    if (this[OnboardingStepKey] == null) this[OnboardingStepKey] = ONBOARDING_STEP_FOLLOW
}

private fun Preferences.hasLoginCookie(): Boolean =
    this[InnerTubeCookieKey]?.let { "SAPISID" in parseCookieString(it) } ?: false

@Composable
fun OnboardingOverlay(
    navController: NavController,
    onFollowsSaved: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val onLoginScreen = backStackEntry?.destination?.route == "login"

    var checked by rememberSaveable { mutableStateOf(false) }
    var done by rememberSaveable { mutableStateOf(false) }
    var stage by rememberSaveable { mutableIntStateOf(STAGE_LOADING) }
    var showSignInOptions by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (checked) return@LaunchedEffect
        if (!isOnboardingNeeded(context)) {
            done = true
        } else {
            val step = context.dataStore.data.first()[OnboardingStepKey]
            delay(LOADING_MIN_MS)
            stage = if (step == ONBOARDING_STEP_FOLLOW) STAGE_FOLLOW else STAGE_WELCOME
        }
        checked = true
    }

    val visible = !done && !onLoginScreen

    BackHandler(enabled = visible) {
        if (stage == STAGE_WELCOME && showSignInOptions) showSignInOptions = false
    }

    AnimatedVisibility(
        visible = visible,
        enter = EnterTransition.None,
        exit = fadeOut(tween(600)),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
        ) {
            Crossfade(
                targetState = stage,
                animationSpec = tween(700),
                label = "onboarding_stage",
            ) { current ->
                when (current) {
                    STAGE_WELCOME ->
                        WelcomeStage(
                            navController = navController,
                            showSignInOptions = showSignInOptions,
                            onContinue = { showSignInOptions = true },
                            onSkip = {
                                scope.launch {
                                    context.safeDataStoreEdit { settings ->
                                        if (settings[OnboardingStepKey] == null) {
                                            settings[OnboardingStepKey] = ONBOARDING_STEP_FOLLOW
                                        }
                                    }
                                }
                                stage = STAGE_FOLLOW
                            },
                        )

                    STAGE_FOLLOW -> FollowStage(onFinished = { stage = STAGE_FINISH })

                    STAGE_FINISH ->
                        FinishStage(
                            onFollowsSaved = onFollowsSaved,
                            onFinished = { done = true },
                        )

                    else -> CenteredLoading()
                }
            }
        }
    }
}

@Composable
private fun CenteredLoading(label: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ContainedLoadingIndicator()
            if (label != null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WelcomeStage(
    navController: NavController,
    showSignInOptions: Boolean,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val accountViewModel: AccountSettingsViewModel = hiltViewModel()
    val context = LocalContext.current
    var showTokenDialog by remember { mutableStateOf(false) }

    val mainButtonShift by animateDpAsState(
        targetValue = if (showSignInOptions) 0.dp else LATER_SLOT_HEIGHT + BUTTON_SPACING,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "main_button_shift",
    )
    val extrasAlpha by animateFloatAsState(
        targetValue = if (showSignInOptions) 1f else 0f,
        animationSpec = tween(durationMillis = 350, delayMillis = if (showSignInOptions) 150 else 0),
        label = "extra_buttons_alpha",
    )

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.small_icon),
                contentDescription = null,
                colorFilter =
                    ColorFilter.tint(
                        color = MaterialTheme.colorScheme.primary,
                        blendMode = BlendMode.SrcIn,
                    ),
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.onboarding_welcome),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BUTTON_SPACING),
        ) {
            FilledTonalButton(
                onClick = { showTokenDialog = true },
                enabled = showSignInOptions,
                modifier = Modifier.fillMaxWidth().height(52.dp).alpha(extrasAlpha),
            ) {
                Text(stringResource(R.string.onboarding_sign_in_token))
            }

            // Sits in the bottom slot as "Continue", then slides up into the Google slot.
            Button(
                onClick = if (showSignInOptions) {
                    { navController.navigate("login") }
                } else {
                    onContinue
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .offset(y = mainButtonShift)
                        // Draw and take taps above the hidden "I'll do it later" slot it slides over.
                        .zIndex(1f),
            ) {
                Crossfade(
                    targetState = showSignInOptions,
                    modifier = Modifier.fillMaxWidth(),
                    animationSpec = tween(350),
                    label = "main_button_label",
                ) { signIn ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(
                                if (signIn) R.string.onboarding_sign_in_google else R.string.continue_action,
                            ),
                        )
                    }
                }
            }

            // A plain clickable row instead of a TextButton, so the whole width (centre included) is tappable.
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(LATER_SLOT_HEIGHT)
                        .alpha(extrasAlpha)
                        .clip(CircleShape)
                        .clickable(enabled = showSignInOptions, onClick = onSkip),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.onboarding_do_it_later),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showTokenDialog) {
        TokenSignInDialog(
            onDismiss = { showTokenDialog = false },
            onSave = { fields ->
                accountViewModel.saveTokenAndRestart(
                    context = context,
                    cookie = fields.cookie,
                    visitorData = fields.visitorData,
                    dataSyncId = fields.dataSyncId,
                    authUser = fields.authUser,
                    accountName = fields.accountName,
                    accountEmail = fields.accountEmail,
                    accountChannelHandle = fields.accountChannelHandle,
                )
            },
        )
    }
}

private data class TokenFields(
    val cookie: String = "",
    val visitorData: String = "",
    val dataSyncId: String = "",
    val authUser: String = "0",
    val accountName: String = "",
    val accountEmail: String = "",
    val accountChannelHandle: String = "",
)

private const val COOKIE_PREFIX = "***INNERTUBE COOKIE*** ="

/** Same template and parsing as the token editor in Account settings. */
@Composable
private fun TokenSignInDialog(
    onDismiss: () -> Unit,
    onSave: (TokenFields) -> Unit,
) {
    val template =
        """
        $COOKIE_PREFIX
        ***VISITOR DATA*** =
        ***DATASYNC ID*** =
        ***AUTH USER*** =0
        ***ACCOUNT NAME*** =
        ***ACCOUNT EMAIL*** =
        ***ACCOUNT CHANNEL HANDLE*** =
        """.trimIndent()

    TextFieldDialog(
        title = { Text(stringResource(R.string.onboarding_sign_in_token)) },
        initialTextFieldValue = TextFieldValue(template),
        singleLine = false,
        maxLines = 20,
        isInputValid = { fullText ->
            val cookie =
                fullText.lines().find { it.startsWith(COOKIE_PREFIX) }
                    ?.substringAfter(COOKIE_PREFIX)?.trim().orEmpty()
            cookie.isNotEmpty() && "SAPISID" in parseCookieString(cookie)
        },
        onDone = { data ->
            var fields = TokenFields()
            data.split("\n").forEach {
                fields =
                    when {
                        it.startsWith(COOKIE_PREFIX) -> fields.copy(cookie = it.substringAfter("="))
                        it.startsWith("***VISITOR DATA*** =") -> fields.copy(visitorData = it.substringAfter("="))
                        it.startsWith("***DATASYNC ID*** =") -> fields.copy(dataSyncId = it.substringAfter("="))
                        it.startsWith("***AUTH USER*** =") -> fields.copy(authUser = it.substringAfter("="))
                        it.startsWith("***ACCOUNT NAME*** =") -> fields.copy(accountName = it.substringAfter("="))
                        it.startsWith("***ACCOUNT EMAIL*** =") -> fields.copy(accountEmail = it.substringAfter("="))
                        it.startsWith("***ACCOUNT CHANNEL HANDLE*** =") ->
                            fields.copy(accountChannelHandle = it.substringAfter("="))
                        else -> fields
                    }
            }
            onSave(fields)
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun FollowStage(onFinished: () -> Unit) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val followed = remember { mutableStateMapOf<String, ArtistItem>() }

    LaunchedEffect(query) {
        val text = query.trim()
        if (text.isEmpty()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(400)
        results =
            withContext(Dispatchers.IO) {
                YouTube.search(text, FILTER_ARTIST).getOrNull()
                    ?.items?.filterIsInstance<ArtistItem>().orEmpty()
            }
        searching = false
    }

    fun setFollowed(
        artist: ArtistItem,
        follow: Boolean,
    ) {
        if (follow) followed[artist.id] = artist else followed.remove(artist.id)
        scope.launch(Dispatchers.IO) {
            val existing = database.artistEntity(artist.id)
            database.withTransaction {
                if (existing == null) {
                    if (follow) {
                        insert(
                            ArtistEntity(
                                id = artist.id,
                                name = artist.title,
                                thumbnailUrl = artist.thumbnail,
                                channelId = artist.channelId ?: artist.id.takeIf { it.startsWith("UC") },
                                bookmarkedAt = LocalDateTime.now(),
                            ),
                        )
                    }
                } else {
                    update(
                        existing.copy(
                            bookmarkedAt = if (follow) LocalDateTime.now() else null,
                            lastUpdateTime = LocalDateTime.now(),
                        ),
                    )
                }
            }
            val cookie = context.dataStore.data.first()[InnerTubeCookieKey]
            val loggedIn = cookie?.let { "SAPISID" in parseCookieString(it) } ?: false
            if (loggedIn) {
                runCatching {
                    val channelId = artist.channelId ?: YouTube.getChannelId(artist.id)
                    if (channelId.isNotEmpty()) YouTube.subscribeChannel(channelId, follow)
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.onboarding_follow_title, ARTISTS_TO_FOLLOW),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.onboarding_follow_subtitle, followed.size, ARTISTS_TO_FOLLOW),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.onboarding_search_hint)) },
            leadingIcon = { Icon(painterResource(R.drawable.search), contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }

                query.isNotBlank() && results.isEmpty() ->
                    Text(
                        text = stringResource(R.string.onboarding_no_artists),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )

                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(results, key = { it.id }) { artist ->
                            val isFollowed = artist.id in followed
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AsyncImage(
                                    model = artist.thumbnail,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(52.dp).clip(CircleShape),
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    text = artist.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(12.dp))
                                if (isFollowed) {
                                    FilledTonalButton(onClick = { setFollowed(artist, false) }) {
                                        Text(stringResource(R.string.onboarding_following))
                                    }
                                } else {
                                    Button(onClick = { setFollowed(artist, true) }) {
                                        Text(stringResource(R.string.onboarding_follow))
                                    }
                                }
                            }
                        }
                    }
            }
        }

        Button(
            onClick = onFinished,
            enabled = followed.size >= ARTISTS_TO_FOLLOW,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(stringResource(R.string.continue_action))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FinishStage(
    onFollowsSaved: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        context.safeDataStoreEdit { it[OnboardingStepKey] = ONBOARDING_STEP_DONE }
        onFollowsSaved()
        delay(FINISH_LOADING_MS)
        onFinished()
    }
    CenteredLoading(label = stringResource(R.string.onboarding_matching_taste))
}
