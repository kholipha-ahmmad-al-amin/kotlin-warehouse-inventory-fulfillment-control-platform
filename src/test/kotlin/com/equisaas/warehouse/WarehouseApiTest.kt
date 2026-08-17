package com.equisaas.warehouse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarehouseApiTest {
    private fun request(method: String, path: String, body: String = "", role: String? = "MANAGER") = ApiRequest(method, path, if (role == null) emptyMap() else mapOf("x-actor" to "test-operator", "x-role" to role), body)

    @Test fun `protected snapshot rejects missing identity`() {
        val response = WarehouseApi(WarehouseEngine()).handle(request("GET", "/api/snapshot", role = null))
        assertEquals(401, response.status)
    }

    @Test fun `product route returns validation error for missing fields`() {
        val response = WarehouseApi(WarehouseEngine()).handle(request("POST", "/api/products", "{\"sku\":\"CABLE-001\"}"))
        assertEquals(422, response.status)
        assertTrue(response.body.contains("name is required"))
    }

    @Test fun `warehouse route supports product receipt and snapshot workflow`() {
        val api = WarehouseApi(WarehouseEngine())
        assertEquals(201, api.handle(request("POST", "/api/products", "{\"sku\":\"CABLE-001\",\"name\":\"Shielded network cable\",\"reorderPoint\":5}")).status)
        assertEquals(201, api.handle(request("POST", "/api/receipts", "{\"sku\":\"CABLE-001\",\"quantity\":12,\"reference\":\"GRN-1001\"}", "RECEIVER")).status)
        val snapshot = api.handle(request("GET", "/api/snapshot"))
        assertEquals(200, snapshot.status)
        assertTrue(snapshot.body.contains("\"available\":12"))
    }
}

