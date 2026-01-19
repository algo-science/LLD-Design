# Amazon Locker System - Low Level Design (LLD)

A robust, thread-safe, and pragmatic implementation of an Amazon Locker System in Kotlin, designed for a 45-minute architectural interview setting.

## 🏗 System Architecture

The system is designed as a standalone `LockerService` that manages locker allocation, retrieval, and expiration. It prioritizes **O(1)** operations and thread safety without over-engineering.

### Core Components

1.  **LockerService**: The central facade managing state and business logic.
2.  **Size Enum**: Defines compartment sizes (`SMALL`, `MEDIUM`, `LARGE`).
3.  **Ticket**: A data class representing an active transaction/token.
4.  **Clock**: An injectable interface for testability (simulating time travel).

### 💾 Data Structures

To ensure **O(1)** time complexity for key operations, we use:

| internal Component | Application Strategy |
| :--- | :--- |
| **Queues** | `Map<Size, ArrayDeque<String>>`<br>Three separate queues (Small, Medium, Large) store *available* locker IDs. Using queues allow O(1) retrieval of the "next available" locker. |
| **Active Lookup** | `ConcurrentHashMap<String, Ticket>`<br>Maps a unique `code` to the `Ticket`. Used for O(1) validation during pickup. |

### 🔄 Algorithms & Logic

#### 1. Allocation & Upgrading (Deposit)
The `findBestFit(size)` algorithm implements a smart upgrade strategy:
- **Request Small**: Check Small -> Empty? Check Medium -> Empty? Check Large.
- **Request Medium**: Check Medium -> Empty? Check Large.
- **Request Large**: Check Large only.

This ensures maximum utilization of the locker bank.

#### 2. Concurrency Control
- **Critical Sections**: Locker allocation (checking and polling from queues) is guarded by a `ReentrantLock`.
- **Validation**: Lookup in `ConcurrentHashMap` is lock-free for reads, ensuring high throughput for pickups.

#### 3. Automatic Cleanup
- The service includes a `cleanup()` method that scans for active tickets older than **3 days**.
- Expired tickets are invalidated, and their lockers are returned to the available pool.

## 🚀 How to Run

### Prerequisites
- **Kotlin**: Installed via brew (`brew install kotlin`) or compatible SDK.

### Run Demo Driver
The `AmazonLocker.kt` file includes a `main` function that simulates a full lifecycle:
```bash
kotlinc AmazonLocker.kt -include-runtime -d AmazonLocker.jar
java -jar AmazonLocker.jar
```

### Run Unit Tests
A custom, dependency-free test runner is included in `AmazonLockerTest.kt` to verify edge cases (Upgrades, Full System, Expiration).
```bash
kotlinc AmazonLocker.kt AmazonLockerTest.kt -include-runtime -d AmazonLockerTest.jar
java -cp AmazonLockerTest.jar amazon_locker.AmazonLockerTestKt
```

## 📂 Project Structure
```text
.
├── AmazonLocker.kt        # Core Logic & Driver
├── AmazonLockerTest.kt    # Unit Tests
└── README.md              # Documentation
```
