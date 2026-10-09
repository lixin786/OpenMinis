package com.openminis.app.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.browser.BrowserFingerprintProfile
import com.openminis.app.browser.BrowserFingerprintRegistry
import com.openminis.app.browser.BrowserTabPool

/**
 * [T-android-browser-fingerprint] The "Fingerprint accounts" section of the
 * browser settings sheet.
 *
 * Lets the user pick which per-account fingerprint identity new tabs use, or
 * turn the feature off entirely. Each account maps 1:1 to a stable profile
 * (UA, canvas, WebGL, screen, timezone, locale) so a returning "same device"
 * keeps reproducing the same values across sessions.
 *
 * This is the UI half of the layer; the injection itself lives in
 * BrowserFingerprintInjector and is applied per WebView at document start.
 *
 * Honest limits shown inline: cookies and the network exit IP are NOT isolated
 * by this (Android shares one CookieManager process-wide, and WebView has no
 * per-tab proxy). Users should not read this as full multi-account isolation.
 */
@Composable
fun FingerprintAccountsSection(tabPool: BrowserTabPool) {
    val context = LocalContext.current
    var currentId by remember { mutableStateOf(tabPool.currentFingerprintId.value) }
    // Re-read whenever the pool's flow changes.
    val flowId by tabPool.currentFingerprintId.collectAsState()
    if (flowId != currentId) currentId = flowId

    var accounts by remember { mutableStateOf(BrowserFingerprintRegistry.list(context)) }
    fun refresh() { accounts = BrowserFingerprintRegistry.list(context) }

    var showAdd by remember { mutableStateOf(false) }
    var newId by remember { mutableStateOf("") }

    Text("Fingerprint accounts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Give each account its own browser identity (canvas, WebGL, UA, screen, timezone). " +
            "New tabs use the selected account. Note: cookies and network IP are NOT isolated here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    // "Off" row
    FingerprintRow(
        label = "Off (default)",
        selected = currentId == null,
        onClick = { tabPool.setFingerprintAccount(null) },
    )

    accounts.forEach { acc ->
        FingerprintRow(
            label = acc.id,
            sub = acc.userAgent.substringAfter("; ").substringBefore(")").take(40),
            selected = currentId == acc.id,
            onClick = { tabPool.setFingerprintAccount(acc.id) },
            onDelete = {
                BrowserFingerprintRegistry.remove(context, acc.id)
                if (currentId == acc.id) tabPool.setFingerprintAccount(null)
                refresh()
            },
        )
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { showAdd = true }) {
            Icon(Icons.Filled.Add, contentDescription = "Add account")
        }
        Text("Add account", style = MaterialTheme.typography.bodyMedium)
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("New fingerprint account") },
            text = {
                Column {
                    Text(
                        "A stable identity is generated from this name. Use the same name to " +
                            "get the same device back later.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newId,
                        onValueChange = { newId = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' }.take(32) },
                        label = { Text("Account name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                }
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    val id = newId.trim()
                    if (id.isNotEmpty()) {
                        BrowserFingerprintRegistry.getOrCreate(context, id, id)
                        tabPool.setFingerprintAccount(id)
                        refresh()
                    }
                    newId = ""
                    showAdd = false
                }) { Text("Create") }
            },
            dismissButton = {
                MinisTextButton(onClick = { newId = ""; showAdd = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FingerprintRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    sub: String? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete $label")
            }
        }
    }
}
