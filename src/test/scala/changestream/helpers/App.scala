package changestream.helpers

import akka.testkit.TestProbe
import changestream.events.MutationWithInfo
import changestream.{ChangeStream, ChangeStreamEventListener}
import com.typesafe.config.ConfigFactory
import spray.json._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

class App extends Database with Config {
  val app = new Thread {
    override def run = ChangeStream.main(Array())
  }
  val probe = TestProbe()

  def initialize = {
    System.setProperty("config.resource", "test.conf")
    System.setProperty("MYSQL_SERVER_ID", Random.nextLong.toString)
  }

  override def beforeAll(): Unit = {
    ChangeStreamEventListener.setEmitterLoader(_ => probe.ref)
    initialize
    ConfigFactory.invalidateCaches()
    app.start()
    Thread.sleep(5000)

    super.beforeAll()
  }

  override def afterAll(): Unit = {
    app.interrupt()

    super.afterAll()
  }

  def assertValidEvent(
                        mutation: String,
                        database: String = "changestream_test",
                        table: String = "users",
                        queryRowCount: Int = 1,
                        transactionRowCount: Int = 1,
                        primaryKeyField: String = "id",
                        currentRow: Int = 1,
                        sql: Option[String] = None
                      ): Unit = {
    val message = probe.expectMsgType[MutationWithInfo](15 seconds)
    message.formattedMessage.isDefined should be(true)
    val json = message.formattedMessage.get.parseJson.asJsObject.fields

    json("mutation") should equal(JsString(mutation))
    json("database") should equal(JsString(database))
    json("table") should equal(JsString(table))
    json("query").asJsObject.fields("row_count") should equal(JsNumber(queryRowCount))
    if(transactionRowCount > 1) {
      json("transaction").asJsObject.fields("row_count") should equal(JsNumber(transactionRowCount))
    }
    json("primary_key").asJsObject.fields.keys should contain(primaryKeyField)
    json("query").asJsObject.fields("current_row") should equal(JsNumber(currentRow))
    if(sql != None) {
      json("query").asJsObject.fields("sql") should equal(JsString(sql.get.trim))
    }
  }

  def validateNoEvents = probe.expectNoMsg(5 seconds)

  def waitAndClear(count: Int = 1) = {
    (1 to count).foreach(idx =>
      probe.expectMsgType[MutationWithInfo](5 seconds)
    )
  }
}