package com.equisaas.warehouse

import java.time.Instant
import java.util.UUID

enum class WarehouseRole { RECEIVER, PICKER, MANAGER }
enum class ReservationStatus { RESERVED, PICKING, RELEASED, DISPATCHED }
enum class FailureKind { VALIDATION, AUTHORIZATION, CONFLICT, NOT_FOUND }

class WarehouseFailure(val kind: FailureKind, message: String) : RuntimeException(message)

data class Product(val sku: String, val name: String, val reorderPoint: Int, val createdAt: Instant = Instant.now())
data class Receipt(val id: String, val sku: String, val quantity: Int, val reference: String, val receivedBy: String, val receivedAt: Instant = Instant.now())
data class Reservation(val id: String, val orderReference: String, val sku: String, val quantity: Int, var status: ReservationStatus, val createdBy: String, val createdAt: Instant = Instant.now(), var updatedAt: Instant = Instant.now())
data class AuditEvent(val id: String = UUID.randomUUID().toString(), val actor: String, val action: String, val entityId: String, val detail: String, val createdAt: Instant = Instant.now())
data class StockView(val sku: String, val name: String, val onHand: Int, val reserved: Int, val available: Int, val reorderPoint: Int, val lowStock: Boolean)

class WarehouseEngine {
    private val products = linkedMapOf<String, Product>()
    private val onHand = linkedMapOf<String, Int>()
    private val receipts = linkedMapOf<String, Receipt>()
    private val reservations = linkedMapOf<String, Reservation>()
    private val auditEvents = mutableListOf<AuditEvent>()

    fun createProduct(actor: String, role: WarehouseRole, sku: String, name: String, reorderPoint: Int): Product {
        requireRole(role, WarehouseRole.MANAGER)
        validateSku(sku)
        if (name.trim().length < 3) fail(FailureKind.VALIDATION, "product name must contain at least three characters")
        if (reorderPoint < 0) fail(FailureKind.VALIDATION, "reorder point cannot be negative")
        if (products.containsKey(sku)) fail(FailureKind.CONFLICT, "product SKU already exists")
        val product = Product(sku, name.trim(), reorderPoint)
        products[sku] = product
        onHand[sku] = 0
        audit(actor, "product_created", sku, "Created product master data")
        return product
    }

    fun receive(actor: String, role: WarehouseRole, sku: String, quantity: Int, reference: String): Receipt {
        requireRole(role, WarehouseRole.RECEIVER, WarehouseRole.MANAGER)
        requireProduct(sku)
        if (quantity <= 0) fail(FailureKind.VALIDATION, "receipt quantity must be positive")
        if (reference.trim().length < 3) fail(FailureKind.VALIDATION, "receipt reference is required")
        val receipt = Receipt("rcv-${UUID.randomUUID()}", sku, quantity, reference.trim(), actor)
        receipts[receipt.id] = receipt
        onHand[sku] = (onHand[sku] ?: 0) + quantity
        audit(actor, "stock_received", receipt.id, "Received $quantity units of $sku")
        return receipt
    }

    fun reserve(actor: String, role: WarehouseRole, id: String, orderReference: String, sku: String, quantity: Int): Reservation {
        requireRole(role, WarehouseRole.PICKER, WarehouseRole.MANAGER)
        requireProduct(sku)
        if (id.trim().length < 4 || orderReference.trim().length < 3) fail(FailureKind.VALIDATION, "reservation id and order reference are required")
        if (quantity <= 0) fail(FailureKind.VALIDATION, "reservation quantity must be positive")
        if (reservations.containsKey(id)) fail(FailureKind.CONFLICT, "reservation id already exists")
        if (available(sku) < quantity) fail(FailureKind.CONFLICT, "insufficient available inventory")
        val reservation = Reservation(id, orderReference.trim(), sku, quantity, ReservationStatus.RESERVED, actor)
        reservations[id] = reservation
        audit(actor, "stock_reserved", id, "Reserved $quantity units of $sku for ${reservation.orderReference}")
        return reservation.copy()
    }

    fun beginPicking(actor: String, role: WarehouseRole, id: String): Reservation {
        requireRole(role, WarehouseRole.PICKER, WarehouseRole.MANAGER)
        val reservation = requireReservation(id)
        transition(reservation, ReservationStatus.RESERVED, ReservationStatus.PICKING, actor, "pick_started")
        return reservation.copy()
    }

    fun release(actor: String, role: WarehouseRole, id: String, reason: String): Reservation {
        requireRole(role, WarehouseRole.PICKER, WarehouseRole.MANAGER)
        if (reason.trim().length < 3) fail(FailureKind.VALIDATION, "release reason is required")
        val reservation = requireReservation(id)
        if (reservation.status !in setOf(ReservationStatus.RESERVED, ReservationStatus.PICKING)) fail(FailureKind.CONFLICT, "only active reservations can be released")
        reservation.status = ReservationStatus.RELEASED
        reservation.updatedAt = Instant.now()
        audit(actor, "reservation_released", id, "Released inventory because ${reason.trim()}")
        return reservation.copy()
    }

    fun dispatch(actor: String, role: WarehouseRole, id: String): Reservation {
        requireRole(role, WarehouseRole.MANAGER)
        val reservation = requireReservation(id)
        transition(reservation, ReservationStatus.PICKING, ReservationStatus.DISPATCHED, actor, "order_dispatched")
        onHand[reservation.sku] = (onHand[reservation.sku] ?: 0) - reservation.quantity
        audit(actor, "stock_dispatched", id, "Dispatched ${reservation.quantity} units of ${reservation.sku}")
        return reservation.copy()
    }

    fun stock(): List<StockView> = products.values.map { product ->
        val held = onHand[product.sku] ?: 0
        val reserved = reservations.values.filter { it.sku == product.sku && it.status in setOf(ReservationStatus.RESERVED, ReservationStatus.PICKING) }.sumOf { it.quantity }
        StockView(product.sku, product.name, held, reserved, held - reserved, product.reorderPoint, held - reserved <= product.reorderPoint)
    }

    fun reservations(): List<Reservation> = reservations.values.map { it.copy() }
    fun audits(): List<AuditEvent> = auditEvents.toList()

    private fun available(sku: String): Int {
        val held = onHand[sku] ?: 0
        val allocated = reservations.values.filter { it.sku == sku && it.status in setOf(ReservationStatus.RESERVED, ReservationStatus.PICKING) }.sumOf { it.quantity }
        return held - allocated
    }

    private fun requireProduct(sku: String): Product = products[sku] ?: fail(FailureKind.NOT_FOUND, "product SKU was not found")
    private fun requireReservation(id: String): Reservation = reservations[id] ?: fail(FailureKind.NOT_FOUND, "reservation was not found")
    private fun transition(reservation: Reservation, from: ReservationStatus, to: ReservationStatus, actor: String, action: String) {
        if (reservation.status != from) fail(FailureKind.CONFLICT, "invalid reservation status transition")
        reservation.status = to
        reservation.updatedAt = Instant.now()
        audit(actor, action, reservation.id, "Reservation moved to ${to.name.lowercase()}")
    }

    private fun validateSku(sku: String) {
        if (!Regex("[A-Z0-9][A-Z0-9-]{2,79}").matches(sku)) fail(FailureKind.VALIDATION, "SKU must use uppercase letters, digits, or hyphens")
    }

    private fun requireRole(role: WarehouseRole, vararg allowed: WarehouseRole) {
        if (role !in allowed) fail(FailureKind.AUTHORIZATION, "role is not permitted for this warehouse action")
    }

    private fun audit(actor: String, action: String, entityId: String, detail: String) {
        auditEvents += AuditEvent(actor = actor, action = action, entityId = entityId, detail = detail)
    }

    private fun fail(kind: FailureKind, message: String): Nothing = throw WarehouseFailure(kind, message)
}

