package io.github.techpool.textkeep

data class LocalMessage(val id:Long,val address:String,val body:String,val date:Long,val type:Int,val read:Boolean,val threadId:Long,val sub:Int,val mms:Boolean=false,val location:String="",val deliveryStatus:Int=-1,val carrierFailed:Boolean=false) {
    val key get() = "${if(mms) "mms" else "sms"}:$id"
    val conversationKey get() = if(threadId > 0) "thread:$threadId" else "sender:$address"
}

enum class InboxFilter(val label: String) { ALL("All"), UNREAD("Unread"), CODES("Codes"), ARCHIVED("Archived") }
data class Conversation(val key: String, val latest: LocalMessage, val preview: LocalMessage, val unread: Int, val pinned: Boolean)

/** Filters search whole conversations, so unread state and last activity stay accurate. */
fun organizeInbox(messages: List<LocalMessage>, search: String, filter: InboxFilter, pinned: Set<String>, archived: Set<String>): List<Conversation> =
    messages.groupBy { it.conversationKey }.mapNotNull { (key, entries) ->
        if ((key in archived) != (filter == InboxFilter.ARCHIVED)) return@mapNotNull null
        val unread = entries.count { it.type == 1 && !it.read }
        if (filter == InboxFilter.UNREAD && unread == 0) return@mapNotNull null
        val matching = entries.filter {
            (search.isBlank() || it.body.contains(search.trim(), true) || it.address.contains(search.trim(), true)) &&
                (filter != InboxFilter.CODES || OtpParser.parse(it.body) != null)
        }
        if (matching.isEmpty()) null else Conversation(key, entries.maxBy { it.date }, matching.maxBy { it.date }, unread, key in pinned)
    }.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.latest.date })
