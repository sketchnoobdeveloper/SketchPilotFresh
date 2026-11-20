package snd.sketchpilot.app

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import snd.sketchpilot.core.agent.AgentRuntime
import snd.sketchpilot.core.agent.AgentTask
import snd.sketchpilot.core.project.Diff
import snd.sketchpilot.core.project.OfflineProjectRepository
import snd.sketchpilot.core.project.TransactionManager
import snd.sketchpilot.core.security.AuditLog
import snd.sketchpilot.core.security.Capability
import snd.sketchpilot.core.security.PermissionPolicy
import snd.sketchpilot.core.sketchware.ProjectStore
import snd.sketchpilot.core.tools.ToolRegistry
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val failure = runCatching { start() }.exceptionOrNull()
        if (failure != null) {
            val message = failure.stackTraceToString()
            android.util.Log.e("SketchPilot", "startup failed", failure)
            runCatching {
                setContent {
                    SketchPilotTheme {
                        Text("SketchPilot failed to start:\n$message")
                    }
                }
            }
        }
    }

    private fun start() {
        val secrets = SecretStore(this)
        val storageRoot = Environment.getExternalStorageDirectory()
        val repository = OfflineProjectRepository(storageRoot)
        val store = ProjectStore(storageRoot)
        val transactions = TransactionManager(store, File(cacheDir, "backups"))
        val registry = ToolRegistry(
            repository,
            PermissionPolicy(
                setOf(Capability.READ, Capability.WRITE, Capability.MCP_CONTROL),
            ),
            AuditLog(),
            transactions,
            store,
        )
        val runtime = AgentRuntime { tool, args -> registry.call(tool, args) }
        setContent { SketchPilotTheme { Root(registry, secrets, runtime) } }
    }
}

@Composable
private fun SketchPilotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(primary = Color(0xFF9BB7FF), secondary = Color(0xFF4DD0E1))
    } else {
        lightColorScheme(primary = Color(0xFF3F51B5), secondary = Color(0xFF00838F))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun Root(registry: ToolRegistry, secrets: SecretStore, runtime: AgentRuntime) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Dashboard", "Projects", "Agent", "Changes", "Providers", "MCP")
    Scaffold(bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { index, label ->
                NavigationBarItem(
                    selected = tab == index,
                    onClick = { tab = index },
                    icon = {},
                    label = { Text(label) },
                )
            }
        }
    }) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Column(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> Dashboard()
                    1 -> Projects(registry)
                    2 -> AgentPanel(registry, runtime)
                    3 -> ChangesPanel(registry)
                    4 -> Providers(secrets)
                    5 -> McpPanel(registry, secrets)
                }
            }
        }
    }
}

@Composable
private fun Dashboard() {
    val context = LocalContext.current
    val hostVersion = remember {
        runCatching { context.packageManager.getPackageInfo("pro.sketchware", 0).versionName }
            .getOrDefault("not installed")
    }
    Text("SketchPilot", style = MaterialTheme.typography.headlineMedium)
    Text("Host: pro.sketchware ($hostVersion)")
    Text("Mode: Read Only")
    Text("Integration: LSPosed native entry (device verification pending)")
    val crash = remember { Diagnostics.lastCrash(context) }
    if (crash != null) {
        Text("Last crash:\n$crash", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Projects(registry: ToolRegistry) {
    var result by remember { mutableStateOf("Loading...") }
    LaunchedEffect(Unit) {
        result = withContext(Dispatchers.IO) {
            registry.call("sketchpilot.project.list", emptyMap()).content
        }
    }
    Text("Projects", style = MaterialTheme.typography.headlineSmall)
    Text(result)
}

@Composable
private fun ChangesPanel(registry: ToolRegistry) {
    var revision by remember { mutableIntStateOf(0) }
    val changes = remember(revision) { registry.stagedChanges() }
    Text("Changes", style = MaterialTheme.typography.headlineSmall)
    if (changes.isEmpty()) {
        Text("No staged changes. Use an edit tool or ask the agent to stage one.")
    }
    changes.forEach { changeSet ->
        androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(changeSet.scId + " · " + changeSet.id.take(8))
                Text(Diff.render(changeSet))
                Row {
                    Button(onClick = {
                        registry.call(
                            "sketchpilot.change.commit",
                            mapOf("change_set_id" to changeSet.id, "approved" to "true"),
                        )
                        revision++
                    }) { Text("Approve") }
                    Button(onClick = {
                        registry.call("sketchpilot.change.rollback", mapOf("change_set_id" to changeSet.id))
                        revision++
                    }) { Text("Rollback") }
                }
            }
        }
    }
}

@Composable
private fun AgentPanel(registry: ToolRegistry, runtime: AgentRuntime) {
    var instruction by remember { mutableStateOf("inspect the ui layout") }
    var trace by remember { mutableStateOf("Ready.") }
    Text("Agent", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(
        instruction,
        { instruction = it },
        label = { Text("Instruction") },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = {
        val projectId = runCatching {
            val raw = registry.call("sketchpilot.project.list").content
            Regex("\"sc_id\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
        }.getOrNull()
        val task = AgentTask("ui-" + System.currentTimeMillis(), instruction, projectId)
        val state = runtime.plan(task, registry.list().map { it.name }.toSet())
        val finished = runtime.run(state)
        trace = finished.trace.joinToString("\n") + "\nstatus: ${finished.status}"
    }) { Text("Plan and run") }
    Text(trace)
}

@Composable
private fun Providers(secrets: SecretStore) {
    var base by remember { mutableStateOf(secrets.read("provider_base").orEmpty()) }
    var model by remember { mutableStateOf(secrets.read("provider_model").orEmpty()) }
    var key by remember { mutableStateOf("") }
    Text("Providers", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(base, { base = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(model, { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        key,
        { key = it },
        label = { Text("API key") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = {
        secrets.save("provider_base", base)
        secrets.save("provider_model", model)
        if (key.isNotBlank()) secrets.save("provider_key", key)
    }) { Text("Save securely") }
}

@Composable
private fun McpPanel(registry: ToolRegistry, secrets: SecretStore) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(McpService.isRunning) }
    var token by remember { mutableStateOf(secrets.read("mcp_token").orEmpty()) }
    LaunchedEffect(Unit) {
        if (token.isBlank()) {
            token = UUID.randomUUID().toString()
            secrets.save("mcp_token", token)
        }
    }
    Text("MCP Server", style = MaterialTheme.typography.headlineSmall)
    Text("Loopback only: http://127.0.0.1:8765/mcp")
    Text(if (running) "Status: running" else "Status: stopped")
    Button(onClick = {
        if (running) context.stopService(Intent(context, McpService::class.java))
        else context.startService(Intent(context, McpService::class.java))
        running = !running
    }) { Text(if (running) "Stop" else "Start") }
    Text("Pairing token: $token")
    Text("Tools:\n" + registry.list().joinToString("\n") { it.name })
}
