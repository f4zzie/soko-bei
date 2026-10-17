package ke.sokobei.repository

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BatchType
import com.datastax.oss.driver.api.core.cql.BatchableStatement
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.cql.Statement
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException
import com.datastax.oss.driver.api.core.servererrors.ProtocolError
import kotlinx.coroutines.future.await
import java.nio.ByteBuffer
import java.util.Base64

data class RowPage(val rows: List<Row>, val nextToken: String?)

/** Thin coroutine bridge over the async driver API. All statements run at the session's LOCAL_QUORUM default. */
class Db(val session: CqlSession) {
    fun prepare(cql: String): PreparedStatement = session.prepare(cql)

    suspend fun exec(statement: Statement<*>): AsyncResultSet = session.executeAsync(statement).await()

    /** Reads every page of a (bounded) query. */
    suspend fun all(statement: BoundStatement): List<Row> {
        val out = mutableListOf<Row>()
        var rs = exec(statement)
        while (true) {
            out += rs.currentPage()
            if (!rs.hasMorePages()) break
            rs = rs.fetchNextPage().await()
        }
        return out
    }

    /** One page with an opaque base64 page token (the driver paging state). */
    suspend fun page(statement: BoundStatement, limit: Int, token: String?): RowPage {
        var s = statement.setPageSize(limit)
        if (token != null) {
            val bytes = runCatching { Base64.getUrlDecoder().decode(token) }.getOrNull()
                ?: throw InvalidPageTokenException()
            s = s.setPagingState(ByteBuffer.wrap(bytes))
        }
        val rs = try {
            exec(s)
        } catch (e: RuntimeException) {
            // A malformed paging state surfaces as a driver/protocol error; anything else is a genuine failure.
            if (token != null && (e is IllegalArgumentException || e is InvalidQueryException || e is ProtocolError)) {
                throw InvalidPageTokenException()
            }
            throw e
        }
        val state = rs.executionInfo.pagingState
        val next = state?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(it.remaining()).also { b -> it.duplicate().get(b) }) }
        return RowPage(rs.currentPage().toList(), next)
    }

    suspend fun batch(type: BatchType, statements: List<BatchableStatement<*>>) {
        if (statements.isEmpty()) return
        exec(BatchStatement.newInstance(type).addAll(statements))
    }

    suspend fun one(statement: BoundStatement): Row? = exec(statement).one()
}

class InvalidPageTokenException : RuntimeException("invalid page_token")
