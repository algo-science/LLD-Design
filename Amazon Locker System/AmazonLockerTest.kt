package amazon_locker

// Minimal Test Runner for Interview Setting
// We don't have JUnit, so we build a simple one.

class TestRunner {
    private var passed = 0
    private var failed = 0

    fun runTest(name: String, test: () -> Unit) {
        try {
            test()
            println("✅ [PASS] $name")
            passed++
        } catch (e: Throwable) {
            println("❌ [FAIL] $name")
            println("   Error: ${e.message}")
            e.printStackTrace()
            failed++
        }
    }

    fun printSummary() {
        println("\n=== Test Summary ===")
        println("Passed: $passed")
        println("Failed: $failed")
        println("Total:  ${passed + failed}")
        if (failed > 0) throw RuntimeException("Some tests failed!")
    }
}

// Simple Assertions
fun assertTrue(condition: Boolean, message: String = "Assertion failed") {
    if (!condition) throw AssertionError(message)
}

fun assertEquals(expected: Any?, actual: Any?, message: String = "Expected $expected but got $actual") {
    if (expected != actual) throw AssertionError(message)
}

fun assertThrows(expectedException: Class<out Throwable>, block: () -> Unit) {
    try {
        block()
        throw AssertionError("Expected exception ${expectedException.simpleName} but none was thrown")
    } catch (e: Throwable) {
        if (!expectedException.isInstance(e)) {
            throw AssertionError("Expected exception ${expectedException.simpleName} but got ${e.javaClass.simpleName}")
        }
    }
}

// --- The Tests ---

class AmazonLockerTest {
    private val runner = TestRunner()

    fun runAll() {
        runner.runTest("Deposit - Basic Flow") {
            val clock = MockClock()
            val service = LockerService(clock)
            
            val ticket = service.deposit(Size.SMALL)
            assertEquals("S-0", ticket.lockerId, "First small locker should be S-0")
            assertTrue(ticket.code.startsWith("S-0"), "Code should contain locker ID")
        }

        runner.runTest("Deposit - Upgrade Strategy (Small -> Medium)") {
             val clock = MockClock()
             val service = LockerService(clock)

             // Fill all Small lockers
             repeat(10) { service.deposit(Size.SMALL) }

             // Request one more Small
             val ticket = service.deposit(Size.SMALL)
             assertTrue(ticket.lockerId.startsWith("M-"), "Should upgrade to Medium when Small is full. Got: ${ticket.lockerId}")
        }

        runner.runTest("Deposit - Full System Exception") {
            val clock = MockClock()
            val service = LockerService(clock)

            // Try to deposit 100 SMALL packages.
            // Logic:
            // 1. First 10 take SMALL.
            // 2. Next 10 take MEDIUM (Upgrade).
            // 3. Next 10 take LARGE (Upgrade).
            // 4. 31st should fail.
            // Total success should be 30.
            
            var successfulDeposits = 0
            try {
                repeat(100) { 
                    service.deposit(Size.SMALL) 
                    successfulDeposits++
                }
            } catch (e: IllegalStateException) {
                // Expected eventually
            }
            assertEquals(30, successfulDeposits, "Should be able to fit exactly 30 items (10S + 10M + 10L) via upgrades")
            
            // Now ensure it throws
            assertThrows(IllegalStateException::class.java) {
                service.deposit(Size.SMALL)
            }
        }

        runner.runTest("Pickup - Success") {
            val clock = MockClock()
            val service = LockerService(clock)
            val ticket = service.deposit(Size.LARGE)
            
            val result = service.pickup(ticket.code)
            assertTrue(result.contains("opened"), "Pickup message should indicate success")
        }

        runner.runTest("Pickup - Invalid Code") {
            val clock = MockClock()
            val service = LockerService(clock)
            service.deposit(Size.SMALL)
            
            assertThrows(IllegalArgumentException::class.java) {
                service.pickup("WRONG-CODE")
            }
        }

        runner.runTest("Cleanup - Expiration Logic") {
            val clock = MockClock()
            val service = LockerService(clock)
            
            val ticket = service.deposit(Size.SMALL)
            
            // Fast forward 4 days (3 days + 1 ms is enough, using 4 days to be safe)
            clock.time += 4L * 24 * 60 * 60 * 1000
            
            service.cleanup()
            
            // Should be gone now
            assertThrows(IllegalArgumentException::class.java) {
                service.pickup(ticket.code)
            }
        }

        runner.printSummary()
    }
}

fun main() {
    AmazonLockerTest().runAll()
}
