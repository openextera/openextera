package com.exteragram.messenger.utils.chats

import androidx.collection.LongSparseArray
import com.exteragram.messenger.ExteraConfig
import com.exteragram.messenger.TabCounterMode
import org.telegram.SQLite.SQLiteCursor
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.messenger.support.LongSparseIntArray
import org.telegram.tgnet.TLRPC

class FolderCounters private constructor(private val account: Int) {

    private var filterCounts = HashMap<Int, Int>()
    private var mainCount = 0
    @Volatile
    private var recountScheduled = false

    class UnreadDialog(
        val id: Long,
        val typeFlags: Int,
        val weight: Int,
        val archived: Boolean
    )

    fun getMainUnreadCount(): Int =
        if (isUnmutedOnly()) mainCount else MessagesStorage.getInstance(account).mainUnreadCount

    fun getUnreadCount(filter: MessagesController.DialogFilter): Int {
        if (!isUnmutedOnly()) {
            return filter.unreadCount
        }
        return filterCounts[filter.id] ?: 0
    }

    fun update(
        filters: List<MessagesController.DialogFilter>,
        users: LongSparseArray<TLRPC.User>,
        encUsers: LongSparseArray<TLRPC.User>,
        encryptedChatsByUsersCount: LongSparseIntArray,
        chats: LongSparseArray<TLRPC.Chat>,
        mutedDialogs: LongSparseArray<Boolean>,
        archivedDialogs: LongSparseArray<Boolean>,
        mentionedDialogs: LongSparseArray<Int>
    ) {
        if (!isUnmutedOnly()) {
            return
        }
        val forumMutes = loadForumMutes()
        val dialogs = ArrayList<UnreadDialog>()
        for (i in 0 until users.size()) {
            val user = users.valueAt(i)
            if (forumMutes[user.id] ?: mutedDialogs.containsKey(user.id)) {
                continue
            }
            val flags = when {
                ChatObject.isUserCollapsedInCommunity(chats, user) -> MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS
                user.bot -> MessagesController.DIALOG_FILTER_FLAG_BOTS
                user.self || user.contact -> MessagesController.DIALOG_FILTER_FLAG_CONTACTS
                else -> MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS
            }
            dialogs.add(UnreadDialog(user.id, flags, 1, archivedDialogs.containsKey(user.id)))
        }
        for (i in 0 until encUsers.size()) {
            val user = encUsers.valueAt(i)
            val count = encryptedChatsByUsersCount.get(user.id, 0)
            if (count == 0 || mutedDialogs.containsKey(user.id)) {
                continue
            }
            val flags = if (user.self || user.contact) {
                MessagesController.DIALOG_FILTER_FLAG_CONTACTS
            } else {
                MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS
            }
            dialogs.add(UnreadDialog(user.id, flags, count, archivedDialogs.containsKey(user.id)))
        }
        for (i in 0 until chats.size()) {
            val chat = chats.valueAt(i)
            if (chat == null || ChatObject.isCommunity(chat)) {
                continue
            }
            val dialogId = -chat.id
            if (forumMutes[dialogId] ?: (mutedDialogs.containsKey(dialogId) && !mentionedDialogs.containsKey(dialogId))) {
                continue
            }
            val flags = when {
                ChatObject.isChatCollapsedInCommunity(chats, chat) -> MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS
                ChatObject.isChannel(chat) && !chat.megagroup -> MessagesController.DIALOG_FILTER_FLAG_CHANNELS
                else -> MessagesController.DIALOG_FILTER_FLAG_GROUPS
            }
            dialogs.add(UnreadDialog(dialogId, flags, 1, archivedDialogs.containsKey(dialogId)))
        }
        val newMainCount = count(dialogs, MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS or MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED, null)
        val newFilterCounts = HashMap<Int, Int>()
        for (filter in filters) {
            newFilterCounts[filter.id] = count(dialogs, filter.flags, filter)
        }
        AndroidUtilities.runOnUIThread {
            mainCount = newMainCount
            filterCounts = newFilterCounts
        }
    }

    private fun loadForumMutes(): HashMap<Long, Boolean> {
        val storage = MessagesStorage.getInstance(account)
        val controller = MessagesController.getInstance(account)
        val result = HashMap<Long, Boolean>()
        var cursor: SQLiteCursor? = null
        try {
            cursor = storage.database.queryFinalized("SELECT did, topic_id, unread_mentions FROM topics WHERE unread_count > 0 OR unread_mentions > 0")
            while (cursor.next()) {
                val dialogId = cursor.longValue(0)
                if (result[dialogId] == false || !storage.isForum(dialogId, MessagesStorage.FORUM_TYPE_CHAT or MessagesStorage.FORUM_TYPE_DIRECT or MessagesStorage.FORUM_TYPE_BOT)) {
                    continue
                }
                result[dialogId] = cursor.intValue(2) == 0 && controller.isDialogMuted(dialogId, cursor.longValue(1))
            }
        } catch (e: Exception) {
            FileLog.e(e)
        } finally {
            cursor?.dispose()
        }
        return result
    }

    fun scheduleRecount() {
        if (!isUnmutedOnly() || recountScheduled) {
            return
        }
        recountScheduled = true
        val storage = MessagesStorage.getInstance(account)
        storage.storageQueue.postRunnable({
            recountScheduled = false
            storage.resetAllUnreadCounters(false)
        }, 500)
    }

    private fun count(dialogs: List<UnreadDialog>, flags: Int, filter: MessagesController.DialogFilter?): Int {
        val alwaysShow = filter?.alwaysShow?.toHashSet()
        val neverShow = filter?.neverShow?.toHashSet()
        var weight = 0
        for (dialog in dialogs) {
            if (neverShow?.contains(dialog.id) == true) {
                continue
            }
            if (alwaysShow?.contains(dialog.id) != true) {
                if (dialog.archived && (flags and MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED) != 0) {
                    continue
                }
                if ((dialog.typeFlags and flags) != dialog.typeFlags) {
                    continue
                }
            }
            weight += dialog.weight
        }
        return weight
    }

    companion object {
        private val instances = arrayOfNulls<FolderCounters>(UserConfig.MAX_ACCOUNT_COUNT)
        private val lockObjects = Array(UserConfig.MAX_ACCOUNT_COUNT) { Any() }

        @JvmStatic
        fun getInstance(account: Int): FolderCounters {
            instances[account]?.let { return it }
            synchronized(lockObjects[account]) {
                return instances[account] ?: FolderCounters(account).also { instances[account] = it }
            }
        }

        fun recountAll() {
            if (!isUnmutedOnly()) {
                return
            }
            for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                if (UserConfig.getInstance(a).isClientActivated) {
                    val storage = MessagesStorage.getInstance(a)
                    storage.storageQueue.postRunnable { storage.resetAllUnreadCounters(false) }
                }
            }
        }

        private fun isUnmutedOnly(): Boolean = ExteraConfig.tabCounterMode == TabCounterMode.UNMUTED
    }
}
