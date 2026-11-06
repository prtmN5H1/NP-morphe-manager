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
import app.morphe.manager.ui.model.CommunitySourceRequest
import app.morphe.manager.ui.model.NewSourceOptions
import app.morphe.manager.ui.model.SourceScope
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
    onSubmitCommunity: (requests: List<CommunitySourceRequest>, options: NewSourceOptions) -> Unit,
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
        var communityLinks by rememberSaveable { mutableStateOf(emptyList<String>()) }
        var communityQueueExpanded by rememberSaveable { mutableStateOf(false) }
        val communityRequests = remember(communityLinks) {
            communityLinks.mapNotNull(::parseCommunitySourceRequest)
        }
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
                    onAddSource = { rawUrl ->
                        val request = parseCommunitySourceRequest(rawUrl)
                        if (request == null) false
                        else {
                            val check = onCheckUrl(request.url)
                            val duplicate = check is RemoteUrlCheck.Rejected &&
                                check.reason == RemoteSourceRejection.Duplicate
                            if (duplicate && request.scope == SourceScope.Whole) false
                            else {
                                val key = communityRequestKey(request)
                                if (communityLinks.none { existing ->
                                        parseCommunitySourceRequest(existing)?.let(::communityRequestKey) == key
                                    }) communityLinks = communityLinks + rawUrl
                                true
                            }
                        }
                    },
                    isSourceAdded = { rawUrl ->
                        val request = parseCommunitySourceRequest(rawUrl)
                        if (request == null || request.scope != SourceScope.Whole) false
                        else {
                            val check = onCheckUrl(request.url)
                            check is RemoteUrlCheck.Rejected && check.reason == RemoteSourceRejection.Duplicate
                        }
                    },
                    isSourceQueued = { rawUrl ->
                        val request = parseCommunitySourceRequest(rawUrl)
                        request != null && communityRequests.any { communityRequestKey(it) == communityRequestKey(request) }
                    },                    onWebViewCreated = { communityWebView = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        
                )
                if (communityRequests.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Defaults.SpaceLarge, vertical = Defaults.SpaceSmall),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${communityRequests.size} source(s) queued",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        AppDialogOutlinedButton(
                            text = if (communityQueueExpanded) "Hide queue" else "Review queue",
                            onClick = { communityQueueExpanded = !communityQueueExpanded }
                        )
                    }
                    if (communityQueueExpanded) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = Defaults.SpaceLarge)
                                .heightIn(max = 112.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            communityRequests.forEach { request ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = request.scope.keptApps?.let { apps ->
                                            if (apps.isEmpty()) "${request.url} · Universal patches"
                                            else "${request.url} · ${apps.size} app(s)"
                                        } ?: request.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    AppDialogOutlinedButton(
                                        text = "Remove",
                                        onClick = {
                                            val key = communityRequestKey(request)
                                            communityLinks = communityLinks.filterNot { raw ->
                                                parseCommunitySourceRequest(raw)?.let(::communityRequestKey) == key
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        NewSourceOptionsGroup(
                            options = options,
                            onOptionsChange = { options = it },
                            offersChooseApps = communityRequests.any { it.scope == SourceScope.Whole },
                            offersPrerelease = remote.offersPrerelease,
                            modifier = Modifier.padding(horizontal = Defaults.SpaceLarge)
                        )
                    }
                }
                Text(
                    text = if (communityRequests.isEmpty()) {
                        "Select one or more sources. They will stay queued while you browse."
                    } else {
                        "${communityRequests.size} source(s) selected. You can keep browsing or add them together."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Defaults.SpaceLarge, vertical = Defaults.SpaceSmall)
                )
                AppDialogButtonRow(
                    primaryText = if (communityRequests.size > 1) "Add ${communityRequests.size} sources" else stringResource(R.string.add),
                    onPrimaryClick = { onSubmitCommunity(communityRequests, options) },
                    primaryEnabled = communityRequests.isNotEmpty(),
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

/** Parses the Community Catalog deep link without discarding its optional app scope. */
private fun parseCommunitySourceRequest(rawUrl: String): CommunitySourceRequest? {
    val normalized = if (rawUrl.startsWith("intent://")) {
        "https://" + rawUrl.removePrefix("intent://").substringBefore("#Intent;")
    } else rawUrl
    val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
    if (uri.host?.lowercase(Locale.US) !in setOf("morphe.software", "www.morphe.software") ||
        uri.path?.trimEnd('/') != "/add-source"
    ) return null
    val repo = uri.getQueryParameter("github")?.let { "https://github.com/${it.trim('/')}" }
        ?: uri.getQueryParameter("gitlab")?.let { "https://gitlab.com/${it.trim('/')}" }
        ?: return null
    return CommunitySourceRequest(
        url = repo,
        scope = SourceScope.fromLink(uri.getQueryParameter("app"), uri.getQueryParameter("appName"))
    )
}

private fun communityRequestKey(request: CommunitySourceRequest): String =
    request.url.trimEnd('/').lowercase(Locale.US) + "|" +
        request.scope.keptApps.orEmpty().sorted().joinToString(",").lowercase(Locale.US) +
        when (request.scope) {
            SourceScope.Whole -> "|whole"
            SourceScope.Universal -> "|universal"
            else -> ""
        }

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
    fun addCommunitySource(addSourceUrl: String): Boolean {
        val normalizedLink = if (addSourceUrl.startsWith("intent://")) {
            "https://" + addSourceUrl.removePrefix("intent://").substringBefore("#Intent;")
        } else addSourceUrl
        val uri = runCatching { Uri.parse(normalizedLink) }.getOrNull() ?: return false
        if (!uri.host.equals("morphe.software", ignoreCase = true) || uri.path != "/add-source") return false
        val repo = uri.getQueryParameter("github")?.let { "https://github.com/$it" }
            ?: uri.getQueryParameter("gitlab")?.let { "https://gitlab.com/$it" }
            ?: return false
        // Do not queue an already-added, blocked, or malformed source from the catalogue.
        if (check(repo) !is RemoteUrlCheck.Accepted) return false
        return add(listOf(repo))
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
    isSourceAdded: (String) -> Boolean,
    isSourceQueued: (String) -> Boolean,
    onWebViewCreated: (WebView) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentAdd by rememberUpdatedState(onAddSource)
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
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.textZoom = 100
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

                addJavascriptInterface(object {
                    /** One lightweight status lookup, made only for links that enter the viewport. */
                    @JavascriptInterface
                    fun status(url: String): String = runCatching {
                        when {
                            currentIsAdded(url) -> "added"
                            currentIsQueued(url) -> "queued"
                            else -> "available"
                        }
                    }.getOrDefault("available")

                    /** Never block the WebView JavaScript thread waiting for Compose's main thread. */
                    @JavascriptInterface
                    fun addSource(url: String) {
                        catalogWebView.post { runCatching { currentAdd(url) } }
                    }
                }, "MorpheCatalog")

                webViewClient = object : WebViewClient() {
                    private fun handleNavigation(rawUrl: String): Boolean {
                        val url = if (rawUrl.startsWith("intent://")) {
                            "https://" + rawUrl.removePrefix("intent://").substringBefore("#Intent;")
                        } else rawUrl
                        val uri = runCatching { Uri.parse(url) }.getOrNull()
                        val host = uri?.host?.lowercase(Locale.US)
                        val isAddSource = host in setOf("morphe.software", "www.morphe.software") &&
                            uri?.path?.trimEnd('/') == "/add-source"
                        if (isAddSource) {
                            currentAdd(url)
                            return true
                        }
                        // Keep every route on the catalogue origin in the embedded browser;
                        // do not let app/source actions escape to a browser or confirmation page.
                        return host !in setOf("morphe-patches.software", "www.morphe-patches.software")
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
                              if (window.__morpheCatalogVisibilityObserver) {
                                try { window.__morpheCatalogVisibilityObserver.disconnect(); } catch(e) {}
                              }

                              function sourceInfo(href) {
                                try {
                                  if (!href) return null;
                                  if (href.indexOf('intent://') === 0) {
                                    href = 'https://' + href.substring(9).split('#Intent;')[0];
                                  }
                                  var u = new URL(href, location.href);
                                  var host = u.hostname.toLowerCase();
                                  if ((host !== 'morphe.software' && host !== 'www.morphe.software') ||
                                      u.pathname.replace(/\/+$/, '') !== '/add-source') return null;
                                  var repo = u.searchParams.get('github');
                                  var provider = 'github';
                                  if (!repo) { repo = u.searchParams.get('gitlab'); provider = 'gitlab'; }
                                  if (!repo) return null;
                                  return { href: href, repo: provider + ':' + repo.replace(/^\/+|\/+$/g, ''),
                                    app: u.searchParams.get('app') || '', appName: u.searchParams.get('appName') || '' };
                                } catch(e) { return null; }
                              }

                              function setState(a, state) {
                                a.dataset.morpheState = state;
                                a.dataset.morpheLabel = state === 'added' ? 'Already Added' :
                                  (state === 'queued' ? 'Queued ✓' : 'Adding…');
                                if (a.dataset.morpheOriginalAria === undefined) {
                                  a.dataset.morpheOriginalAria = a.getAttribute('aria-label') || a.textContent || 'Add source';
                                }
                                a.setAttribute('aria-label', a.dataset.morpheOriginalAria +
                                  (state === 'added' ? ', already added' : ', ' + state));
                                a.setAttribute('aria-disabled', 'true');
                                a.style.opacity = state === 'added' ? '0.68' : '0.82';
                                a.style.pointerEvents = 'none';
                              }

                              function clearState(a) {
                                delete a.dataset.morpheState;
                                delete a.dataset.morpheLabel;
                                a.removeAttribute('aria-disabled');
                                a.style.opacity = '';
                                a.style.pointerEvents = '';
                                if (a.dataset.morpheOriginalAria !== undefined) {
                                  a.setAttribute('aria-label', a.dataset.morpheOriginalAria);
                                }
                              }

                              var style = document.getElementById('morphe-catalog-status-style');
                              if (!style) {
                                style = document.createElement('style');
                                style.id = 'morphe-catalog-status-style';
                                style.textContent =
                                  'a[data-morphe-state]::after{' +
                                  'content:attr(data-morphe-label);display:inline-flex;align-items:center;' +
                                  'margin-inline-start:.45em;padding:.12em .5em;border-radius:999px;' +
                                  'font-size:.72em;line-height:1.5;background:rgba(90,190,190,.18);' +
                                  'color:inherit;white-space:nowrap;vertical-align:middle;}';
                                document.head.appendChild(style);
                              }

                              function decorateAnchor(a, force) {
                                if (!a || !a.getAttribute) return;
                                var href = a.getAttribute('href') || '';
                                if (!sourceInfo(href)) return;
                                if (a.dataset.morpheChecked === '1' && !force) return;
                                a.dataset.morpheChecked = '1';
                                a.dataset.morpheRawHref = href;
                                var state = 'available';
                                try { state = MorpheCatalog.status(href); } catch(e) {}
                                if (state === 'added' || state === 'queued') setState(a, state);
                                else clearState(a);
                              }

                              function observeAnchor(a) {
                                if (!a || !a.matches || !a.matches('a[href]') || !sourceInfo(a.getAttribute('href') || '')) return;
                                if (window.__morpheCatalogVisibilityObserver) {
                                  window.__morpheCatalogVisibilityObserver.observe(a);
                                } else decorateAnchor(a, false);
                              }

                              function scan(node) {
                                if (!node) return;
                                if (node.nodeType === 1) {
                                  observeAnchor(node);
                                  if (node.querySelectorAll) node.querySelectorAll('a[href]').forEach(observeAnchor);
                                } else if (node.querySelectorAll) {
                                  node.querySelectorAll('a[href]').forEach(observeAnchor);
                                }
                              }

                              // Only check source links when visible. A full catalogue can contain
                              // hundreds of links; synchronous bridge calls for every card freeze WebView.
                              window.__morpheCatalogVisibilityObserver = new IntersectionObserver(function(entries) {
                                entries.forEach(function(entry) {
                                  if (entry.isIntersecting) decorateAnchor(entry.target, true);
                                });
                              }, { root: null, rootMargin: '160px 0px', threshold: 0 });

                              if (!window.__morpheCatalogClickBound) {
                                window.__morpheCatalogClickBound = true;
                                document.addEventListener('click', function(ev) {
                                  var target = ev.target;
                                  var a = target && target.closest ? target.closest('a[href]') : null;
                                  if (!a) return;
                                  var href = a.getAttribute('href') || a.href;
                                  if (!sourceInfo(href)) return;
                                  ev.preventDefault();
                                  ev.stopPropagation();
                                  if (ev.stopImmediatePropagation) ev.stopImmediatePropagation();
                                  if (a.dataset.morpheState === 'added' || a.dataset.morpheState === 'queued' ||
                                      a.dataset.morpheState === 'pending') return;
                                  setState(a, 'pending');
                                  try { MorpheCatalog.addSource(href); } catch(e) { clearState(a); }
                                }, true);
                              }

                              // Keep in-catalog routes in this WebView. Source deep links are queued natively.
                              if (!window.__morpheCatalogOpenWrapped) {
                                window.__morpheCatalogOpenWrapped = true;
                                var originalOpen = window.open;
                                window.open = function(url) {
                                  var raw = String(url || '');
                                  if (sourceInfo(raw)) {
                                    try { MorpheCatalog.addSource(raw); } catch(e) {}
                                    return null;
                                  }
                                  try {
                                    var u = new URL(raw, location.href);
                                    if (u.hostname.toLowerCase() === 'morphe-patches.software' ||
                                        u.hostname.toLowerCase() === 'www.morphe-patches.software') {
                                      location.href = u.href;
                                    }
                                  } catch(e) {}
                                  return null;
                                };
                              }

                              scan(document);
                              window.__morpheCatalogRefresh = function() {
                                // Invalidate cached states, but let IntersectionObserver refresh only
                                // visible source buttons instead of synchronously querying the whole catalogue.
                                document.querySelectorAll('a[data-morphe-checked="1"]').forEach(function(a) {
                                  a.dataset.morpheChecked = '0';
                                  if (window.__morpheCatalogVisibilityObserver) {
                                    window.__morpheCatalogVisibilityObserver.unobserve(a);
                                    window.__morpheCatalogVisibilityObserver.observe(a);
                                  }
                                });
                              };
                              window.__morpheCatalogObserver = new MutationObserver(function(records) {
                                records.forEach(function(record) {
                                  if (record.type === 'attributes' && record.target && record.target.matches &&
                                      record.target.matches('a[href]')) {
                                    record.target.dataset.morpheChecked = '0';
                                    observeAnchor(record.target);
                                  }
                                  record.addedNodes && record.addedNodes.forEach(scan);
                                });
                              });
                              window.__morpheCatalogObserver.observe(document.documentElement, {
                                childList: true, subtree: true, attributes: true, attributeFilter: ['href']
                              });
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
