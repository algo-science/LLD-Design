# Amazon Locker System - Low Level Design (LLD)

A robust, thread-safe, and pragmatic implementation of an Amazon Locker System in Kotlin, Java, and C++, designed for a 45-minute architectural interview setting.

## 1. Requirements & Scope
**Goal:** Design the control software for a self-service package pickup system where drivers deposit packages and customers retrieve them using secure codes.

### 1.1 Functional Requirements
1.  **Deposit Package:**
    * Input: Package Size (Small, Medium, Large).
    * Action: Assign a compartment, open the door, and generate a secure Access Token.
    * Constraint: If no compartment of the requested size is available, return an error.
2.  **Pickup Package:**
    * Input: Access Token.
    * Action: Validate token, open the specific compartment, and mark the locker as free.
3.  **Expiration Policy:**
    * Access Tokens are valid for **3 days** (Implementation) / **7 days** (Design).
    * Expired tokens must be rejected during pickup.
    * Staff need a mechanism to open all compartments containing expired items.

### 1.2 System Boundaries (Out of Scope)
To strictly scope the LLD to the locker software itself, the following are excluded:
* **Logistics:** We do not track where the package came from or where it is going.
* **User Notifications:** Sending SMS/Emails with codes is handled by an upstream Notification Service.
* **Hardware Drivers:** We assume an interface `compartment.open()` exists.
* **Payment/GUI:** UI rendering and payment processing are external.

---

## 2. Architecture & Entity Design

### 2.1 Entity Strategy: The "Package" Class Trap
A common design mistake is creating a `Package` entity. In this context, the system does not manage package metadata (sender, weight, contents). It only cares about **Size**.
* **Decision:** `Package` is treated as a simple data parameter (Size), not a class. Tracking package history violates the Single Responsibility Principle (SRP) for the Locker System.

### 2.2 Core Classes

#### A. LockerSystem (The Orchestrator)
Acts as the Facade/Entry point for the API. It manages the lifecycle of the system.
* **State:**
    * `compartments`: Data structure managing storage units (Optimized as Queues in implementation).
    * `token_map`: A Map linking `token_string` -> `Ticket` object.
* **Responsibilities:** Allocation logic, token generation, and validation.

#### B. Compartment (The Resource)
Represents the physical storage unit.
* **State:**
    * `id`: Unique identifier.
    * `size`: Enum (SMALL, MEDIUM, LARGE).
    * `status`: Enum (AVAILABLE, OCCUPIED, BROKEN).
* **Responsibilities:** Manages its own physical state.

#### C. AccessToken / Ticket (The Key)
Represents the "ticket" to open a door.
* **State:**
    * `code`: The unique string entered by the user.
    * `expiry_time`: Timestamp.
    * `locker_id`: Reference to the Compartment ID.
* **Responsibilities:** Enforces expiration logic (`is_expired()`).

---

## 3. Implementation Logic

### 3.1 Deposit Workflow (Allocation)
We use a "First Fit" or "Best Fit" strategy to find a locker.

```python
class LockerSystem:
    def deposit_package(self, size):
        # 1. Search for available space
        target = self._find_available_compartment(size)
        
        if target is None:
            # Edge Case: Full capacity
            raise Exception("No locker available for this size")

        # 2. Physical Act
        target.open_door()
        target.set_status(Status.OCCUPIED)

        # 3. Security & Token Generation
        token = AccessToken(
            code=generate_unique_code(),
            expiry=datetime.now() + timedelta(days=3),
            compartment=target
        )
        
        # 4. Storage
        self.token_map[token.code] = token
        
        return token.code
```

### 3.2 Pickup Workflow (Validation & Cleanup)
This workflow must handle validation failures and cleanup to ensure the locker can be reused.

```python
class LockerSystem:
    def pickup_package(self, code):
        # 1. Look up token
        token = self.token_map.get(code)
        
        if token is None:
            raise Exception("Invalid Code")

        # 2. Check Expiry
        if token.is_expired():
            # Edge Case: Code exists but is stale.
            # Do NOT open door. Staff intervention required.
            raise Exception("Code Expired. Contact Support.")

        # 3. Retrieve Compartment
        # Note: We use the direct reference stored in the token
        compartment = token.get_compartment()
        
        # 4. Open Door
        compartment.open_door()
        
        # 5. Cleanup (Critical)
        compartment.set_status(Status.AVAILABLE)
        del self.token_map[code]
```

### 3.3 Maintenance Workflow (Expiry Handling)
Admin function to clear stale packages.

```python
class LockerSystem:
    def open_expired_lockers(self):
        for token in self.token_map.values():
            if token.is_expired():
                # Open door for staff removal
                token.get_compartment().open_door()
                
                # Note: We do NOT auto-clear the map/status here.
                # Staff must physically confirm removal before marking 'AVAILABLE'.
```

---

## 4. Extensibility & Edge Cases

### 4.1 Size Fallback Strategy
**Scenario:** A user needs a Medium locker, but all Mediums are full. Large lockers are empty.
**Solution:** Modify `_find_available_compartment` to iterate through sizes by priority.
* **Logic:** `[REQUESTED, NEXT_LARGER, LARGEST]`
* **Constraint:** Never fallback to a smaller size (physics).
* **Implementation:**
    ```python
    def _find_available_compartment(self, requested_size):
        valid_sizes = get_sizes_gte(requested_size) # e.g., [MEDIUM, LARGE]
        for size in valid_sizes:
            for c in self.compartments:
                if c.size == size and c.status == Status.AVAILABLE:
                    return c
        return None
    ```

### 4.2 Handling Broken Lockers
**Scenario:** A door mechanism jams or the screen breaks.
**Solution:** Refactor `isOccupied` boolean to a `Status` Enum.
* **Enum:** `AVAILABLE`, `OCCUPIED`, `MAINTENANCE`.
* **Impact:** The search logic strictly filters for `c.status == AVAILABLE`. Lockers in `MAINTENANCE` are effectively invisible to the allocation algorithm.

### 4.3 "Ghost" Deposits (Empty Lockers)
**Scenario:** A driver requests a locker, the door opens, but they leave without putting the package in. The system thinks it's `OCCUPIED`.
**Solution:** Implement a **Two-Phase Commit** pattern.
1.  **Reserve:** Driver requests size -> System returns `ReservationID`, opens door, sets status `RESERVED`.
2.  **Confirm:** Driver places package and closes door -> System detects closure (or driver confirms on UI) -> Status becomes `OCCUPIED` -> Access Token generated.
3.  **Timeout:** A background job checks for `RESERVED` lockers > 5 minutes and resets them to `AVAILABLE`.

---

## 5. Architectural Trade-offs & Discussions
*This section synthesizes common critiques and alternative designs discussed during review.*

### 5.1 Concurrency & Race Conditions
* **Problem:** If two drivers tap "Deposit" simultaneously (or via a mobile app API), the system might return the same compartment to both.
* **Hardware Limit:** If there is only one physical screen/keypad, concurrency is naturally serialized by the hardware interface.
* **Software Limit:** If exposed via API, use a **Locking Mechanism**.
    * *Option A:* `ReentrantLock` on the `deposit` method (Simple, but bottlenecks performance).
    * *Option B:* Lock specific compartments or use a `ConcurrentHashMap` for reservation logic (Higher complexity, better scale).

### 5.2 Optimization: Map vs. List
* **Critique:** "Iterating through the list of compartments is O(N). Why not use `Map<Size, List<AvailableCompartments>>` for O(1) access?"
* **Rebuttal:** Premature optimization. Amazon Lockers typically have <100 slots. Iterating a list of 100 items takes microseconds using a list.
* **Implementation Decision:** While a List is sufficient, we implemented the **O(1)** approach using `Map<Size, Deque>` to demonstrate scalability principles during the interview.

### 5.3 Storing ID vs. Object Reference
* **Design:** `AccessToken` holds `Compartment compartment` (Object Reference).
* **Alternative:** Hold `String compartmentId`.
* **Analysis:** Storing the ID requires a secondary lookup (`locker_system.get_compartment(id)`) during the pickup phase. Storing the direct object reference is memory-efficient (it points to the existing object on the heap) and faster (O(1) access to the door).

### 5.4 Encapsulation vs. Orchestration
* **Critique:** "Why does LockerSystem manage Tokens? Shouldn't there be a TokenService?"
* **Analysis:** For an LLD (Low-Level Design), splitting into services adds boilerplate. `LockerSystem` acting as the Facade is appropriate. However, creating a `Package` entity to track "where is my stuff" is an Anti-Pattern here; that data belongs in the upstream Logistics Service, not the hardware controller.

---

## 6. Implementation Details

We specifically implemented the **Optimal Scale Strategy**:

* **O(1) Lookup**: `ConcurrentHashMap<String, Ticket>`
* **O(1) Allocation**: `Map<Size, ArrayDeque<String>>` (Pre-sorted queues of free lockers).

### Data Structures

| Internal Component | Implementation Strategy |
| :--- | :--- |
| **Queues** | `Map<Size, ArrayDeque<String>>`<br>Three separate queues (Small, Medium, Large) store *available* locker IDs. |
| **Active Lookup** | `ConcurrentHashMap<String, Ticket>`<br>Maps a unique `code` to the `Ticket`. Used for O(1) validation during pickup. |

---

## 🚀 How to Run

### Prerequisites
- **Kotlin**: `brew install kotlin`
- **Java**: JDK 8+
- **C++**: GCC/Clang with C++17 support

### Run Demo Driver

**Kotlin**:
```bash
kotlinc AmazonLocker.kt -include-runtime -d AmazonLocker.jar
java -jar AmazonLocker.jar
```

**Java**:
```bash
cd Java
javac -d . AmazonLocker.java
java amazon_locker.AmazonLocker
```

**C++**:
```bash
cd Cpp
g++ -std=c++17 AmazonLocker.cpp -o locker
./locker
```

### Run Unit Tests
```bash
kotlinc AmazonLocker.kt AmazonLockerTest.kt -include-runtime -d AmazonLockerTest.jar
java -cp AmazonLockerTest.jar amazon_locker.AmazonLockerTestKt
```

## 📂 Project Structure
```text
.
├── AmazonLocker.kt        # Kotlin Implementation (Main)
├── AmazonLockerTest.kt    # Kotlin Unit Tests
├── Cpp
│   └── AmazonLocker.cpp   # C++ Implementation
├── Java
│   └── AmazonLocker.java  # Java Implementation
└── README.md              # Documentation
```
