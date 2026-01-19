package amazon_locker;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

// --- Core Domain ---

enum Size {
    SMALL, MEDIUM, LARGE
}

class Ticket {
    final String lockerId;
    final String code;
    final long creationTime;

    public Ticket(String lockerId, String code, long creationTime) {
        this.lockerId = lockerId;
        this.code = code;
        this.creationTime = creationTime;
    }

    @Override
    public String toString() {
        return "Ticket{lockerId='" + lockerId + "', code='" + code + "', creationTime=" + creationTime + "}";
    }
}

// --- Infrastructure / Interfaces ---

interface Clock {
    long currentTimeMillis();
}

class SystemClock implements Clock {
    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}

// --- Service ---

class LockerService {
    private final Clock clock;
    
    // 3 Queues for O(1) scaling logic
    private final EnumMap<Size, Deque<String>> queues = new EnumMap<>(Size.class);
    
    // O(1) Lookup for validation
    private final Map<String, Ticket> activeTickets = new ConcurrentHashMap<>();
    
    private final Lock lock = new ReentrantLock();

    public LockerService(Clock clock) {
        this.clock = clock;
        // Initialize queues
        queues.put(Size.SMALL, new ArrayDeque<>());
        queues.put(Size.MEDIUM, new ArrayDeque<>());
        queues.put(Size.LARGE, new ArrayDeque<>());
        
        // Fill Lockers (Simulation)
        for (int i = 0; i < 10; i++) queues.get(Size.SMALL).add("S-" + i);
        for (int i = 0; i < 10; i++) queues.get(Size.MEDIUM).add("M-" + i);
        for (int i = 0; i < 10; i++) queues.get(Size.LARGE).add("L-" + i);
    }

    /**
     * Input: size
     * Output: Ticket
     */
    public Ticket deposit(Size requestedSize) {
        lock.lock();
        try {
            String bestLockerId = findBestFit(requestedSize);
            if (bestLockerId == null) {
                throw new IllegalStateException("No locker available for size " + requestedSize);
            }

            // Generate simple logic for code
            String code = bestLockerId + "-" + (clock.currentTimeMillis() % 10000);
            
            Ticket ticket = new Ticket(bestLockerId, code, clock.currentTimeMillis());
            activeTickets.put(code, ticket);
            return ticket;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Input: Locker Code
     * Output: Success message
     */
    public String pickup(String code) {
        // Validation is O(1)
        Ticket ticket = activeTickets.remove(code);
        if (ticket == null) {
            throw new IllegalArgumentException("Invalid or expired code: " + code);
        }

        lock.lock();
        try {
            returnLocker(ticket.lockerId);
        } finally {
            lock.unlock();
        }
        
        return "Locker " + ticket.lockerId + " opened. Package retrieved.";
    }

    /**
     * Removes all expired packages (older than 3 days)
     */
    public void cleanup() {
        long threeDaysInMillis = 3L * 24 * 60 * 60 * 1000;
        long now = clock.currentTimeMillis();
        
        lock.lock();
        try {
            Iterator<Map.Entry<String, Ticket>> iterator = activeTickets.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Ticket> entry = iterator.next();
                if (now - entry.getValue().creationTime > threeDaysInMillis) {
                    Ticket ticket = entry.getValue();
                    iterator.remove(); // Remove from active tickets
                    returnLocker(ticket.lockerId); // Return to pool
                    System.out.println("Expired package removed from " + ticket.lockerId);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // --- Helper Functions ---

    private String findBestFit(Size size) {
        // Cascading check: Requested -> Next Size Up
        switch (size) {
            case SMALL:
                String s = queues.get(Size.SMALL).poll();
                if (s != null) return s;
                // Fallthrough to Medium check if Small unavailable? 
                // In Java switch fallthrough logic is tricky if sharing return logic.
                // Explicit checks are cleaner.
                String m = queues.get(Size.MEDIUM).poll();
                if (m != null) return m;
                return queues.get(Size.LARGE).poll();
            case MEDIUM:
                String med = queues.get(Size.MEDIUM).poll();
                if (med != null) return med;
                return queues.get(Size.LARGE).poll();
            case LARGE:
                return queues.get(Size.LARGE).poll();
            default:
                return null;
        }
    }

    private void returnLocker(String lockerId) {
        if (lockerId.startsWith("S-")) queues.get(Size.SMALL).add(lockerId);
        else if (lockerId.startsWith("M-")) queues.get(Size.MEDIUM).add(lockerId);
        else if (lockerId.startsWith("L-")) queues.get(Size.LARGE).add(lockerId);
    }
}

// --- Driver / Test ---

class MockClock implements Clock {
    long time = 1000L;
    @Override
    public long currentTimeMillis() { return time; }
}

public class AmazonLocker {
    public static void main(String[] args) {
        System.out.println("=== Amazon Locker System Demo (Java) ===");
        
        MockClock mockClock = new MockClock();
        LockerService service = new LockerService(mockClock);

        // 1. DEPOSIT
        System.out.println("\n[Action] Deposit Small Package");
        Ticket ticket1 = service.deposit(Size.SMALL);
        System.out.println("Received Ticket: " + ticket1);

        // 2. DEPOSIT Next Size Up Logic
        // Drain smalls...
        for (int i = 0; i < 9; i++) service.deposit(Size.SMALL);
        
        System.out.println("\n[Action] Deposit Small Package (Small queues empty, expect Medium)");
        Ticket ticket2 = service.deposit(Size.SMALL);
        System.out.println("Received Ticket: " + ticket2 + " (Should be M-...)");

        // 3. PICKUP
        System.out.println("\n[Action] Pickup Ticket 1");
        System.out.println(service.pickup(ticket1.code));

        // 4. CLEANUP (Expired)
        System.out.println("\n[Action] Simulate 4 days pass...");
        Ticket large = service.deposit(Size.LARGE);
        System.out.println("Deposited Large package (to be expired): " + large);
        
        mockClock.time += 4L * 24 * 60 * 60 * 1000;
        
        System.out.println("[Action] Running Cleanup...");
        service.cleanup();
        
        System.out.println("\nDemo Completed.");
    }
}
