package ke.sokobei.app.data.db

import androidx.room.TypeConverter
import ke.sokobei.shared.UnitDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.math.BigDecimal

class Converters {
    @TypeConverter fun bigDecimalToString(v: BigDecimal?): String? = v?.toPlainString()
    @TypeConverter fun stringToBigDecimal(v: String?): BigDecimal? = v?.toBigDecimal()

    @TypeConverter fun stringsToJson(v: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), v)
    @TypeConverter fun jsonToStrings(v: String): List<String> = json.decodeFromString(ListSerializer(String.serializer()), v)

    @TypeConverter fun unitsToJson(v: List<UnitDto>): String = json.encodeToString(ListSerializer(UnitDto.serializer()), v)
    @TypeConverter fun jsonToUnits(v: String): List<UnitDto> = json.decodeFromString(ListSerializer(UnitDto.serializer()), v)

    @TypeConverter fun statusToString(v: ReportStatus): String = v.name
    @TypeConverter fun stringToStatus(v: String): ReportStatus = ReportStatus.valueOf(v)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
