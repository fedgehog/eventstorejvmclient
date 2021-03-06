package eventstore

import akka.testkit.TestProbe
import ExpectedVersion._
import OperationFailed._

/**
 * @author Yaroslav Klymko
 */
// TODO improve expectMsgType[ReadStreamEventsSucceed]
class AppendToStreamSpec extends TestConnectionSpec {
  "append to stream" should {
    "succeed for zero events" in new AppendToStreamScope {
      appendToStreamSucceed(Nil, NoStream) mustEqual EventNumber.First
    }

    "create stream with NoStream exp ver on first write if does not exist" in new AppendToStreamScope {
      append(newEvent, NoStream) mustEqual EventNumber.First
      streamEvents must haveSize(1)
    }

    "create stream with ANY exp ver on first write if does not exist" in new AppendToStreamScope {
      append(newEvent, Any) mustEqual EventNumber.First
      streamEvents must haveSize(1)
    }

    "fail create stream with wrong exp ver if does not exist" in new AppendToStreamScope {
      appendToStreamFailed(newEvent, ExpectedVersion.First) mustEqual WrongExpectedVersion
      appendToStreamFailed(newEvent, ExpectedVersion(1)) mustEqual WrongExpectedVersion
    }

    "fail writing with correct exp ver to deleted stream" in new AppendToStreamScope {
      appendEventToCreateStream()
      deleteStream()
      appendToStreamFailed(newEvent, ExpectedVersion.First) mustEqual StreamDeleted
    }

    "fail writing with any exp ver to deleted stream" in new AppendToStreamScope {
      appendEventToCreateStream()
      deleteStream()
      appendToStreamFailed(newEvent, Any) mustEqual StreamDeleted
    }

    "fail writing with invalid exp ver to deleted stream" in new AppendToStreamScope {
      appendEventToCreateStream()
      deleteStream()
      appendToStreamFailed(newEvent, ExpectedVersion(1)) mustEqual StreamDeleted
    }

    "succeed writing with correct exp ver to existing stream" in new AppendToStreamScope {
      appendEventToCreateStream()
      append(newEvent, ExpectedVersion.First) mustEqual EventNumber(1)
      append(newEvent, ExpectedVersion(1)) mustEqual EventNumber(2)
    }

    "succeed writing with any exp ver to existing stream" in new AppendToStreamScope {
      appendEventToCreateStream()
      append(newEvent, Any) mustEqual EventNumber(1)
      append(newEvent, Any) mustEqual EventNumber(2)
    }

    "fail writing with wrong exp ver to existing stream" in new AppendToStreamScope {
      appendEventToCreateStream()
      appendToStreamFailed(newEvent, NoStream) mustEqual WrongExpectedVersion
      appendToStreamFailed(newEvent, ExpectedVersion(1)) mustEqual WrongExpectedVersion
    }

    "be able to append multiple events at once" in new AppendToStreamScope {
      val events = appendMany()
      streamEvents mustEqual events
    }

    "be able to append many events at once" in new AppendToStreamScope {
      val size = 1000
      appendMany(size = size)
      actor ! ReadStreamEvents(streamId, EventNumber.Last, 1, ReadDirection.Backward)
      expectMsgType[ReadStreamEventsSucceed].resolvedIndexedEvents.head.eventRecord.number mustEqual EventNumber(size - 1)
      deleteStream()
    }

    "be able to append many events at once concurrently" in new AppendToStreamScope {
      val n = 10
      val size = 100

      Seq.fill(n)(TestProbe()).foreach(x => appendMany(size = size, testKit = x))

      actor ! ReadStreamEvents(streamId, EventNumber.Last, 1, ReadDirection.Backward)
      expectMsgType[ReadStreamEventsSucceed].resolvedIndexedEvents.head.eventRecord.number mustEqual EventNumber(size * n - 1)

      deleteStream()
    }
  }

  trait AppendToStreamScope extends TestConnectionScope {
    def append(event: Event, expVer: ExpectedVersion = Any) = appendToStreamSucceed(Seq(event), expVer)

    def appendToStreamFailed(event: Event, expVer: ExpectedVersion = Any) = {
      actor ! AppendToStream(streamId, expVer, Seq(event))
      expectMsgType[AppendToStreamFailed].reason
    }
  }
}
