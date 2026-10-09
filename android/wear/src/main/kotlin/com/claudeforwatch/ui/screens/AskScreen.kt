package com.claudeforwatch.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.claudeforwatch.R
import com.claudeforwatch.appGraph
import com.claudeforwatch.core.store.ChatRole
import com.claudeforwatch.platform.StreamingOngoingActivity
import com.claudeforwatch.platform.rememberTextInputLauncher
import com.claudeforwatch.ui.CenteredBox
import com.claudeforwatch.ui.ChatBubble
import com.claudeforwatch.ui.ListButton
import com.claudeforwatch.ui.ListScreen
import com.claudeforwatch.ui.Paragraph
import com.claudeforwatch.ui.vm.ChatViewModel

/** One big mic button → dictation → streamed reply, optional read-aloud (PLAN §2). */
@Composable
fun AskScreen(onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val vm: ChatViewModel = viewModel(key = "ask") { ChatViewModel(graph, context, null) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var askedNotifications by rememberSaveable { mutableStateOf(false) }

    val input = rememberTextInputLauncher { text ->
        vm.startNewThread()
        vm.send(text)
    }
    // The Ongoing Activity chip needs POST_NOTIFICATIONS on API 33+; ask once, then dictate.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        input.dictate()
    }
    val ask = {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications && !StreamingOngoingActivity.canPost(context)) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            input.dictate()
        }
    }

    LifecycleStartEffect(vm) {
        onStopOrDispose { vm.stop() }
    }

    val thread = ui.thread
    if (thread == null && !ui.isStreaming) {
        ScreenScaffold {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Ask Claude", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    FilledIconButton(onClick = ask, modifier = Modifier.size(88.dp)) {
                        Icon(painterResource(R.drawable.ic_mic), contentDescription = "Dictate a question", modifier = Modifier.size(40.dp))
                    }
                    TextButton(onClick = { input.type("Ask Claude") }) { Text("Type") }
                    ui.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        return
    }

    val question = thread?.messages?.lastOrNull { it.role == ChatRole.User }?.text
    val answer = ui.streamingText ?: thread?.messages?.lastOrNull()?.takeIf { it.role == ChatRole.Assistant }?.text
    ListScreen(
        edgeButton = {
            EdgeButton(onClick = ask, enabled = !ui.isStreaming) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = "Ask again")
            }
        },
    ) { spec ->
        question?.let { q -> item { ChatBubble(q, fromUser = true) } }
        when {
            ui.isStreaming && answer.isNullOrEmpty() -> item { CenteredBox { CircularProgressIndicator() } }
            answer != null -> item { ChatBubble(answer, fromUser = false) }
            // Finished without an answer (an error is shown below): no spinner.
        }
        ui.error?.let { msg -> item { Paragraph(msg, spec, color = MaterialTheme.colorScheme.error) } }
        item { ReadAloudSwitch(ui.readAloud, vm::setReadAloud, spec) }
        if (thread != null && !ui.isStreaming) {
            item { ListButton("Continue in Chats", spec, onClick = { onOpenChat(thread.id) }, iconRes = R.drawable.ic_chat) }
        }
    }
}

@Composable
fun TransformingLazyColumnItemScope.ReadAloudSwitch(checked: Boolean, onChange: (Boolean) -> Unit, spec: TransformationSpec) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        label = { Text("Read aloud") },
    )
}
