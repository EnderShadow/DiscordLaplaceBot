package matt.bot.discord.laplace

import java.lang.IllegalArgumentException
import java.util.*

class RingBuffer<T>(private val capacity: Int)
{
    private val backingStorage = LinkedList<T>()
    
    init
    {
        if(capacity < 1)
            throw IllegalArgumentException("capacity must be at least 1")
    }
    
    fun add(obj: T)
    {
        backingStorage.add(obj)
        if(backingStorage.size > capacity)
            backingStorage.removeFirst()
    }
    
    fun update(obj: T, equalityFunction: (T, T) -> Boolean)
    {
        val index = backingStorage.indexOfFirst {equalityFunction(obj, it)}
        if(index < 0)
            throw IllegalArgumentException("obj must be in the buffer in order to update it")
        backingStorage[index] = obj
    }
    
    fun remove(obj: T) = backingStorage.remove(obj)
    
    fun asList() = backingStorage.toList()
}