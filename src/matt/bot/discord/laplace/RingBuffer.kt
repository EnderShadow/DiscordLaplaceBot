package matt.bot.discord.laplace

class RingBuffer<T>(size: Int): Iterable<T>
{
    private val data: Array<T>
    private val mask: Int
    private var writeIndex = 0
    
    var size = 0
        private set
    val maxSize
        get() = data.size
    
    init
    {
        @Suppress("UNCHECKED_CAST")
        if(size > 0 && (size and (size - 1)) == 0)
            data = arrayOfNulls<Any>(size) as Array<T>
        else
            throw IllegalArgumentException("Cannot use a negative size or a size that is not a power of 2")
        
        mask = size - 1
    }
    
    fun add(element: T)
    {
        data[writeIndex] = element
        writeIndex = (writeIndex + 1) and mask
        if(size < data.size)
            size++
    }
    
    fun update(element: T, equalityFunction: (T, T) -> Boolean)
    {
        for(i in 0 until size)
        {
            if(equalityFunction.invoke(element, data[i]))
            {
                data[i] = element
                return
            }
        }
    }
    
    override fun iterator(): Iterator<T>
    {
        return RingBufferIterator()
    }
    
    inner class RingBufferIterator: Iterator<T>
    {
        private var index = (writeIndex - size) and mask
        
        override fun hasNext() = index != writeIndex
        
        override fun next(): T
        {
            if(hasNext())
                return data[index++ and mask]
            else
                throw NoSuchElementException()
        }
    }
}