package com.example.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.ui.file.FileScreen
import com.example.data.model.Sender
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class AppScreen {
    CHAT, MEMORIES, FILES
}

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val isIncognito by viewModel.isIncognito.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val currentConvId by viewModel.currentConversationId.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var currentScreen by remember { mutableStateOf(AppScreen.CHAT) }

    // Auto-scroll to bottom of the list when messages change
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.background,
                drawerTonalElevation = 0.dp,
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .testTag("navigation_drawer")
            ) {
                DrawerContent(
                    currentScreen = currentScreen,
                    onScreenSelected = { screen ->
                        currentScreen = screen
                        coroutineScope.launch { drawerState.close() }
                    },
                    conversations = conversations,
                    currentConversationId = currentConvId,
                    isIncognito = isIncognito,
                    onConversationSelected = { id ->
                        currentScreen = AppScreen.CHAT
                        viewModel.selectConversation(id)
                        coroutineScope.launch { drawerState.close() }
                    },
                    onDeleteConversation = { id ->
                        viewModel.deleteConversation(id)
                    },
                    onNewChatClicked = {
                        currentScreen = AppScreen.CHAT
                        viewModel.startNewConversation()
                        coroutineScope.launch { drawerState.close() }
                    },
                    onIncognitoToggled = { checked ->
                        viewModel.toggleIncognito(checked)
                        coroutineScope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        if (currentScreen == AppScreen.CHAT) {
            Scaffold(
                modifier = modifier
                    .fillMaxSize()
                    .testTag("chat_screen")
                    .background(MaterialTheme.colorScheme.background),
                contentWindowInsets = WindowInsets.safeDrawing
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    // 1. Premium Top Navigation Bar (with menu toggle & status indicator)
                    TopNavigationBar(
                        isIncognito = isIncognito,
                        onMenuClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            coroutineScope.launch { drawerState.open() }
                        },
                        onClearClick = {
                            viewModel.clearChat()
                            focusManager.clearFocus()
                        },
                        canClear = messages.isNotEmpty()
                    )

                    // Conversation / Empty State Area
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (messages.isEmpty()) {
                            EmptyStateScreen(
                                isIncognito = isIncognito,
                                onSuggestionClick = { suggestion ->
                                    viewModel.sendMessage(suggestion)
                                }
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                contentPadding = PaddingValues(vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(messages, key = { it.id }) { message ->
                                    MessageItem(message = message)
                                }
                            }
                        }
                    }

                    // 2. Phase 8D: In-App Proactive Delivery Card
                    val activeDelivery by viewModel.activeProactiveDelivery.collectAsState()
                    val checkedDelivery = viewModel.checkActiveProactiveDelivery()
                    if (checkedDelivery != null && !isIncognito) {
                        ProactiveDeliveryCard(
                            delivery = checkedDelivery,
                            onAcknowledge = { viewModel.acknowledgeProactiveDelivery(checkedDelivery.id) },
                            onDismiss = { viewModel.dismissProactiveDelivery(checkedDelivery.id) }
                        )
                    }

                    // 3. Message Input Area at the Bottom (Keyboard Aware)
                    InputArea(
                        text = inputText,
                        onTextChanged = { viewModel.updateInputText(it) },
                        onSendClicked = {
                            viewModel.sendMessage()
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        },
                        isThinking = isThinking
                    )
                }
            }
        } else if (currentScreen == AppScreen.MEMORIES) {
            com.example.ui.memory.MemoryScreen(
                viewModel = viewModel,
                onMenuClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    coroutineScope.launch { drawerState.open() }
                },
                modifier = modifier.fillMaxSize()
            )
        } else {
            FileScreen(
                onBack = { currentScreen = AppScreen.CHAT },
                modifier = modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun DrawerContent(
    currentScreen: AppScreen,
    onScreenSelected: (AppScreen) -> Unit,
    conversations: List<com.example.data.model.Conversation>,
    currentConversationId: String,
    isIncognito: Boolean,
    onConversationSelected: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onNewChatClicked: () -> Unit,
    onIncognitoToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // App Identity Header
        Text(
            text = "AETHER ASSIST",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 5.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
            ),
            modifier = Modifier.padding(vertical = 12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Actions: New Chat & Toggle Incognito
        Button(
            onClick = onNewChatClicked,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("drawer_new_chat_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "New Chat",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Navigation Workspace Section
        Text(
            text = "WORKSPACE",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
            ),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Assistant Chat Navigation Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (currentScreen == AppScreen.CHAT) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                    else Color.Transparent
                )
                .clickable { onScreenSelected(AppScreen.CHAT) }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("drawer_nav_chat"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = if (currentScreen == AppScreen.CHAT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Assistant Chat",
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = if (currentScreen == AppScreen.CHAT) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                    color = if (currentScreen == AppScreen.CHAT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                )
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Long-Term Memory Navigation Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (currentScreen == AppScreen.MEMORIES) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                    else Color.Transparent
                )
                .clickable { onScreenSelected(AppScreen.MEMORIES) }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("drawer_nav_memories"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = if (currentScreen == AppScreen.MEMORIES) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Long-Term Memory",
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = if (currentScreen == AppScreen.MEMORIES) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                    color = if (currentScreen == AppScreen.MEMORIES) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                )
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Local Files Navigation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (currentScreen == AppScreen.FILES) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                    else Color.Transparent
                )
                .clickable { onScreenSelected(AppScreen.FILES) }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("drawer_nav_files"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = if (currentScreen == AppScreen.FILES) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Files & Knowledge",
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = if (currentScreen == AppScreen.FILES) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                    color = if (currentScreen == AppScreen.FILES) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                )
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Mode Toggles Section
        Text(
            text = "MODES",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
            ),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VisibilityOff,
                            contentDescription = null,
                            tint = if (isIncognito) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Incognito Mode",
                            style = TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        )
                    }
                    Text(
                        text = "History will not be saved",
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Normal,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(
                    checked = isIncognito,
                    onCheckedChange = onIncognitoToggled,
                    modifier = Modifier.testTag("incognito_toggle_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        uncheckedTrackColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Historical Conversations Section Header
        Text(
            text = "RECENT CHATS",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
            ),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // List of conversations
        Box(modifier = Modifier.weight(1f)) {
            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No saved chats",
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                        )
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        val isSelected = conv.id == currentConversationId && !isIncognito
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                                    else Color.Transparent
                                )
                                .clickable { onConversationSelected(conv.id) }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .testTag("conversation_item_${conv.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = conv.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = 13.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                                    )
                                )
                                Text(
                                    text = formatTimestamp(conv.lastUpdated),
                                    style = TextStyle(
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                    ),
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            IconButton(
                                onClick = { onDeleteConversation(conv.id) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("delete_conversation_button_${conv.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete chat",
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Composable
fun TopNavigationBar(
    isIncognito: Boolean,
    onMenuClick: () -> Unit,
    onClearClick: () -> Unit,
    canClear: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left Area: Menu toggle and Identity
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(
                        onClick = onMenuClick,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("hamburger_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open Navigation Menu",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = "A E T H E R",
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 4.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    )

                    // Incognito Mode status banner pill
                    if (isIncognito) {
                        Box(
                            modifier = Modifier
                                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("incognito_status_badge")
                        ) {
                            Text(
                                text = "INCOGNITO",
                                style = TextStyle(
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 8.sp,
                                    letterSpacing = 1.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }

                // Right Area: Restart/Clear active context
                if (canClear) {
                    IconButton(
                        onClick = onClearClick,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("clear_chat_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Clear conversation",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Razor-thin divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline)
            )
        }
    }
}

@Composable
fun EmptyStateScreen(
    isIncognito: Boolean,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Minimalist Emblem
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    if (isIncognito) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f)
                )
                .border(
                    width = 1.dp,
                    color = if (isIncognito) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isIncognito) Icons.Default.VisibilityOff else Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Title
        Text(
            text = if (isIncognito) "INCOGNITO WORKSPACE" else "A E T H E R",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Light,
                fontSize = if (isIncognito) 18.sp else 24.sp,
                letterSpacing = if (isIncognito) 4.sp else 8.sp,
                color = MaterialTheme.colorScheme.onBackground
            ),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Subtitle
        Text(
            text = if (isIncognito) {
                "Incognito session active. Messages are completely transient and will never be saved to persistent database history."
            } else {
                "An autonomous cognitive workspace, designed for absolute focus and zero noise."
            },
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Active suggestions
        Column(
            modifier = Modifier.widthIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val suggestions = if (isIncognito) {
                listOf(
                    "Perform a transient security audit",
                    "Analyze ephemeral logic patterns",
                    "Explain state boundaries"
                )
            } else {
                listOf(
                    "Inquire about my capabilities",
                    "Draft a technical architecture roadmap",
                    "Explain quantum cryptography simply"
                )
            }

            suggestions.forEach { suggestion ->
                Surface(
                    onClick = { onSuggestionClick(suggestion) },
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .testTag("suggestion_card_${suggestion.take(10)}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = suggestion,
                            style = TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            modifier = Modifier
                                .size(14.dp)
                                .alpha(0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MessageItem(
    message: Message,
    modifier: Modifier = Modifier
) {
    val isUser = message.sender == Sender.USER

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("message_row_${message.id}"),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            // User Chat Bubble
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 2.dp, 18.dp))
                    .background(
                        if (MaterialTheme.colorScheme.primary == PureWhite) BubbleBgUserDark else BubbleBgUserLight
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = message.content,
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Normal,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        } else {
            // Assistant Chat Bubble & Structure
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.03f))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Aether",
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                    )

                    if (message.status == MessageStatus.THINKING) {
                        ThinkingIndicator(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.02f))
                        )
                    } else {
                        Text(
                            text = message.content,
                            style = TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Normal,
                                fontSize = 15.sp,
                                lineHeight = 23.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            ),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "Thinking")

    val dot1Alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                0.2f at 0
                1f at 300
                0.2f at 600
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "Dot1"
    )

    val dot2Alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                0.2f at 150
                1f at 450
                0.2f at 750
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "Dot2"
    )

    val dot3Alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                0.2f at 300
                1f at 600
                0.2f at 900
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "Dot3"
    )

    Row(
        modifier = modifier
            .padding(vertical = 10.dp, horizontal = 14.dp)
            .testTag("thinking_indicator"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val dotColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer(alpha = dot1Alpha)
                .background(dotColor, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer(alpha = dot2Alpha)
                .background(dotColor, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer(alpha = dot3Alpha)
                .background(dotColor, CircleShape)
        )
    }
}

@Composable
fun InputArea(
    text: String,
    onTextChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    isThinking: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (MaterialTheme.colorScheme.primary == PureWhite) InputBgDark else InputBgLight
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = "Message Aether...",
                            style = TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        )
                    }

                    BasicTextField(
                        value = text,
                        onValueChange = onTextChanged,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Normal,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (text.isNotBlank() && !isThinking) {
                                    onSendClicked()
                                }
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("message_input_field"),
                        enabled = !isThinking
                    )
                }

                val isEnabled = text.isNotBlank() && !isThinking
                val sendButtonColor = MaterialTheme.colorScheme.primary
                val onSendButtonColor = MaterialTheme.colorScheme.onPrimary

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isEnabled) sendButtonColor else sendButtonColor.copy(alpha = 0.2f)
                        )
                        .clickable(enabled = isEnabled) {
                            onSendClicked()
                        }
                        .testTag("send_message_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Send Message",
                        tint = if (isEnabled) onSendButtonColor else onSendButtonColor.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ProactiveDeliveryCard(
    delivery: com.example.ai.proactive.ProactiveDelivery,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("proactive_delivery_card_${delivery.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header with Icon and Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = delivery.title.uppercase(),
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp,
                        color = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("proactive_dismiss_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Message Content
            Text(
                text = delivery.message,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                modifier = Modifier.testTag("proactive_message")
            )

            if (delivery.explanation.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                // Context/Reason
                Text(
                    text = delivery.explanation,
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Normal,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.testTag("proactive_explanation")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onAcknowledge,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("proactive_acknowledge_button")
                ) {
                    Text(
                        text = "ACKNOWLEDGE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}
