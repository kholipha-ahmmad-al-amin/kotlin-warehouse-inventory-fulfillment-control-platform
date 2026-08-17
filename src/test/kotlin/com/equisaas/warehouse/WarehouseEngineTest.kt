package com.equisaas.warehouse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WarehouseEngineTest {
    private fun ready(): WarehouseEngine = WarehouseEngine().also { engine ->
        engine.createProduct("manager", WarehouseRole.MANAGER, "CABLE-001", "Shielded network cable", 5)
        engine.receive("receiver", WarehouseRole.RECEIVER, "CABLE-001", 20, "GRN-1001")
    }

    @Test fun `manager creates product and receiver adds stock`() {
        val engine = ready()
        val stock = engine.stock().single()
        assertEquals(20, stock.onHand)
        assertEquals(20, stock.available)
        assertTrue(engine.audits().any { it.action == "stock_received" })
    }

    @Test fun `picker cannot create a product`() {
        val failure = assertFailsWith<WarehouseFailure> { WarehouseEngine().createProduct("picker", WarehouseRole.PICKER, "CABLE-001", "Cable", 2) }
        assertEquals(FailureKind.AUTHORIZATION, failure.kind)
    }

    @Test fun `invalid product fields are rejected`() {
        val failure = assertFailsWith<WarehouseFailure> { WarehouseEngine().createProduct("manager", WarehouseRole.MANAGER, "bad sku", "x", -1) }
        assertEquals(FailureKind.VALIDATION, failure.kind)
    }

    @Test fun `reservation cannot exceed available stock`() {
        val engine = ready()
        engine.reserve("picker", WarehouseRole.PICKER, "RES-1001", "ORDER-101", "CABLE-001", 18)
        val failure = assertFailsWith<WarehouseFailure> { engine.reserve("picker", WarehouseRole.PICKER, "RES-1002", "ORDER-102", "CABLE-001", 3) }
        assertEquals(FailureKind.CONFLICT, failure.kind)
    }

    @Test fun `dispatch requires picking state and manager authority`() {
        val engine = ready()
        engine.reserve("picker", WarehouseRole.PICKER, "RES-1001", "ORDER-101", "CABLE-001", 4)
        val stateFailure = assertFailsWith<WarehouseFailure> { engine.dispatch("manager", WarehouseRole.MANAGER, "RES-1001") }
        assertEquals(FailureKind.CONFLICT, stateFailure.kind)
        engine.beginPicking("picker", WarehouseRole.PICKER, "RES-1001")
        val roleFailure = assertFailsWith<WarehouseFailure> { engine.dispatch("picker", WarehouseRole.PICKER, "RES-1001") }
        assertEquals(FailureKind.AUTHORIZATION, roleFailure.kind)
    }

    @Test fun `complete fulfillment reduces stock and writes evidence`() {
        val engine = ready()
        engine.reserve("picker", WarehouseRole.PICKER, "RES-1001", "ORDER-101", "CABLE-001", 4)
        engine.beginPicking("picker", WarehouseRole.PICKER, "RES-1001")
        val output = engine.dispatch("manager", WarehouseRole.MANAGER, "RES-1001")
        assertEquals(ReservationStatus.DISPATCHED, output.status)
        assertEquals(16, engine.stock().single().onHand)
        assertTrue(engine.audits().any { it.action == "stock_dispatched" })
    }
}

