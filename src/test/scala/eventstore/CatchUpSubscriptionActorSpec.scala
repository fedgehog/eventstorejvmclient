package eventstore

import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.specification.Scope
import org.specs2.mock.Mockito
import akka.testkit.{TestProbe, TestActorRef, ImplicitSender, TestKit}
import akka.actor.ActorSystem
import ReadDirection.Forward
import scala.concurrent.duration._

/**
 * @author Yaroslav Klymko
 */
class CatchUpSubscriptionActorSpec extends SpecificationWithJUnit with Mockito {
  "CatchUpSubscriptionActor" should {

    "read events from given position" in new CatchUpSubscriptionActorScope(Some(Position(123))) {
      connection expectMsg readAllEvents(Position(123))
    }

    "read events from start if no position given" in new CatchUpSubscriptionActorScope {
      connection expectMsg readAllEvents(Position.First)
    }

    "ignore read events with position out of interest" in new CatchUpSubscriptionActorScope {
      connection expectMsg readAllEvents(Position.First)

      actor ! readAllEventsSucceed(Position.First, Position(3), re0, re1, re2)
      expectMsg(re0)
      expectMsg(re1)
      expectMsg(re2)

      connection expectMsg readAllEvents(Position(3))

      actor ! readAllEventsSucceed(Position(3), Position(5), re0, re1, re2, re3, re4)

      expectMsg(re3)
      expectMsg(re4)

      connection expectMsg readAllEvents(Position(5))

      actor ! readAllEventsSucceed(Position(3), Position(5), re0, re1, re2, re3, re4)

      expectNoMsg(duration)
      connection expectMsg readAllEvents(Position(5))
    }
    "ignore read events with position out of interest when start position is given" in new CatchUpSubscriptionActorScope(Some(Position(1))) {
      connection expectMsg readAllEvents(Position(1))

      actor ! readAllEventsSucceed(Position.First, Position(3), re0, re1, re2)
      expectMsg(re2)
      expectNoMsg(duration)

      connection expectMsg readAllEvents(Position(3))
    }

    "read events until none left and subscribe to new ones" in new CatchUpSubscriptionActorScope {
      connection expectMsg readAllEvents(Position.First)
      val position = Position(1)
      val nextPosition = Position(2)
      val re = resolvedEvent(position)
      actor ! readAllEventsSucceed(position, nextPosition, re)

      expectMsg(re)

      connection expectMsg readAllEvents(nextPosition)
      actor ! readAllEventsSucceed(nextPosition, nextPosition)

      connection.expectMsg(subscribeTo)
    }

    "subscribe to new events if nothing to read" in new CatchUpSubscriptionActorScope {
      connection expectMsg readAllEvents(Position.First)
      val position = Position.First
      actor ! readAllEventsSucceed(position, position)
      connection.expectMsg(subscribeTo)

      actor ! SubscribeToAllCompleted(1)

      connection expectMsg readAllEvents(Position.First)
      actor ! readAllEventsSucceed(position, position)

      expectMsg(LiveProcessingStarted)
    }

    "stop reading events as soon as stop received" in new CatchUpSubscriptionActorScope {
      connection expectMsg readAllEvents(Position.First)

      actor ! StopSubscription
      expectMsg(SubscriptionDropped(SubscriptionDropped.Unsubscribed))

      expectNoActivity
    }

    "ignore read events after stop received" in new CatchUpSubscriptionActorScope {
      connection expectMsg readAllEvents(Position.First)

      actor ! StopSubscription
      expectMsg(SubscriptionDropped(SubscriptionDropped.Unsubscribed))

      val position = Position(1)
      val re = resolvedEvent(position)
      actor ! readAllEventsSucceed(position, Position(2), re)

      expectNoActivity
    }

    "catch events that appear in between reading and subscribing" in new CatchUpSubscriptionActorScope() {
      connection expectMsg readAllEvents(Position.First)

      val position = Position(1)
      actor ! readAllEventsSucceed(Position.First, Position(2), re0, re1)

      expectMsg(re0)
      expectMsg(re1)

      connection expectMsg readAllEvents(Position(2))
      actor ! readAllEventsSucceed(Position(2), Position(2))

      expectNoMsg(duration)
      connection.expectMsg(subscribeTo)

      actor ! SubscribeToAllCompleted(4)

      connection expectMsg readAllEvents(Position(2))

      actor ! StreamEventAppeared(re2)
      actor ! StreamEventAppeared(re3)
      actor ! StreamEventAppeared(re4)
      expectNoMsg(duration)

      actor ! readAllEventsSucceed(Position(2), Position(3), re1, re2)
      expectMsg(re2)

      connection expectMsg readAllEvents(Position(3))

      actor ! StreamEventAppeared(re5)
      actor ! StreamEventAppeared(re6)
      expectNoMsg(duration)

      actor ! readAllEventsSucceed(Position(3), Position(6), re3, re4, re5)

      expectMsg(re3)
      expectMsg(re4)
      expectMsg(LiveProcessingStarted)
      expectMsg(re5)
      expectMsg(re6)

      actor ! StreamEventAppeared(re5)
      actor ! StreamEventAppeared(re6)

      expectNoActivity
    }

    "stop subscribing if stop received when subscription not yet confirmed" in new CatchUpSubscriptionActorScope() {
      connection expectMsg readAllEvents(Position.First)
      actor ! readAllEventsSucceed(Position.First, Position.First)

      connection.expectMsg(subscribeTo)
      actor ! StopSubscription

      expectNoActivity

      actor ! SubscribeToAllCompleted(1)

      connection.expectMsg(UnsubscribeFromStream)

      actor ! SubscriptionDropped(SubscriptionDropped.Unsubscribed)
      expectMsg(SubscriptionDropped(SubscriptionDropped.Unsubscribed))

      expectNoActivity
    }

    "not unsubscribe if subscription failed" in new CatchUpSubscriptionActorScope() {
      connection expectMsg readAllEvents(Position.First)
      actor ! readAllEventsSucceed(Position.First, Position.First)

      connection.expectMsg(subscribeTo)
      actor ! SubscriptionDropped(SubscriptionDropped.AccessDenied)

      expectMsg(SubscriptionDropped(SubscriptionDropped.AccessDenied))

      expectNoActivity
    }

    "not unsubscribe if subscription failed if stop received " in new CatchUpSubscriptionActorScope() {
      connection expectMsg readAllEvents(Position.First)
      actor ! readAllEventsSucceed(Position.First, Position.First)

      actor ! StopSubscription
      connection.expectMsg(subscribeTo)

      expectNoActivity

      actor ! SubscriptionDropped(SubscriptionDropped.AccessDenied)
      expectMsg(SubscriptionDropped(SubscriptionDropped.AccessDenied))

      expectNoActivity
    }

    "stop catching events that appear in between reading and subscribing if stop received" in new CatchUpSubscriptionActorScope() {
      connection expectMsg readAllEvents(Position.First)

      val position = Position(1)
      actor ! readAllEventsSucceed(Position.First, Position(2), re0, re1)

      expectMsg(re0)
      expectMsg(re1)

      connection expectMsg readAllEvents(Position(2))

      actor ! readAllEventsSucceed(Position(2), Position(2))

      expectNoMsg(duration)
      connection.expectMsg(subscribeTo)

      actor ! SubscribeToAllCompleted(5)

      connection expectMsg readAllEvents(Position(2))

      actor ! StreamEventAppeared(re3)
      actor ! StreamEventAppeared(re4)

      actor ! StopSubscription

      expectNoMsg(duration)
      connection.expectMsg(UnsubscribeFromStream)

      actor ! SubscriptionDropped(SubscriptionDropped.Unsubscribed)
      expectMsg(SubscriptionDropped(SubscriptionDropped.Unsubscribed))

      expectNoActivity
    }

    "continue with subscription if no events appear in between reading and subscribing" in new CatchUpSubscriptionActorScope() {
      val position = Position.First
      connection expectMsg readAllEvents(position)
      actor ! readAllEventsSucceed(position, position)

      connection.expectMsg(subscribeTo)
      expectNoMsg(duration)

      actor ! SubscribeToAllCompleted(1)

      connection expectMsg readAllEvents(position)
      actor ! readAllEventsSucceed(position, position)

      expectMsg(LiveProcessingStarted)

      expectNoActivity
    }

    "continue with subscription if no events appear in between reading and subscribing and position is given" in new CatchUpSubscriptionActorScope(Some(Position(1))) {
      val position = Position(1)
      connection expectMsg readAllEvents(position)

      actor ! readAllEventsSucceed(position, position)

      connection.expectMsg(subscribeTo)
      expectNoMsg(duration)

      actor ! SubscribeToAllCompleted(1)

      expectMsg(LiveProcessingStarted)

      expectNoActivity
    }

    "forward events while subscribed" in new CatchUpSubscriptionActorScope() {
      val position = Position.First
      connection expectMsg readAllEvents(position)
      actor ! readAllEventsSucceed(position, position)

      connection.expectMsg(subscribeTo)
      expectNoMsg(duration)

      actor ! SubscribeToAllCompleted(1)

      connection expectMsg readAllEvents(position)
      actor ! readAllEventsSucceed(position, position)

      expectMsg(LiveProcessingStarted)

      actor ! StreamEventAppeared(re1)
      expectMsg(re1)

      expectNoMsg(duration)

      actor ! StreamEventAppeared(re2)
      actor ! StreamEventAppeared(re3)
      expectMsg(re2)
      expectMsg(re3)
    }

    "ignore wrong events while subscribed" in new CatchUpSubscriptionActorScope(Some(Position(1))) {
      val position = Position(1)
      connection expectMsg readAllEvents(position)
      actor ! readAllEventsSucceed(position, position)

      connection.expectMsg(subscribeTo)
      actor ! SubscribeToAllCompleted(2)

      connection expectMsg readAllEvents(position)
      actor ! readAllEventsSucceed(position, position)

      expectMsg(LiveProcessingStarted)

      actor ! StreamEventAppeared(re0)
      actor ! StreamEventAppeared(re1)
      actor ! StreamEventAppeared(re1)
      actor ! StreamEventAppeared(re2)
      expectMsg(re2)
      actor ! StreamEventAppeared(re2)
      actor ! StreamEventAppeared(re1)
      actor ! StreamEventAppeared(re3)
      expectMsg(re3)
      actor ! StreamEventAppeared(re5)
      expectMsg(re5)
      actor ! StreamEventAppeared(re4)
      expectNoMsg(duration)
    }

    "stop subscription when stop received" in new CatchUpSubscriptionActorScope(Some(Position(1))) {
      connection expectMsg readAllEvents(Position(1))

      val position = Position(1)
      actor ! readAllEventsSucceed(position, position)

      connection.expectMsg(subscribeTo)
      actor ! SubscribeToAllCompleted(1)
      expectMsg(LiveProcessingStarted)

      val re = resolvedEvent(Position(2))
      actor ! StreamEventAppeared(re)
      expectMsg(re)

      actor ! StopSubscription
      connection.expectMsg(UnsubscribeFromStream)

      actor ! SubscriptionDropped(SubscriptionDropped.Unsubscribed)
      expectMsg(SubscriptionDropped(SubscriptionDropped.Unsubscribed))

      expectNoActivity
    }
  }

  abstract class CatchUpSubscriptionActorScope(position: Option[Position.Exact] = None)
    extends TestKit(ActorSystem()) with ImplicitSender with Scope {
    val duration = FiniteDuration(1, SECONDS)
    val readBatchSize = 10
    val resolveLinkTos = false
    val connection = TestProbe()
    val actor = TestActorRef(new CatchUpSubscriptionActor(connection.ref, testActor, position, resolveLinkTos, readBatchSize))

    val re0 = resolvedEvent(Position(0))
    val re1 = resolvedEvent(Position(1))
    val re2 = resolvedEvent(Position(2))
    val re3 = resolvedEvent(Position(3))
    val re4 = resolvedEvent(Position(4))
    val re5 = resolvedEvent(Position(5))
    val re6 = resolvedEvent(Position(6))

    def resolvedEvent(x: Position.Exact) = ResolvedEvent(mock[EventRecord], None, x)
    def readAllEvents(x: Position) = ReadAllEvents(x, readBatchSize, Forward, resolveLinkTos = resolveLinkTos)
    def subscribeTo = SubscribeTo(EventStream.All, resolveLinkTos = resolveLinkTos)

    def readAllEventsSucceed(position: Position.Exact, nextPosition: Position.Exact, res: ResolvedEvent*) =
      ReadAllEventsSucceed(position, res, nextPosition, Forward)

    def expectNoActivity {
      expectNoMsg(duration)
      connection.expectNoMsg(duration)
    }
  }
}
