package com.claudeforwatch.phone

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.wearable.MessageClient

/**
 * Phone companion: signs in (personal flavor) or takes an API key and sends it to the Claude for
 * Watch app on every connected watch over the Wear Data Layer. Stores nothing on the phone.
 */
class MainActivity : ComponentActivity() {
    private val vm: PhoneViewModel by viewModels()
    private var resultListener: MessageClient.OnMessageReceivedListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneTheme {
                PhoneScreen(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Watch replies are only shown while the activity is visible.
        resultListener = vm.link.addResultListener { nodeId, text -> vm.onWatchResult(nodeId, text) }
        vm.refreshWatches()
    }

    override fun onStop() {
        resultListener?.let { vm.link.removeResultListener(it) }
        resultListener = null
        super.onStop()
    }
}

private fun openCustomTab(context: Context, url: String): Boolean = try {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
    true
} catch (e: ActivityNotFoundException) {
    false
}

private fun clipboardText(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString()

private const val WARNING =
    "Signing in with your Claude account uses the same private sign-in that Claude Code and the " +
        "Claude app use. Anthropic does not support third-party apps using it and may block or " +
        "suspend accounts that do. Use it only with your own account, at your own risk. The " +
        "supported option is an API key."

@Composable
private fun PhoneScreen(vm: PhoneViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var browserMissing by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.openUrl.collect { url ->
            browserMissing = !openCustomTab(context, url)
            if (!browserMissing) vm.onTabOpened() else vm.showPasteFallback()
        }
    }
    LifecycleResumeEffect(vm) {
        vm.onResumed()
        onPauseOrDispose { }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Claude for Watch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Sends a sign-in to the Claude for Watch app on your paired Wear OS watch, so you don't " +
                    "have to type on the watch. Nothing is stored on this phone: credentials stay in " +
                    "memory until they're sent.",
                style = MaterialTheme.typography.bodyMedium,
            )

            WatchesCard(ui.watches, onRefresh = vm::refreshWatches)

            if (BuildConfig.PERSONAL_MODE) {
                ClaudeAccountCard(ui.signIn, vm, browserMissing)
            }

            ApiKeyCard(ui.apiKeyError, vm, onPaste = { clipboardText(context)?.let { vm.onApiKeyChanged(it.trim()) } })

            ui.notice?.let { NoticeCard(it) }
            if (ui.deliveries.isNotEmpty()) DeliveriesCard(ui.deliveries)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun WatchesCard(state: WatchesState, onRefresh: () -> Unit) = Section("Watches") {
    when (state) {
        WatchesState.Loading -> Text("Looking for connected watches…", style = MaterialTheme.typography.bodyMedium)
        is WatchesState.Unavailable -> Text(state.message, style = MaterialTheme.typography.bodyMedium)
        is WatchesState.Ready ->
            if (state.watches.isEmpty()) {
                Text(
                    "No watch connected. Pair the watch with this phone in the Wear OS app (or Galaxy Wearable), " +
                        "keep it nearby, and install Claude for Watch on it (same build as this app: " +
                        (if (BuildConfig.PERSONAL_MODE) "personal" else "store") + ").",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.watches.forEach { Text("• ${it.name}", style = MaterialTheme.typography.bodyLarge) }
            }
    }
    TextButton(onClick = onRefresh) { Text("Refresh") }
}

@Composable
private fun ClaudeAccountCard(state: SignInState, vm: PhoneViewModel, browserMissing: Boolean) = Section("Claude account") {
    var understood by remember { mutableStateOf(false) }
    when (state) {
        SignInState.Idle, is SignInState.Failed -> {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    WARNING,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = understood, onCheckedChange = { understood = it })
                Text("I understand", style = MaterialTheme.typography.bodyMedium)
            }
            if (state is SignInState.Failed) ErrorText(state.message)
            Button(onClick = vm::startSignIn, enabled = understood, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in with Claude and send to watch")
            }
        }
        is SignInState.Waiting -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Finish signing in in the browser, then come back here.", style = MaterialTheme.typography.bodyMedium)
            }
            if (browserMissing) ErrorText("No browser found to open the sign-in page.")
            if (state.showPaste) {
                PasteCodeFallback(vm)
            } else {
                TextButton(onClick = vm::showPasteFallback) { Text("Browser didn't come back? Paste the code instead") }
            }
            OutlinedButton(onClick = vm::cancelSignIn) { Text("Cancel") }
        }
        SignInState.Exchanging -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Signing in…", style = MaterialTheme.typography.bodyMedium)
        }
        is SignInState.Ready -> {
            Text("Signed in as ${state.who}. Sent to your watch (see below).", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::resendRecord) { Text("Send again") }
                OutlinedButton(onClick = vm::forgetRecord) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun PasteCodeFallback(vm: PhoneViewModel) {
    Text("Paste the code instead", style = MaterialTheme.typography.titleSmall)
    Text(
        "If the browser can't return to this app, open the code page, sign in, copy the code it shows " +
            "(it looks like abc…#xyz…) and paste it here.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(onClick = vm::openCodePage) { Text("Open the code page") }
    OutlinedTextField(
        value = vm.codeInput,
        onValueChange = { vm.codeInput = it },
        label = { Text("Code") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.submitPastedCode() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = vm::submitPastedCode, enabled = vm.codeInput.isNotBlank()) { Text("Use this code") }
}

@Composable
private fun ApiKeyCard(error: String?, vm: PhoneViewModel, onPaste: () -> Unit) = Section("Send an API key to the watch") {
    Text(
        "A Console API key (sk-ant-…). If the watch is signed in with Claude, the key is used for chat only and sessions keep your Claude sign-in. Max and Team plans include monthly API credits for it.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = vm.apiKeyInput,
        onValueChange = vm::onApiKeyChanged,
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { vm.sendApiKey() }),
        isError = error != null,
        modifier = Modifier.fillMaxWidth(),
    )
    if (error != null) ErrorText(error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPaste) { Text("Paste") }
        Button(onClick = vm::sendApiKey, enabled = vm.apiKeyInput.isNotBlank()) { Text("Send to watch") }
    }
}

@Composable
private fun NoticeCard(text: String) = Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
) {
    Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun DeliveriesCard(deliveries: List<Delivery>) = Section("Result") {
    deliveries.forEach { d ->
        Column {
            Text(d.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            val color = when (d.status) {
                Delivery.Status.Ok -> MaterialTheme.colorScheme.primary
                Delivery.Status.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(d.detail, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
private fun ErrorText(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)

private val Accent = Color(0xFFD97757)

@Composable
private fun PhoneTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Accent)
        else -> lightColorScheme(primary = Accent)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
