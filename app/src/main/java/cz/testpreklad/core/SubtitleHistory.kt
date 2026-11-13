package cz.testpreklad.core

/** Historie posledních titulků – když něco nestihneš přečíst, najdeš to v aplikaci. */
object SubtitleHistory {
    data class Item(val time: Long, val original: String, val translated: String)

    private const val MAX = 200
    private val items = ArrayDeque<Item>()

    @Synchronized
    fun add(original: String, translated: String, time: Long = System.currentTimeMillis()) {
        val last = items.lastOrNull()
        if (last != null && TextLogic.similar(last.original, original)) return
        items.addLast(Item(time, original, translated))
        while (items.size > MAX) items.removeFirst()
    }

    /** Nejnovější první. */
    @Synchronized
    fun all(): List<Item> = items.reversed()

    @Synchronized
    fun clear() = items.clear()

    @Synchronized
    fun size(): Int = items.size
}
