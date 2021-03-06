package filodb.core.metadata

import scala.concurrent.duration.FiniteDuration

import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.joda.time.{DateTime, DateTimeZone}
import org.scalactic._


object TimeComputations {
  import Column.ColumnType._

  /**
   * Syntax: :timeslice <colName> <durationString>
   * Valid for: LongColumn, TimestampColumn
   * Bucketizes the time value into a time bucket by the <durationString> which has a format like
   * "10s" - 10 seconds, "2m" - 2 minutes, "5h" - 5 hours... this must be a time string recognized
   * by the Typesafe Config library.
   * Produces a Long column with the bucketed time in milliseconds.
   */
  object TimesliceComputation extends SingleColumnComputation {
    def funcName: String = "timeslice"

    def parseDurationMillis(arg: String): Long Or InvalidFunctionSpec = {
      try {
        val config = ConfigFactory.parseString(s"a = $arg")
        Good(config.as[FiniteDuration]("a").toMillis)
      } catch {
        case e: Exception => Bad(BadArgument(e.getMessage))
      }
    }

    def analyze(expr: String,
                dataset: String,
                schema: Seq[Column]): ComputedColumn Or InvalidFunctionSpec = {
      for { info <- parse(expr, schema, Set(LongColumn, TimestampColumn))
            duration <- parseDurationMillis(info.param) }
      yield {
        val extractor = wrap((l: Long) => l / duration * duration)
        computedColumn(expr, dataset, info, LongColumn, extractor)
      }
    }
  }

  /**
   * Syntax: :monthOfYear <colName>
   * Valid for: LongColumn, TimestampColumn
   * Produces an IntColumn with values between 1 and 12 for the month of the timestamp/long original col
   */
  object MonthOfYearComputation extends SingleColumnComputation {
    def funcName: String = "monthOfYear"

    def analyze(expr: String,
                dataset: String,
                schema: Seq[Column]): ComputedColumn Or InvalidFunctionSpec = {
      for { args <- fixedNumArgs(expr, 1)
            sourceColIndex <- columnIndex(schema, args(0))
            sourceColType <- validatedColumnType(schema, sourceColIndex, Set(LongColumn, TimestampColumn)) }
      yield {
        val extractor = wrap((l: Long) => new DateTime(l, DateTimeZone.UTC).getMonthOfYear)
        computedColumn(expr, dataset, Seq(sourceColIndex), IntColumn, extractor)
      }
    }
  }
}