package amazon_locker

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.ArrayDeque

// --- Core Domain ---

enum class Size {
    SMALL, MEDIUM, LARGE
}

data class Ticket(
    val lockerId: String,
    val code: String,
    val creationTime: Long
)

// --- Infrastructure / Interfaces ---

interface Clock {
    fun currentTimeMillis(): Long
}

class SystemClock : Clock {
    override fun currentTimeMillis() = System.currentTimeMillis()
}

// --- Service ---

class LockerService(private val clock: Clock) {
    // 3 Queues for O(1) scaling logic
    private val smallLockers = ArrayDeque<String>()
    private val mediumLockers = ArrayDeque<String>()
    private val largeLockers = ArrayDeque<String>()

    // O(1) Lookup for validation
    private val activeTickets = ConcurrentHashMap<String, Ticket>()

    private val lock = ReentrantLock()

    init {
        // Initialize lockers (Simulation)
        repeat(10) { smallLockers.add("S-$it") }
        repeat(10) { mediumLockers.add("M-$it") }
        repeat(10) { largeLockers.add("L-$it") }
    }

    /**
     * Input: size
     * Output: Locker Code (Tiket)
     */
    fun deposit(requestedSize: Size): Ticket {
        lock.withLock {
            val bestLockerId = findBestFit(requestedSize) 
                ?: throw IllegalStateException("No locker available for size $requestedSize")

            // Generate simple logic for code (UUID or random in real life)
            val code = "${bestLockerId}-${clock.currentTimeMillis() % 10000}"
            
            val ticket = Ticket(
                lockerId = bestLockerId,
                code = code,
                creationTime = clock.currentTimeMillis()
            )
            
            activeTickets[code] = ticket
            return ticket
        }
    }

    /**
     * Input: Locker Code
     * Output: Success message (and unlocking side effect)
     */
    fun pickup(code: String): String {
        // Validation is O(1)
        val ticket = activeTickets.remove(code) 
            ?: throw IllegalArgumentException("Invalid or expired code: $code")

        // Return locker to the correct queue
        lock.withLock {
            returnLocker(ticket.lockerId)
        }
        
        return "Locker ${ticket.lockerId} opened. Package retrieved."
    }

    /**
     * Removes all expired packages (older than 3 days)
     */
    fun cleanup() {
        val threeDaysInMillis = 3L * 24 * 60 * 60 * 1000
        val now = clock.currentTimeMillis()
        
        lock.withLock {
            // Iterate safely over a concurrent map snapshot or handle removing efficiently
            // Since we need to return lockers to queue, we need lock.
            // Note: In a real DB, this would be a query. Here strict checking.
            val iterator = activeTickets.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.creationTime > threeDaysInMillis) {
                    val ticket = entry.value
                    iterator.remove() // Remove from active tickets
                    returnLocker(ticket.lockerId) // Return to pool
                    println("Expired package removed from ${ticket.lockerId}")
                }
            }
        }
    }

    // --- Helper Functions (Extensible) ---
    
    private fun findBestFit(size: Size): String? {
        // Cascading check: Requested -> Next Size Up
        return when (size) {
            Size.SMALL -> smallLockers.poll() ?: mediumLockers.poll() ?: largeLockers.poll()
            Size.MEDIUM -> mediumLockers.poll() ?: largeLockers.poll()
            Size.LARGE -> largeLockers.poll()
        }
    }

    private fun returnLocker(lockerId: String) {
        when {
            lockerId.startsWith("S-") -> smallLockers.add(lockerId)
            lockerId.startsWith("M-") -> mediumLockers.add(lockerId)
            lockerId.startsWith("L-") -> largeLockers.add(lockerId)
        }
    }
}

// --- Driver / Test ---

class MockClock : Clock {
    var time = 1000L
    override fun currentTimeMillis() = time
}

fun main() {
    println("=== Amazon Locker System Demo ===")
    
    val mockClock = MockClock()
    val service = LockerService(mockClock)

    // 1. DEPOSIT
    println("\n[Action] Deposit Small Package")
    val ticket1 = service.deposit(Size.SMALL)
    println("Received Ticket: $ticket1")

    // 2. DEPOSIT Next Size Up Logic (Consume all smalls first to test)
    // Draining smalls...
    repeat(9) { service.deposit(Size.SMALL) }
    
    println("\n[Action] Deposit Small Package (Small queues empty, expect Medium)")
    val ticket2 = service.deposit(Size.SMALL)
    println("Received Ticket: $ticket2 (Should be M-...)")

    // 3. PICKUP
    println("\n[Action] Pickup Ticket 1")
    println(service.pickup(ticket1.code))

    // 4. CLEANUP (Expired)
    println("\n[Action] Simulate 4 days pass...")
    service.deposit(Size.LARGE).also { println("Deposited Large package (to be expired): $it") }
    
    // Fast forward time
    mockClock.time += 4L * 24 * 60 * 60 * 1000 
    
    println("[Action] Running Cleanup...")
    service.cleanup()
    
    println("\nDemo Completed.")
}
