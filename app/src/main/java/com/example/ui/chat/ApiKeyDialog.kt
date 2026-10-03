package com.example.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
    val uriHandler = LocalUriHandler.current

    var geminiKey by remember { mutableStateOf(ApiKeyStorage.getGeminiKey(context)) }
    var openRouterKey by remember { mutableStateOf(ApiKeyStorage.getOpenRouterKey(context)) }
    var groqKey by remember { mutableStateOf(ApiKeyStorage.getGroqKey(context)) }

    var showGeminiSecret by remember { mutableStateOf(false) }
    var showOpenRouterSecret by remember { mutableStateOf(false) }
    var showGroqSecret by remember { mutableStateOf(false) }

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
                    text = "API Key Configuration",
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
                    text = "Enter at least one API key below to unlock AI responses in Aether.",
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                )

                // 1. Gemini Key Field
                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini API Key (Recommended)", fontSize = 12.sp) },
                    placeholder = { Text("AIzaSy...", fontSize = 12.sp) },
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

                // Quick Get Free Gemini Key Link
                TextButton(
                    onClick = {
                        try {
                            uriHandler.openUri("https://aistudio.google.com/app/apikey")
                        } catch (e: Exception) { e.printStackTrace() }
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Get free Gemini Key from Google AI Studio",
                            style = TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                // 2. OpenRouter / Qwen Key Field
                OutlinedTextField(
                    value = openRouterKey,
                    onValueChange = { openRouterKey = it },
                    label = { Text("OpenRouter / Qwen Key", fontSize = 12.sp) },
                    placeholder = { Text("sk-or-v1-...", fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = if (showOpenRouterSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showOpenRouterSecret = !showOpenRouterSecret }) {
                            Text(if (showOpenRouterSecret) "Hide" else "Show", fontSize = 10.sp)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_openrouter_api_key"),
                    textStyle = TextStyle(fontSize = 13.sp)
                )

                // 3. Groq Key Field
                OutlinedTextField(
                    value = groqKey,
                    onValueChange = { groqKey = it },
                    label = { Text("Groq API Key", fontSize = 12.sp) },
                    placeholder = { Text("gsk_...", fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = if (showGroqSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showGroqSecret = !showGroqSecret }) {
                            Text(if (showGroqSecret) "Hide" else "Show", fontSize = 10.sp)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_groq_api_key"),
                    textStyle = TextStyle(fontSize = 13.sp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    ApiKeyStorage.saveGeminiKey(context, geminiKey)
                    ApiKeyStorage.saveOpenRouterKey(context, openRouterKey)
                    ApiKeyStorage.saveGroqKey(context, groqKey)
                    onKeysSaved()
                    onDismiss()
                },
                modifier = Modifier.testTag("save_api_keys_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Save & Connect", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
