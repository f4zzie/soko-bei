package ke.sokobei.app.data

import ke.sokobei.app.data.api.ApiJson
import ke.sokobei.shared.ReportDto
import ke.sokobei.shared.ReportRequest
import ke.sokobei.shared.TrendResponse
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/** Guards the JSON field names the R8 rules must preserve (see android/NOTES.md, Release build / R8). */
class WireFormatTest {
    @Test fun report_request_encodes_snake_case_names_and_plain_number() {
        val req = ReportRequest("r1", 1700000000000L, "m1", "c1", "standard", "sack", BigDecimal("2.50"), 4500)
        val obj = Json.parseToJsonElement(ApiJson.encodeToString(ReportRequest.serializer(), req)).jsonObject
        assertEquals(
            setOf("report_id", "reported_at_ms", "market_id", "commodity_id", "grade", "unit", "quantity", "price_kes"),
            obj.keys,
        )
        assertEquals("2.50", obj["quantity"]!!.jsonPrimitive.content)
        assertEquals(false, obj["quantity"]!!.jsonPrimitive.isString)
    }

    @Test fun report_dto_decodes_snake_case_payload() {
        val dto = ApiJson.decodeFromString(
            ReportDto.serializer(),
            """{"report_id":"r1","reported_at_ms":1,"received_at_ms":2,"market_id":"m","commodity_id":"c","grade":"standard",
               "unit":"sack","quantity":2.5,"price_kes":4500,"kg_per_unit":90,"conversion_version":1,"price_per_kg_kes":50.00}""",
        )
        assertEquals("r1", dto.reportId)
        assertEquals(2L, dto.receivedAtMs)
        assertEquals(BigDecimal("2.5"), dto.quantity)
        assertEquals(BigDecimal("50.00"), dto.pricePerKgKes)
    }

    @Test fun trend_response_decodes_nested_points() {
        val t = ApiJson.decodeFromString(
            TrendResponse.serializer(),
            """{"commodity_id":"c","market_id":"m","days":7,"points":[{"day":"2026-01-01","grade":"standard",
               "min_per_kg":10,"median_per_kg":12.5,"max_per_kg":15,"report_count":3}]}""",
        )
        assertEquals("c", t.commodityId)
        assertEquals(BigDecimal("12.5"), t.points.single().medianPerKg)
        assertEquals(3, t.points.single().reportCount)
    }
}
