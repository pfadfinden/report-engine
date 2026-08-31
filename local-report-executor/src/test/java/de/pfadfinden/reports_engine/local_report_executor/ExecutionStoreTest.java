package de.pfadfinden.reports_engine.local_report_executor;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.pfadfinden.reports_engine.executor.Port.ExecutionStatus;

class ExecutionStoreTest {

    @Test
    void unknownExecutionIdReturnsNull() {
        ExecutionStore store = new ExecutionStore();

        assertNull(store.get("missing"));
    }

    @Test
    void createPendingMakesTheStatePendingAndRetrievable() {
        ExecutionStore store = new ExecutionStore();

        store.createPending("exec-1");

        ExecutionState state = store.get("exec-1");
        assertEquals(ExecutionStatus.PENDING, state.status);
        assertNull(state.outputFile);
        assertNull(state.errorMessage);
        assertNull(state.outputFormat);
    }

    @Test
    void getReturnsTheSameStateInstanceOnRepeatedCalls() {
        ExecutionStore store = new ExecutionStore();
        store.createPending("exec-1");

        assertSame(store.get("exec-1"), store.get("exec-1"));
    }

    @Test
    void differentExecutionIdsGetIndependentState() {
        ExecutionStore store = new ExecutionStore();
        store.createPending("exec-1");
        store.createPending("exec-2");

        assertNotSame(store.get("exec-1"), store.get("exec-2"));

        store.get("exec-1").status = ExecutionStatus.DONE;

        assertEquals(ExecutionStatus.PENDING, store.get("exec-2").status);
    }

    @Test
    void reCreatingPendingForAnExistingIdResetsItsState() {
        ExecutionStore store = new ExecutionStore();
        store.createPending("exec-1");
        store.get("exec-1").status = ExecutionStatus.DONE;

        store.createPending("exec-1");

        assertEquals(ExecutionStatus.PENDING, store.get("exec-1").status);
    }
}
