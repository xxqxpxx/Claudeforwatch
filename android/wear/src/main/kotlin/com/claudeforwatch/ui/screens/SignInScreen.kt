package com.claudeforwatch.ui.screens

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Dialog
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.claudeforwatch.R
import com.claudeforwatch.appGraph
import com.claudeforwatch.platform.QrCode
import com.claudeforwatch.platform.rememberTextInputLauncher
import com.claudeforwatch.ui.Header
import com.claudeforwatch.ui.ListButton
import com.claudeforwatch.ui.ListScreen
import com.claudeforwatch.ui.Paragraph
import com.claudeforwatch.ui.vm.SignInStep
import com.claudeforwatch.ui.vm.SignInViewModel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** docs/PROTOCOL.md §1.2, verbatim. Shown before the Claude-account flow. */
const val ACCOUNT_WARNING =
    "Signing in with your Claude account uses the same private sign-in that Claude Code and the Claude app use. " +
        "Anthropic does not support third-party apps using it and may block or suspend accounts that do. " +
        "Use it only with your own account, at your own risk. The supported option is an API key."

/**
 * API key (both builds) via the RemoteInput sheet, so the key can be pasted from the phone.
 * PERSONAL_MODE adds: warning → QR of the authorize URL (+ "Open on phone") → "Enter code".
 */
@Composable
fun SignInScreen(onDone: () -> Unit) {
    val graph = LocalContext.current.appGraph
    val vm: SignInViewModel = viewModel { SignInViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val keyInput = rememberTextInputLauncher { vm.submitApiKey(it) }
    val codeInput = rememberTextInputLauncher { vm.submitCode(it) }

    LaunchedEffect(ui.step) { if (ui.step == SignInStep.Done) onDone() }

    when (val step = ui.step) {
        SignInStep.Choose, SignInStep.Done -> ListScreen { spec ->
            item { Header("Sign in", spec) }
            item {
                ListButton(
                    "Use an API key", spec, primary = true,
                    onClick = { keyInput.type("Paste API key", allowEmoji = false) },
                    secondary = "Paste with your phone keyboard",
                    iconRes = R.drawable.ic_keyboard,
                )
            }
            if (ui.personalMode) {
                item {
                    ListButton("Sign in with Claude", spec, onClick = vm::beginClaudeAccount, secondary = "Personal build only", iconRes = R.drawable.ic_claude)
                }
            }
            ui.error?.let { msg -> item { Paragraph(msg, spec, color = MaterialTheme.colorScheme.error) } }
            item {
                Paragraph(
                    "Create a key at platform.claude.com. Max and Team plans include monthly API credits.",
                    spec,
                )
            }
        }
        SignInStep.Working -> ScreenScaffold { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        SignInStep.Warning -> ListScreen { spec ->
            item { Header("Before you continue", spec) }
            item { Paragraph(ACCOUNT_WARNING, spec, color = MaterialTheme.colorScheme.onSurface, center = false) }
            item { ListButton("I understand", spec, onClick = vm::acceptWarning, primary = true) }
            item { ListButton("Use an API key instead", spec, onClick = vm::back) }
        }
        is SignInStep.Qr -> QrStep(
            url = step.authorizeUrl,
            error = ui.error,
            onEnterCode = { codeInput.type("Paste code", allowEmoji = false) },
            onNewCode = vm::showQr,
            onCancel = vm::back,
            onDismissError = vm::dismissError,
        )
    }
}

@Composable
private fun QrStep(
    url: String,
    error: String?,
    onEnterCode: () -> Unit,
    onNewCode: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val helper = remember { RemoteActivityHelper(context) }
    val phoneStatus by helper.availabilityStatus.collectAsState(initial = RemoteActivityHelper.STATUS_UNKNOWN)
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var phoneMessage by remember { mutableStateOf<String?>(null) }

    // Round screens: keep the square (and its finder patterns) inside the circle.
    val fraction = if (config.isScreenRound) 0.56f else 0.8f
    val sideDp = (config.screenWidthDp * fraction).dp
    val sidePx = with(density) { sideDp.toPx() }.roundToInt().coerceAtLeast(64)
    val bitmap = remember(url, sidePx) { QrCode.render(url, sidePx).asImageBitmap() }

    ScreenScaffold(scrollState = rememberTransformingLazyColumnState(), scrollIndicator = null, edgeButton = { EdgeButton(onClick = onEnterCode) { Text("Enter code") } }) { _ ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = (-(config.screenHeightDp * 0.08f)).dp)) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "QR code with the Claude sign-in link. Tap for options.",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(sideDp).clickable { optionsOpen = true },
                )
                Text("Scan · tap for options", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    Dialog(visible = optionsOpen || error != null, onDismissRequest = { optionsOpen = false; onDismissError() }) {
        ListScreen { spec ->
            error?.let { msg -> item { Paragraph(msg, spec, color = MaterialTheme.colorScheme.error) } }
            item { Paragraph("Sign in on your phone, copy the code, then tap Enter code.", spec) }
            if (phoneStatus != RemoteActivityHelper.STATUS_UNAVAILABLE) {
                item {
                    ListButton("Open on phone", spec, iconRes = R.drawable.ic_phone, secondary = phoneMessage, onClick = {
                        scope.launch {
                            phoneMessage = try {
                                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
                                helper.startRemoteActivity(intent).await()
                                "Check your phone"
                            } catch (e: Exception) {
                                "Couldn't reach the phone. Scan the QR instead."
                            }
                        }
                    })
                }
            }
            item { ListButton("Enter code", spec, primary = true, onClick = { optionsOpen = false; onDismissError(); onEnterCode() }) }
            item { ListButton("New code", spec, onClick = { optionsOpen = false; onDismissError(); onNewCode() }) }
            item { ListButton("Cancel", spec, onClick = { optionsOpen = false; onDismissError(); onCancel() }) }
        }
    }
}
