package eventstore


/**
 * @author Yaroslav Klymko
 */

sealed trait Message
sealed trait In extends Message
sealed trait Out extends Message
sealed trait InOut extends In with Out

private[eventstore] case object HeartbeatRequestCommand extends InOut
private[eventstore] case object HeartbeatResponseCommand extends InOut

private[eventstore] case object Ping extends InOut
private[eventstore] case object Pong extends InOut

case object PrepareAck extends Message
case object CommitAck extends Message

case object SlaveAssignment extends Message
case object CloneAssignment extends Message

case object SubscribeReplica extends Message
case object CreateChunk extends Message
case object PhysicalChunkBulk extends Message
case object LogicalChunkBulk extends Message



// TODO how to verify ???
case class DeniedToRoute(externalTcpAddress: String,
                         externalTcpPort: Int,
                         externalHttpAddress: String,
                         externalHttpPort: Int) extends Message



case class AppendToStream(streamId: EventStream.Id,
                          expectedVersion: ExpectedVersion,
                          events: Seq[Event],
                          requireMaster: Boolean = true) extends Out

sealed trait AppendToStreamCompleted extends In
case class AppendToStreamSucceed(firstEventNumber: EventNumber.Exact) extends AppendToStreamCompleted
case class AppendToStreamFailed(reason: OperationFailed.Value, message: Option[String]) extends AppendToStreamCompleted



case class DeleteStream(streamId: EventStream.Id,
                        expectedVersion: ExpectedVersion.Existing,
                        requireMaster: Boolean = true) extends Out

sealed trait DeleteStreamCompleted extends In
case object DeleteStreamSucceed extends DeleteStreamCompleted
case class DeleteStreamFailed(reason: OperationFailed.Value, message: Option[String]) extends DeleteStreamCompleted



case class TransactionStart(streamId: EventStream.Id,
                            expectedVersion: ExpectedVersion,
                            requireMaster: Boolean = true) extends Out

sealed trait TransactionStartCompleted extends In

case class TransactionStartSucceed(transactionId: Long) extends TransactionStartCompleted {
  require(transactionId >= 0, s"transactionId must be >= 0, but is $transactionId")
}

case class TransactionStartFailed(reason: OperationFailed.Value,
                                  message: Option[String]) extends TransactionStartCompleted



case class TransactionWrite(transactionId: Long, events: Seq[Event], requireMaster: Boolean = true) extends Out {
  require(transactionId >= 0, s"transactionId must be >= 0, but is $transactionId")
}

sealed trait TransactionWriteCompleted extends In {
  def transactionId: Long
}

case class TransactionWriteSucceed(transactionId: Long) extends TransactionWriteCompleted{
  require(transactionId >= 0, s"transactionId must be >= 0, but is $transactionId")
}

case class TransactionWriteFailed(transactionId: Long,
                                  reason: OperationFailed.Value,
                                  message: Option[String]) extends TransactionWriteCompleted {
  require(transactionId >= 0, s"transactionId must be >= 0, but is $transactionId")
}



case class TransactionCommit(transactionId: Long, requireMaster: Boolean = true) extends Out {
  require(transactionId >= 0, s"transactionId must be >= 0, but is $transactionId")
}

sealed trait TransactionCommitCompleted extends In

case class TransactionCommitSucceed(transactionId: Long) extends TransactionCommitCompleted{
  require(transactionId >= 0, s"transactionId must be >= 0, but is $transactionId")
}

case class TransactionCommitFailed(transactionId: Long,
                                   reason: OperationFailed.Value,
                                   message: Option[String]) extends TransactionCommitCompleted {
  require(transactionId >= 0, s"transactionId must be >= 0, but is $transactionId")
}



case class ReadEvent(streamId: EventStream.Id,
                     eventNumber: EventNumber,
                     resolveLinkTos: Boolean = false,
                     requireMaster: Boolean = true) extends Out

sealed trait ReadEventCompleted extends In
case class ReadEventSucceed(resolvedIndexedEvent: ResolvedIndexedEvent) extends ReadEventCompleted
case class ReadEventFailed(reason: ReadEventFailed.Value, message: Option[String]) extends ReadEventCompleted
object ReadEventFailed extends Enumeration {
  val NotFound, NoStream, StreamDeleted, Error, AccessDenied = Value
}



case class ReadStreamEvents(streamId: EventStream.Id,
                            fromEventNumber: EventNumber,
                            maxCount: Int,
                            direction: ReadDirection.Value,
                            resolveLinkTos: Boolean = false, // TODO test resolveLinkTos,
                            requireMaster: Boolean = true) extends Out {
  require(maxCount > 0, s"maxCount must be > 0, but is $maxCount")
  require(
    direction != ReadDirection.Forward || fromEventNumber != EventNumber.Last,
    s"fromEventNumber must not be EventNumber.Last")
}

sealed trait ReadStreamEventsCompleted extends In {
  def direction: ReadDirection.Value
}

case class ReadStreamEventsSucceed(resolvedIndexedEvents: Seq[ResolvedIndexedEvent],
                                   nextEventNumber: EventNumber,
                                   lastEventNumber: EventNumber.Exact,
                                   endOfStream: Boolean,
                                   lastCommitPosition: Long,
                                   direction: ReadDirection.Value) extends ReadStreamEventsCompleted {
  require(
    direction != ReadDirection.Forward || nextEventNumber != EventNumber.Last,
    s"lastEventNumber must not be EventNumber.Last")
}

case class ReadStreamEventsFailed(reason: ReadStreamEventsFailed.Value,
                                  message: Option[String],
                                  lastCommitPosition: Long,
                                  direction: ReadDirection.Value) extends ReadStreamEventsCompleted

object ReadStreamEventsFailed extends Enumeration {
  val NoStream, StreamDeleted, Error, AccessDenied = Value
}



case class ReadAllEvents(position: Position,
                         maxCount: Int,
                         direction: ReadDirection.Value,
                         resolveLinkTos: Boolean = false, // TODO test resolveLinkTos,
                         requireMaster: Boolean = true) extends Out {
  require(maxCount > 0, s"maxCount must be > 0, but is $maxCount")
}

sealed trait ReadAllEventsCompleted extends In {
  def position: Position.Exact
  def direction: ReadDirection.Value
}

case class ReadAllEventsSucceed(position: Position.Exact,
                                resolvedEvents: Seq[ResolvedEvent],
                                nextPosition: Position.Exact,
                                direction: ReadDirection.Value) extends ReadAllEventsCompleted

case class ReadAllEventsFailed(reason: ReadAllEventsFailed.Value,
                               message: Option[String],
                               position: Position.Exact,
                               direction: ReadDirection.Value) extends ReadAllEventsCompleted

object ReadAllEventsFailed extends Enumeration {
  val Error, AccessDenied = Value
}


case class SubscribeTo(stream: EventStream, resolveLinkTos: Boolean = false /*TODO test resolveLinkTos*/) extends Out

sealed trait SubscribeCompleted extends In

case class SubscribeToAllCompleted(lastCommit: Long) extends SubscribeCompleted {
  require(lastCommit > 0, s"lastCommit must be > 0, but is $lastCommit") // TODO not sure about this restriction
}

case class SubscribeToStreamCompleted(lastCommit: Long, lastEventNumber: Option[EventNumber.Exact]) extends SubscribeCompleted {
  require(lastCommit > 0, s"lastCommit must be > 0, but is $lastCommit")
}

case class StreamEventAppeared(resolvedEvent: ResolvedEvent) extends In



case object UnsubscribeFromStream extends Out
case class SubscriptionDropped(reason: SubscriptionDropped.Value) extends In
object SubscriptionDropped extends Enumeration {
  val Unsubscribed, AccessDenied = Value
}



case object ScavengeDatabase extends Out

case object BadRequest extends In