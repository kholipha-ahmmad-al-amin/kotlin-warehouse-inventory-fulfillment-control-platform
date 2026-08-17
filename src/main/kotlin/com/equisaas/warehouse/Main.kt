package com.equisaas.warehouse

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.Executors

data class ApiRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)
data class ApiResponse(val status: Int, val contentType: String, val body: String)

class WarehouseApi(private val engine: WarehouseEngine) {
    fun handle(request: ApiRequest): ApiResponse = try {
        when {
            request.method == "GET" && request.path == "/" -> htmlResponse(dashboard())
            request.method == "GET" && request.path == "/health" -> json(200, "{\"status\":\"ok\",\"service\":\"warehouse-fulfillment-control\"}")
            request.method == "GET" && request.path == "/api/snapshot" -> withIdentity(request) { _, _ -> json(200, snapshotJson()) }
            request.method == "GET" && request.path == "/api/audit" -> withIdentity(request) { _, _ -> json(200, auditJson()) }
            request.method == "POST" && request.path == "/api/products" -> withIdentity(request) { actor, role -> json(201, productJson(engine.createProduct(actor, role, text(request.body, "sku"), text(request.body, "name"), number(request.body, "reorderPoint")))) }
            request.method == "POST" && request.path == "/api/receipts" -> withIdentity(request) { actor, role -> json(201, receiptJson(engine.receive(actor, role, text(request.body, "sku"), number(request.body, "quantity"), text(request.body, "reference")))) }
            request.method == "POST" && request.path == "/api/reservations" -> withIdentity(request) { actor, role -> json(201, reservationJson(engine.reserve(actor, role, text(request.body, "id"), text(request.body, "orderReference"), text(request.body, "sku"), number(request.body, "quantity")))) }
            request.method == "POST" && request.path.startsWith("/api/reservations/") && request.path.endsWith("/pick") -> withIdentity(request) { actor, role -> json(200, reservationJson(engine.beginPicking(actor, role, reservationId(request.path)))) }
            request.method == "POST" && request.path.startsWith("/api/reservations/") && request.path.endsWith("/release") -> withIdentity(request) { actor, role -> json(200, reservationJson(engine.release(actor, role, reservationId(request.path, "release"), text(request.body, "reason")))) }
            request.method == "POST" && request.path.startsWith("/api/reservations/") && request.path.endsWith("/dispatch") -> withIdentity(request) { actor, role -> json(200, reservationJson(engine.dispatch(actor, role, reservationId(request.path, "dispatch")))) }
            else -> error(404, "route not found")
        }
    } catch (failure: WarehouseFailure) {
        error(when (failure.kind) { FailureKind.VALIDATION -> 422; FailureKind.AUTHORIZATION -> 403; FailureKind.CONFLICT -> 409; FailureKind.NOT_FOUND -> 404 }, failure.message ?: "warehouse request failed")
    } catch (failure: IllegalArgumentException) {
        error(422, failure.message ?: "invalid request")
    }

    private fun withIdentity(request: ApiRequest, action: (String, WarehouseRole) -> ApiResponse): ApiResponse {
        val actor = request.headers["x-actor"]?.trim().orEmpty()
        if (actor.length < 3) return error(401, "authenticated actor header is required")
        val role = runCatching { WarehouseRole.valueOf(request.headers["x-role"]?.uppercase() ?: "") }.getOrElse { return error(401, "valid warehouse role header is required") }
        return action(actor, role)
    }

    private fun text(body: String, key: String): String = Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(body)?.groupValues?.get(1)?.replace("\\\"", "\"") ?: throw IllegalArgumentException("$key is required")
    private fun number(body: String, key: String): Int = Regex("\\\"$key\\\"\\s*:\\s*(-?\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: throw IllegalArgumentException("$key must be a whole number")
    private fun reservationId(path: String, suffix: String = "pick"): String = path.removePrefix("/api/reservations/").removeSuffix("/$suffix").trim().ifEmpty { throw IllegalArgumentException("reservation id is required") }
    private fun json(status: Int, body: String) = ApiResponse(status, "application/json", body)
    private fun htmlResponse(body: String) = ApiResponse(200, "text/html; charset=utf-8", body)
    private fun error(status: Int, message: String) = json(status, "{\"error\":\"${escape(message)}\"}")
    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    private fun productJson(product: Product) = "{\"sku\":\"${escape(product.sku)}\",\"name\":\"${escape(product.name)}\",\"reorderPoint\":${product.reorderPoint}}"
    private fun receiptJson(receipt: Receipt) = "{\"id\":\"${escape(receipt.id)}\",\"sku\":\"${escape(receipt.sku)}\",\"quantity\":${receipt.quantity},\"reference\":\"${escape(receipt.reference)}\"}"
    private fun reservationJson(reservation: Reservation) = "{\"id\":\"${escape(reservation.id)}\",\"orderReference\":\"${escape(reservation.orderReference)}\",\"sku\":\"${escape(reservation.sku)}\",\"quantity\":${reservation.quantity},\"status\":\"${reservation.status}\",\"updatedAt\":\"${reservation.updatedAt}\"}"
    private fun snapshotJson(): String = "{\"stock\":[${engine.stock().joinToString(",") { "{\"sku\":\"${escape(it.sku)}\",\"name\":\"${escape(it.name)}\",\"onHand\":${it.onHand},\"reserved\":${it.reserved},\"available\":${it.available},\"reorderPoint\":${it.reorderPoint},\"lowStock\":${it.lowStock}}" }}],\"reservations\":[${engine.reservations().joinToString(",") { reservationJson(it) }}]}"
    private fun auditJson(): String = "{\"events\":[${engine.audits().asReversed().take(30).joinToString(",") { "{\"id\":\"${escape(it.id)}\",\"actor\":\"${escape(it.actor)}\",\"action\":\"${escape(it.action)}\",\"entityId\":\"${escape(it.entityId)}\",\"detail\":\"${escape(it.detail)}\",\"createdAt\":\"${it.createdAt}\"}" }}]}"
    private fun dashboard(): String = WarehouseApi::class.java.getResource("/dashboard.html")?.readText() ?: "<h1>Dashboard asset unavailable</h1>"
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 10500
    val api = WarehouseApi(WarehouseEngine())
    val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
    server.createContext("/") { exchange -> respond(exchange, api.handle(exchange.toRequest())) }
    server.executor = Executors.newFixedThreadPool(8)
    server.start()
    println("Warehouse fulfillment control listening on 0.0.0.0:$port at ${Instant.now()}")
}

private fun HttpExchange.toRequest(): ApiRequest = ApiRequest(requestMethod, requestURI.path, requestHeaders.entries.associate { it.key.lowercase() to it.value.firstOrNull().orEmpty() }, requestBody.bufferedReader().readText())
private fun respond(exchange: HttpExchange, response: ApiResponse) { val body = response.body.toByteArray(); exchange.responseHeaders.set("Content-Type", response.contentType); exchange.sendResponseHeaders(response.status, body.size.toLong()); exchange.responseBody.use { it.write(body) } }

