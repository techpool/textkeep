package io.github.techpool.textkeep

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

private fun messageDate(time: Long): String {
    val date = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    return when(date.toLocalDate()) {
        today -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern(if(date.year == today.year) "d MMM" else "d MMM yyyy"))
    }
}

@Composable fun SenderAvatar(address: String, code: Boolean = false, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(48.dp).clip(CircleShape).background(if(code) colors.tertiaryContainer else colors.secondaryContainer), contentAlignment = Alignment.Center) {
        if(code) Icon(Icons.Outlined.Password, null, tint = colors.onTertiaryContainer, modifier = Modifier.size(23.dp))
        else if(address.any { it.isLetter() }) Text(address.filter { it.isLetter() }.take(2).uppercase(), style = MaterialTheme.typography.titleSmall, color = colors.onSecondaryContainer)
        else Icon(Icons.Outlined.PersonOutline, null, tint = colors.onSecondaryContainer, modifier = Modifier.size(25.dp))
    }
}

@Composable fun EmptyMessages(title: String, detail: String, icon: ImageVector = Icons.Outlined.Forum) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(icon, null, Modifier.padding(22.dp).size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable fun InboxScreen(
    messages: List<LocalMessage>, loading: Boolean, search: String, onSearch: (String) -> Unit,
    filter: InboxFilter, onFilter: (InboxFilter) -> Unit, pinned: Set<String>, archived: Set<String>,
    onOpen: (LocalMessage) -> Unit, onPin: (String) -> Unit, onArchive: (String) -> Unit,
    onLoadMore: () -> Unit, onCopy: (String) -> Unit
) {
    val threads = remember(messages, search, filter, pinned, archived) { organizeInbox(messages, search, filter, pinned, archived) }
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    LaunchedEffect(search, filter) { listState.scrollToItem(0) }
    var menu by remember { mutableStateOf<Conversation?>(null) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(search, onSearch, Modifier.fillMaxWidth().padding(horizontal = 20.dp), placeholder = { Text("Search messages or senders", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if(search.isNotEmpty()) IconButton(onClick = { onSearch("") }) { Icon(Icons.Outlined.Close, "Clear search") } },
            singleLine = true, shape = RoundedCornerShape(28.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InboxFilter.entries.forEach { item -> FilterChip(filter == item, onClick = { focus.clearFocus(); onFilter(item) }, label = { Text(item.label) },
                leadingIcon = if(filter == item) { { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) } } else null) }
        }
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 100.dp)) {
            if(threads.isEmpty() && !loading) item {
                EmptyMessages(if(search.isNotBlank()) "No matches yet" else when(filter) { InboxFilter.UNREAD -> "You're all caught up"; InboxFilter.CODES -> "Your codes, in one place"; InboxFilter.ARCHIVED -> "A little less clutter"; else -> "A fresh start" },
                    if(search.isNotBlank()) "Try another word or load older messages." else when(filter) { InboxFilter.UNREAD -> "New unread conversations will appear here."; InboxFilter.CODES -> "Messages with recognized verification codes appear here. Detection may miss some codes."; InboxFilter.ARCHIVED -> "Archive conversations from their menu. They stay on your phone and can be restored."; else -> "Your SMS and MMS conversations will appear here." })
            }
            items(threads, key = { it.key }) { thread ->
                val message = thread.preview
                val code = remember(message.body) { OtpParser.parse(message.body) }
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(thread.latest) }, onLongClick = { menu = thread }).padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SenderAvatar(thread.latest.address, code != null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(thread.latest.address, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = if(thread.unread > 0) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if(thread.pinned) Icon(Icons.Outlined.PushPin, "Pinned", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(messageDate(thread.latest.date), style = MaterialTheme.typography.labelSmall, color = if(thread.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((if(message.type == 3) "Draft · " else if(message.type != 1) "You · " else "") + message.body.replace('\n',' '), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if(thread.unread > 0) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(if(thread.unread > 99) "99+" else thread.unread.toString()) }
                        }
                        if(filter == InboxFilter.CODES && code != null) SuggestionChip(onClick = { onCopy(code) }, label = { Text("Copy $code") }, icon = { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp)) })
                    }
                    Box {
                        IconButton(onClick = { menu = thread }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreVert, "Options for ${thread.latest.address}", Modifier.size(20.dp)) }
                        DropdownMenu(expanded = menu?.key == thread.key, onDismissRequest = { menu = null }) {
                            DropdownMenuItem(text = { Text(if(thread.pinned) "Unpin conversation" else "Pin conversation") }, onClick = { onPin(thread.key); menu = null }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) })
                            DropdownMenuItem(text = { Text(if(thread.key in archived) "Restore to inbox" else "Archive conversation") }, onClick = { onArchive(thread.key); menu = null }, leadingIcon = { Icon(Icons.Outlined.Archive, null) })
                        }
                    }
                }
            }
            item { Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = onLoadMore, enabled = !loading) { Text("Load older messages") }
                Text("Search and filters cover the messages loaded on this phone.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
}

@Composable fun ConversationScreen(
    entries: List<LocalMessage>, reply: String, onReplyChange: (String) -> Unit, onSend: () -> Unit,
    onComposeOptions: () -> Unit, busy: Boolean, onDelete: (LocalMessage) -> Unit,
    onEdit: (LocalMessage) -> Unit, onRetry: (LocalMessage) -> Unit, onOpenPart: (Long) -> Unit,
    onDownload: (LocalMessage) -> Unit, onCopy: (String) -> Unit, onLoadMore: () -> Unit
) {
    val listState = rememberLazyListState()
    val sorted = remember(entries) { entries.sortedByDescending { it.date } }
    LaunchedEffect(sorted.firstOrNull()?.key) { if(listState.firstVisibleItemIndex < 2) listState.animateScrollToItem(0) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), state = listState, reverseLayout = true, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(sorted, key = { it.key }) { entry ->
                val outgoing = entry.type != 1
                var menu by remember { mutableStateOf(false) }
                val code = remember(entry.body) { OtpParser.parse(entry.body) }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if(outgoing) Alignment.End else Alignment.Start) {
                    Surface(modifier = Modifier.widthIn(max = 340.dp).combinedClickable(onClick = {}, onLongClick = { menu = true }),
                        color = if(outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = if(outgoing) 6.dp else 22.dp, bottomStart = if(outgoing) 22.dp else 6.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectionContainer { Text(entry.body, style = MaterialTheme.typography.bodyLarge) }
                            if(code != null) FilledTonalButton(onClick = { onCopy(code) }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Copy $code") }
                            if(entry.mms) {
                                Regex("\\[part ([0-9]+)\\]").findAll(entry.body).forEach { part -> TextButton(onClick = { onOpenPart(part.groupValues[1].toLong()) }) { Icon(Icons.Outlined.Attachment, null); Spacer(Modifier.width(6.dp)); Text("Open attachment") } }
                                if(entry.location.isNotBlank()) TextButton(onClick = { onDownload(entry) }) { Text("Download MMS") }
                            }
                            if((entry.mms && entry.type == 4 && entry.carrierFailed) || (!entry.mms && entry.type == 5)) TextButton(onClick = { onRetry(entry) }) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Text("Retry sending") }
                            if(!entry.mms && entry.type == 3) TextButton(onClick = { onEdit(entry) }) { Text("Edit draft") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val state = if(entry.carrierFailed) "Failed" else if(entry.mms) mapOf(1 to "MMS",2 to "Sent · MMS",3 to "Draft",4 to "Sending · MMS")[entry.type] else when(entry.type) { 1 -> null; 2 -> when { entry.deliveryStatus < 0 -> "Sent"; entry.deliveryStatus >= 64 -> "Delivery failed"; entry.deliveryStatus >= 32 -> "Delivery pending"; else -> "Delivered" }; 3 -> "Draft"; 4 -> "Sending"; 5 -> "Failed"; 6 -> "Queued"; else -> null }
                        Text(listOfNotNull(messageDate(entry.date), state, if(entry.sub >= 0) "SIM ${entry.sub}" else null).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box {
                            IconButton(onClick = { menu = true }, Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreHoriz, "Message options", Modifier.size(18.dp)) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) { DropdownMenuItem(text = { Text("Delete on this phone") }, onClick = { menu = false; onDelete(entry) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }) }
                        }
                    }
                }
            }
            item { TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("Load earlier messages") } }
        }
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onComposeOptions, enabled = !busy) { Icon(Icons.Outlined.Add, "More compose options") }
                OutlinedTextField(reply, onReplyChange, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 5,
                    enabled = !busy, shape = RoundedCornerShape(26.dp), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                FilledIconButton(onClick = onSend, enabled = !busy && reply.isNotBlank(), modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.Send, "Send reply") }
            }
        }
    }
}

@Composable fun ComposeScreen(recipient: String, onRecipient: (String) -> Unit, body: String, onBody: (String) -> Unit, sub: String, onSub: (String) -> Unit, sims: List<Pair<String,String>>, attached: Boolean, onAttach: () -> Unit, onRemoveAttachment: () -> Unit, busy: Boolean, onSend: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        OutlinedTextField(recipient, onRecipient, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("To") }, placeholder = { Text("Phone number") }, leadingIcon = { Icon(Icons.Outlined.PersonOutline, null) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        OutlinedTextField(body, onBody, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Message") }, placeholder = { Text("Say something…") }, minLines = 6, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onAttach, enabled = !busy) { Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if(attached) "Change image" else "Add image") }
            if(attached) IconButton(onClick = onRemoveAttachment, enabled = !busy) { Icon(Icons.Outlined.Close, "Remove attached image") }
        }
        if(attached) Text("Image attached · sends as carrier MMS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text("Send from", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("-1" to "System default") + sims).forEach { (id, name) -> FilterChip(sub == id, { onSub(id) }, enabled = !busy, label = { Text(name) }, leadingIcon = { Icon(Icons.Outlined.SimCard, null, Modifier.size(16.dp)) }) }
        }
        if(sims.isEmpty()) Text("Use Settings → Messaging permissions to enable SIM selection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onSave, enabled = !busy && recipient.isNotBlank() && body.isNotBlank() && !attached, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Save draft") }
            Button(onClick = onSend, enabled = !busy && recipient.isNotBlank() && (body.isNotBlank() || attached), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if(busy) "Working…" else "Send") }
        }
    }
}
