/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.sources

import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.view.View
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.colorResource
import app.morphe.manager.R
import app.morphe.manager.domain.repository.PatchBundleRepository.LocalFileCheck
import app.morphe.manager.domain.repository.PatchBundleRepository.RemoteSourceRejection
import app.morphe.manager.domain.repository.PatchBundleRepository.RemoteUrlCheck
import app.morphe.manager.ui.model.NewSourceOptions
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.util.COMMUNITY_PATCHES_URL
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.HomeViewModel.PickedBundle
import app.morphe.manager.util.REMOTE_URL_PLACEHOLDER
import app.morphe.manager.util.toFilePath
import com.composables.icons.fontawesome.FontAwesome
import com.composables.icons.fontawesome.brands.*
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale

/**
 * Panel sources are added from, several at once: links pasted in any form and files attached from
 * storage, listed together above the field. Each pending source says whether it will be added, and
 * only those that will are counted and submitted.
 *
 * @param localFiles Files picked so far with what importing each would do, held by the caller
 * since the picker opens outside.
 * @param onCheckUrl Checks a link the way the add will, so a refusal shows before submitting.
 */
@Composable
fun AddSourcePanel(
    visible: Boolean,
    onClose: () -> Unit,
    onSubmit: (urls: List<String>, options: NewSourceOptions) -> Unit,
    onPickFiles: () -> Unit,
    onRemoveFile: (Uri) -> Unit,
    localFiles: List<PickedBundle>,
    onCheckUrl: (String) -> RemoteUrlCheck,
    modifier: Modifier = Modifier
) {
    var showCommunityNotice by rememberSaveable { mutableStateOf(false) }
    var showCommunityCatalog by rememberSaveable { mutableStateOf(false) }
    var communityWebView by remember { mutableStateOf<WebView?>(null) }
    val prefs: PreferencesManager = koinInject()
    val communityNoticeAcknowledged by prefs.communityNoticeAcknowledged.getAsState()
    val openCommunityPatches = {
        if (communityNoticeAcknowledged) showCommunityCatalog = true
        else showCommunityNotice = true
    }

    DialogHost(visible = showCommunityNotice) {
        ConfirmDialog(
            title = stringResource(R.string.sources_dialog_community_notice_title),
            message = stringResource(R.string.sources_dialog_community_notice_message),
            primaryText = stringResource(R.string.open),
            isPrimaryDestructive = false,
            onDismiss = { showCommunityNotice = false },
            onConfirm = {
                showCommunityNotice = false
                showCommunityCatalog = true
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    prefs.communityNoticeAcknowledged.update(true)
                }
            }
        )
    }

    // Submitting clears the files, which would otherwise drop out of the panel as it slides away
    val files = rememberWhileVisible(visible, localFiles)

    DockedPanel(visible = visible, onBack = onClose, modifier = modifier) {
        // Composed only while the panel is up, so the links typed and the choices made start over
        // each time it opens
        var options by rememberSaveable { mutableStateOf(NewSourceOptions()) }
        val remote = rememberRemoteLinksState(onCheckUrl)
        val readyCount = remote.readyLinks.size + files.count { it.adds }

        if (showCommunityCatalog) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(bottom = Defaults.SpaceSmall)
            ) {
                PanelHeader(title = { PanelTitle(text = stringResource(R.string.sources_dialog_community)) }) {
                    TitleAction(
                        icon = MaterialSymbols.Rounded.Arrow_back,
                        contentDescription = stringResource(android.R.string.cancel),
                        onClick = {
                            if (communityWebView?.canGoBack() == true) communityWebView?.goBack()
                            else showCommunityCatalog = false
                        },
                        style = TitleActionStyle.Neutral
                    )
                    TitleAction(
                        icon = MaterialSymbols.Rounded.Close,
                        contentDescription = stringResource(R.string.close),
                        onClick = onClose,
                        style = TitleActionStyle.Neutral
                    )
                }
                CommunityCatalogWebView(
                    onAddSource = { url -> remote.addCommunitySource(url) },
                    onRemoveSource = { url -> remote.removeCommunitySource(url) },
                    isSourceAdded = { url ->
                        when (val check = onCheckUrl(url)) {
                            is RemoteUrlCheck.Rejected -> check.reason == RemoteSourceRejection.Duplicate
                            is RemoteUrlCheck.Accepted -> false
                        }
                    },
                    isSourceQueued = { url ->
                        remote.readyLinks.any { it.trimEnd('/').equals(url.trimEnd('/'), ignoreCase = true) }
                    },
                    onWebViewCreated = { communityWebView = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        
                )
                if (remote.readyLinks.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = Defaults.SpaceLarge)
                            .heightIn(max = 132.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        PendingSources(
                            state = remote,
                            files = emptyList(),
                            onRemoveFile = {},
                        )
                    }
                    NewSourceOptionsGroup(
                        options = options,
                        onOptionsChange = { options = it },
                        offersChooseApps = true,
                        offersPrerelease = remote.offersPrerelease,
                        modifier = Modifier.padding(horizontal = Defaults.SpaceLarge)
                    )
                }
                Text(
                    text = if (remote.readyLinks.isEmpty()) {
                        "Select one or more sources. They will stay queued while you browse."
                    } else {
                        "${remote.readyLinks.size} source(s) selected. You can keep browsing or add them together."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Defaults.SpaceLarge, vertical = Defaults.SpaceSmall)
                )
                AppDialogButtonRow(
                    primaryText = if (remote.readyLinks.size > 1) {
                        pluralStringResource(R.plurals.sources_dialog_add_count, remote.readyLinks.size, remote.readyLinks.size.toString())
                    } else {
                        stringResource(R.string.add)
                    },
                    onPrimaryClick = { onSubmit(remote.readyLinks, options) },
                    primaryEnabled = remote.readyLinks.isNotEmpty(),
                    modifier = Modifier.padding(horizontal = Defaults.SpaceLarge)
                )
                Text(
                    text = "Only add sources you trust. Community patches are third-party code.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Defaults.SpaceLarge, vertical = Defaults.SpaceSmall)
                )
            }
        } else Column(modifier = Modifier.padding(bottom = Defaults.SpaceSmall)) {
            PanelHeader(title = { PanelTitle(text = stringResource(R.string.sources_dialog_add_source)) }) {
                // Where sources are found rather than a way of adding one, so it stays out of the actions
                TitleAction(
                    icon = MaterialSymbols.Rounded.Groups,
                    contentDescription = stringResource(R.string.sources_dialog_community),
                    onClick = openCommunityPatches,
                    style = TitleActionStyle.Neutral
                )
                TitleAction(
                    icon = MaterialSymbols.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    onClick = onClose,
                    style = TitleActionStyle.Neutral
                )
            }
            // Scrolled between the header and the actions once the keyboard leaves too little room
            // for it all, so adding stays in reach however many sources are pending
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScrollFade(scrollState)
                    .verticalScroll(scrollState)
                    .padding(horizontal = Defaults.SpaceLarge)
                    .padding(bottom = Defaults.SpaceMedium),
                verticalArrangement = Arrangement.spacedBy(Defaults.SpaceMedium)
            ) {
                PendingSources(state = remote, files = files, onRemoveFile = onRemoveFile)
                // The two ways a source comes in, kept together apart from what is asked of it
                Column(verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)) {
                    LinkField(state = remote, showHint = remote.entries.isEmpty() && files.isEmpty())
                    // Files join the same list as links, picked from storage rather than typed
                    AppDialogOutlinedButton(
                        text = stringResource(R.string.sources_dialog_local_files),
                        onClick = onPickFiles,
                        icon = MaterialSymbols.Rounded.Upload_file,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                NewSourceOptionsGroup(
                    options = options,
                    onOptionsChange = { options = it },
                    offersChooseApps = true,
                    offersPrerelease = remote.offersPrerelease
                )
            }
            Box(modifier = Modifier.padding(horizontal = Defaults.SpaceLarge)) {
                AppDialogButtonRow(
                    primaryText = if (readyCount > 1) {
                        pluralStringResource(R.plurals.sources_dialog_add_count, readyCount, readyCount.toString())
                    } else {
                        stringResource(R.string.add)
                    },
                    onPrimaryClick = { onSubmit(remote.readyLinks, options) },
                    primaryEnabled = readyCount > 0
                )
            }
        }
    }
}

/**
 * The switches a source is added with, as one group: whether its app list opens once it has loaded,
 * and whether it starts on prerelease. Each row shows only where its choice applies, and the group
 * nothing where none does.
 */
@Composable
internal fun NewSourceOptionsGroup(
    options: NewSourceOptions,
    onOptionsChange: (NewSourceOptions) -> Unit,
    offersChooseApps: Boolean,
    offersPrerelease: Boolean,
    modifier: Modifier = Modifier
) {
    if (!offersChooseApps && !offersPrerelease) return

    // Spaced by each row rather than by the column, so a row folding in brings its gap along
    Column(modifier = modifier) {
        // What a source holds is only known once it has loaded, so this asks now and the list opens then
        if (offersChooseApps) {
            ToggleRow(
                title = stringResource(R.string.sources_dialog_choose_apps),
                description = stringResource(R.string.sources_dialog_choose_apps_description),
                checked = options.chooseApps,
                onCheckedChange = { onOptionsChange(options.copy(chooseApps = it)) },
                showDivider = false
            )
        }
        // Only a source on a stable or dev branch has a pre-release channel, see createRemotes
        ListRowFold(visible = offersPrerelease) {
            ToggleRow(
                title = stringResource(R.string.sources_management_prerelease_toggle),
                description = stringResource(R.string.sources_management_prerelease_toggle_description),
                checked = options.usePrerelease,
                onCheckedChange = { onOptionsChange(options.copy(usePrerelease = it)) },
                modifier = Modifier.padding(top = if (offersChooseApps) Defaults.SpaceSmall else 0.dp),
                showDivider = false
            )
        }
    }
}

/** Links in pasted text however they came: one per line, in a list, or inside prose or markdown. */
private val LINK_PATTERN = Regex(
    """(?:https?://)?(?:[\w-]+\.)+[a-z]{2,}(?::\d+)?(?:/[^\s,;<>()\[\]"'`]*)?""",
    RegexOption.IGNORE_CASE
)

private fun extractLinks(text: String): List<String> =
    LINK_PATTERN.findAll(text).map { it.value.trimEnd('.') }.toList()

/** A link waiting in the panel, with what adding it would do. */
private class RemoteLinkEntry(val link: String, val check: RemoteUrlCheck) {
    /** Two links reading the same bundle are the same source, however each was written. */
    val key = ((check as? RemoteUrlCheck.Accepted)?.endpoint ?: link).lowercase(Locale.US)
}

/**
 * Links of the panel: the ones taken into the list, and the ones still in the field, which
 * count as well. A paste of several links or the keyboard's Done moves the field into the list.
 *
 * Typing is never rewritten. A field reset under the keyboard races the keys still on their way,
 * and whichever lands in between is lost, so links typed one after another simply stay in the
 * field until Done.
 */
@Stable
private class RemoteLinksState(
    private val check: (String) -> RemoteUrlCheck,
    linksState: MutableState<List<String>>,
    inputState: MutableState<String>
) {
    private var links by linksState
    var input by inputState
        private set

    val entries by derivedStateOf { links.map { RemoteLinkEntry(it, check(it)) } }

    /** Links in the field. Text that holds none is still checked, to say why it is not one. */
    private val inputEntries by derivedStateOf {
        if (input.isBlank()) emptyList()
        else extractLinks(input).ifEmpty { listOf(input.trim()) }.map { RemoteLinkEntry(it, check(it)) }
    }

    /** What the field holds, checked: the first link it would leave out, or null while it is empty. */
    val inputCheck by derivedStateOf {
        inputEntries.firstOrNull { it.check is RemoteUrlCheck.Rejected }?.check
            ?: inputEntries.firstOrNull()?.check
    }

    private val readyEntries by derivedStateOf {
        (entries + inputEntries)
            .distinctBy { it.key }
            .filter { it.check is RemoteUrlCheck.Accepted }
    }

    /** Links that will become sources, the ones in the field included. */
    val readyLinks by derivedStateOf { readyEntries.map { it.link } }

    /** Whether any of [readyLinks] could start on prerelease. */
    val offersPrerelease by derivedStateOf {
        readyEntries.any { (it.check as RemoteUrlCheck.Accepted).supportsPrerelease }
    }

    fun onInputChange(text: String) {
        // Grown by more than a key at once is a paste, which leaves nothing in flight to race
        val pastedLinks = extractLinks(text).takeIf { text.length - input.length > 1 && it.size > 1 }
        input = if (pastedLinks != null && add(pastedLinks)) "" else text
    }

    fun clearInput() {
        input = ""
    }

    fun commitInput() {
        if (add(extractLinks(input))) input = ""
    }

    /** @return Whether [text] held any link. */
    fun paste(text: String): Boolean = add(extractLinks(text))

    fun remove(link: String) {
        links = links - link
    }

    /** Adds a repository selected from the embedded community catalogue to the normal pending list. */
    private fun communityRepositoryUrl(addSourceUrl: String): String? {
        val normalizedLink = if (addSourceUrl.startsWith("intent://")) {
            "https://" + addSourceUrl.removePrefix("intent://").substringBefore("#Intent;")
        } else addSourceUrl
        val uri = runCatching { Uri.parse(normalizedLink) }.getOrNull() ?: return null
        if (!uri.host.equals("morphe.software", ignoreCase = true) || uri.path != "/add-source") return null
        return uri.getQueryParameter("github")?.let { "https://github.com/$it" }
            ?: uri.getQueryParameter("gitlab")?.let { "https://gitlab.com/$it" }
    }

    fun addCommunitySource(addSourceUrl: String): Boolean {
        val repo = communityRepositoryUrl(addSourceUrl) ?: return false
        if (check(repo) !is RemoteUrlCheck.Accepted) return false
        return add(listOf(repo))
    }

    /** Tapping a queued catalogue card again removes it from the pending batch. */
    fun removeCommunitySource(addSourceUrl: String): Boolean {
        val repo = communityRepositoryUrl(addSourceUrl) ?: return false
        val key = RemoteLinkEntry(repo, check(repo)).key
        val queuedLink = entries.firstOrNull { it.key == key }?.link ?: return false
        remove(queuedLink)
        return true
    }

    private fun add(newLinks: List<String>): Boolean {
        if (newLinks.isEmpty()) return false
        val taken = entries.mapTo(mutableSetOf()) { it.key }
        links = links + newLinks.filter { taken.add(RemoteLinkEntry(it, check(it)).key) }
        return true
    }
}

@Composable
private fun rememberRemoteLinksState(check: (String) -> RemoteUrlCheck): RemoteLinksState {
    val links = rememberSaveable(
        stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it })
    ) { mutableStateOf(emptyList()) }
    val input = rememberSaveable { mutableStateOf("") }
    return remember(check) { RemoteLinksState(check, links, input) }
}

private val RemoteSourceRejection.hintRes: Int
    get() = when (this) {
        // Says what a link has to be rather than only that it is not
        RemoteSourceRejection.Invalid -> R.string.sources_dialog_url_invalid
        else -> messageRes
    }

/** Hosts whose links name a repository, by the icon they show. Any other host serves a bundle file. */
private val REPOSITORY_HOST_ICONS: Map<String, ImageVector> by lazy {
    mapOf(
        "github.com" to FontAwesome.Brands.Github,
        "raw.githubusercontent.com" to FontAwesome.Brands.Github,
        "gitlab.com" to FontAwesome.Brands.Gitlab
    )
}

/** The link without its scheme, the way it reads in the address bar. */
internal fun String.bareLink() = substringAfter("://").removePrefix("www.")

private fun String.linkHost() = bareLink().substringBefore('/').lowercase(Locale.US)

/** A repository as owner/repo, anything else as its bare link. */
internal fun remoteLinkTitle(link: String): String {
    val bare = link.bareLink()
    val segments = bare.substringAfter('/', "").split('/').filter { it.isNotBlank() }
    return if (link.linkHost() in REPOSITORY_HOST_ICONS && segments.size >= 2) "${segments[0]}/${segments[1]}" else bare
}

internal fun remoteLinkIcon(link: String): ImageVector = REPOSITORY_HOST_ICONS[link.linkHost()] ?: MaterialSymbols.Rounded.Link

@Composable
private fun LinkField(state: RemoteLinksState, showHint: Boolean) {
    val context = LocalContext.current
    val messages = koinInject<UserMessages>()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val inputCheck = state.inputCheck
    val noLinksMessage = stringResource(R.string.sources_dialog_paste_no_links)

    // Links taken from the field into the list above push it down, out of the room the keyboard
    // leaves, so it is scrolled back into view as the list grows. A row removed leaves it be
    val inView = remember { BringIntoViewRequester() }
    val entryCount = state.entries.size
    var lastEntryCount by remember { mutableIntStateOf(entryCount) }
    LaunchedEffect(entryCount) {
        if (entryCount > lastEntryCount) {
            // The rows are laid out by the next frame, and the field's place with them
            withFrameNanos { }
            inView.bringIntoView()
        }
        lastEntryCount = entryCount
    }

    Column(
        modifier = Modifier.bringIntoViewRequester(inView),
        verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)
    ) {
        AppDialogTextField(
            value = state.input,
            onValueChange = state::onInputChange,
            label = { Text(stringResource(R.string.sources_dialog_remote_url)) },
            placeholder = { Text(REMOTE_URL_PLACEHOLDER) },
            trailingIcon = {
                if (state.input.isNotEmpty()) {
                    TooltipIconButton(
                        onClick = state::clearInput,
                        icon = MaterialSymbols.Rounded.Close,
                        contentDescription = stringResource(R.string.clear)
                    )
                } else {
                    TooltipIconButton(
                        onClick = {
                            scope.launch {
                                val text = clipboard.getClipEntry()?.clipData
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)?.coerceToText(context)?.toString()
                                if (text == null || !state.paste(text)) {
                                    messages.show(noLinksMessage)
                                }
                            }
                        },
                        icon = MaterialSymbols.Rounded.Content_paste,
                        contentDescription = stringResource(R.string.paste)
                    )
                }
            },
            isError = inputCheck is RemoteUrlCheck.Rejected,
            keyboardOptions = RemoteUrlKeyboardOptions,
            keyboardActions = KeyboardActions(onDone = { state.commitInput() })
        )

        // What a link can be until the first source is in the list
        RemoteUrlCheckHint(check = inputCheck, showHint = showHint)
    }
}

/** Keyboard for typing a source link, which takes it as written. */
internal val RemoteUrlKeyboardOptions = KeyboardOptions(
    // A keyboard fixing "github" into "GitHub" or capitalizing a pasted link rewrites it
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri,
    imeAction = ImeAction.Done
)

/**
 * Live validation feedback on a link being typed, from its [check]. Without a link to check, it
 * says what a link can be where [showHint] is set, and is gone otherwise.
 */
@Composable
internal fun RemoteUrlCheckHint(check: RemoteUrlCheck?, showHint: Boolean) {
    AnimatedVisibility(visible = check != null || showHint) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)
        ) {
            val (icon, color, text) = when (check) {
                is RemoteUrlCheck.Rejected -> Triple(
                    MaterialSymbols.Rounded.Error,
                    MaterialTheme.colorScheme.error,
                    stringResource(check.reason.hintRes)
                )
                is RemoteUrlCheck.Accepted -> Triple(
                    MaterialSymbols.Rounded.Check_circle,
                    SemanticTone.Success.accent,
                    stringResource(R.string.sources_dialog_url_valid)
                )
                null -> Triple(
                    MaterialSymbols.Rounded.Info,
                    dialogSecondaryTextColor(),
                    stringResource(R.string.sources_dialog_remote_url_hint)
                )
            }
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

/** The links and files waiting to be added, links first. */
@Composable
private fun PendingSources(
    state: RemoteLinksState,
    files: List<PickedBundle>,
    onRemoveFile: (Uri) -> Unit
) {
    if (state.entries.isEmpty() && files.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(CompactCardSpacing)) {
        state.entries.forEach { entry ->
            val rejection = (entry.check as? RemoteUrlCheck.Rejected)?.reason
            PendingSourceRow(
                icon = remoteLinkIcon(entry.link),
                title = remoteLinkTitle(entry.link),
                detail = rejection?.let { stringResource(it.hintRes) } ?: entry.link.bareLink(),
                isError = rejection != null,
                onRemove = { state.remove(entry.link) }
            )
        }
        files.forEach { file ->
            val check = file.check
            PendingSourceRow(
                icon = if (check is LocalFileCheck.Update) MaterialSymbols.Rounded.Update else MaterialSymbols.Rounded.Draft.autoMirrored,
                title = file.name,
                detail = when (check) {
                    is LocalFileCheck.Update -> stringResource(R.string.sources_dialog_local_updates, check.title)
                    LocalFileCheck.Duplicate -> stringResource(R.string.sources_management_already_exists)
                    LocalFileCheck.NotBundle -> stringResource(R.string.sources_dialog_local_invalid_extension)
                    // Where it is read from, while it is checked and once it is known to be new
                    else -> file.uri.toFilePath().takeIf { it.startsWith("/") }?.substringBeforeLast("/").orEmpty()
                },
                isError = check == LocalFileCheck.Duplicate || check == LocalFileCheck.NotBundle,
                onRemove = { onRemoveFile(file.uri) }
            )
        }
    }
}

/**
 * One source waiting to be added, a link or a file: what it is, and under it where it comes
 * from or why it will be left out.
 */
@Composable
private fun PendingSourceRow(
    icon: ImageVector,
    title: String,
    detail: String,
    isError: Boolean,
    onRemove: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    CompactListCard(onClick = null) {
        if (isError) {
            CompactCardIconTile(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer) {
                Icon(MaterialSymbols.Rounded.Error, contentDescription = null, modifier = Modifier.size(CompactCardGlyphSize))
            }
        } else {
            CompactCardIconTile {
                Icon(icon, contentDescription = null, modifier = Modifier.size(CompactCardGlyphSize))
            }
        }
        CardHeadingText(name = title, description = detail, modifier = Modifier.weight(1f))
        TooltipIconButton(
            onClick = onRemove,
            icon = MaterialSymbols.Rounded.Close,
            contentDescription = stringResource(R.string.remove),
            tint = dialogSecondaryTextColor()
        )
    }
}


/**
 * The official community catalogue embedded in the Add Source panel. The website remains the
 * source of truth for catalogue content; add-source links are routed into Morphe's existing
 * validation and pending-source flow rather than launching an external browser.
 */
@Composable
private fun CommunityCatalogWebView(
    onAddSource: (String) -> Boolean,
    onRemoveSource: (String) -> Boolean,
    isSourceAdded: (String) -> Boolean,
    isSourceQueued: (String) -> Boolean,
    onWebViewCreated: (WebView) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentAdd by rememberUpdatedState(onAddSource)
    val currentRemove by rememberUpdatedState(onRemoveSource)
    val currentIsAdded by rememberUpdatedState(isSourceAdded)
    val currentIsQueued by rememberUpdatedState(isSourceQueued)
    val currentOnWebViewCreated by rememberUpdatedState(onWebViewCreated)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                val catalogWebView = this
                currentOnWebViewCreated(this)
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN,
                        android.view.MotionEvent.ACTION_MOVE ->
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL ->
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun isAdded(url: String): Boolean =
                        runCatching { currentIsAdded(url) }.getOrDefault(false)

                    @JavascriptInterface
                    fun isQueued(url: String): Boolean =
                        runCatching { currentIsQueued(url) }.getOrDefault(false)

                    @JavascriptInterface
                    fun addSource(url: String): Boolean {
                        var accepted = false
                        val latch = java.util.concurrent.CountDownLatch(1)
                        catalogWebView.post {
                            accepted = runCatching { currentAdd(url) }.getOrDefault(false)
                            latch.countDown()
                        }
                        runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
                        return accepted
                    }
                    @JavascriptInterface
                    fun removeSource(url: String): Boolean {
                        var removed = false
                        val latch = java.util.concurrent.CountDownLatch(1)
                        catalogWebView.post {
                            removed = runCatching { currentRemove(url) }.getOrDefault(false)
                            latch.countDown()
                        }
                        runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
                        return removed
                    }
                }, "MorpheCatalog")

                webViewClient = object : WebViewClient() {
                    private fun handleNavigation(url: String): Boolean {
                        if (url.startsWith("intent://")) {
                            val httpsUrl = "https://" + url.removePrefix("intent://").substringBefore("#Intent;")
                            if (httpsUrl.startsWith("https://morphe.software/add-source")) {
                                currentAdd(httpsUrl)
                                return true
                            }
                        }
                        if (url.startsWith("https://morphe.software/add-source")) {
                            currentAdd(url)
                            return true
                        }
                        // Keep the full website navigation available inside this WebView.
                        // Only the native add-source deep link is intercepted above.
                        return false
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                        handleNavigation(url)

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: android.webkit.WebResourceRequest
                    ): Boolean = handleNavigation(request.url.toString())

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        view.evaluateJavascript("""
                            (function() {
                              if (window.__morpheCatalogObserver) {
                                try { window.__morpheCatalogObserver.disconnect(); } catch(e) {}
                              }
                              function sourceUrl(href) {
                                try {
                                  if (href.indexOf('intent://') === 0) href = 'https://' + href.substring(9).split('#Intent;')[0];
                                  var u = new URL(href, location.href);
                                  if (u.hostname !== 'morphe.software' || !u.pathname.startsWith('/add-source')) return null;
                                  var owner = u.searchParams.get('github');
                                  if (owner) return 'https://github.com/' + owner.replace(/^\/+|\/+$/g, '');
                                  owner = u.searchParams.get('gitlab');
                                  if (owner) return 'https://gitlab.com/' + owner.replace(/^\/+|\/+$/g, '');
                                } catch(e) {}
                                return null;
                              }
                              var statusCache = Object.create(null);
                              var decorateQueued = false;
                              function setLabel(a, value) {
                                // Assigning textContent unconditionally creates a new text node. The
                                // observer watches childList, so doing that on every pass loops forever.
                                if (a.textContent !== value) a.textContent = value;
                              }
                              function decorate() {
                                document.querySelectorAll('a[href]').forEach(function(a) {
                                  var src = sourceUrl(a.href);
                                  if (!src) {
                                    try {
                                      var u = new URL(a.href, location.href);
                                      if (u.hostname === location.hostname) a.target = '_self';
                                    } catch(e) {}
                                    return;
                                  }
                                  a.target = '_self';
                                  if (a.dataset.morpheOriginalText === undefined) a.dataset.morpheOriginalText = a.textContent;
                                  var status = statusCache[src];
                                  if (!status) {
                                    var added = false, queued = false;
                                    try { added = MorpheCatalog.isAdded(src); } catch(e) {}
                                    try { queued = MorpheCatalog.isQueued(src); } catch(e) {}
                                    status = statusCache[src] = added ? 'added' : (queued ? 'queued' : 'new');
                                  }
                                  if (status === 'added') {
                                    setLabel(a, 'Already Added');
                                    a.style.pointerEvents = 'none';
                                    a.style.opacity = '0.65';
                                    a.setAttribute('aria-disabled', 'true');
                                    a.dataset.morpheStatus = 'added';
                                  } else {
                                    if (status === 'queued') {
                                      setLabel(a, 'Queued ✓ · Tap to remove');
                                      a.style.opacity = '0.85';
                                      a.dataset.morpheStatus = 'queued';
                                    } else {
                                      setLabel(a, a.dataset.morpheOriginalText || 'Add');
                                      a.style.opacity = '';
                                      delete a.dataset.morpheStatus;
                                    }
                                    a.style.pointerEvents = '';
                                    a.removeAttribute('aria-disabled');
                                    if (!a.dataset.morpheClickBound) {
                                      a.dataset.morpheClickBound = '1';
                                      a.addEventListener('click', function(ev) {
                                        ev.preventDefault(); ev.stopPropagation();
                                        var wasQueued = statusCache[src] === 'queued';
                                        var ok = false;
                                        try {
                                          ok = wasQueued
                                            ? MorpheCatalog.removeSource(a.href)
                                            : MorpheCatalog.addSource(a.href);
                                        } catch(e) {}
                                        if (ok) {
                                          statusCache[src] = wasQueued ? 'new' : 'queued';
                                          if (wasQueued) {
                                            setLabel(a, a.dataset.morpheOriginalText || 'Add');
                                            a.style.opacity = '';
                                            delete a.dataset.morpheStatus;
                                          } else {
                                            setLabel(a, 'Queued ✓ · Tap to remove');
                                            a.style.opacity = '0.85';
                                            a.dataset.morpheStatus = 'queued';
                                          }
                                          a.style.pointerEvents = '';
                                          a.removeAttribute('aria-disabled');
                                        }
                                      }, true);
                                    }
                                  }
                                });
                              }
                              function scheduleDecorate() {
                                if (decorateQueued) return;
                                decorateQueued = true;
                                requestAnimationFrame(function() {
                                  decorateQueued = false;
                                  decorate();
                                });
                              }
                              window.__morpheCatalogDecorate = scheduleDecorate;
                              window.__morpheCatalogRefresh = function() {
                                statusCache = Object.create(null);
                                scheduleDecorate();
                              };
                              decorate();
                              window.__morpheCatalogObserver = new MutationObserver(scheduleDecorate);
                              window.__morpheCatalogObserver.observe(document.documentElement, {childList:true, subtree:true, attributes:true, attributeFilter:['href']});
                            })();
                        """.trimIndent(), null)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.d(
                            "MorpheCommunityWeb",
                            "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                        )
                        return true
                    }

                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message
                    ): Boolean = false
                }
                loadUrl("https://morphe-patches.software/")
            }
        },
        update = { view ->
            // Re-check visible Add controls when the native queue changes (including removals).
            view.evaluateJavascript("window.__morpheCatalogRefresh && window.__morpheCatalogRefresh();", null)
        }
    )
}
