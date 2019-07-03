package followers

import akka.NotUsed
import akka.event.Logging
import akka.stream.scaladsl.{BroadcastHub, Flow, Framing, Keep, MergeHub, Sink, Source}
import akka.stream.{ActorAttributes, Materializer}
import akka.util.ByteString
import followers.model.{Event, Followers, Identity}

import scala.collection.{SortedSet, mutable}
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * Utility object that describe stream manipulations used by the server
  * implementation.
  */
object Server {

  /**
    * A flow that consumes chunks of bytes and produces `String` messages.
    *
    * Each incoming chunk of bytes doesn’t necessarily contain ''exactly one'' message
    * payload (it can contain fragments of payloads only). You have to process these
    * chunks to produce ''frames'' containing exactly one message payload.
    *
    * Messages are delimited by the '\n' character.
    *
    * If the last frame does not end with a delimiter, this flow should fail the
    * stream instead of returning a truncated frame.
    *
    * Hint: you may find the [[Framing]] flows useful.
    */
  val reframedFlow: Flow[ByteString, String, NotUsed] =
    Flow[ByteString]
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = false))
      .map(_.utf8String)

  /**
    * A flow that consumes chunks of bytes and produces [[Event]] messages.
    *
    * Each incoming chunk of bytes doesn't necessarily contain exactly one message payload (it
    * can contain fragments of payloads only). You have to process these chunks to produce
    * frames containing exactly one message payload before you can parse such messages with
    * [[Event.parse]].
    *
    * Hint: reuse `reframedFlow`
    */
  val eventParserFlow: Flow[ByteString, Event, NotUsed] =
    reframedFlow.map(str => Event.parse(str))

  /**
    * Implement a Sink that will look for the first [[Identity]]
    * (we expect there will be only one), from a stream of commands and materializes a Future with it.
    *
    * Subsequent values once we found an identity can be ignored.
    *
    * Note that you may need the Sink's materialized value; you may
    * want to compare the signatures of `Flow.to` and `Flow.toMat`
    * (and have a look at `Keep.right`).
    */
  val identityParserSink: Sink[ByteString, Future[Identity]] =
    reframedFlow.map(str => Identity.parse(str)).toMat(Sink.head)(Keep.right)

  /**
    * A flow that consumes unordered messages and produces messages ordered by `sequenceNr`.
    *
    * User clients expect to be notified of events in the correct order, regardless of the order in which the
    * event source sent them.
    *
    * You will have to buffer messages with a higher sequence number than the next one
    * expected to be produced. The first message to produce has a sequence number of 1.
    *
    * You may want to use `statefulMapConcat` in order to keep the state needed for this
    * operation around in the operator.
    */
  val reintroduceOrdering: Flow[Event, Event, NotUsed] =
    Flow[Event].statefulMapConcat { () =>
      var nextSequenceNr = 1
      var ids = mutable.Map.empty[Int, Event]
      event =>
        if (event.sequenceNr > nextSequenceNr) {
          // Enqueue value
          ids.put(event.sequenceNr, event)
          Nil
        } else if (event.sequenceNr == nextSequenceNr) {
          // Dequeue all elements in a row
          var answer = List[Event]()
          answer = event :: answer
          nextSequenceNr += 1
          while (ids.keys.exists(x => x == nextSequenceNr)) {
            answer = ids(nextSequenceNr) :: answer
            nextSequenceNr += 1
          }
          answer.reverse
        } else {
          Nil
        }
    }

  /**
    * A flow that associates a state of [[Followers]] to
    * each incoming [[Event]].
    *
    * Hints:
    *  - start with a state where nobody follows nobody,
    *  - you may find the `statefulMapConcat` operation useful.
    */
  val followersFlow: Flow[Event, (Event, Followers), NotUsed] =
    Flow[Event].statefulMapConcat { () =>
      var state: Followers = Map.empty

      event =>
        event match {
          case Event.Follow(sequenceNr, fromUserId, toUserId) =>
            val followers: Set[Int] = state.getOrElse(fromUserId, Set.empty) + toUserId
            state = state + (fromUserId -> followers)
          case Event.Unfollow(sequenceNr, fromUserId, toUserId) =>
            val followers: Set[Int] = state.getOrElse(fromUserId, Set.empty) - toUserId
            state = state + (fromUserId -> followers)
          case Event.Broadcast(sequenceNr)                        =>
          case Event.PrivateMsg(sequenceNr, fromUserId, toUserId) =>
          case Event.StatusUpdate(sequenceNr, fromUserId)         =>
        }
        (event, state) :: Nil
    }

  /**
    * @return Whether the given user should be notified by the incoming `Event`,
    *         given the current state of `Followers`. See [[Event]] for more
    *         information of when users should be notified about them.
    * @param userId Id of the user
    * @param eventAndFollowers Event and current state of followers
    */
  def isNotified(userId: Int)(eventAndFollowers: (Event, Followers)): Boolean = {
    val event = eventAndFollowers._1
    val followers = eventAndFollowers._2

    event match {
      case model.Event.Follow(sequenceNr, fromUserId, toUserId) =>
        toUserId == userId
      case model.Event.Unfollow(sequenceNr, fromUserId, toUserId) =>
        false
      case model.Event.Broadcast(sequenceNr) =>
        true
      case model.Event.PrivateMsg(sequenceNr, fromUserId, toUserId) =>
        toUserId == userId
      case model.Event.StatusUpdate(sequenceNr, fromUserId) =>
        followers.getOrElse(userId, Set.empty[Int]).contains(fromUserId)
    }
  }

  // Utilities to temporarily have unimplemented parts of the program
  private def unimplementedFlow[A, B, C]: Flow[A, B, C] =
    Flow.fromFunction[A, B](_ => ???).mapMaterializedValue(_ => ??? : C)

  private def unimplementedSink[A, B]: Sink[A, B] =
    Sink.ignore.mapMaterializedValue(_ => ??? : B)

}

/**
  * Creates a hub accepting several client connections and a single event connection.
  *
  * @param executionContext Execution context for `Future` values transformations
  * @param materializer Stream materializer
  */
class Server()(implicit executionContext: ExecutionContext, materializer: Materializer) extends ExtraStreamOps {
  import Server._

  /**
    * The hub is instantiated here. It allows new user clients to join afterwards
    * and receive notifications from their event feed (see the `clientFlow()` member
    * below). The hub also allows new events to be pushed to it (see the `eventsFlow`
    * member below).
    *
    * The following expression creates the hub and returns a pair containing:
    *  1. A `Sink` that consumes events data,
    *  2. and a `Source` of decoded events paired with the state of followers.
    */
  val (inboundSink: Sink[ByteString, NotUsed], broadcastOut: Source[(Event, Followers), NotUsed]) = {

    /**
      * A flow that consumes the event source, re-frames it,
      * decodes the events, re-orders them, and builds a Map of
      * followers at each point it time. It produces a stream
      * of the decoded events associated with the current state
      * of the followers Map.
      */
    val incomingDataFlow: Flow[ByteString, (Event, Followers), NotUsed] =
      eventParserFlow.via(reintroduceOrdering).via(followersFlow)

    // Wires the MergeHub and the BroadcastHub together and runs the graph
    MergeHub
      .source[ByteString](256)
      .via(incomingDataFlow)
      .toMat(BroadcastHub.sink(256))(Keep.both)
      .withAttributes(ActorAttributes
        .logLevels(Logging.DebugLevel, Logging.DebugLevel, Logging.DebugLevel))
      .run()
  }

  /**
    * The "final form" of the event flow.
    *
    * It consumes byte strings which are the events, and feeds them to the hub inbound.
    *
    * The server does not need to write any data back to the event source (use
    * `Source.maybe` to represent something that does not write data, yet at the same
    * time does NOT complete the stream, otherwise the connection could be closed).
    *
    * Note that you still want the connection to be closed when the event source
    * is completed. Compare the documentation of `Flow.fromSinkAndSource` and
    * `Flow.fromSinkAndSourceCoupled` to find how to achieve that.
    */
  val eventsFlow: Flow[ByteString, Nothing, NotUsed] =
    Flow.fromSinkAndSourceCoupled(inboundSink, Source.maybe)

  /**
    * @return The source of events for the given user
    * @param userId Id of the user
    *
    * Reminder on delivery semantics of messages:
    *
    * Follow:          Only the To User Id should be notified
    * Unfollow:        No clients should be notified
    * Broadcast:       All connected user clients should be notified
    * Private Message: Only the To User Id should be notified
    * Status Update:   All current followers of the From User ID should be notified
    */
  def outgoingFlow(userId: Int): Source[ByteString, NotUsed] = {
    broadcastOut
      .filter(p => isNotified(userId)(p))
      .mapConcat[ByteString] { case (e, _) => scala.collection.immutable.Iterable.apply(e.render) }
  }

  /**
    * The "final form" of the client flow.
    *
    * Clients will connect to this server and send their id as an Identity message (e.g. "21323\n").
    *
    * The server should establish a link from the event source towards the clients, in such way that they
    * receive only the events that they are interested about.
    *
    * The incoming side of this flow needs to extract the client id to then properly construct the outgoing Source,
    * as it will need this identifier to notify the server which data it is interested about.
    *
    * Hints:
    *   - since the clientId will be emitted as a materialized value of `identityParserSink`,
    *     you may need to use mapMaterializedValue to side effect it into a shared Promise/Future that the Source
    *     side can utilise to construct such Source "from that client id future".
    *   - Remember to use `via()` to connect a `Flow`, and `to()` to connect a `Sink`.
    */
  def clientFlow(): Flow[ByteString, ByteString, NotUsed] = {
    val clientIdPromise = Promise[Identity]()
//    clientIdPromise.future.map(id => actorSystem.log.info("Connected follower: {}", id.userId))

    // A sink that parses the client identity and completes `clientIdPromise` with it
    val incoming: Sink[ByteString, NotUsed] =
      Flow[ByteString]
        .to(identityParserSink.mapMaterializedValue(fId => clientIdPromise.completeWith(fId)))

    val outgoing: Source[ByteString, Future[NotUsed]] = Source.fromFutureSource(clientIdPromise.future.map { identity =>
      outgoingFlow(identity.userId)
    })

    Flow.fromSinkAndSource(incoming, outgoing)
  }

}
