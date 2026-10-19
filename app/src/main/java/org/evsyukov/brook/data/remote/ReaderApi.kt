package org.evsyukov.brook.data.remote

/** What synchronization needs from the server; [GReaderClient] in the app, a fake in tests. */
interface ReaderApi {
    suspend fun tagList(): List<TagDto>
    suspend fun subscriptions(): List<SubscriptionDto>
    suspend fun unreadCounts(): UnreadCountsDto

    suspend fun streamContents(
        streamId: String,
        count: Int = 100,
        continuation: String? = null,
        oldestFirst: Boolean = false,
        exclude: String? = null,
        include: String? = null,
        newerThanSeconds: Long? = null,
    ): StreamContentsDto

    suspend fun allItemIds(streamId: String, exclude: String? = null, pageSize: Int = 10_000): List<Long>
    suspend fun itemContents(ids: Collection<Long>): List<ItemDto>
    suspend fun editTag(ids: Collection<Long>, add: List<String> = emptyList(), remove: List<String> = emptyList())
}
