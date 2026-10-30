package com.example.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.ApiKeyStorage

@Composable
fun ApiKeyDialog(
    onDismiss: () -> Unit,
    onKeysSaved: () -> Unit
) {
    val context = LocalContext.current
    var geminiKey by remember { mutableStateOf(ApiKeyStorage.getGeminiKey(context).let { if (it == ApiKeyStorage.PROXY_SENTINEL_KEY) "" else it }) }
    var proxyUrl by remember { mutableStateOf(ApiKeyStorage.getGeminiProxyBaseUrl(context)) }
    var showGeminiSecret by remember { mutableStateOf(false) }

    val audit = remember(geminiKey, proxyUrl) { ApiKeyStorage.auditSecurity(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("api_key_dialog"),
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "API Key / Backend Configuration",
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Enter your Google Gemini API key to enable chat and autonomous capabilities on this device.",
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini API Key (AIzaSy...)", fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = if (showGeminiSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showGeminiSecret = !showGeminiSecret }) {
                            Text(if (showGeminiSecret) "Hide" else "Show", fontSize = 10.sp)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_gemini_api_key"),
                    textStyle = TextStyle(fontSize = 13.sp)
                )

                OutlinedTextField(
                    value = proxyUrl,
                    onValueChange = { proxyUrl = it },
                    label = { Text("Optional Backend Proxy URL (https://...)", fontSize = 12.sp) },
                    placeholder = { Text("https://my-proxy.com/api/", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_proxy_url"),
                    textStyle = TextStyle(fontSize = 13.sp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    ApiKeyStorage.saveGeminiKey(context, geminiKey)
                    ApiKeyStorage.saveGeminiProxyBaseUrl(context, proxyUrl)
                    onKeysSaved()
                    onDismiss()
                },
                modifier = Modifier.testTag("save_api_keys_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Save", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_api_keys_button")
            ) {
                Text("Cancel", fontSize = 13.sp)
            }
        }
    )
}
