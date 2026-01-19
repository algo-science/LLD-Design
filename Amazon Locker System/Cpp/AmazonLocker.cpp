#include <iostream>
#include <vector>
#include <string>
#include <deque>
#include <unordered_map>
#include <mutex>
#include <chrono>
#include <memory>
#include <stdexcept>
#include <algorithm>

// --- Core Domain ---

enum class Size {
    SMALL,
    MEDIUM,
    LARGE
};

struct Ticket {
    std::string lockerId;
    std::string code;
    long long creationTime;
};

// --- Infrastructure / Interfaces ---

class Clock {
public:
    virtual long long currentTimeMillis() = 0;
    virtual ~Clock() = default;
};

class SystemClock : public Clock {
public:
    long long currentTimeMillis() override {
        using namespace std::chrono;
        return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
    }
};

// --- Service ---

class LockerService {
private:
    std::shared_ptr<Clock> clock;
    
    // 3 Queues for O(1) scaling logic
    // Using vector of deques mapped by enum index. 0=Small, 1=Medium, 2=Large
    std::deque<std::string> queues[3]; // Direct array for simplicity 
    
    // O(1) Lookup for validation
    std::unordered_map<std::string, Ticket> activeTickets;
    
    std::mutex mtx;

    // Helper to map Size enum to int index
    int sizeToInt(Size s) {
        return static_cast<int>(s);
    }
    
    std::string sizeToString(Size s) {
        switch(s) {
            case Size::SMALL: return "SMALL";
            case Size::MEDIUM: return "MEDIUM";
            case Size::LARGE: return "LARGE";
        }
        return "UNKNOWN";
    }

    std::string findBestFit(Size requestedSize) {
        // Cascading check: Requested -> Next Size Up
        if (requestedSize == Size::SMALL) {
            if (!queues[0].empty()) { std::string s = queues[0].front(); queues[0].pop_front(); return s; }
            if (!queues[1].empty()) { std::string s = queues[1].front(); queues[1].pop_front(); return s; }
            if (!queues[2].empty()) { std::string s = queues[2].front(); queues[2].pop_front(); return s; }
        } else if (requestedSize == Size::MEDIUM) {
            if (!queues[1].empty()) { std::string s = queues[1].front(); queues[1].pop_front(); return s; }
            if (!queues[2].empty()) { std::string s = queues[2].front(); queues[2].pop_front(); return s; }
        } else if (requestedSize == Size::LARGE) {
            if (!queues[2].empty()) { std::string s = queues[2].front(); queues[2].pop_front(); return s; }
        }
        return ""; // Empty string indicates failure
    }

    void returnLocker(const std::string& lockerId) {
        if (lockerId.front() == 'S') queues[0].push_back(lockerId);
        else if (lockerId.front() == 'M') queues[1].push_back(lockerId);
        else if (lockerId.front() == 'L') queues[2].push_back(lockerId);
    }

public:
    LockerService(std::shared_ptr<Clock> clk) : clock(clk) {
        // Initialize lockers (Simulation)
        for(int i=0; i<10; ++i) queues[0].push_back("S-" + std::to_string(i));
        for(int i=0; i<10; ++i) queues[1].push_back("M-" + std::to_string(i));
        for(int i=0; i<10; ++i) queues[2].push_back("L-" + std::to_string(i));
    }

    Ticket deposit(Size requestedSize) {
        std::lock_guard<std::mutex> lock(mtx);
        
        std::string bestLockerId = findBestFit(requestedSize);
        if (bestLockerId.empty()) {
            throw std::runtime_error("No locker available for size " + sizeToString(requestedSize));
        }

        // Generate simple logic for code
        long long now = clock->currentTimeMillis();
        std::string code = bestLockerId + "-" + std::to_string(now % 10000);
        
        Ticket ticket = { bestLockerId, code, now };
        activeTickets[code] = ticket;
        return ticket;
    }

    std::string pickup(const std::string& code) {
        // Validation is O(1)
        // Find if ticket exists. Lock needed? Map read/write is not thread safe by default in C++ unless strict.
        // For strict interview correctness, guarding entire map access is safest in C++ STL.
        // Java ConcurrentHashMap handles fine-grained locking internally. C++ needs manual.
        
        std::unique_lock<std::mutex> lock(mtx);
        auto it = activeTickets.find(code);
        if (it == activeTickets.end()) {
            throw std::invalid_argument("Invalid or expired code: " + code);
        }
        
        Ticket ticket = it->second;
        // Remove from map
        activeTickets.erase(it);
        
        // Return locker
        returnLocker(ticket.lockerId);
        
        lock.unlock(); // Explicit unlock just for clarity (RAII handles it otherwise)
        
        return "Locker " + ticket.lockerId + " opened. Package retrieved.";
    }

    void cleanup() {
        long long threeDaysInMillis = 3LL * 24 * 60 * 60 * 1000;
        long long now = clock->currentTimeMillis();
        
        std::lock_guard<std::mutex> lock(mtx);
        
        auto it = activeTickets.begin();
        while (it != activeTickets.end()) {
            if (now - it->second.creationTime > threeDaysInMillis) {
                // Expired
                std::cout << "Expired package removed from " << it->second.lockerId << std::endl;
                returnLocker(it->second.lockerId);
                it = activeTickets.erase(it);
            } else {
                ++it;
            }
        }
    }
};

// --- Driver / Test ---

class MockClock : public Clock {
public:
    long long time = 1000;
    long long currentTimeMillis() override { return time; }
};

int main() {
    std::cout << "=== Amazon Locker System Demo (C++) ===" << std::endl;
    
    auto mockClock = std::make_shared<MockClock>();
    LockerService service(mockClock);

    try {
        // 1. DEPOSIT
        std::cout << "\n[Action] Deposit Small Package" << std::endl;
        Ticket ticket1 = service.deposit(Size::SMALL);
        std::cout << "Received Ticket: " << ticket1.code << " (" << ticket1.lockerId << ")" << std::endl;

        // 2. DEPOSIT Next Size Up Logic
        // Drain smalls...
        for(int i=0; i<9; ++i) service.deposit(Size::SMALL);
        
        std::cout << "\n[Action] Deposit Small Package (Small queues empty, expect Medium)" << std::endl;
        Ticket ticket2 = service.deposit(Size::SMALL);
        std::cout << "Received Ticket: " << ticket2.code << " (" << ticket2.lockerId << ") (Should be M-...)" << std::endl;

        // 3. PICKUP
        std::cout << "\n[Action] Pickup Ticket 1" << std::endl;
        std::cout << service.pickup(ticket1.code) << std::endl;

        // 4. CLEANUP (Expired)
        std::cout << "\n[Action] Simulate 4 days pass..." << std::endl;
        Ticket large = service.deposit(Size::LARGE);
        std::cout << "Deposited Large package (to be expired): " << large.code << std::endl;
        
        mockClock->time += 4LL * 24 * 60 * 60 * 1000;
        
        std::cout << "[Action] Running Cleanup..." << std::endl;
        service.cleanup();
        
        std::cout << "\nDemo Completed." << std::endl;
    } catch (const std::exception& e) {
        std::cerr << "Error: " << e.what() << std::endl;
        return 1;
    }

    return 0;
}
