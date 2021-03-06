package com.spingo.op_rabbit.consumer

import com.rabbitmq.client.Envelope
import com.rabbitmq.client.MessageProperties
import org.scalatest.{FunSpec, Matchers}
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._

class DirectivesSpec extends FunSpec with Matchers {
  val dummyEnvelope = new Envelope(1L, false, "kthx", "bai")

  val acked: Result = Right()

  def await[T](f: Future[T], duration: Duration = 5 seconds) =
    Await.result(f, duration)

  import Directives._
  import com.spingo.op_rabbit.properties._

  def resultFor(delivery: Delivery)(handler: Handler) = {
    val handled = Promise[Result]
    handler(handled, delivery)
    await(handled.future)
  }

  def testDelivery(consumerTag: String = "very-tag", envelope: Envelope = dummyEnvelope, properties: Seq[MessageProperty] = Seq(), body: Array[Byte] = "message".getBytes) = {
    Delivery(consumerTag, envelope, builderWithProperties(properties).build, body)
  }

  describe("property") {
    it("rejects when the property is not defined") {
      resultFor(testDelivery()) {
        property(ReplyTo) { replyTo =>
          ack()
        }
      } should be (Left(ValueExpectedExtractRejection("Property ReplyTo was not provided")))
    }

    it("yields the value when the property is defined") {
      val delivery = testDelivery(properties = Seq(ReplyTo("place")))
      resultFor(delivery) {
        property(ReplyTo) { replyTo =>
          replyTo should be ("place")
          ack()
        }
      } should be (acked)
    }

    it("converts the header value to the specified type") {
      val delivery = testDelivery(properties = Seq(Header("recovery-attempts", "1")))
      resultFor(delivery) {
        (property(Header("recovery-attempts")).as(typeOf[Int])) { (i) =>
          i should be (1)
          ack()
        }
      } should be (acked)
    }

    it("rejects when the conversion fails") {
      val delivery = testDelivery(properties = Seq(Header("recovery-attempts", "a number of times")))
      an [ExtractRejection] should be thrownBy {
        resultFor(delivery) {
          (property(Header("recovery-attempts")).as(typeOf[Int])) { (i) =>
            ack()
          }
        }
      }
    }
  }

  describe("as") {
    it("converts a compound directive to the provided case class") {
      resultFor(testDelivery()) {
        case class LosContainer(a:Int, name: String, b:Option[Int]) {}

        (provide(1) & provide("name") & provide(2)).as(LosContainer) { container =>
          container should be (LosContainer(1, "name", Some(2)))
          println(container)
          ack()
        }
      } should be (acked)
    }

    it("Converts headers also") {
      case class VeryPerson(name: String, age: Int)
      resultFor(testDelivery(properties = Seq(Header("name", "Scratch"), Header("age", 27)))) {
        (property(Header("name")).as(typeOf[String]) & property(Header("age")).as(typeOf[Int])).as(VeryPerson) { person =>
          person should be (VeryPerson("Scratch", 27))
          ack()
        }
      } should be (acked)
    }
  }

  describe("conjuction") {
    it("yields the value for both directives") {
      val delivery = testDelivery(body = "hi".getBytes, properties = Seq(ReplyTo("place")))
      resultFor(delivery) {
        (body(as[String]) & property(ReplyTo)) { (body, replyTo) =>
          replyTo should be ("place")
          body should be ("hi")
          ack()
        }
      } should be (acked)
    }
  }

  describe("|") {
    it("recovers from extractor related rejections") {
      val delivery = testDelivery()
      resultFor(delivery) {
        ((property(ReplyTo) & property(AppId)) | (provide("default-reply-to") & provide("default-app"))) { (replyTo, appId) =>
          replyTo should be ("default-reply-to")
          appId should be ("default-app")
          ack()
        }
      } should be (acked)
    }
  }

  describe("optionalProperty") {
    it("yields none when the property is not defined") {
      val delivery = testDelivery()
      resultFor(delivery) {
        optionalProperty(ReplyTo) { replyTo =>
          replyTo should be (None)
          ack()
        }
      } should be (acked)
    }
  }
}
